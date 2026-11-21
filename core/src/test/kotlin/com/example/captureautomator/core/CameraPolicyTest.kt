package com.example.captureautomator.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.*

class CameraPolicyTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `stop deadline includes readiness time and re-resolves after wait`() = runTest {
        val timing = PlaybackTiming { }
        var resolutions = 0
        var dispatchedAt = 0L
        val result = captureOnce(timing, 2000, { resolutions++; "stop" }, { it },
            beforeFinalResolve = { timing.waitUntil(5000) { currentTime } }) {
            dispatchedAt = currentTime; true
        }
        assertTrue(result.gestureCompleted)
        assertEquals(5000, dispatchedAt)
        assertEquals(4, resolutions) // three stable probes plus final post-wait resolution
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `changed stop state after deadline wait never dispatches`() = runTest {
        val timing = PlaybackTiming { }
        var dispatched = false
        val result = captureOnce(timing, 2000, { if (currentTime < 1000) "stop" else null }, { it },
            beforeFinalResolve = { timing.waitUntil(5000) { currentTime } }) { dispatched = true; true }
        assertFalse(result.gestureAttempted)
        assertFalse(dispatched)
    }
    @Test fun `shared shutter ID cannot override wrong mode description or class`() {
        val video = UiLocator(resourceId = CameraPolicy.SHUTTER, contentDescription = "Start Recording", className = "Button")
        assertEquals(0f, SelectorPolicy.confidence(UiCandidate(CameraPolicy.SHUTTER, "Take photo", className = "Button"), video))
        assertEquals(0f, SelectorPolicy.confidence(UiCandidate(CameraPolicy.SHUTTER, "Stop Recording", className = "Button"), video))
        assertEquals(0f, SelectorPolicy.confidence(UiCandidate(CameraPolicy.SHUTTER, "Start Recording", className = "TextView"), video))
        assertEquals(1f, SelectorPolicy.confidence(UiCandidate(CameraPolicy.SHUTTER, "Start Recording", className = "Button"), video))
    }

    @Test fun `actual OEM shutter labels distinguish photo idle video and recording`() {
        assertEquals(CameraState.PHOTO_READY, CameraPolicy.state("Take photo. Button. Double-tap to take a photo."))
        assertEquals(CameraState.VIDEO_READY, CameraPolicy.state("Start Recording"))
        assertEquals(CameraState.RECORDING, CameraPolicy.state("Stop recording. Button."))
        assertEquals(CameraState.UNKNOWN, CameraPolicy.state(null))
        assertEquals(CameraState.UNKNOWN, CameraPolicy.state("Camera"))
    }

    @Test fun `post-stop start label produces one stop transition and duplicate events are ignored`() {
        val tracker = CameraRecordingTracker()
        assertNull(tracker.observe(CameraPolicy.state("Take photo")))
        assertEquals(ActionKind.SELECT_VIDEO_MODE, tracker.observe(CameraPolicy.state("Start Recording")))
        assertEquals(ActionKind.START_VIDEO, tracker.observe(CameraPolicy.state("Stop Recording")))
        repeat(10) { assertNull(tracker.observe(CameraState.RECORDING)) }
        assertNull(tracker.observe(CameraState.UNKNOWN))
        assertEquals(ActionKind.STOP_VIDEO, tracker.observe(CameraPolicy.state("Start Recording")))
        repeat(10) { assertNull(tracker.observe(CameraState.VIDEO_READY)) }
        assertEquals(ActionKind.SELECT_PHOTO_MODE, tracker.observe(CameraState.PHOTO_READY))
    }

    @Test fun `repair creates separate executable copy without changing original`() {
        val shutter = UiLocator(resourceId = CameraPolicy.SHUTTER, contentDescription = "Start Recording")
        val original = Preset(name = "Original", apps = listOf(AppSpec(CameraPolicy.PACKAGE, "Camera")),
            media = MediaExpectation(1, 1), steps = listOf(
                AutomationStep(label = "Photo", kind = ActionKind.TAKE_PHOTO, locator = shutter),
                AutomationStep(label = "Scroll", kind = ActionKind.SCROLL,
                    locator = UiLocator(resourceId = "com.android.camera:id/side_button_recycler_left")),
                AutomationStep(label = "Start video", kind = ActionKind.START_VIDEO, locator = shutter),
                AutomationStep(label = "Start video", kind = ActionKind.STOP_VIDEO, locator = shutter, interActionDelayMs = 8088)))
        val copy = CameraPolicy.repairCopy(original)
        assertNotEquals(original.id, copy.id)
        assertEquals(ActionKind.SELECT_VIDEO_MODE, copy.steps[1].kind)
        assertEquals("Stop video", copy.steps[3].label)
        assertEquals(0, copy.steps[3].interActionDelayMs)
        assertEquals(8088, original.steps[3].interActionDelayMs)
        assertEquals(ActionKind.SCROLL, original.steps[1].kind)
        assertTrue(PresetValidation.canExecute(copy).isEmpty())
    }

    @Test fun `repair never silently reinterprets second start as stop`() {
        val p = Preset(name = "Wrong", apps = listOf(AppSpec(CameraPolicy.PACKAGE, "Camera")),
            media = MediaExpectation(0, 1), steps = List(2) {
                AutomationStep(label = "Start", kind = ActionKind.START_VIDEO, locator = UiLocator(resourceId = CameraPolicy.SHUTTER)) })
        assertThrows(IllegalArgumentException::class.java) { CameraPolicy.repairCopy(p) }
    }

    @Test fun `only constrained known camera imports can skip review`() {
        val step = AutomationStep(label = "Photo", kind = ActionKind.TAKE_PHOTO,
            packageName = CameraPolicy.PACKAGE, locator = UiLocator(resourceId = CameraPolicy.SHUTTER))
        assertTrue(CameraPolicy.safeImportedStep(step))
        assertFalse(CameraPolicy.safeImportedStep(step.copy(kind = ActionKind.ENTER_TEXT)))
        assertFalse(CameraPolicy.safeImportedStep(step.copy(kind = ActionKind.TAP)))
        assertFalse(CameraPolicy.safeImportedStep(step.copy(packageName = "other.camera")))
        assertFalse(CameraPolicy.safeImportedStep(step.copy(locator = UiLocator(resourceId = "wrong:id/shutter"))))
    }
}
