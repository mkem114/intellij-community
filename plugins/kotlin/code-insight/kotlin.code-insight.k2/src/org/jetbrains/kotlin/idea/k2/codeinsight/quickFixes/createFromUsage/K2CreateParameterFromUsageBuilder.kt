// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.quickFixes.createFromUsage

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.findParentOfType
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.k2.codeinsight.quickFixes.createFromUsage.K2CreateFromUsageUtil.getExpectedKotlinType
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.CreateFromUsageUtil
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.CreateLocalVariableUtil
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.CreateParameterUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getAssignmentByLHS
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypeAndBranch
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedElement

object K2CreateParameterFromUsageBuilder {
    fun generateCreateParameterAction(element: KtElement): IntentionAction? {
        val refExpr = element.findParentOfType<KtNameReferenceExpression>(strict = false) ?: return null
        if (refExpr.getQualifiedElement() != refExpr) return null
        if (refExpr.getParentOfTypeAndBranch<KtCallableReferenceExpression> { callableReference } != null) return null

        val pair = getContainerClass(refExpr)
        if (pair.first == null) return null
        return CreateParameterFromUsageAction(refExpr, refExpr.getReferencedName(), pair.second)
    }

    private fun getContainerClass(refExpr: KtNameReferenceExpression): Pair<PsiElement?, CreateParameterUtil.ValVar> {
        val varExpected = refExpr.getAssignmentByLHS() != null
        return CreateParameterUtil.chooseContainerPreferringClass(refExpr, varExpected)
    }

    internal class CreateParameterFromUsageAction(refExpr: KtNameReferenceExpression, private val propertyName: String, valVar: CreateParameterUtil.ValVar) : IntentionAction {
        val pointer: SmartPsiElementPointer<KtNameReferenceExpression> = SmartPointerManager.createPointer(refExpr)
        override fun getText(): String = KotlinBundle.message("fix.create.from.usage.local.variable", propertyName)
        private var declarationText:String = computeDeclarationText()

        private fun computeDeclarationText(): String {
            //val handler = KotlinFirIntroduceParameterHandler()
            val refExpr = pointer.element ?: return ""
            val assignment = refExpr.getAssignmentByLHS()
            val varExpected = assignment != null
            val originalElement: KtExpression = assignment ?: refExpr

            val valVar = if (varExpected) "var" else "val"
            val initializer =
                analyze(refExpr) {
                    if (assignment == null) {
                        val expressionForTypeGuess = originalElement.getAssignmentByLHS()?.right ?: originalElement
                        expressionForTypeGuess.getExpectedKotlinType()?.ktType?.defaultInitializer
                    }
                    else {
                        "x"
                    }
                }
            return "$valVar $propertyName = $initializer"
        }

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            val refExpr = pointer.element ?: return
            val container = getContainerClass(refExpr).first ?: return
            if (!ReadonlyStatusHandler.ensureFilesWritable(project, PsiUtil.getVirtualFile(container))) {
                return
            }
            WriteCommandAction.writeCommandAction(project).run<Throwable> {
                val (actualContainer, actualAnchor) = when (container) {
                    is KtBlockExpression -> container to refExpr
                    is KtDeclarationWithBody -> {
                        val newFunction = CreateLocalVariableUtil.convert(container, true)
                        val bodyBlock = newFunction.bodyExpression!!
                        bodyBlock to ((bodyBlock as? KtBlockExpression)?.firstStatement ?: newFunction)
                    }
                    else -> throw IllegalStateException(container.toString())
                }
                val createdDeclaration = KtPsiFactory(pointer.project).createDeclaration(declarationText) as KtVariableDeclaration
                val assignment = pointer.element?.getAssignmentByLHS()

                if (assignment == null) {
                    CreateFromUsageUtil.placeDeclarationInContainer(createdDeclaration, actualContainer, actualAnchor)
                }
                else {
                    createdDeclaration.initializer!!.replace(assignment.right!!)
                    assignment.replace(createdDeclaration)
                }
            }
        }

        override fun startInWriteAction(): Boolean = false
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
            return pointer.element != null
        }
        override fun getFamilyName(): String = KotlinBundle.message("fix.create.from.usage.family")
    }

}
