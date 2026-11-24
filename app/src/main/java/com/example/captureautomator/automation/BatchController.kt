package com.example.captureautomator.automation

import android.content.Context
import android.util.Log
import com.example.captureautomator.core.*
import com.example.captureautomator.data.Codec
import com.example.captureautomator.data.PresetRepository
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

data class BatchUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val current: String = "",
    val position: Int = 0,
    val total: Int = 0,
    val reports: List<ExecutionReport> = emptyList(),
    val error: String = "",
    val cleanupDetails: List<String> = emptyList(),
    val sessionId: String = "",
    val phase: String = "IDLE",
    val stopping: Boolean = false,
    val cancelled: Boolean = false,
    val detail: String = "",
    val skipped: Int = 0
)

object BatchController {
    private val mutable = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val archiveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var paused = false
    private var recovered = false
    private var stopReason = "Stopped by tester"

    private fun checkpoint(context: Context, value: BatchUiState) {
        val checkpoint = BatchCheckpoint(value.sessionId, value.running, value.phase,
            value.position, value.total, value.reports.map { it.id })
        context.getSharedPreferences("batch_session", Context.MODE_PRIVATE).edit()
            .putString("checkpoint", Codec.gson.toJson(checkpoint)).apply()
    }

    /** A killed process never resumes taps. Leave the app unlocked and explain the interruption. */
    fun recover(context: Context) {
        if (recovered || mutable.value.running || job?.isActive == true) return
        recovered = true
        val preferences = context.getSharedPreferences("batch_session", Context.MODE_PRIVATE)
        val old = runCatching { Codec.gson.fromJson(preferences.getString("checkpoint", null), BatchCheckpoint::class.java) }.getOrNull()
        val message = old?.interruptionMessage() ?: return
        mutable.value = BatchUiState(sessionId = old.sessionId, position = old.position,
            total = old.total, phase = "INTERRUPTED", current = "Previous batch interrupted", error = message)
        runCatching { checkpoint(context, mutable.value) }
    }

