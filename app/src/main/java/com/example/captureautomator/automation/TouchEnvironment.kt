package com.example.captureautomator.automation

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.captureautomator.core.*
import com.example.captureautomator.util.AndroidDevice

/** Only package, window and field geometry is inspected. Never reads text or IME key nodes. */
object TouchEnvironment {
    private val stableWindows = mutableMapOf<String, Pair<List<Any?>, Long>>()
    private val anchors = mutableMapOf<String, TouchProfile>()
    fun bind(profile: TouchProfile) { anchors[profile.packageName] = profile }
    private fun Rect.viewport() = TouchViewport(left, top, width(), height())
    private fun TouchProfile.viewport() = TouchViewport(left, top, width, height)
    private fun bounds(window: AccessibilityWindowInfo) = Rect().also(window::getBoundsInScreen)
    fun keyboardBounds(service: AutomationAccessibilityService): List<TouchViewport> = service.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true }
        .map(::bounds).filter { !it.isEmpty }.map { it.viewport() }
    fun keyboardVisible(service: AutomationAccessibilityService): Boolean = service.windows.any {
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true
    }

    private fun appWindow(service: AutomationAccessibilityService, pkg: String): AccessibilityWindowInfo? {
        val windows = service.windows
        val focused = windows.firstOrNull { it.isFocused }
        if (focused?.type == AccessibilityWindowInfo.TYPE_APPLICATION)
            return focused.takeIf { it.root?.packageName?.toString() == pkg }
        if (focused != null && focused.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive &&
                it.root?.isVisibleToUser == true && it.root?.packageName?.toString()?.let { p -> p != pkg } == true }) return null
        val candidates = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (Build.VERSION.SDK_INT < 33 || it.displayId == 0) &&
            it.root?.packageName?.toString() == pkg && it.root?.isVisibleToUser == true }
        if (focused?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && service.activePackage() != pkg) return null
        return candidates.firstOrNull { it.isActive } ?: candidates.maxByOrNull { it.layer }
    }
    fun appViewport(service: AutomationAccessibilityService, profile: TouchProfile): TouchViewport? {
        val window = appWindow(service, profile.packageName) ?: return null
        return TouchInputPolicy.intersection(profile.viewport(), bounds(window).viewport())
    }
    fun windowStable(service: AutomationAccessibilityService, profile: TouchProfile): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val signature: List<Any?> = listOf(appViewport(service, profile), keyboardVisible(service), keyboardBounds(service))
        val previous = stableWindows[profile.packageName]
        if (previous?.first != signature) { stableWindows[profile.packageName] = signature to now; return false }
        return now - previous.second >= 120
    }
    fun popup(service: AutomationAccessibilityService, profile: TouchProfile): Boolean? {
        val area = appViewport(service, profile) ?: return null
        if (area == profile.viewport()) return false
        if (keyboardVisible(service) && TouchInputPolicy.keyboardResize(profile.viewport(), area, keyboardBounds(service))) return false
        return true
    }

    data class InputLayout(val captureAreas: List<TouchViewport>, val controlArea: TouchViewport,
                           val keyboard: Boolean, val complete: Boolean,
                           val keys: List<TouchViewport>, val passwords: List<TouchViewport>, val fields: List<TouchViewport>)

    fun inputLayout(service: AutomationAccessibilityService, profile: TouchProfile): InputLayout? {
        val window = appWindow(service, profile.packageName) ?: return null
        val root = window.root ?: return null
        val keyboard = keyboardVisible(service)
        val keys = keyboardBounds(service)
        val fields = mutableListOf<TouchViewport>(); val passwords = mutableListOf<TouchViewport>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1500) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (node.isEditable || node.isPassword)) {
                val b = Rect().also(node::getBoundsInScreen)
                if (!b.isEmpty) { if (node.isPassword) passwords += b.viewport() else fields += b.viewport() }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        val frame = profile.viewport()
        val app = TouchInputPolicy.intersection(frame, bounds(window).viewport())
        val minBarHeight = (50 * service.resources.displayMetrics.density).toInt()
        val keyboardTop = keys.filter { TouchInputPolicy.intersection(frame, it) != null }.minOfOrNull { it.top }
        val controlArea = frame.copy(height = minOf(frame.height,
            maxOf(minBarHeight, (keyboardTop ?: (frame.top + frame.height)) - frame.top)))
        val complete = queue.isEmpty()
        val regions = if (!complete || app == null || (keyboard && keys.isEmpty())) emptyList()
            else TouchInputPolicy.captureRegions(app, keys + passwords + if (keyboard) fields else emptyList())
        return InputLayout(regions, controlArea, keyboard, complete, keys, passwords, fields)
    }

    fun avoidsInput(service: AutomationAccessibilityService, profile: TouchProfile, stroke: TouchStroke): Boolean {
        val window = appWindow(service, profile.packageName) ?: return false
        val b = bounds(window)
        if (stroke.points.any { val p = TouchPolicy.map(it, profile); !b.contains(p.first.toInt(), p.second.toInt()) }) return false
        val layout = inputLayout(service, profile) ?: return false
        if (!layout.complete || (layout.keyboard && layout.keys.isEmpty())) return false
        return TouchInputPolicy.allowed(stroke, profile, layout.keys, layout.passwords, layout.fields, layout.keyboard)
    }

    @Suppress("DEPRECATION")
    fun current(service: AutomationAccessibilityService, pkg: String): TouchProfile? {
        if ((service.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled) return null
        val focused = appWindow(service, pkg) ?: return null
        if (Build.VERSION.SDK_INT >= 33 && focused.displayId != 0) return null
        val root = focused.root ?: return null
        if (!root.refresh()) return null
        val windows = service.windows
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val candidates = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (Build.VERSION.SDK_INT < 33 || it.displayId == 0) && it.root?.packageName?.toString() == pkg }
            .map { bounds(it).viewport() }
        val selected = TouchViewportPolicy.mainWindow(candidates, metrics.widthPixels, metrics.heightPixels)
        val area = selected?.let { Rect(it.left, it.top, it.left + it.width, it.top + it.height) }
            ?: Rect().also(root::getBoundsInScreen)
        val safe = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safe.set(insets.left, insets.top, metrics.widthPixels - insets.right, metrics.heightPixels - insets.bottom)
        } else {
            fun dim(name: String): Int { val id = service.resources.getIdentifier(name, "dimen", "android"); return if (id == 0) 0 else service.resources.getDimensionPixelSize(id) }
            safe.set(0, dim("status_bar_height"), metrics.widthPixels, metrics.heightPixels - dim("navigation_bar_height"))
        }
        if (!area.intersect(safe) || area.width() < 100 || area.height() < 100) return null
        var profile = TouchProfile(pkg, AndroidDevice.version(service, pkg), Build.MANUFACTURER, Build.MODEL,
            metrics.widthPixels, metrics.heightPixels, wm.defaultDisplay.rotation, metrics.densityDpi,
            service.resources.configuration.fontScale, service.resources.configuration.locales.toLanguageTags(),
            area.left, area.top, area.width(), area.height(), androidSdk = Build.VERSION.SDK_INT)
        val multiWindow = windows.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
            (it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.isVisibleToUser == true && it.root?.packageName?.toString()?.let { p -> p != pkg } == true) }
        val base = anchors[pkg]
        val keys = keyboardBounds(service)
        if (base != null) {
            val restored = profile.copy(left = base.left, top = base.top, width = base.width, height = base.height)
            val regularPopup = TouchViewportPolicy.canReuseAnchor(base.viewport(), safe.viewport(), area.viewport(), multiWindow)
            // Keep absolute coordinates, never scale them. Injection is still clipped to the live app window.
            val imeResize = keys.isNotEmpty() && TouchInputPolicy.keyboardResize(base.viewport(), area.viewport(), keys)
            if ((regularPopup || imeResize) && TouchPolicy.compatibility(base, restored).isEmpty()) return restored
        }
        // A session can start with an already-open keyboard. Bind the unchanged full-screen origin.
        if (!multiWindow && keys.isNotEmpty() && TouchInputPolicy.keyboardResize(safe.viewport(), area.viewport(), keys))
            profile = profile.copy(left = safe.left, top = safe.top, width = safe.width(), height = safe.height())
        if (!multiWindow && profile.viewport() == safe.viewport()) anchors[pkg] = profile
        return profile
    }
}
