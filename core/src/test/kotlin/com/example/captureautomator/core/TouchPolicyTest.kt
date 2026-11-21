package com.example.captureautomator.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TouchPolicyTest {
    private fun profile() = TouchProfile("camera.test", "1", "Maker", "Model", 1080, 2400,
        0, 420, 1f, "en-US", 0, 90, 1080, 2180, androidSdk = 34)
    private fun tap() = TouchStroke(listOf(TouchPoint(.5f, .8f, 0)), 75)
    private fun preset() = Preset(schemaVersion = 2, name = "Portrait touch", apps = listOf(AppSpec("camera.test", "Camera")),
        media = MediaExpectation(1, 0), playbackMode = PlaybackMode.RECORDED_TOUCHES, touchProfile = profile(),
        steps = listOf(AutomationStep(label = "Tap", kind = ActionKind.RECORDED_TOUCH, packageName = "camera.test", touchStroke = tap())))

    @Test fun `raw media expectation does not require camera action classification`() {
        assertTrue(PresetValidation.canExecute(preset()).isEmpty())
        assertEquals(Outcome.PARTIAL, Verdict.outcome(emptyList(), MediaResult(), preset().media))
        assertEquals(Outcome.FAIL, Verdict.outcome(emptyList(), MediaResult(0, 0, true), preset().media))
        assertEquals(Outcome.PASS, Verdict.outcome(emptyList(), MediaResult(1, 0, true), preset().media))
    }

    @Test fun `raw schema and mode cannot silently fall back to smart playback`() {
        assertTrue(PresetValidation.errors(preset().copy(schemaVersion = 1)).any { it.contains("schema 2") })
        assertTrue(PresetValidation.errors(preset().copy(playbackMode = null)).any { it.contains("Recorded Touches") })
        assertTrue(PresetValidation.errors(preset().copy(touchProfile = null)).isNotEmpty())
        assertTrue(PresetValidation.errors(preset().copy(steps = listOf(AutomationStep(label = "Text", kind = ActionKind.ENTER_TEXT)))).isNotEmpty())
    }

    @Test fun `device version orientation window font locale and OS mismatches block replay`() {
        val original = profile()
        val mismatches = listOf(original.copy(model = "Other"), original.copy(manufacturer = "Other"),
            original.copy(appVersion = "2"), original.copy(rotation = 1), original.copy(width = 1000),
            original.copy(top = 120), original.copy(fontScale = 1.1f), original.copy(localeTags = "hi-IN"),
            original.copy(androidSdk = 35), original.copy(displayId = 1), original.copy(densityDpi = 480),
            original.copy(packageName = "other.app"))
        assertTrue(TouchPolicy.compatibility(original, original).isEmpty())
        mismatches.forEach { assertTrue(TouchPolicy.compatibility(original, it).isNotEmpty()) }
    }

    @Test fun `paths reject nonfinite out of bounds timestamps excessive duration and samples`() {
        val bad = listOf(tap().copy(points = emptyList()), tap().copy(durationMs = 10_001),
            TouchStroke(listOf(TouchPoint(Float.NaN, .5f, 0)), 75),
            TouchStroke(listOf(TouchPoint(1.1f, .5f, 0)), 75),
            TouchStroke(listOf(TouchPoint(.5f, .5f, 5)), 75),
            TouchStroke(listOf(TouchPoint(.5f, .5f, 0), TouchPoint(.6f, .6f, 90)), 75),
            TouchStroke(List(257) { TouchPoint(.5f, .5f, 0) }, 75))
        bad.forEach { assertTrue(TouchPolicy.errors(it).isNotEmpty()) }
    }

    @Test fun `map uses application area and never maps onto excluded right or bottom edges`() {
        val p = profile()
        assertEquals(0f to 90f, TouchPolicy.map(TouchPoint(0f, 0f, 0), p))
        assertEquals(1079f to 2269f, TouchPolicy.map(TouchPoint(1f, 1f, 0), p))
    }

    @Test fun `single pointer builder preserves tap hold and bounded swipe`() {
        val tap = TouchStrokeBuilder().apply { add(.5f, .8f, 0); add(.5f, .8f, 80) }.finish(80)!!
        assertEquals(1, tap.points.size)
        val hold = TouchStrokeBuilder().apply { add(.5f, .8f, 0); add(.5f, .8f, 900) }.finish(900)!!
        assertEquals(900, hold.durationMs)
        val builder = TouchStrokeBuilder()
        for (i in 0..1000) builder.add(i / 1000f, .5f, i * 8L)
        val swipe = builder.finish(8000)!!
        assertTrue(swipe.points.size <= 256)
        assertEquals(0f, swipe.points.first().x)
        assertEquals(1f, swipe.points.last().x)
    }

    @Test fun `multitouch cancelled and out of area streams cannot be partly forwarded`() {
        val multi = TouchStrokeBuilder().apply { add(.5f, .5f, 0); add(.6f, .6f, 20, 2); add(.5f, .5f, 50) }
        assertNull(multi.finish(50))
        assertNull(TouchStrokeBuilder().apply { add(.5f, .5f, 0); invalidate() }.finish(50))
        assertNull(TouchStrokeBuilder().apply { add(.5f, .5f, 0); add(-.1f, .5f, 50) }.finish(50))
    }

    @Test fun `calibration makes new device copy and keeps original geometry and paths`() {
        val original = preset()
        val current = profile().copy(model = "New device", screenWidth = 1440, width = 1440, appVersion = "2")
        val moved = TouchPolicy.moveEndpoint(tap(), false, .3f, .7f)!!
        val copy = TouchPolicy.calibratedCopy(original, current, listOf(moved))
        assertNotEquals(original.id, copy.id)
        assertEquals("Model", original.touchProfile!!.model)
        assertEquals(.5f, original.steps.single().touchStroke!!.points.single().x)
        assertEquals("New device", copy.touchProfile!!.model)
        assertEquals("2", copy.apps.single().recordedVersion)
        assertEquals(.3f, copy.steps.single().touchStroke!!.points.single().x, .0001f)
        assertTrue(PresetValidation.canExecute(copy).isEmpty())
    }

    @Test fun `calibration rejects incomplete review and paths moved outside the app`() {
        assertThrows(IllegalArgumentException::class.java) { TouchPolicy.calibratedCopy(preset(), profile(), emptyList()) }
        assertNull(TouchPolicy.moveEndpoint(tap(), false, -1f, .5f))
        val swipe = TouchStroke(listOf(TouchPoint(.1f, .2f, 0), TouchPoint(.5f, .4f, 500)), 500)
        val moved = TouchPolicy.moveEndpoint(swipe, true, .8f, .7f)!!
        assertEquals(swipe.points.first(), moved.points.first())
        assertEquals(.8f, moved.points.last().x, .0001f)
    }

    @Test fun `raw gesture failures are never retried even with imported attempt count five`() = runTest {
        var attempts = 0
        runPlaybackStep(PlaybackTiming { }, preset().steps.single().copy(maxAttempts = 5)) {
            attempts++; false to "uncertain"
        }
        assertEquals(1, attempts)
    }
    @Test fun `same app popup uses main viewport independent of window ordering`() {
        val main = TouchViewport(0, 90, 1080, 2180)
        val popup = TouchViewport(100, 800, 880, 500)
        assertTrue(TouchViewportPolicy.isInsetDialog(main, popup))
        assertFalse(TouchViewportPolicy.isInsetDialog(main, main.copy(height = 1000)))
        assertFalse(TouchViewportPolicy.isInsetDialog(main, popup.copy(left = -10)))
        assertEquals(main, TouchViewportPolicy.mainWindow(listOf(popup, main), 1080, 2400))
        assertEquals(main, TouchViewportPolicy.mainWindow(listOf(main, popup), 1080, 2400))
        assertNull(TouchViewportPolicy.mainWindow(listOf(main.copy(width = 1500)), 1080, 2400))
    }

    private fun multiApp(): Preset {
        val original = preset()
        val second = profile().copy(packageName = "other.app")
        return original.copy(apps = original.apps + AppSpec("other.app", "Second"),
            touchProfiles = listOf(profile(), second), steps = original.steps + listOf(
                AutomationStep(label = "Open Second", kind = ActionKind.LAUNCH_APP, packageName = "other.app"),
                original.steps.single().copy(id = "second", packageName = "other.app")))
    }

    @Test fun `multi app touches require profiles and explicit transitions`() {
        val preset = multiApp()
        assertTrue(PresetValidation.canExecute(preset).isEmpty())
        assertTrue(PresetValidation.errors(preset.copy(touchProfiles = listOf(profile()))).isNotEmpty())
        assertTrue(PresetValidation.errors(preset.copy(steps = preset.steps.filter { it.kind != ActionKind.LAUNCH_APP })).isNotEmpty())
        assertTrue(PresetValidation.errors(preset.copy(touchProfiles = TouchPolicy.profiles(preset) + profile())).isNotEmpty())
        assertEquals("other.app", TouchPolicy.profile(preset, "other.app")!!.packageName)
    }

    @Test fun `multi app calibration copies every binding without modifying original`() {
        val original = multiApp()
        val profiles = TouchPolicy.profiles(original).map { it.copy(model = "New", appVersion = "2") }
        val copy = TouchPolicy.calibratedCopy(original, profiles, listOf(tap(), tap()))
        assertTrue(PresetValidation.canExecute(copy).isEmpty())
        assertTrue(TouchPolicy.profiles(copy).all { it.model == "New" })
        assertTrue(TouchPolicy.profiles(original).all { it.model == "Model" })
        assertThrows(IllegalArgumentException::class.java) {
            TouchPolicy.calibratedCopy(original, profiles.take(1), listOf(tap(), tap()))
        }
    }

}
