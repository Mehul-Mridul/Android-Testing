package com.example.captureautomator.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.captureautomator.core.*

/** Reads only known camera controls, and only when called by explicit recording/playback. */
class CameraUi(private val service: AutomationAccessibilityService, private val timing: PlaybackTiming) {
    private val ui = UiResolver(service, timing)
    var recordingStartedAtElapsedMs: Long? = null
        private set

    companion object {
        data class Observation(val state: CameraState, val evidence: Map<String, String>)
        fun observe(root: AccessibilityNodeInfo?): Observation {
            if (root?.packageName?.toString() != CameraPolicy.PACKAGE)
                return Observation(CameraState.UNKNOWN, mapOf("targetForeground" to "false"))
            val visible = mutableListOf<AccessibilityNodeInfo>()
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (++visited > 1000 || depth > 40) return
                if (node.packageName?.toString() == CameraPolicy.PACKAGE && node.isVisibleToUser) visible += node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            val shutters = visible.filter { it.viewIdResourceName == CameraPolicy.SHUTTER && it.isEnabled }
            val shutterState = if (shutters.size == 1) CameraPolicy.state(shutters.single().contentDescription?.toString()) else CameraState.UNKNOWN
            val strips = visible.filter { it.viewIdResourceName == CameraPolicy.MODE_STRIP }
            val strip = strips.singleOrNull() ?: visible.singleOrNull {
                it.viewIdResourceName == "${CameraPolicy.PACKAGE}:id/camera_mode_menu_root"
            }
            val stripBounds = Rect().also { strip?.getBoundsInScreen(it) }
            val expectedLabel = if (shutterState == CameraState.PHOTO_READY) "PhotoMode" else "VideoMode"
            val modes = visible.filter { it.contentDescription?.toString() == expectedLabel }
            val centers = modes.map { Rect().also(it::getBoundsInScreen).centerX() }
            val centered = !stripBounds.isEmpty && centers.any {
                kotlin.math.abs(it - stripBounds.centerX()) < stripBounds.width() * .1f
            }
            val state = when (shutterState) {
                CameraState.RECORDING -> CameraState.RECORDING
                CameraState.PHOTO_READY, CameraState.VIDEO_READY -> if (centered) shutterState else CameraState.UNKNOWN
                else -> CameraState.UNKNOWN
            }
            return Observation(state, mapOf("shutterCount" to shutters.size.toString(),
                "shutterState" to shutterState.name, "stripCount" to strips.size.toString(),
                "stripCenterX" to stripBounds.centerX().toString(), "modeCenters" to centers.joinToString(),
                "centered" to centered.toString()))
        }
        fun state(root: AccessibilityNodeInfo?): CameraState = observe(root).state

    }

    fun state(): CameraState = state(service.currentRoot())

    suspend fun waitFor(expected: CameraState, timeoutMs: Long): Boolean {
        var stable = 0
        return timing.poll(timeoutMs) {
            stable = if (state() == expected) stable + 1 else 0
            stable >= 2
        }
    }

    suspend fun ensureMode(video: Boolean, timeoutMs: Long): Pair<Boolean, String> {
        val expected = if (video) CameraState.VIDEO_READY else CameraState.PHOTO_READY
        timing.waitMs(0)
        if (state() == expected) return waitFor(expected, timeoutMs) to "MODE_CONFIRMED: $expected"
        if (state() == CameraState.RECORDING) return false to "MODE_BLOCKED: camera is recording"
        val locator = CameraPolicy.modeLocator(video)
        if (!timing.poll(timeoutMs) { ui.visibleElement(locator) })
            return false to "MODE_NOT_FOUND: ${if (video) "Video" else "Photo"} control; no shutter sent"
        timing.waitMs(0)
        val tapped = ui.capture(locator, CameraPolicy.PACKAGE, timeoutMs) { state() != CameraState.RECORDING }
        if (!tapped.first) return false to "MODE_GESTURE_FAILED: no shutter sent"
        return waitFor(expected, timeoutMs) to "MODE_TRANSITION: expected=$expected observed=${state()} evidence=${observe(service.currentRoot()).evidence}"
    }

    suspend fun capture(kind: ActionKind, timeoutMs: Long, videoDurationMs: Long = 5000): Pair<Boolean, String> {
        if (kind != ActionKind.STOP_VIDEO) {
            val mode = ensureMode(kind == ActionKind.START_VIDEO, timeoutMs)
            if (!mode.first) return mode
        }
        val expected = CameraPolicy.required(kind)
        if (!waitFor(expected, timeoutMs)) return false to "CAMERA_STATE_MISMATCH: expected=$expected observed=${state()}; no shutter sent"
        val startBeforeAction = recordingStartedAtElapsedMs
        var dispatchedAt = 0L
        val gesture = ui.capture(UiLocator(resourceId = CameraPolicy.SHUTTER), CameraPolicy.PACKAGE, timeoutMs,
            beforeFinalResolve = {
                if (kind == ActionKind.STOP_VIDEO) {
                    val start = checkNotNull(recordingStartedAtElapsedMs) { "No observed start gesture for timed stop" }
                    timing.waitUntil(start + videoDurationMs, android.os.SystemClock::elapsedRealtime)
                }
            }, onDispatch = {
                dispatchedAt = android.os.SystemClock.elapsedRealtime()
                if (kind == ActionKind.START_VIDEO) recordingStartedAtElapsedMs = dispatchedAt
            }) {
            state() == expected
        }
        if (!gesture.first) return gesture
        val post = when (kind) {
            ActionKind.START_VIDEO -> CameraState.RECORDING
            ActionKind.STOP_VIDEO -> CameraState.VIDEO_READY
            else -> CameraState.PHOTO_READY
        }
        val confirmed = waitFor(post, timeoutMs)
        if (confirmed && kind == ActionKind.STOP_VIDEO) recordingStartedAtElapsedMs = null
        val commandDuration = if (kind == ActionKind.STOP_VIDEO && startBeforeAction != null) dispatchedAt - startBeforeAction else 0
        return confirmed to "CAMERA_ACTION: kind=$kind gestureCompleted=true stateConfirmed=$confirmed expected=$post observed=${state()} commandVideoDurationMs=$commandDuration; media still requires verification; no capture retry"
    }
}
