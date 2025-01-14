// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ml.logs

import com.intellij.platform.ml.MLApiPlatform
import com.intellij.platform.ml.environment.Environment
import com.intellij.platform.ml.logs.schema.EventPair
import com.intellij.platform.ml.session.AnalysedRootContainer
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
enum class PostponedLoggingDecision {
  ENABLED,
  DISABLED
}

@ApiStatus.Internal
interface PostponedLogDecisionListener {
  fun onLoggingDecisionMade(callback: (PostponedLoggingDecision) -> Unit)
}

@ApiStatus.Internal
class PostponedLoggingController : PostponedLogDecisionListener {
  private val loggingDecisionCallbacks = mutableListOf<(PostponedLoggingDecision) -> Unit>()

  override fun onLoggingDecisionMade(callback: (PostponedLoggingDecision) -> Unit) {
    loggingDecisionCallbacks.add(callback)
  }

  fun logDecision(postponedDecision: PostponedLoggingDecision) {
    loggingDecisionCallbacks.forEach { it(postponedDecision) }
  }
}

@ApiStatus.Internal
class MLSessionPostponableLogger<P : Any>(
  logDecisionListener: PostponedLogDecisionListener,
  private val baseLogger: MLSessionLogger<P>,
) : MLSessionLogger<P> {
  private var loggingDecision: PostponedLoggingDecision? = null
  private var recordedLog: LoggedSession<P>? = null

  init {
    logDecisionListener.onLoggingDecisionMade { onDecisionMade(it) }
  }

  private fun onDecisionMade(decision: PostponedLoggingDecision) = synchronized(this) {
    require(loggingDecision == null) { "Trying to override the already recorded logging decision" }
    loggingDecision = decision
    tryLogging()
  }

  private fun tryLogging() {
    val decidedToLog = loggingDecision?.let { it == PostponedLoggingDecision.ENABLED } ?: return
    recordedLog?.let {
      if (decidedToLog) baseLogger.logComponents(it.apiPlatform, it.permanentSessionEnvironment, it.permanentCallParameters, it.session, it.structure)
    }
  }

  private data class LoggedSession<P : Any>(
    val apiPlatform: MLApiPlatform,
    val permanentSessionEnvironment: Environment,
    val permanentCallParameters: Environment,
    val session: List<EventPair<*>>,
    val structure: AnalysedRootContainer<P>?
  )

  override fun logComponents(apiPlatform: MLApiPlatform, permanentSessionEnvironment: Environment, permanentCallParameters: Environment, session: List<EventPair<*>>, structure: AnalysedRootContainer<P>?) {
    require(recordedLog == null) { "Trying to override an already logged ML session" }
    recordedLog = LoggedSession(apiPlatform, permanentSessionEnvironment, permanentCallParameters, session, structure)
    tryLogging()
  }
}
