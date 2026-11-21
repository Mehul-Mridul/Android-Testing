package com.example.captureautomator.data

import com.example.captureautomator.core.*
import org.junit.Assert.*
import org.junit.Test

class TouchCodecTest {
    private fun raw() = Preset(schemaVersion = 2, name = "Touch", apps = listOf(AppSpec("camera.test", "Camera")),
        media = MediaExpectation(1, 0), playbackMode = PlaybackMode.RECORDED_TOUCHES,
        touchProfile = TouchProfile("camera.test", "1", "Maker", "Model", 1080, 2400, 0, 420,
            1f, "en-US", 0, 90, 1080, 2180, androidSdk = 34), steps = listOf(
            AutomationStep(label = "Tap Portrait", kind = ActionKind.RECORDED_TOUCH, packageName = "camera.test",
                touchStroke = TouchStroke(listOf(TouchPoint(.3f, .7f, 0)), 80))))

    @Test fun oldSchemaWithoutNewFieldsLoadsAsSmartControls() {
        val json = """{"schemaVersion":1,"id":"old","name":"Old","apps":[{"packageName":"camera.test","displayName":"Camera"}],
            "media":{"photos":0,"videos":0,"videoDurationMs":5000,"durationToleranceMs":2000},
            "steps":[{"id":"wait","label":"Wait","kind":"WAIT_MS","waitMs":0,"timeoutMs":500,"maxAttempts":1}]}"""
        val parsed = Codec.parsePreset(json)
        assertFalse(TouchPolicy.isTouch(parsed))
        assertTrue(PresetValidation.canExecute(parsed).isEmpty())
    }

    @Test fun rawRoundTripPreservesPathProfileAndStartCheckpoint() {
        val preset = raw()
        val loaded = Codec.parsePreset(Codec.export(preset))
        assertEquals(preset, loaded)
        assertTrue(TouchPolicy.isTouch(loaded))
        assertEquals(true, loaded.requireTouchStart)
    }

    @Test fun unsupportedSchemaAndWrongModeAreRejected() {
        for (preset in listOf(raw().copy(schemaVersion = 3), raw().copy(schemaVersion = 1), raw().copy(playbackMode = null))) {
            assertThrows(IllegalArgumentException::class.java) { Codec.parsePreset(Codec.export(preset)) }
        }
    }

    @Test fun rawExpectedMediaDoesNotBypassActualVerification() {
        val loaded = Codec.parsePreset(Codec.export(raw()))
        assertTrue(PresetValidation.canExecute(loaded).isEmpty())
        assertEquals(Outcome.FAIL, Verdict.outcome(emptyList(), MediaResult(0, 0, true), loaded.media))
    }
}
