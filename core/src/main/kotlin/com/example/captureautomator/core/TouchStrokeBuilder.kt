package com.example.captureautomator.core

import kotlin.math.abs

/** Bounded single-pointer sampling. Invalid/multi-touch streams are never partly forwarded. */
class TouchStrokeBuilder {
    private val points = mutableListOf<TouchPoint>()
    private var invalid = false
    fun add(x: Float, y: Float, timeMs: Long, pointers: Int = 1) {
        if (pointers != 1 || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f ||
            timeMs !in 0..TouchPolicy.MAX_DURATION_MS || (points.lastOrNull()?.timeMs ?: 0) > timeMs) {
            invalid = true
            return
        }
        if (points.size > 1 && timeMs - points.last().timeMs < 8) {
            points[points.lastIndex] = TouchPoint(x, y, timeMs)
            return
        }
        points += TouchPoint(x, y, timeMs)
        if (points.size > TouchPolicy.MAX_POINTS) {
            val reduced = points.filterIndexed { index, _ -> index == 0 || index == points.lastIndex || index % 2 == 0 }
            points.clear(); points += reduced
        }
    }
    fun invalidate() { invalid = true }
    fun finish(durationMs: Long): TouchStroke? {
        if (invalid || points.isEmpty() || durationMs !in 0..TouchPolicy.MAX_DURATION_MS) return null
        val duration = durationMs.coerceAtLeast(1)
        val first = points.first()
        val stationary = points.all { abs(it.x - first.x) < .003f && abs(it.y - first.y) < .003f }
        val result = TouchStroke(if (stationary) listOf(first.copy(timeMs = 0)) else points.toList(), duration)
        return result.takeIf { TouchPolicy.errors(it).isEmpty() }
    }
}
