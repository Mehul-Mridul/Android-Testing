package com.example.captureautomator.core

import kotlinx.coroutines.withTimeoutOrNull

data class CaptureAttempt(val gestureCompleted: Boolean, val gestureAttempted: Boolean, val status: String)

fun isNewRunMedia(key: String, baselineIds: Set<String>, addedSeconds: Long, startedSeconds: Long): Boolean =
    key !in baselineIds && addedSeconds >= startedSeconds

suspend fun runPlaybackStep(
    timing: PlaybackTiming, step: AutomationStep, beforeAttempts: suspend () -> Unit = {},
    action: suspend (Int) -> Pair<Boolean, String>
): Pair<Boolean, String> {
    timing.waitMs(step.interActionDelayMs)
    beforeAttempts()
    val singleAttempt = step.kind in setOf(ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO,
        ActionKind.STOP_VIDEO, ActionKind.LAUNCH_APP)
    val limit = if (singleAttempt) 1 else step.maxAttempts
    var result = false to "Not executed"
    for (attempt in 1..limit) {
        timing.waitMs(0)
        result = action(attempt)
        if (result.first) break
        if (attempt != limit) timing.waitMs(350L * attempt)
    }
    return result
}

/** The only callback allowed to act is dispatch, invoked at most once after fresh resolution. */
suspend fun <T : Any> captureOnce(
    timing: PlaybackTiming, timeoutMs: Long, resolve: () -> T?, signature: (T) -> String,
    dispatch: suspend (T) -> Boolean
): CaptureAttempt {
    var previous: String? = null
    var stable = 0
    val ready = timing.poll(timeoutMs) {
        val current = resolve()?.let(signature)
        stable = if (current != null && current == previous) stable + 1 else 0
        previous = current
        stable >= 2
    }
    if (!ready) return CaptureAttempt(false, false, "CAPTURE_NOT_READY")
    timing.waitMs(0)
    val fresh = resolve() ?: return CaptureAttempt(false, false, "CAPTURE_CHANGED")
    if (signature(fresh) != previous) return CaptureAttempt(false, false, "CAPTURE_MOVED")
    val completed = dispatch(fresh)
    return CaptureAttempt(completed, true, if (completed) "GESTURE_COMPLETED" else "GESTURE_FAILED_OR_UNCERTAIN")
}

/** Fixed scan budget and hard coroutine deadline; transient results never trigger another capture. */
suspend fun pollMedia(
    timing: PlaybackTiming, expected: MediaExpectation, scan: suspend () -> MediaResult,
    onScan: (Int, MediaResult) -> Unit = { _, _ -> }
): MediaResult {
    var last = MediaResult(issues = listOf("Media verification timed out before first scan"))
    var previousEvidence: List<String>? = null
    var stable = 0
    val completed = withTimeoutOrNull(20_000) {
        for (index in 0..30) {
            timing.waitMs(0)
            last = scan()
            onScan(index + 1, last)
            val exact = last.verificationComplete && last.issues.isEmpty() &&
                last.photosFound == expected.photos && last.videosFound == expected.videos
            stable = if (exact && last.mediaEvidence == previousEvidence) stable + 1 else 0
            previousEvidence = last.mediaEvidence
            if (exact && stable >= 2) return@withTimeoutOrNull true
            if (index < 30) timing.waitMs(500)
        }
        false
    }
    return if (completed == true) last else last.copy(
        verificationComplete = last.verificationComplete && last.issues.isNotEmpty(),
        issues = last.issues + "Media verification deadline reached; no capture was retried")
}
