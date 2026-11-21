package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.captureautomator.core.UiLocator
import com.example.captureautomator.core.UiCandidate
import com.example.captureautomator.core.SelectorPolicy
import com.example.captureautomator.core.PlaybackTiming
import com.example.captureautomator.core.captureOnce
import com.example.captureautomator.core.TouchStroke
import com.example.captureautomator.core.TouchProfile
import com.example.captureautomator.core.TouchPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Fail-closed semantic matching. Coordinates are NEVER used without explicit preset opt-in. */
class UiResolver(private val service: AutomationAccessibilityService,
                 private val timing: PlaybackTiming = PlaybackTiming { }) {
    data class Match(val node: AccessibilityNodeInfo, val confidence: Float, val reason: String)

    fun resolve(locator: UiLocator?): Match? {
        if (locator == null) return null
        val root = service.currentRoot() ?: return null
        val candidates = mutableListOf<Match>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 45 || candidates.size > 5_000) return
            val score = score(node, locator)
            if (score >= 0.75f) candidates += Match(node, score, "Semantic selector")
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        val sorted = candidates.sortedByDescending { it.confidence }
        val best = sorted.firstOrNull() ?: return null
        // Two indistinguishable "Save" buttons is an error, not permission to tap at random.
        if (sorted.size > 1 && abs(sorted[0].confidence - sorted[1].confidence) < 0.04f) return null
        return best
    }

    private fun score(node: AccessibilityNodeInfo, locator: UiLocator): Float =
        SelectorPolicy.confidence(UiCandidate(
            resourceId = node.viewIdResourceName,
            contentDescription = node.contentDescription?.toString(),
            text = if (node.isEditable || node.isPassword) null else node.text?.toString(),
            className = node.className?.toString(),
            visible = node.isVisibleToUser
        ), locator)

    fun visibleElement(locator: UiLocator?): Boolean = resolve(locator) != null

    fun activeFingerprint(): String {
        val root = service.currentRoot() ?: return "no-window"
        val content = StringBuilder(root.packageName ?: "")
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count++ > 100 || depth > 15) return
            content.append('|').append(n.viewIdResourceName.orEmpty())
            if (!n.isEditable && !n.isPassword) {
                content.append(':').append(n.contentDescription?.take(48) ?: n.text?.take(48) ?: "")
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return content.toString()
    }

    suspend fun waitForElement(locator: UiLocator?, appears: Boolean, timeoutMs: Long): Boolean {
        return timing.poll(timeoutMs) { visibleElement(locator) == appears }
    }

    suspend fun waitForApp(packageName: String, timeoutMs: Long): Boolean {
        var stable = 0
        return timing.poll(timeoutMs) {
            val root = service.currentRoot()
            val bounds = Rect().also { root?.getBoundsInScreen(it) }
            val ready = service.activePackage() == packageName &&
                root?.packageName?.toString() == packageName && root.isVisibleToUser &&
                root.isEnabled && !bounds.isEmpty && root.childCount > 0
            stable = if (ready) stable + 1 else 0
            stable >= 2
        }
    }

    suspend fun waitForScreenChange(before: String, timeoutMs: Long): Boolean {
        return timing.poll(timeoutMs) { activeFingerprint() != before }
    }

    /** Readiness probes never click. Resolve again after the final pause gate, then send one stroke.
     * Capture never tries ACTION_CLICK followed by a gesture: a false return can still be ambiguous.
     */
    suspend fun capture(locator: UiLocator?, packageName: String, timeoutMs: Long,
                        beforeFinalResolve: suspend () -> Unit = {}, onDispatch: () -> Unit = {},
                        stateReady: () -> Boolean = { true }): Pair<Boolean, String> {
        fun ready(): Match? {
            if (service.activePackage() != packageName || !stateReady()) return null
            val match = resolve(locator) ?: return null
            val node = match.node
            if (!node.refresh() || score(node, locator ?: return null) < .75f) return null
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val window = service.currentWindowBounds()
            return match.takeIf { node.packageName?.toString() == packageName &&
                node.isVisibleToUser && node.isEnabled && !bounds.isEmpty &&
                window.contains(bounds) }
        }
        val result = captureOnce(timing, timeoutMs, ::ready,
            signature = { match -> Rect().also(match.node::getBoundsInScreen).toShortString() },
            beforeFinalResolve = beforeFinalResolve,
            dispatch = { match ->
                val bounds = Rect().also(match.node::getBoundsInScreen)
                onDispatch()
                gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 80)
            })
        return result.gestureCompleted to "${result.status}: gestureAttempted=${result.gestureAttempted}; " +
            "camera acceptance/save unconfirmed until MediaStore verification; no capture retry"
    }

    suspend fun tap(locator: UiLocator?, kind: String, durationMs: Long): Pair<Boolean, String> {
        if (locator == null) return false to "No selector"
        val match = resolve(locator)
        if (match != null) {
            if (kind == "TAP" && clickNode(match.node)) return true to "Semantic ACTION_CLICK ${match.confidence}"
            val bounds = Rect().also { match.node.getBoundsInScreen(it) }
            if (bounds.isEmpty) return false to "Matched node has no valid bounds"
            val result = when (kind) {
                "LONG_PRESS" -> gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), durationMs.coerceAtLeast(650))
                "DOUBLE_TAP" -> gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 65) &&
                    gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                        bounds.centerX().toFloat(), bounds.centerY().toFloat(), 65)
                else -> gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 80)
            }
            return result to "Gesture at resolved semantic node ${match.confidence}"
        }
        val normalizedX = locator.normalizedX
        val normalizedY = locator.normalizedY
        if (locator.allowCoordinateFallback && normalizedX != null && normalizedY != null) {
            val point = mapWindowCoords(normalizedX, normalizedY)
            return gesture(point.first, point.second, point.first, point.second,
                if (kind == "LONG_PRESS") 650 else 80) to "Explicitly approved coordinate fallback"
        }
        return false to "No unique high-confidence selector; manual repair required"
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cursor: AccessibilityNodeInfo? = node
        var count = 0
        while (cursor != null && count++ < 5) {
            if (cursor.isClickable && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cursor = cursor.parent
        }
        return false
    }

    fun scroll(locator: UiLocator?): Boolean {
        val root = service.currentRoot() ?: return false
        val target = resolve(locator)?.node ?: root
        fun scrollFirst(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 15) return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (scrollFirst(child, depth + 1)) return true
            }
            return false
        }
        return scrollFirst(target, 0)
    }

    fun enterText(locator: UiLocator?, authorizedText: String?): Boolean {
        if (authorizedText.isNullOrBlank()) return false
        val node = resolve(locator)?.node ?: return false
        if (node.isPassword) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, authorizedText)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun swipe(locator: UiLocator?, durationMs: Long): Boolean {
        if (locator?.allowCoordinateFallback != true) return false
        val sx = locator.normalizedX ?: return false
        val sy = locator.normalizedY ?: return false
        val ex = locator.endNormalizedX ?: return false
        val ey = locator.endNormalizedY ?: return false
        val start = mapWindowCoords(sx, sy)
        val end = mapWindowCoords(ex, ey)
        return gesture(start.first, start.second, end.first, end.second, durationMs.coerceIn(80, 3_000))
    }

    private fun mapWindowCoords(x: Float, y: Float): Pair<Float, Float> {
        val b = service.currentWindowBounds()
        return (b.left + b.width() * x.coerceIn(0f, 1f)) to
            (b.top + b.height() * y.coerceIn(0f, 1f))
    }

    /** One raw stroke, with no selector or camera-mode inference and no retry. */
    suspend fun replayTouch(stroke: TouchStroke, profile: TouchProfile,
                            ready: () -> Boolean, onDispatch: () -> Unit = {}): Pair<Boolean, String> {
        if (TouchPolicy.errors(stroke).isNotEmpty()) return false to "TOUCH_INVALID: path rejected"
        timing.waitMs(0)
        if (!ready()) return false to "TOUCH_INCOMPATIBLE: foreground/window changed; no gesture sent"
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val (x, y) = TouchPolicy.map(point, profile)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val description = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, stroke.durationMs)).build()
        val deferred = CompletableDeferred<Boolean>()
        onDispatch()
        currentCoroutineContext().ensureActive()
        if (!ready()) return false to "TOUCH_INCOMPATIBLE: state changed before dispatch; no gesture sent"
        val dispatched = service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { deferred.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { deferred.complete(false) }
        }, Handler(Looper.getMainLooper()))
        val completed = dispatched && (withTimeoutOrNull(stroke.durationMs + 2000) { deferred.await() } ?: false)
        return completed to if (completed) "TOUCH_GESTURE_COMPLETED: one recorded stroke; media not yet verified"
            else "TOUCH_GESTURE_FAILED_OR_UNCERTAIN: not retried"
    }

    private suspend fun gesture(x: Float, y: Float, endX: Float, endY: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, duration)).build()
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { deferred.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { deferred.complete(false) }
        }, Handler(Looper.getMainLooper()))
        return dispatched && (withTimeoutOrNull(duration + 2_000) { deferred.await() } ?: false)
    }
}
