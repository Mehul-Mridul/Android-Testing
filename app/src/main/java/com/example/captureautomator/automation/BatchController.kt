package com.example.captureautomator.automation

import android.content.Context
import com.example.captureautomator.core.*
import com.example.captureautomator.data.Codec
import com.example.captureautomator.data.PresetRepository
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
    val cleanupDetails: List<String> = emptyList()
)

object BatchController {
    private val mutable = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var paused = false

    fun run(context: Context, presets: List<Preset>, repository: PresetRepository, stopOnFail: Boolean) {
        if (mutable.value.running || job?.isActive == true || ExecutionController.state.value.running ||
            RecordingSession.state.value?.active == true || TouchCalibration.state.value.running) return
        val service = AutomationAccessibilityService.instance
        if (service == null || presets.isEmpty()) {
            mutable.value = BatchUiState(error = "Enable accessibility and select at least one preset")
            return
        }
        paused = false
        mutable.value = BatchUiState(running = true, total = presets.size)
        job = scope.launch {
            val results = mutableListOf<ExecutionReport>()
            val timing = PlaybackTiming {
                currentCoroutineContext().ensureActive()
                while (paused) delay(100)
            }
            suspend fun clearRecents(stage: String): Boolean {
                mutable.value = mutable.value.copy(current = stage)
                val result = RecentAppsCleanup.clear(service, timing)
                mutable.value = mutable.value.copy(cleanupDetails = mutable.value.cleanupDetails + "$stage: $result")
                // Wait for the launcher to finish removing tasks before starting another one.
                timing.waitMs(1000)
                val returned = com.example.captureautomator.util.AndroidDevice.launch(service, service.packageName) &&
                    UiResolver(service, timing).waitForApp(service.packageName, 5000)
                if (!returned) {
                    mutable.value = mutable.value.copy(error = "$stage: could not return to Automator")
                    return false
                }
                if (!result.startsWith("RECENTS_CLEAR_REQUESTED") && !result.startsWith("RECENTS_ALREADY_EMPTY")) {
                    mutable.value = mutable.value.copy(error = "$stage: $result")
                    return false
                }
                return true
            }
            try {
                if (!clearRecents("Clearing Recents before batch")) return@launch
                for ((index, preset) in presets.withIndex()) {
                    mutable.value = mutable.value.copy(position = index + 1, current = preset.name)
                    service.showIndicator(false, "Batch ${index + 1}/${presets.size}: ${preset.name}")
                    val runner = ExecutionEngine(context.applicationContext, service,
                        onProgress = { _, step, _ -> mutable.value = mutable.value.copy(current = "${preset.name}: ${step.label}") },
                        gate = {
                            currentCoroutineContext().ensureActive()
                            while (paused) { delay(150); currentCoroutineContext().ensureActive() }
                        })
                    val report = runner.execute(BatchPolicy.forExecution(preset))
                    withContext(NonCancellable) { repository.saveReport(report) }
                    results += report
                    mutable.value = mutable.value.copy(reports = results.toList())
                    if ((stopOnFail || report.results.any { it.message.startsWith("App switch withheld") } || report.media.issues.any { it.contains("deadline reached") }) && report.outcome != Outcome.PASS) break
                }
                clearRecents("Clearing Recents after batch")
                mutable.value = mutable.value.copy(current = "Batch finished")
            } catch (_: CancellationException) {
                // Completed individual reports remain saved; never silently retry media captures.
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = e.message.orEmpty())
            } finally {
                val file = File(context.filesDir, "batch_${UUID.randomUUID()}.json")
                withContext(NonCancellable + Dispatchers.IO) {
                    file.writeText(Codec.gson.toJson(results))
                    File(context.filesDir, "batch-status-" + file.name.removePrefix("batch_")).writeText(
                        Codec.gson.toJson(mutable.value.copy(reports = results.toList(), running = false)))
                }
                mutable.value = mutable.value.copy(running = false, paused = false, reports = results.toList())
                service.clearIndicator()
            }
        }
    }

    fun pause() { if (mutable.value.running) { paused = true; mutable.value = mutable.value.copy(paused = true) } }
    fun resume() { if (mutable.value.running) { paused = false; mutable.value = mutable.value.copy(paused = false) } }
    fun stop() { paused = false; job?.cancel() }
}
