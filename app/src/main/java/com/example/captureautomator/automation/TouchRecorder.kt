package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.example.captureautomator.core.*
import com.example.captureautomator.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Explicit touch recording; native keyboard/password regions never enter a capture window. */
object TouchRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var forwarding: Job? = null
    private var windows: TouchWindows? = null
    private val canvases = mutableListOf<TouchSurface>()
    private var bar: View? = null
    private var label: TextView? = null
    private var shown: List<Any>? = null
    private var lastCompletedAt = 0L
    private var busy = false
    private var generation = 0
    private val message = MutableStateFlow("")
    val status: StateFlow<String> = message
    internal fun controlsVisible() = bar?.isAttachedToWindow == true
    internal fun captureWindowCount() = canvases.size

    fun resetDelay() { lastCompletedAt = SystemClock.elapsedRealtime() }
    fun stop() {
        generation++
        monitor?.cancel(); forwarding?.cancel()
        windows?.close(); windows = null; canvases.clear(); bar = null; label = null; shown = null; busy = false
    }
    private fun showApp(service: AutomationAccessibilityService) {
        service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun hide() { windows?.close(); canvases.clear(); bar = null; label = null; shown = null }

    private fun render(service: AutomationAccessibilityService, binding: TouchProfile,
                       layout: TouchEnvironment.InputLayout, paused: Boolean, capture: Boolean = true) {
        val controls = windows!!.controlBounds(layout.controlArea,3)
        // A public geometric hole makes controls touchable regardless of OEM overlay input ordering.
        val areas = if (paused || !capture) emptyList() else layout.captureAreas.flatMap {
            TouchInputPolicy.captureRegions(it,listOf(controls))
        }.takeIf { it.size <= 32 }.orEmpty()
        val key: List<Any> = listOf(binding, areas, layout.controlArea, paused, layout.keyboard)
        if (shown != key || bar == null) {
            hide()
            for (area in areas) {
                val surface = TouchSurface(service, binding, { forward(service, binding, it) }, {
                    message.value = it; android.widget.Toast.makeText(service, it, android.widget.Toast.LENGTH_SHORT).show()
                }, area = area)
                windows!!.addCanvas(surface, binding, area); canvases += surface
            }
            // Controls are always added last, above every public capture window.
            val controls = windows!!.bar(binding, "Ready (drag)", listOf(
                (if (paused) "Resume" else "Pause") to {
                    if (RecordingSession.state.value?.paused == true) RecordingSession.resume(service) else RecordingSession.pause(service)
                    hide()
                },
                "Back" to { back(service, binding) },
                "Stop" to { RecordingSession.stop(service); service.clearIndicator(); showApp(service) }
            ), layout.controlArea)
            bar = controls.first; label = controls.second; shown = key
        }
        label?.text = if (paused) "Paused (drag)" else if (layout.keyboard) "Keyboard (drag)" else "Ready (drag)"
    }

    fun start(service: AutomationAccessibilityService) {
        stop()
        val owner = generation
        windows = TouchWindows(service)
        message.value = "Open the selected app; wait for the Ready touch controls."
        monitor = scope.launch {
            try {
                var unchangedSince = SystemClock.elapsedRealtime()
                var previous: TouchProfile? = null
                var stable = 0
                var wasAvailable = false
                while (isActive) {
                    val draft = RecordingSession.state.value ?: break
                    if (!draft.active || draft.playbackMode != PlaybackMode.RECORDED_TOUCHES) break
                    if (busy || windows?.controlsDragging == true) { delay(20); continue }
                    (draft.touchProfiles ?: listOfNotNull(draft.touchProfile)).forEach(TouchEnvironment::bind)
                    val current = draft.apps.firstNotNullOfOrNull { TouchEnvironment.current(service, it.packageName) }
                    if (current == null) {
                        hide(); wasAvailable = false; stable = 0
                        message.value = "Touch capture suspended outside the selected app. Your recording is retained."
                    } else {
                        stable = if (current == previous) stable + 1 else 0
                        if (current != previous) unchangedSince = SystemClock.elapsedRealtime()
                        previous = current
                        val profiles = draft.touchProfiles ?: listOfNotNull(draft.touchProfile)
                        val recorded = profiles.firstOrNull { it.packageName == current.packageName }
                        if (recorded == null && (stable < 2 || SystemClock.elapsedRealtime() - unchangedSince < 1000)) {
                            delay(80); continue
                        }
                        val binding = recorded ?: current
                        if (draft.activeTouchPackage != current.packageName || recorded == null) {
                            hide(); RecordingSession.touchProfile(service, binding); resetDelay()
                        }
                        val layout = TouchEnvironment.inputLayout(service, binding)
                        if (layout == null) { hide(); delay(40); continue }
                        if (layout.keyboard) RecordingSession.noteKeyboard(service)
                        val inputStable = TouchEnvironment.windowStable(service, binding)
                        val compatible = TouchPolicy.compatibility(binding, current).isEmpty()
                        if (!compatible && !layout.keyboard) {
                            hide(); wasAvailable = false
                            message.value = "Touch capture suspended: restore the recorded orientation/app window. Your recording is retained."
                            delay(120); continue
                        }
                        // Keyboard animation must never remove the user's Back/Stop controls.
                        render(service, binding, layout, draft.paused, capture = compatible)
                        if (!wasAvailable) resetDelay()
                        wasAvailable = true
                        message.value = when {
                            draft.paused -> "Paused. Keyboard input is never recorded."
                            !inputStable -> "Window settling; wait briefly before the next app tap. Keyboard input is not recorded."
                            layout.keyboard -> "Keyboard input is not recorded. App buttons remain available; Back closes the keyboard."
                            else -> "Ready: single-finger gestures are forwarded after release."
                        }
                    }
                    delay(60)
                }
            } finally { if (owner == generation) hide() }
        }
    }

    private fun forward(service: AutomationAccessibilityService, profile: TouchProfile, stroke: TouchStroke) {
        if (busy || RecordingSession.state.value?.paused == true) return
        val releasedAt = SystemClock.elapsedRealtime()
        val delayMs = (releasedAt - lastCompletedAt - stroke.durationMs).coerceAtLeast(0)
        busy = true; hide(); message.value = "Forwarding one gesture; wait for Ready."
        val owner = generation
        forwarding = scope.launch {
            var stepId: String? = null
            var completed = false
            try {
                delay(16)
                if (delayMs > 300_000) {
                    RecordingSession.stopWithIssue(service, "Recorded idle delay exceeded five minutes"); return@launch
                }
                val result = UiResolver(service).replayTouch(stroke, profile, ready = {
                    val draft = RecordingSession.state.value
                    val current = TouchEnvironment.current(service, profile.packageName)
                    draft?.active == true && !draft.paused && current != null && TouchPolicy.compatibility(profile, current).isEmpty() &&
                        TouchEnvironment.windowStable(service, profile)
                }, onDispatch = {
                    stepId = RecordingSession.recordTouch(service, stroke, delayMs, TouchEnvironment.popup(service, profile),
                        TouchEnvironment.keyboardVisible(service), TouchEnvironment.appViewport(service, profile))
                })
                completed = result.first
                stepId?.let { RecordingSession.touchCompleted(service, it, completed) }
                if (completed) lastCompletedAt = releasedAt
                if (!completed && RecordingSession.state.value?.active == true) {
                    if (stepId == null) {
                        // A protected field or changing IME was rejected before dispatch: keep recording.
                        message.value = "Input/window transition skipped safely. Keyboard typing is not recorded."
                    } else {
                        message.value = "Gesture could not be forwarded safely. It was not retried."
                        RecordingSession.stopWithIssue(service, message.value); service.clearIndicator(); showApp(service)
                    }
                }
            } finally {
                if (!completed) stepId?.let { RecordingSession.touchCompleted(service, it, false) }
                if (owner == generation) busy = false
            }
        }
    }

    private fun back(service: AutomationAccessibilityService, profile: TouchProfile) {
        if (busy || RecordingSession.state.value?.paused == true) return
        busy = true; hide()
        val owner = generation
        forwarding = scope.launch {
            try {
                delay(100)
                val current = TouchEnvironment.current(service, profile.packageName)
                val draft = RecordingSession.state.value
                val keyboard = TouchEnvironment.keyboardVisible(service)
                val appWindow = TouchEnvironment.appViewport(service, profile)
                val ready = current != null && (TouchPolicy.compatibility(profile, current).isEmpty() || keyboard)
                if (draft?.active == true && !draft.paused && ready && appWindow != null) {
                    val wait = (SystemClock.elapsedRealtime() - lastCompletedAt).coerceIn(0, 300_000)
                    if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                        RecordingSession.recordTouchBack(service, wait, keyboard, appWindow)
                    delay(200); resetDelay()
                }
            } finally { if (owner == generation) busy = false }
        }
    }
}
