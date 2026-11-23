package com.example.captureautomator.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TouchInputPolicyTest {
    private val full = TouchViewport(0, 80, 1080, 2200)
    private val ime = TouchViewport(0, 1400, 1080, 880)
    private val edit = TouchViewport(100, 500, 800, 150)
    private val password = TouchViewport(100, 700, 800, 150)
    private val p = TouchProfile("app", "1", "maker", "phone", 1080, 2400, 0, 440, 1f, "en", 0, 80, 1080, 2200, androidSdk = 34)
    private fun tap(x: Float, y: Float) = TouchStroke(listOf(TouchPoint(x / 1079, (y - 80) / 2199, 0)), 1)
    @Test fun `bottom keyboard resize retains frame but split and moved panes do not`() {
        assertTrue(TouchInputPolicy.keyboardResize(full, full.copy(height = 1320), listOf(ime)))
        assertFalse(TouchInputPolicy.keyboardResize(full, full.copy(left = 20, width = 1060, height = 1320), listOf(ime)))
        assertFalse(TouchInputPolicy.keyboardResize(full, full.copy(height = 1320), emptyList()))
        assertFalse(TouchInputPolicy.keyboardResize(full, full.copy(height = 1000), listOf(ime)))
    }
    @Test fun `capture windows exclude keyboard and fields without blocking ordinary buttons`() {
        val regions = TouchInputPolicy.captureRegions(full, listOf(ime, edit, password))
        assertTrue(regions.isNotEmpty())
        assertTrue(regions.all { r -> listOf(ime, edit, password).none { TouchInputPolicy.intersection(r, it) != null } })
        assertTrue(regions.any { TouchInputPolicy.intersection(it, TouchViewport(500, 300, 1, 1)) != null })
    }
    @Test fun `floating keyboard is excluded as a hole rather than hiding entire recorder`() {
        val floating = TouchViewport(500, 1100, 500, 600)
        val regions = TouchInputPolicy.captureRegions(full, listOf(floating))
        assertTrue(regions.size > 1)
        assertTrue(regions.all { TouchInputPolicy.intersection(it, floating) == null })
    }
    @Test fun `fullscreen keyboard leaves controls only and no capture surfaces`() {
        assertTrue(TouchInputPolicy.captureRegions(full, listOf(full)).isEmpty())
    }
    @Test fun `keyboard keys and password fields cannot become recorded strokes`() {
        assertFalse(TouchInputPolicy.allowed(tap(500f, 1700f), p, listOf(ime), listOf(password), listOf(edit), true))
        for (keyboard in listOf(true, false))
            assertFalse(TouchInputPolicy.allowed(tap(500f, 750f), p, emptyList(), listOf(password), listOf(edit), keyboard))
    }
    @Test fun `non password focus tap can open keyboard but typing area remains native afterward`() {
        assertTrue(TouchInputPolicy.allowed(tap(500f, 550f), p, emptyList(), listOf(password), listOf(edit), false))
        assertFalse(TouchInputPolicy.allowed(tap(500f, 550f), p, listOf(ime), listOf(password), listOf(edit), true))
        assertTrue(TouchInputPolicy.allowed(tap(980f, 200f), p, listOf(ime), listOf(password), listOf(edit), true))
    }
    @Test fun `field long press and selection swipe are excluded`() {
        assertFalse(TouchInputPolicy.allowed(tap(500f, 550f).copy(durationMs = 800), p, emptyList(), emptyList(), listOf(edit), false))
        val swipe = TouchStroke(listOf(TouchPoint(.1f, .2f, 0), TouchPoint(.8f, .25f, 200)), 200)
        assertFalse(TouchInputPolicy.allowed(swipe, p, emptyList(), emptyList(), listOf(edit), false))
    }
    @Test fun `fast swipe crossing keyboard between samples fails closed`() {
        val stroke = TouchStroke(listOf(TouchPoint(.1f, .1f, 0), TouchPoint(.8f, .9f, 200)), 200)
        assertFalse(TouchInputPolicy.allowed(stroke, p, listOf(ime), emptyList(), emptyList(), true))
    }
    @Test fun `region splitting has bounded cost and no overlaps`() {
        val holes = (0..40).map { TouchViewport(it * 24, 800, 12, 100) }
        assertTrue(TouchInputPolicy.captureRegions(full, holes, 4).isEmpty())
        val rs = TouchInputPolicy.captureRegions(full, listOf(edit, password, ime))
        for (i in rs.indices) for (j in i + 1 until rs.size) assertNull(TouchInputPolicy.intersection(rs[i], rs[j]))
    }
    @Test fun `landscape tablet keyboard preserves the correct safe frame`() {
        val base = TouchViewport(80, 48, 2480, 1456)
        val keyboard = TouchViewport(80, 960, 2480, 544)
        assertTrue(TouchInputPolicy.keyboardResize(base, base.copy(height = 912), listOf(keyboard)))
        assertFalse(TouchInputPolicy.keyboardResize(base, base.copy(width = 1200, height = 912), listOf(keyboard)))
    }
}
