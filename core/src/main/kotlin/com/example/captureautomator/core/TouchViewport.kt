package com.example.captureautomator.core

/** Geometry only, after Android has checked foreground ownership, window type and keyboard. */
data class TouchViewport(val left: Int, val top: Int, val width: Int, val height: Int)
object TouchViewportPolicy {
    /** A contained floating modal is not a resized task. Edge-attached/split windows fail closed. */
    fun isInsetDialog(main: TouchViewport, popup: TouchViewport): Boolean =
        popup.width > 0 && popup.height > 0 && popup.left > main.left && popup.top > main.top &&
            popup.left.toLong() + popup.width < main.left.toLong() + main.width &&
            popup.top.toLong() + popup.height < main.top.toLong() + main.height

    /** Reuse a full-screen binding only when today's safe area still matches it.
     * Another visible application or split divider means this may be a resized pane.
     */
    fun canReuseAnchor(base: TouchViewport, safe: TouchViewport, current: TouchViewport,
                       multiWindow: Boolean): Boolean {
        if (multiWindow || base != safe) return false
        val bottomSheet = current.width == base.width && current.left == base.left &&
            current.top > base.top && current.height > 0 &&
            current.top.toLong() + current.height == base.top.toLong() + base.height
        return isInsetDialog(base, current) || bottomSheet
    }

    fun mainWindow(candidates: List<TouchViewport>, screenWidth: Int, screenHeight: Int): TouchViewport? =
        candidates.filter { it.width > 0 && it.height > 0 && it.left >= 0 && it.top >= 0 &&
            it.left.toLong() + it.width <= screenWidth && it.top.toLong() + it.height <= screenHeight }
            .maxByOrNull { it.width.toLong() * it.height }
}
