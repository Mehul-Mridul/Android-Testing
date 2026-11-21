package com.example.captureautomator.automation

import android.content.Intent
import android.view.View
import com.example.captureautomator.core.*
import com.example.captureautomator.data.PresetRepository
import com.example.captureautomator.ui.MainActivity
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TouchCalibrationState(val running: Boolean = false, val message: String = "", val savedPresetId: String? = null)

/** Preview/edit only: calibration never injects a gesture. Each path is explicitly accepted. */
object TouchCalibration {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var overlay: TouchWindows? = null
    private var service: AutomationAccessibilityService? = null
    private val mutable = MutableStateFlow(TouchCalibrationState())
    val state: StateFlow<TouchCalibrationState> = mutable

    private fun openApp(service: AutomationAccessibilityService) = service.startActivity(
        Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

    fun cancel(returnToApp: Boolean = true) {
        if (!mutable.value.running && job?.isActive != true) return
        job?.cancel(); overlay?.close(); service?.clearIndicator()
        mutable.value = TouchCalibrationState(message = "Calibration cancelled; original preset preserved")
        if (returnToApp) service?.let(::openApp)
    }

    fun start(service: AutomationAccessibilityService, preset: Preset, repository: PresetRepository) {
        if (mutable.value.running || ExecutionController.state.value.running || BatchController.state.value.running ||
            RecordingSession.state.value?.active == true) return
        require(TouchPolicy.isTouch(preset))
        require(PresetValidation.errors(preset).isEmpty()) { PresetValidation.errors(preset).joinToString("; ") }
        this.service = service
        mutable.value = TouchCalibrationState(true, "Drag each dot/start/end to the matching control. Accept does not touch the app.")
        job = scope.launch {
            val windows = TouchWindows(service); overlay = windows
            service.showIndicator(false, "Touch calibration: preview only")
            try {
                val profiles = mutableMapOf<String, TouchProfile>()
                suspend fun openProfile(pkg: String): TouchProfile {
                    check(AndroidDevice.launch(service, pkg)) { "Cannot open target app" }
                    delay(1_000) // Do not bind to the launch animation's transient window geometry.
                    return withTimeoutOrNull(15_000) {
                    var previous: TouchProfile? = null
                    var found: TouchProfile? = null
                    var stable = 0
                    while (found == null) {
                        val current = TouchEnvironment.current(service, pkg)
                        stable = if (current != null && current == previous) stable + 1 else 0
                        if (stable >= 3) found = current
                        previous = current
                        if (found == null) delay(150)
                    }
                    found
                    } ?: error("Open the target app in one orientation without a keyboard, then retry calibration")
                }
                val originals = preset.steps.filter { it.kind == ActionKind.RECORDED_TOUCH }
                val accepted = mutableListOf<TouchStroke>()
                var activePkg: String? = null
                for ((index, step) in originals.withIndex()) {
                    val pkg = checkNotNull(step.packageName)
                    val profile = if (activePkg != pkg) openProfile(pkg).also {
                        profiles[pkg]?.let { previous -> check(TouchPolicy.compatibility(previous, it).isEmpty()) }
                        profiles[pkg] = it; activePkg = pkg
                    } else checkNotNull(profiles[pkg])
                    val original = checkNotNull(step.touchStroke)
                    val done = CompletableDeferred<TouchStroke>()
                    var edited = original
                    var exploring = false
                    var surface: TouchSurface? = null
                    var bar: View? = null
                    fun clear() {
                        surface?.editedStroke?.let { edited = it }
                        windows.close(); surface = null; bar = null
                    }
                    try {
                        while (!done.isCompleted) {
                            val current = TouchEnvironment.current(service, pkg)
                            if (current == null) clear()
                            else {
                                if (TouchPolicy.compatibility(profile, current).isNotEmpty()) {
                                    clear()
                                    mutable.value = TouchCalibrationState(true, "Calibration paused: restore the original app window and orientation. Accepted paths are retained.")
                                    delay(150); continue
                                }
                                if (bar == null) {
                                    if (!exploring) {
                                        surface = TouchSurface(service, profile, {}, {}, edited)
                                        windows.addCanvas(surface!!, profile)
                                    }
                                    bar = windows.bar(profile, "${index + 1}/${originals.size} (drag)", listOf(
                                        (if (exploring) "Edit" else "Explore") to { exploring = !exploring; clear() },
                                        "Accept" to {
                                            val frame = TouchEnvironment.current(service, pkg)
                                            if (frame != null && TouchPolicy.compatibility(profile, frame).isEmpty())
                                                done.complete(surface?.editedStroke ?: edited)
                                        },
                                        "Cancel" to { cancel() })).first
                                }
                            }
                            delay(100)
                        }
                        accepted += done.await()
                    } finally { clear() }
                }
                for (app in preset.apps) if (app.packageName !in profiles) profiles[app.packageName] = openProfile(app.packageName)
                val copy = TouchPolicy.calibratedCopy(preset, profiles.values.toList(), accepted)
                repository.save(copy)
                mutable.value = TouchCalibrationState(message = "Calibrated copy saved. Match the original starting screen before replay.", savedPresetId = copy.id)
                openApp(service)
            } catch (_: CancellationException) {
                mutable.value = TouchCalibrationState(message = "Calibration cancelled; original preset preserved")
            } catch (e: Exception) {
                mutable.value = TouchCalibrationState(message = "Calibration stopped: ${e.message}")
                openApp(service)
            } finally { windows.close(); service.clearIndicator() }
        }
    }
}

/** One per-run visual checkpoint, because coordinates cannot recognise an app's starting screen. */
object TouchStartGate {
    private var pending: CompletableDeferred<Boolean>? = null
    private var overlay: TouchWindows? = null
    fun cancel() { pending?.cancel(); overlay?.close() }

    suspend fun await(service: AutomationAccessibilityService, binding: TouchProfile): Boolean {
        val done = CompletableDeferred<Boolean>(); pending = done
        val windows = TouchWindows(service); overlay = windows
        var bar: View? = null
        return try {
            withTimeoutOrNull(120_000) {
                while (!done.isCompleted) {
                    val frame = TouchEnvironment.current(service, binding.packageName)
                    if (frame == null) { windows.close(); bar = null }
                    else if (bar == null) {
                        bar = windows.bar(frame, "Match start screen", listOf(
                            "Start" to {
                                val current = TouchEnvironment.current(service, binding.packageName)
                                if (current != null) {
                                    val errors = TouchPolicy.compatibility(binding, current)
                                    if (errors.isEmpty()) done.complete(true)
                                    else android.widget.Toast.makeText(service, errors.joinToString("; "), android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            "Stop" to { ExecutionController.stop(); BatchController.stop(); done.cancel() })).first
                    }
                    delay(100)
                }
                done.await()
            } ?: false
        } finally { windows.close(); pending = null; overlay = null }
    }
}
