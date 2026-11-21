package com.example.captureautomator.core

import kotlin.math.abs

data class UiCandidate(
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val className: String? = null,
    val visible: Boolean = true
)

/** Deliberately refuses to guess between equivalent accessibility nodes. */
object SelectorPolicy {
    fun confidence(candidate: UiCandidate, locator: UiLocator): Float {
        if (!candidate.visible) return 0f
        if (!locator.resourceId.isNullOrBlank() && candidate.resourceId == locator.resourceId) return 1f
        if (!locator.className.isNullOrBlank() && candidate.className != locator.className) return 0f
        if (!locator.contentDescription.isNullOrBlank() &&
            candidate.contentDescription?.trim()?.equals(locator.contentDescription.trim(), true) == true) return 0.92f
        if (!locator.text.isNullOrBlank() &&
            candidate.text?.trim()?.equals(locator.text.trim(), true) == true) return 0.84f
        return 0f
    }

    fun uniqueBestIndex(candidates: List<UiCandidate>, locator: UiLocator): Int? {
        val scored = candidates.mapIndexed { index, candidate -> index to confidence(candidate, locator) }
            .filter { it.second >= .75f }.sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        if (scored.size > 1 && abs(scored[0].second - scored[1].second) < .04f) return null
        return scored[0].first
    }
}
