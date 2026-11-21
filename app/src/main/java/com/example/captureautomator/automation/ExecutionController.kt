package com.example.captureautomator.automation

import android.content.Context
import com.example.captureautomator.core.*
import com.example.captureautomator.data.PresetRepository
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One active test at a time. UI can pause, cancel, and inspect the final report. */
data class ExecutionUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val presetName: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentStep: String = "",
    val completed: List<StepResult> = emptyList(),
    val report: ExecutionReport? = null,
    val error: String = ""
)

object ExecutionController {
    private val mutable = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var isPaused = false

    fun run(context: Context, preset: Preset, repository: PresetRepository) {
        if (mutable.value.running || job?.isActive == true || BatchController.state.value.running) return
        if (TouchCalibration.state.value.running) {
            mutable.value = ExecutionUiState(error = "Finish or cancel touch calibration first")
            return
        }
        if (RecordingSession.state.value?.active == true) {
            mutable.value = ExecutionUiState(error = "Stop recording before starting playback")
            return
        }
        val service = AutomationAccessibilityService.instance
        if (service == null) {
            mutable.value = ExecutionUiState(error = "Enable the accessibility service in Android Settings")
            return
        }
        isPaused = false
        mutable.value = ExecutionUiState(running = true, presetName = preset.name,
            totalSteps = preset.steps.size)
        val startedAt = System.currentTimeMillis()
        job = scope.launch {
            service.showIndicator(false, preset.name)
            try {
                val engine = ExecutionEngine(context.applicationContext, service,
                    onProgress = { index, step, results ->
                        mutable.value = mutable.value.copy(stepIndex = index + 1,
                            currentStep = step.label, completed = results)
                    },
                    gate = {
                        currentCoroutineContext().ensureActive()
                        while (isPaused) {
                            delay(140)
                            currentCoroutineContext().ensureActive()
                        }
                    })
                val report = engine.execute(preset)
                withContext(NonCancellable) { repository.saveReport(report) }
                mutable.value = mutable.value.copy(running = false, paused = false,
                    report = report, completed = report.results)
            } catch (_: CancellationException) {
                val partial = mutable.value.completed
                val report = ExecutionReport(presetId = preset.id, presetName = preset.name,
                    device = AndroidDevice.profile(context), startedAt = startedAt,
                    endedAt = System.currentTimeMillis(), outcome = Outcome.CANCELLED,
                    results = partial, media = MediaResult(issues = listOf("Stopped by tester")))
                withContext(NonCancellable) { repository.saveReport(report) }
                mutable.value = mutable.value.copy(running = false, paused = false, report = report)
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(running = false, paused = false,
                    error = "Execution failed: ${e.message}")
            } finally { service.clearIndicator() }
        }
    }

    fun pause() { if (mutable.value.running) { isPaused = true; mutable.value = mutable.value.copy(paused = true) } }
    fun resume() { if (mutable.value.running) { isPaused = false; mutable.value = mutable.value.copy(paused = false) } }
    fun stop() { isPaused = false; job?.cancel() }
    fun reset() { if (job?.isActive != true) mutable.value = ExecutionUiState() }
}
