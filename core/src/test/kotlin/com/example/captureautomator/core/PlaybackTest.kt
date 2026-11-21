package com.example.captureautomator.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTest {
    @Test fun `recorded delay applies once outside retries and capture attempt limit is one`() = runTest {
        var attempts = 0
        val timing = PlaybackTiming { }
        val tap = AutomationStep(label = "Tap", kind = ActionKind.TAP,
            interActionDelayMs = 1000, maxAttempts = 3)
        runPlaybackStep(timing, tap) { attempts++; false to "retry" }
        assertEquals(3, attempts)
        assertEquals(2050, currentTime) // 1000 + retry backoffs 350 and 700
        attempts = 0
        runPlaybackStep(timing, tap.copy(kind = ActionKind.TAKE_PHOTO)) {
            attempts++; false to "uncertain gesture"
        }
        assertEquals(1, attempts)
        assertEquals(3050, currentTime)
    }
    @Test fun `baseline IDs and old rows are never attributed to run`() {
        val baseline = setOf("p:1", "v:2")
        assertFalse(isNewRunMedia("p:1", baseline, 100, 100))
        assertFalse(isNewRunMedia("p:3", baseline, 99, 100))
        assertTrue(isNewRunMedia("p:3", baseline, 100, 100))
        assertEquals(setOf("p:1", "v:2"), baseline)
    }
    private val app = AppSpec("com.android.camera", "Camera")
    private val locator = UiLocator(resourceId = "com.android.camera:id/shutter_button")
    private fun step(kind: ActionKind, pkg: String = app.packageName) =
        AutomationStep(label = kind.name, kind = kind, packageName = pkg, locator = locator)
    private fun preset(vararg kinds: ActionKind, photos: Int = 1, videos: Int = 0) =
        Preset(name = "Smoke", apps = listOf(app), media = MediaExpectation(photos, videos),
            steps = kinds.map(::step))

    @Test fun `one photo zero video smoke workflow is executable`() {
        assertTrue(PresetValidation.canExecute(preset(ActionKind.LAUNCH_APP, ActionKind.TAKE_PHOTO,
            ActionKind.VERIFY_MEDIA)).isEmpty())
    }

    @Test fun `generic shutter tap cannot satisfy media expectation`() {
        assertTrue(PresetValidation.canExecute(preset(ActionKind.LAUNCH_APP, ActionKind.TAP))
            .any { it.contains("Capture/count mismatch") })
    }

    @Test fun `two photo one video expectation blocks one photo preset`() {
        assertTrue(PresetValidation.canExecute(preset(ActionKind.TAKE_PHOTO, photos = 2, videos = 1))
            .any { it.contains("Capture/count mismatch") })
    }

    @Test fun `bad video sequences fail even when counts match`() {
        val bad = listOf(
            preset(ActionKind.STOP_VIDEO, ActionKind.START_VIDEO, photos = 0, videos = 1),
            preset(ActionKind.START_VIDEO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO,
                ActionKind.STOP_VIDEO, photos = 0, videos = 2),
            preset(ActionKind.START_VIDEO, ActionKind.TAKE_PHOTO, ActionKind.STOP_VIDEO, videos = 1),
            preset(ActionKind.START_VIDEO, ActionKind.VERIFY_MEDIA, ActionKind.STOP_VIDEO, photos = 0, videos = 1))
        bad.forEach { assertTrue(PresetValidation.playbackErrors(it).isNotEmpty()) }
        val good = preset(ActionKind.START_VIDEO, ActionKind.STOP_VIDEO, photos = 0, videos = 1)
        assertTrue(PresetValidation.playbackErrors(good).isEmpty())
        assertTrue(PresetValidation.playbackErrors(good.copy(steps = listOf(step(ActionKind.START_VIDEO),
            step(ActionKind.STOP_VIDEO, "other.camera")))).any { it.contains("target differs") })
    }

    @Test fun `classification review and delay bounds block unsafe playback`() {
        val p = preset(ActionKind.TAKE_PHOTO)
        assertTrue(PresetValidation.canExecute(p.copy(steps = p.steps.map {
            it.copy(classificationUncertain = true) })).any { it.contains("uncertain") })
        for (invalid in listOf(-1L, 300_001L)) {
            assertTrue(PresetValidation.errors(p.copy(steps = p.steps.map {
                it.copy(interActionDelayMs = invalid) })).any { it.contains("recorded delay") })
        }
    }

    @Test fun `recorded wait pauses and stops cancellably`() = runTest {
        var paused = false
        val timing = PlaybackTiming { while (paused) delay(10) }
        val job = launch { timing.waitMs(1000) }
        advanceTimeBy(200)
        paused = true
        advanceTimeBy(2000)
        assertTrue(job.isActive)
        paused = false
        advanceUntilIdle()
        assertEquals(3000, currentTime)
        val stopped = launch { timing.waitMs(300_000) }
        runCurrent()
        stopped.cancelAndJoin()
        assertTrue(stopped.isCancelled)
    }

    @Test fun `late shutter is polled then freshly resolved and dispatched once`() = runTest {
        var resolves = 0
        var dispatches = 0
        val result = captureOnce(PlaybackTiming { }, 2000,
            resolve = { resolves++; if (currentTime < 540) null else "shutter" },
            signature = { it }, dispatch = { dispatches++; true })
        assertTrue(result.gestureCompleted)
        assertEquals(1, dispatches)
        assertTrue(resolves >= 6)
    }

    @Test fun `stale moved disabled and ambiguous shutters never dispatch`() = runTest {
        for (mode in listOf("absent", "stale", "moved")) {
            var resolves = 0
            var dispatches = 0
            val result = captureOnce(PlaybackTiming { }, 600,
                resolve = {
                    resolves++
                    when {
                        mode == "absent" -> null
                        resolves <= 3 -> "shutter"
                        mode == "stale" -> null
                        else -> "moved shutter"
                    }
                }, signature = { it }, dispatch = { dispatches++; true })
            assertFalse(result.gestureAttempted)
            assertEquals(0, dispatches)
        }
    }

    @Test fun `failed capture gesture is never retried`() = runTest {
        var dispatches = 0
        val result = captureOnce(PlaybackTiming { }, 2000, { "shutter" }, { it }) {
            dispatches++; false
        }
        assertFalse(result.gestureCompleted)
        assertTrue(result.gestureAttempted)
        assertEquals(1, dispatches)
    }

    @Test fun `pause gate before final resolve prevents dispatch after stop`() {
        var resolves = 0
        var dispatches = 0
        assertThrows(CancellationException::class.java) {
            runTest {
                captureOnce(PlaybackTiming { if (resolves >= 3) throw CancellationException() },
                    2000, { resolves++; "shutter" }, { it }) { dispatches++; true }
            }
        }
        assertEquals(0, dispatches)
    }

    @Test fun `late MediaStore file must settle before success`() = runTest {
        var scans = 0
        val media = pollMedia(PlaybackTiming { }, MediaExpectation(1, 0), scan = {
            scans++
            if (scans < 4) MediaResult(issues = listOf("Not indexed"), verificationComplete = true)
            else MediaResult(1, 0, true, mediaEvidence = listOf("photo-id"))
        })
        assertEquals(6, scans)
        assertTrue(media.issues.isEmpty())
    }

    @Test fun `slow shared save arriving after old timeout is verified once stable`() = runTest {
        val result = pollMedia(PlaybackTiming { }, MediaExpectation(1, 0), scan = {
            if (currentTime < 28_000) MediaResult(verificationComplete = true)
            else MediaResult(1, 0, true, mediaEvidence = listOf("completed-night-photo"))
        }, maxScans = 61, intervalMs = 1000)
        assertEquals(Outcome.PASS, Verdict.outcome(emptyList(), result, MediaExpectation(1, 0)))
        assertEquals(30_000, currentTime)
    }

    @Test fun `missing or inaccessible media stops at bounded scan budget`() = runTest {
        var scans = 0
        val media = pollMedia(PlaybackTiming { }, MediaExpectation(1, 0), scan = {
            scans++; MediaResult(issues = listOf("Unreadable"))
        })
        assertEquals(31, scans)
        assertEquals(15_000, currentTime)
        assertFalse(media.verificationComplete)
        assertEquals(Outcome.PARTIAL, Verdict.outcome(emptyList(), media, MediaExpectation(1, 0)))
    }

    @Test fun `media polling can be stopped and never passes excess media`() = runTest {
        val job = launch { pollMedia(PlaybackTiming { }, MediaExpectation(1, 0), scan = { MediaResult() }) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        val excess = pollMedia(PlaybackTiming { }, MediaExpectation(1, 0), scan = {
            MediaResult(2, 0, true, listOf("Excess photos"))
        })
        assertEquals(Outcome.FAIL, Verdict.outcome(emptyList(), excess, MediaExpectation(1, 0)))
    }
}