    fun run(context: Context, presets: List<Preset>, repository: PresetRepository, stopOnFail: Boolean) {
        val app = context.applicationContext
        recover(app)
        if (mutable.value.running || job?.isActive == true || ExecutionController.state.value.running ||
            RecordingSession.state.value?.active == true || TouchCalibration.state.value.running) return
        val service = AutomationAccessibilityService.instance
        if (service == null || presets.isEmpty()) {
            mutable.value = BatchUiState(error = "Connect Accessibility and select at least one preset")
            return
        }
        paused = false
        stopReason = "Stopped by tester"
        val id = UUID.randomUUID().toString()
        mutable.value = BatchUiState(running = true, total = presets.size, sessionId = id, phase = "STARTING")
        fun event(name: String, detail: String = "") {
            Log.i("CaptureBatch", Codec.gson.toJson(mapOf("sessionId" to id, "event" to name,
                "phase" to mutable.value.phase, "position" to mutable.value.position.toString(), "detail" to detail)))
        }
        fun stage(phase: String, current: String) {
            mutable.value = mutable.value.copy(phase = phase, current = current)
            checkpoint(app, mutable.value)
            event("PHASE")
        }
        job = scope.launch {
            var unsafeToCleanup = false
            var haltReason = ""
            val timing = PlaybackTiming {
                currentCoroutineContext().ensureActive()
                while (paused) { delay(100); currentCoroutineContext().ensureActive() }
                if (AutomationAccessibilityService.instance !== service) {
                    stopReason = "Accessibility disconnected; reconnect it before starting another batch"
                    throw CancellationException(stopReason)
                }
            }
            suspend fun clearRecents(phase: String, label: String): Boolean {
                timing.waitMs(0)
                stage(phase, label)
                val result = RecentAppsCleanup.clear(service, timing)
                mutable.value = mutable.value.copy(cleanupDetails = mutable.value.cleanupDetails + "$label: $result")
                event("RECENTS_RESULT", result)
                timing.waitMs(1000)
                val returned = AndroidDevice.launch(app, app.packageName) &&
                    UiResolver(service, timing).waitForApp(app.packageName, 5000)
                timing.waitMs(0)
                if (!returned) {
                    mutable.value = mutable.value.copy(error = "$label: could not return to Automator")
                    return false
                }
                if (!result.startsWith("RECENTS_CLEAR_REQUESTED") && !result.startsWith("RECENTS_ALREADY_EMPTY")) {
                    mutable.value = mutable.value.copy(error = "$label: $result")
                    return false
                }
                return true
            }
            BatchRunLoop<Preset, ExecutionReport>(
                before = {
                    service.beginBatchSession("Preparing batch (${presets.size} presets)")
                    event("STARTED")
                    clearRecents("STARTUP_CLEANUP", "Clearing Recents before batch")
                },
                execute = { preset ->
                    timing.waitMs(0)
                    mutable.value = mutable.value.copy(position = mutable.value.position + 1)
                    mutable.value=mutable.value.copy(detail="Launching prepared preset")
                    stage("RUNNING_PRESET", preset.name)
                    service.showIndicator(false, "Batch ${mutable.value.position}/${presets.size}: ${preset.name}")
                    ExecutionEngine(app, service,
                        onProgress = { index, step, _ -> mutable.value = mutable.value.copy(current = "${preset.name}: ${step.label}",
                            detail="Step ${index+1}/${preset.steps.size}") },
                        gate = { timing.waitMs(0) },
                        onStatus = { message -> mutable.value=mutable.value.copy(detail=message) }).execute(BatchPolicy.forExecution(preset))
                },
                onResult = { report ->
                    mutable.value = mutable.value.copy(reports = mutable.value.reports + report)
                    stage("SAVING_REPORT", "Saving ${report.presetName}")
                    event("PRESET_FINISHED", report.outcome.name)
                },
                save = { repository.saveReport(it) },
                stopAfter = { report ->
                    val decision=BatchPolicy.decide(report,stopOnFail)
                    unsafeToCleanup=decision.unsafe
                    if(decision.stop) {
                        haltReason="${report.presetName}: ${decision.reason}"
                        mutable.value=mutable.value.copy(error=haltReason,skipped=(presets.size-mutable.value.position).coerceAtLeast(0))
                    }
                    event(if(decision.stop) "HANDOFF_BLOCKED" else "HANDOFF_ALLOWED",decision.reason)
                    decision.stop
                },
                after = {
                    if (unsafeToCleanup) {
                        mutable.value = mutable.value.copy(cleanupDetails = mutable.value.cleanupDetails +
                            "RECENTS_SKIPPED: capture/save state is uncertain; inspect the target app")
                    } else clearRecents("FINAL_CLEANUP", "Clearing Recents after batch")
                },
                onStopped = {
                    mutable.value = mutable.value.copy(cancelled = true, current = "Batch stopped", error = stopReason)
                    event("CANCELLED", stopReason)
                },
                onFailure = { e ->
                    mutable.value = mutable.value.copy(error = "Batch failed: ${e.message ?: e.javaClass.simpleName}")
                    event("ERROR", e.javaClass.simpleName)
                },
                onIdle = {
                    // No suspending I/O before releasing ownership, even in a cancelled coroutine.
                    val value = mutable.value
                    var finished = value.copy(running = false, paused = false, stopping = false,
                        skipped=(value.total-value.position).coerceAtLeast(0),
                        detail=haltReason.ifBlank { if(value.cancelled) stopReason else "" },
                        phase = when {
                            value.cancelled -> "CANCELLED"
                            unsafeToCleanup -> "BLOCKED"
                            haltReason.isNotBlank() && value.position<value.total -> "STOPPED_ON_FAILURE"
                            value.error.isNotBlank() && haltReason.isBlank() -> "FAILED"
                            value.reports.any { it.outcome!=Outcome.PASS } -> "COMPLETED_WITH_ERRORS"
                            else -> "COMPLETED"
                        },
                        current = when {
                            value.cancelled -> "Batch stopped"
                            value.position<value.total -> "Batch ended after ${value.position} of ${value.total} presets"
                            value.reports.any { it.outcome!=Outcome.PASS } -> "Batch finished with failed or unverified presets"
                            else -> "Batch finished"
                        })
                    try {
                        runCatching { service.clearIndicator() }.onFailure { event("NOTIFICATION_CLEAR_FAILED") }
                        runCatching { checkpoint(app, finished) }.onFailure {
                            finished = finished.copy(error = (finished.error + " Report checkpoint could not be saved").trim())
                        }
                    } finally {
                        paused = false
                        mutable.value = finished
                        job = null
                    }
                    event("IDLE")
                    // Archives cannot hold the playback lock or clear a newer session's notification.
                    val snapshot = finished
                    archiveScope.launch {
                        runCatching {
                            File(app.filesDir, "batch_$id.json").writeText(Codec.gson.toJson(snapshot.reports))
                            File(app.filesDir, "batch-status-$id.json").writeText(Codec.gson.toJson(snapshot))
                        }.onFailure { Log.e("CaptureBatch", "Archive write failed for $id: ${it.javaClass.simpleName}") }
                    }
                }
            ).run(presets)
        }
    }

    fun pause() { if (mutable.value.running && !mutable.value.stopping) { paused = true; mutable.value = mutable.value.copy(paused = true) } }
    fun resume() { if (mutable.value.running && !mutable.value.stopping) { paused = false; mutable.value = mutable.value.copy(paused = false) } }
    fun stop() = interrupt("Stopped by tester; check the target app if video may still be recording")
    fun interrupt(reason: String) {
        if (!mutable.value.running) return
        stopReason = reason
        paused = false
        mutable.value = mutable.value.copy(paused = false, stopping = true, current = "Stopping batch…")
        job?.cancel()
    }
}
