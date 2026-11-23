package com.example.captureautomator.core

/** Public geometry policy only. Contains no text, key values or keyboard node contents. */
object TouchInputPolicy {
    private fun right(r: TouchViewport) = r.left + r.width
    private fun bottom(r: TouchViewport) = r.top + r.height
    fun intersection(a: TouchViewport, b: TouchViewport): TouchViewport? {
        val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
        val w = minOf(right(a), right(b)) - l; val h = minOf(bottom(a), bottom(b)) - t
        return if (w > 0 && h > 0) TouchViewport(l, t, w, h) else null
    }
    fun keyboardResize(base: TouchViewport, current: TouchViewport, keyboards: List<TouchViewport>): Boolean =
        base.left == current.left && base.top == current.top && base.width == current.width &&
            current.height in 1..base.height && keyboards.any {
                val overlap = intersection(base, it)
                overlap != null && it.top <= bottom(current) + 4 && bottom(it) >= bottom(base) - 4
            }

    /** Partition public overlay windows instead of using Android's hidden touch-region API.
     * Excluded rectangles receive native touches directly, never recorder samples. */
    fun captureRegions(app: TouchViewport, excluded: List<TouchViewport>, limit: Int = 32): List<TouchViewport> {
        var regions = listOf(app)
        for (hole in excluded) {
            regions = regions.flatMap { r ->
                val cut = intersection(r, hole)
                if (cut == null) listOf(r) else listOf(
                    TouchViewport(r.left, r.top, r.width, cut.top - r.top),
                    TouchViewport(r.left, bottom(cut), r.width, bottom(r) - bottom(cut)),
                    TouchViewport(r.left, cut.top, cut.left - r.left, cut.height),
                    TouchViewport(right(cut), cut.top, right(r) - right(cut), cut.height)
                ).filter { it.width > 1 && it.height > 1 }
            }
            if (regions.size > limit) return emptyList()
        }
        return regions
    }
    fun intersects(stroke: TouchStroke, profile: TouchProfile, area: TouchViewport): Boolean {
        val points = stroke.points.map { TouchPolicy.map(it, profile) }
        fun inside(x: Float, y: Float) = x >= area.left && x < right(area) && y >= area.top && y < bottom(area)
        if (points.any { inside(it.first, it.second) }) return true
        // Conservative swept boxes also reject a fast swipe crossing a keyboard between samples.
        return points.zipWithNext().any { (a, b) ->
            maxOf(a.first, b.first) >= area.left && minOf(a.first, b.first) < right(area) &&
                maxOf(a.second, b.second) >= area.top && minOf(a.second, b.second) < bottom(area)
        }
    }
    fun focusTap(stroke: TouchStroke, profile: TouchProfile): Boolean {
        val p = stroke.points.map { TouchPolicy.map(it, profile) }
        return stroke.durationMs <= 350 && p.isNotEmpty() && p.all {
            kotlin.math.abs(it.first - p.first().first) <= 12 && kotlin.math.abs(it.second - p.first().second) <= 12
        }
    }
    fun allowed(stroke: TouchStroke, profile: TouchProfile, keyboards: List<TouchViewport>,
                passwords: List<TouchViewport>, editable: List<TouchViewport>, keyboardVisible: Boolean): Boolean {
        if (TouchPolicy.errors(stroke).isNotEmpty()) return false
        if ((keyboards + passwords).any { intersects(stroke, profile, it) }) return false
        return editable.none { intersects(stroke, profile, it) } || (!keyboardVisible && focusTap(stroke, profile))
    }
}
