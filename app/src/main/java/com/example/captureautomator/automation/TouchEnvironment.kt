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
import com.example.captureautomator.core.TouchPolicy
import com.example.captureautomator.core.TouchProfile
import com.example.captureautomator.core.TouchStroke
import com.example.captureautomator.core.TouchViewport
import com.example.captureautomator.core.TouchViewportPolicy
import com.example.captureautomator.util.AndroidDevice

object TouchEnvironment {
    private data class Anchor(val profile: TouchProfile)
    private val anchors = mutableMapOf<String, Anchor>()

    /** A recorded binding is revalidated against live safe area/configuration before reuse. */
    fun bind(profile: TouchProfile) { anchors[profile.packageName] = Anchor(profile) }

    // Compare the focused window to the bound app area, excluding system insets.
    fun popup(service: AutomationAccessibilityService, profile: TouchProfile): Boolean? {
        val window = service.windows.firstOrNull { it.isFocused } ?: return null
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.root?.packageName != profile.packageName) return null
        val bounds = Rect().also(window::getBoundsInScreen)
        return !bounds.contains(Rect(profile.left, profile.top, profile.left + profile.width, profile.top + profile.height))
    }

    /** Inspect field geometry only. A focused caption does not block buttons elsewhere. */
    fun avoidsInput(service: AutomationAccessibilityService, profile: TouchProfile, stroke: TouchStroke): Boolean {
        // A progress/toast-like application popup can temporarily take focus. Do not
        // record a tap through it into the underlying screen: its lifetime varies on replay.
        val focused = service.windows.firstOrNull { it.isFocused }
        if (focused != null) {
            if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                focused.root?.packageName?.toString() != profile.packageName) return false
            val windowBounds = Rect().also(focused::getBoundsInScreen)
            if (stroke.points.any {
                    val point = TouchPolicy.map(it, profile)
                    !windowBounds.contains(point.first.toInt(), point.second.toInt())
                }) return false
        }
        val root = service.currentRoot() ?: return false
        if (root.packageName?.toString() != profile.packageName) return false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1500) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (node.isEditable || node.isPassword)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val points = stroke.points.map { TouchPolicy.map(it, profile) }
                if (points.any { bounds.contains(it.first.toInt(), it.second.toInt()) }) return false
                for ((a, b) in points.zipWithNext()) {
                    val pathBounds = Rect(minOf(a.first, b.first).toInt(), minOf(a.second, b.second).toInt(),
                        maxOf(a.first, b.first).toInt() + 1, maxOf(a.second, b.second).toInt() + 1)
                    if (Rect.intersects(bounds, pathBounds)) return false
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return queue.isEmpty()
    }

    /** Reads package/focus/geometry only. Keyboard and editable fields are deliberately excluded. */
    @Suppress("DEPRECATION")
    fun current(service: AutomationAccessibilityService, pkg: String): TouchProfile? {
        if ((service.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled) return null
        val windows = service.windows
        if (windows.any { window ->
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    window.root?.isVisibleToUser == true
            }) return null
        val focused = windows.firstOrNull { it.isFocused }
        val root = if (focused != null) {
            if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
            if (Build.VERSION.SDK_INT >= 33 && focused.displayId != 0) return null
            focused.root
        } else service.currentRoot()
        if (root?.packageName?.toString() != pkg || !root.refresh()) return null
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val candidates = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (Build.VERSION.SDK_INT < 33 || it.displayId == 0) && it.root?.packageName?.toString() == pkg
        }.map { Rect().also(it::getBoundsInScreen) }
        val viewport = TouchViewportPolicy.mainWindow(candidates.map {
            TouchViewport(it.left, it.top, it.width(), it.height()) }, metrics.widthPixels, metrics.heightPixels)
        val area = viewport?.let { Rect(it.left, it.top, it.left + it.width, it.top + it.height) }
            ?: Rect().also(root::getBoundsInScreen)
        if (!area.intersect(0, 0, metrics.widthPixels, metrics.heightPixels)) return null
        val safe = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safe.set(insets.left, insets.top, metrics.widthPixels - insets.right, metrics.heightPixels - insets.bottom)
        } else {
            fun dimension(name: String): Int {
                val id = service.resources.getIdentifier(name, "dimen", "android")
                return if (id == 0) 0 else service.resources.getDimensionPixelSize(id)
            }
            safe.set(0, dimension("status_bar_height"), metrics.widthPixels,
                metrics.heightPixels - dimension("navigation_bar_height"))
        }
        if (!area.intersect(safe)) return null
        if (area.width() < 100 || area.height() < 100) return null
        val profile = TouchProfile(pkg, AndroidDevice.version(service, pkg), Build.MANUFACTURER, Build.MODEL,
            metrics.widthPixels, metrics.heightPixels, wm.defaultDisplay.rotation, metrics.densityDpi,
            service.resources.configuration.fontScale, service.resources.configuration.locales.toLanguageTags(),
            area.left, area.top, area.width(), area.height(), androidSdk = Build.VERSION.SDK_INT)
        val multiWindow = windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.root?.isVisibleToUser == true &&
                    window.root?.packageName?.toString()?.let { it != pkg } == true)
        }
        val safeViewport = TouchViewport(safe.left, safe.top, safe.width(), safe.height())
        if (multiWindow) anchors.remove(pkg)
        val base = anchors[pkg]?.profile
        if (base != null && TouchViewportPolicy.canReuseAnchor(
                TouchViewport(base.left, base.top, base.width, base.height), safeViewport,
                TouchViewport(profile.left, profile.top, profile.width, profile.height), multiWindow)) {
            val restored = profile.copy(left = base.left, top = base.top, width = base.width, height = base.height)
            if (TouchPolicy.compatibility(base, restored).isEmpty()) return restored
        }
        if (!multiWindow && area == safe) anchors[pkg] = Anchor(profile)
        return profile
    }
}
