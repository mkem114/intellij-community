// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.blockingContext
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.progress.impl.ProgressSuspender
import com.intellij.openapi.progress.util.PingProgress
import com.intellij.openapi.project.*
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts.ProgressText
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.gist.GistManager
import com.intellij.util.gist.GistManagerImpl
import com.intellij.util.indexing.dependencies.ProjectIndexingDependenciesService
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.Predicate

@ApiStatus.Internal
class UnindexedFilesScannerExecutorImpl(private val project: Project, cs: CoroutineScope) : Disposable,
                                                                                            UnindexedFilesScannerExecutor {
  // helpers for tests
  private val scanningWaitsForNonDumbModeOverride = MutableStateFlow<Boolean?>(null)
  private var taskFilter: Predicate<UnindexedFilesScanner>? = null

  private val runningDumbTask = AtomicReference<ProgressIndicator>()

  // note that shouldShowProgressIndicator = false in UnindexedFilesScannerExecutor, so there is no suspender for the progress indicator
  private val pauseReason = MutableStateFlow<PersistentList<String>>(persistentListOf())
  override fun getPauseReason(): StateFlow<PersistentList<String>> = pauseReason

  override val isRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)

  /**
   * Modification counter that increases each time the executor starts or stops
   *
   * This is not the same as [isRunning], because [isRunning] is a state flow, meaning that it is conflated and deduplicated, i.e. short
   * transitions true-false-true can be missed in [isRunning]. [startedOrStoppedEvent] is still conflated, but never miss the latest event.
   *
   * TODO: [isRunning] should be a shared flow without deduplication, then we wont need [startedOrStoppedEvent]
   */
  override val startedOrStoppedEvent: MutableStateFlow<Int> = MutableStateFlow(0)

  // Use from synchronized block, because UnindexedFilesScanner.tryMergeWith is not idempotent yet
  private val scanningTask = MutableStateFlow<UnindexedFilesScanner?>(null)
  private val scanningEnabled = MutableStateFlow(true)

  @Volatile
  private var runningTask: Job? = null

  init {
    // Note about Dispatchers.IO: we'll do "runBlocking" in UnindexedFilesScanner.ScanningSession.collectIndexableFilesConcurrently
    // Make sure that we are not using limited dispatchers here (e.g., Dispatchers.Default).
    cs.childScope("Scanning (root)", Dispatchers.IO).launch {
      while (true) {
        try {
          waitUntilNextTaskExecutionAllowed()

          // first set isRunning, otherwise we can find ourselves in a situation
          // isRunning=false, hasScheduledTask=false, but in fact we do have a scheduled task
          // which is about to be running.

          // write action is needed, because otherwise we may get "Constraint inSmartMode cannot be satisfied" in NBRA
          writeAction {
            isRunning.value = true
          }

          startedOrStoppedEvent.getAndUpdate(Int::inc)

          // Use from synchronized block, because UnindexedFilesScanner.tryMergeWith is not idempotent yet
          val task = synchronized(scanningTask) {
            scanningTask.getAndUpdate { null }
          } ?: continue
          try {
            logInfo("Running task: $task")
            LOG.assertTrue(runningTask == null, "Task is already running (will be cancelled)")
            runningTask?.cancel() // We expect that running task is null. But it's better to be on the safe side
            coroutineScope {
              runningTask = async(CoroutineName("Scanning")) {
                runScanningTask(task)
              }
            }
            logInfo("Task finished: $task")
          }
          catch (t: Throwable) {
            logInfo("Task interrupted: $task. ${t.message}")
            project.service<ProjectIndexingDependenciesService>().requestHeavyScanningOnProjectOpen("Task interrupted: $task")
            checkCanceled() // this will re-throw cancellation

            // other exceptions: log and forget
            logError("Failed to execute task $task", t)
          }
          finally {
            task.close()
            LOG.assertTrue(runningTask?.isActive != true, "Task job should have been cancelled or finished")
            runningTask = null
          }
        }
        catch (t: Throwable) {
          checkCanceled() // this will re-throw cancellation

          // other exceptions: log and forget
          logError("Unexpected exception during scanning (ignored)", t)
        }
        finally {
          // We don't care about finishing scanning without a write action. This looks harmless at the moment
          isRunning.value = false
          startedOrStoppedEvent.getAndUpdate(Int::inc)
        }
      }
    }
  }

  private fun prepareLogMessage(message: String) = "[${project.locationHash}] $message"
  private fun logInfo(message: String) = LOG.info(prepareLogMessage(message))
  private fun logError(message: String, t: Throwable) = LOG.error(prepareLogMessage(message), t)

  private fun scanningWaitsForNonDumbMode(override: Boolean?): Boolean = override ?: Registry.`is`("scanning.waits.for.non.dumb.mode", true)

  @VisibleForTesting
  fun scanningWaitsForNonDumbMode(): Boolean = scanningWaitsForNonDumbMode(scanningWaitsForNonDumbModeOverride.value)

  private suspend fun waitUntilNextTaskExecutionAllowed() {
    // wait until scanning is enabled
    var flow: Flow<Boolean> = scanningEnabled.combine(scanningTask) { enabled, scanningTask ->
      enabled && scanningTask != null
    }

    // Delay scanning tasks until all the scheduled dumb tasks are finished.
    // For example, PythonLanguageLevelPusher.initExtra is invoked from RequiredForSmartModeActivity and may submit additional dumb tasks.
    // We want scanning to start after all these "extra" dumb tasks are finished.
    // Note that a project may become dumb immediately after the check. This is not a problem - we schedule scanning anyway.
    if (scanningWaitsForNonDumbMode()) {
      flow = flow.combine(
        // nested flow is needed because of negation (!shouldWaitForNonDumb)
        DumbServiceImpl.getInstance(project).isDumbAsFlow.combine(scanningWaitsForNonDumbModeOverride) { isDumb, scanningWaitsCurrentValue ->
          isDumb && scanningWaitsForNonDumbMode(scanningWaitsCurrentValue)
        }
      ) { shouldRun, shouldWaitForNonDumb ->
        shouldRun && !shouldWaitForNonDumb
      }
    }

    flow.first { it }
  }

  @TestOnly
  fun overrideScanningWaitsForNonDumbMode(newValue: Boolean?) {
    scanningWaitsForNonDumbModeOverride.value = newValue
  }

  private suspend fun runScanningTask(task: UnindexedFilesScanner) {
    val shouldShowProgress: StateFlow<Boolean> = if (task.shouldHideProgressInSmartMode()) {
      project.service<DumbModeWhileScanningTrigger>().isDumbModeForScanningActive()
    }
    else {
      MutableStateFlow(true)
    }
    coroutineScope {
      val progressScope = childScope("Scanning progress")
      val progressReporter = IndexingProgressReporter()
      val taskIndicator = IndexingProgressReporter.CheckPauseOnlyProgressIndicatorImpl(progressScope, getPauseReason())
      IndexingProgressReporter.launchIndexingProgressUIReporter(progressScope, project, shouldShowProgress, progressReporter,
                                                                IndexingBundle.message("progress.indexing.scanning"),
                                                                taskIndicator.getPauseReason())


      (GistManager.getInstance() as GistManagerImpl).mergeDependentCacheInvalidations().use {
        task.applyDelayedPushOperations()
      }
      blockingContext {
        task.perform(taskIndicator, progressReporter)
      }

      progressScope.cancel()
    }
  }

  private fun cancelAllTasks(debugReason: String) {
    scanningEnabled.value = false
    try {
      // Use from synchronized block, because UnindexedFilesScanner.tryMergeWith is not idempotent yet
      synchronized(scanningTask) {
        scanningTask.getAndUpdate { null }?.close()
      }
      runningTask?.cancel(debugReason)
    }
    finally {
      scanningEnabled.value = true
    }
  }

  override fun submitTask(task: FilesScanningTask) {
    task as UnindexedFilesScanner
    LOG.debug(Throwable("submit task, ${project.name}[${project.locationHash}], thread=${Thread.currentThread()}"))

    if (taskFilter?.test(task) == false) {
      logInfo("Skipping task (rejected by filter): $task")
      task.close()
      return
    }

    // Two tasks with limited checks should be just run one after another.
    // A case of a full check followed by a limited change cancelling the first one and making a full check anew results
    // in endless restart of full checks on Windows with empty Maven cache.
    // So only in case the second one is a full check should the first one be cancelled.
    if (task.isFullIndexUpdate()) {
      // we don't want to execute any of the existing tasks - the only task we want to execute will be submitted the few lines below
      cancelAllTasks("Full scanning is queued")
      cancelRunningScannerTaskInDumbQueue()
    }
    if (UnindexedFilesScannerExecutor.shouldScanInSmartMode()) {
      startTaskInSmartMode(task)
    }
    else {
      startTaskInDumbMode(task)
    }
  }

  private fun startTaskInSmartMode(task: UnindexedFilesScanner) {
    // Use from synchronized block, because UnindexedFilesScanner.tryMergeWith is not idempotent yet
    synchronized(scanningTask) {
      val old = scanningTask.value
      val merged = old?.tryMergeWith(task)
      val new = merged ?: task
      val updated = scanningTask.compareAndSet(old, new)
      LOG.assertTrue(updated, "Use scanningTask from synchronized block, because UnindexedFilesScanner.tryMergeWith is not idempotent yet")
      if (updated) {
        old?.close()
        if (new != task) task.close() else Unit
      }
      else {
        merged?.close()
      }
    }
  }

  private fun startTaskInDumbMode(task: UnindexedFilesScanner) {
    wrapAsDumbTask(task).queue(project)
  }

  @VisibleForTesting
  fun wrapAsDumbTask(task: UnindexedFilesScanner): DumbModeTask {
    return FilesScanningTaskAsDumbModeTaskWrapper(project, task, runningDumbTask)
  }

  private fun cancelRunningScannerTaskInDumbQueue() {
    val indicator = runningDumbTask.get()
    if (indicator != null) {
      indicator.cancel()
      val suspender = ProgressSuspender.getSuspender(indicator)
      if (suspender != null && suspender.isSuspended) {
        suspender.resumeProcess()
      }
    }
  }

  override fun cancelAllTasksAndWait() {
    cancelAllTasks("cancelAllTasksAndWait") // this also cancels a running task even if it is paused by ProgressSuspender
    while (isRunning.value && !project.isDisposed) {
      PingProgress.interactWithEdtProgress()
      LockSupport.parkNanos(50_000_000)
    }
  }

  override fun dispose() {
    // Use from synchronized block, because UnindexedFilesScanner.tryMergeWith is not idempotent yet
    synchronized(scanningTask) {
      scanningTask.getAndUpdate { null }?.close()
    }
    runningTask?.cancel()
  }

  /**
   * This method does not have "happens before" semantics. It requests GUI suspender to suspend and executes runnable without waiting for
   * all the running tasks to pause.
   */
  override fun suspendScanningAndIndexingThenRun(activityName: @ProgressText String, runnable: Runnable) {
    pauseReason.update { it.add(activityName) }
    try {
      DumbService.getInstance(project).suspendIndexingAndRun(activityName, runnable)
    }
    finally {
      pauseReason.update { it.remove(activityName) }
    }
  }

  override fun suspendQueue() {
    scanningEnabled.value = false
  }

  override fun resumeQueue() {
    scanningEnabled.value = true
  }

  override val hasQueuedTasks: Boolean
    get() = scanningTask.value != null

  @TestOnly
  fun setTaskFilterInTest(disposable: Disposable, filter: Predicate<UnindexedFilesScanner>) {
    Disposer.register(disposable) { taskFilter = null }
    taskFilter = filter
  }

  companion object {
    private val LOG = Logger.getInstance(UnindexedFilesScannerExecutor::class.java)

    @JvmStatic
    fun getInstance(project: Project): UnindexedFilesScannerExecutorImpl = project.service<UnindexedFilesScannerExecutor>() as UnindexedFilesScannerExecutorImpl
  }
}
