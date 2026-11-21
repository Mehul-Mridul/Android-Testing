package com.example.captureautomator.core

import kotlinx.coroutines.delay

/** Pause time does not consume recorded waits. Stop cancels at every gate/delay. */
class PlaybackTiming(private val gate: suspend () -> Unit) {
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
