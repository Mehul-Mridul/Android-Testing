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

/** Visible capture-and-forward only during an explicitly started touch session. */
object TouchRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var forwarding: Job? = null
    private var windows: TouchWindows? = null
    private var canvas: TouchSurface? = null
    private var bar: View? = null
    private var label: TextView? = null
    private var lastCompletedAt = 0L
    private var busy = false
    private var generation = 0
    private val message = MutableStateFlow("")
    val status: StateFlow<String> = message

    fun resetDelay() { lastCompletedAt = SystemClock.elapsedRealtime() }
    fun stop() {
        generation++
        monitor?.cancel(); forwarding?.cancel()
        windows?.close(); windows = null; canvas = null; bar = null; label = null; busy = false
    }
    private fun showApp(service: AutomationAccessibilityService) {
        service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun hide() { windows?.close(); canvas = null; bar = null; label = null }

    fun start(service: AutomationAccessibilityService) {
        stop()
        val owner = generation
        windows = TouchWindows(service)
        message.value = "Open the selected app; wait for the Ready touch controls."
        monitor = scope.launch {
            try {
                val verifier = MediaVerifier(service)
                var segmentStart = System.currentTimeMillis()
                var segmentBase = verifier.baseline()
                var nextMinimum: Int? = null
                var previous: TouchProfile? = null
                var stable = 0
                var wasAvailable = false
                while (isActive) {
                    val draft = RecordingSession.state.value ?: break
                    if (!draft.active || draft.playbackMode != PlaybackMode.RECORDED_TOUCHES) break
                    if (busy) { hide(); delay(100); continue }
                    val current = draft.apps.firstNotNullOfOrNull { TouchEnvironment.current(service, it.packageName) }
                    if (current == null) {
                        hide(); wasAvailable = false; stable = 0
                        message.value = "Touch capture suspended outside the selected app or while a keyboard is visible."
                    } else {
                        stable = if (current == previous) stable + 1 else 0
                        previous = current
                        val profiles = draft.touchProfiles ?: listOfNotNull(draft.touchProfile)
                        val recorded = profiles.firstOrNull { it.packageName == current.packageName }
                        if (stable < 2) { hide(); delay(120); continue }
                        val binding = recorded ?: current
                        if (draft.activeTouchPackage != current.packageName || recorded == null) {
                            hide()
                            if (draft.activeTouchPackage != current.packageName) {
                                segmentStart = System.currentTimeMillis()
                                segmentBase = verifier.baseline()
                            }
                            RecordingSession.touchProfile(service, binding, nextMinimum)
                            nextMinimum = null
                            resetDelay()
                        }
                        if (TouchPolicy.compatibility(binding, current).isNotEmpty()) {
                            hide(); wasAvailable = false
                            message.value = "Touch capture suspended: restore the recorded orientation/app window. Your recording is retained."
                            delay(120); continue
                        }
                        if (!wasAvailable) resetDelay()
                        wasAvailable = true
                        if (bar == null) {
                            if (!draft.paused) {
                                canvas = TouchSurface(service, binding, { forward(service, binding, it) }, { message.value = it; android.widget.Toast.makeText(service, it, android.widget.Toast.LENGTH_LONG).show() })
                                windows?.addCanvas(canvas!!, binding)
                            }
                            val controls = windows!!.bar(binding, if (draft.paused) "Paused (drag)" else "Ready (drag)", listOf(
                                (if (draft.paused) "Resume" else "Pause") to { if (RecordingSession.state.value?.paused == true) RecordingSession.resume(service) else RecordingSession.pause(service); hide() },
                                "Back" to { back(service, binding) },
                                *if (draft.apps.size > 1) arrayOf("Next" to {
                                    val next = draft.apps[(draft.apps.indexOfFirst { it.packageName == binding.packageName } + 1) % draft.apps.size]
                                    if (!busy) {
                                        busy = true
                                        hide()
                                        forwarding = scope.launch {
                                            try {
                                                val touched = draft.steps.takeLastWhile { it.kind != ActionKind.LAUNCH_APP }
                                                    .any { it.kind == ActionKind.RECORDED_TOUCH }
                                                val requiresSave = !draft.paused && touched && (draft.media.photos > 0 || draft.media.videos > 0)
                                                if (requiresSave) android.widget.Toast.makeText(service, "Waiting for this app to save", android.widget.Toast.LENGTH_SHORT).show()
                                                val count = if (requiresSave) verifier.waitForSaved(segmentBase, draft.media,
                                                    binding.packageName, segmentStart, 1, PlaybackTiming { ensureActive() }) else 0
                                                if ((!requiresSave || count > 0) && service.activePackage() == binding.packageName) {
                                                    nextMinimum = count
                                                    com.example.captureautomator.util.AndroidDevice.launch(service, next.packageName)
                                                } else android.widget.Toast.makeText(service, "App switch withheld. Check the local save, or Pause then Next for navigation only.", android.widget.Toast.LENGTH_LONG).show()
                                            } finally { if (generation == owner) busy = false }
                                        }
                                    }
                                }) else emptyArray(),
                                "Stop" to { RecordingSession.stop(service); service.clearIndicator(); showApp(service) }))
                            bar = controls.first; label = controls.second
                        }
                        if (draft.paused) { windows?.remove(canvas); canvas = null; label?.text = "Paused (drag)" }
                        else if (canvas == null) { hide() }
                        else message.value = "Ready: single-finger gestures are forwarded after release."
                    }
                    delay(120)
                }
            } finally { if (owner == generation) hide() }
        }
    }

    private fun forward(service: AutomationAccessibilityService, profile: TouchProfile, stroke: TouchStroke) {
        if (busy || RecordingSession.state.value?.paused == true) return
        val delayMs = (SystemClock.elapsedRealtime() - lastCompletedAt - stroke.durationMs).coerceAtLeast(0)
        busy = true; hide(); message.value = "Forwarding one gesture; wait for Ready."
        val owner = generation
        forwarding = scope.launch {
            var stepId: String? = null
            var completed = false
            try {
                delay(100) // Remove both overlays before injection; never capture our own gesture.
                if (delayMs > 300_000) {
                    RecordingSession.stopWithIssue(service, "Recorded idle delay exceeded five minutes")
                    return@launch
                }
                val result = UiResolver(service).replayTouch(stroke, profile, ready = {
                    val draft = RecordingSession.state.value
                    val current = TouchEnvironment.current(service, profile.packageName)
                    draft?.active == true && !draft.paused && current != null && TouchPolicy.compatibility(profile, current).isEmpty()
                }, onDispatch = { stepId = RecordingSession.recordTouch(service, stroke, delayMs) })
                completed = result.first
                stepId?.let { RecordingSession.touchCompleted(service, it, completed) }
                lastCompletedAt = SystemClock.elapsedRealtime()
                if (!completed && RecordingSession.state.value?.active == true) {
                    message.value = "Gesture could not be forwarded safely. It was not retried."
                    RecordingSession.stopWithIssue(service, message.value); service.clearIndicator(); showApp(service)
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
                if (draft?.active == true && !draft.paused && current != null && TouchPolicy.compatibility(profile, current).isEmpty()) {
                    val wait = (SystemClock.elapsedRealtime() - lastCompletedAt).coerceIn(0, 300_000)
                    if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) RecordingSession.recordTouchBack(service, wait)
                    delay(200); resetDelay()
                }
            } finally { if (owner == generation) busy = false }
        }
    }
}
