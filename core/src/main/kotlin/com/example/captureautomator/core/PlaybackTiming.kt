package com.example.captureautomator.core

import kotlinx.coroutines.delay

/** Pause time does not consume recorded waits. Stop cancels at every gate/delay. */
class PlaybackTiming(private val gate: suspend () -> Unit) {
    /** Real camera recording continues during scheduling delays and pauses. Recompute the
     * remaining deadline each time instead of adding readiness time to the requested duration. */
    suspend fun waitUntil(deadlineMs: Long, nowMs: () -> Long) {
        while (true) {
            gate()
            val remaining = deadlineMs - nowMs()
            if (remaining <= 0) return
            delay(minOf(remaining, 100L))
        }
    }

    suspend fun waitMs(durationMs: Long) {
        var remaining = durationMs
        while (remaining > 0) {
            gate()
            val slice = minOf(remaining, 100L)
            delay(slice)
            gate()
            remaining -= slice
        }
        gate()
    }

    suspend fun poll(timeoutMs: Long, intervalMs: Long = 180, ready: () -> Boolean): Boolean {
        var remaining = timeoutMs
        while (true) {
            gate()
            if (ready()) return true
            if (remaining <= 0) return false
            val slice = minOf(intervalMs, remaining)
            waitMs(slice)
            remaining -= slice
        }
    }
}
