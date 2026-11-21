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
import com.example.captureautomator.core.TouchViewport
import com.example.captureautomator.core.TouchViewportPolicy
import com.example.captureautomator.util.AndroidDevice

object TouchEnvironment {
    private data class Anchor(val profile: TouchProfile)
    private val anchors = mutableMapOf<String, Anchor>()

    /** Reads package/focus/geometry only. Keyboard and editable fields are deliberately excluded. */
    @Suppress("DEPRECATION")
    fun current(service: AutomationAccessibilityService, pkg: String): TouchProfile? {
        if ((service.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled) return null
        val windows = service.windows
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return null
        val focused = windows.firstOrNull { it.isFocused }
        val root = if (focused != null) {
            if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
            if (Build.VERSION.SDK_INT >= 33 && focused.displayId != 0) return null
            focused.root
        } else service.currentRoot()
        if (root?.packageName?.toString() != pkg || !root.refresh()) return null
        val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (input?.isEditable == true || input?.isPassword == true) return null
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
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            if (!area.intersect(insets.left, insets.top, metrics.widthPixels - insets.right,
                    metrics.heightPixels - insets.bottom)) return null
        } else {
            fun dimension(name: String): Int {
                val id = service.resources.getIdentifier(name, "dimen", "android")
                return if (id == 0) 0 else service.resources.getDimensionPixelSize(id)
            }
            if (!area.intersect(0, dimension("status_bar_height"), metrics.widthPixels,
                    metrics.heightPixels - dimension("navigation_bar_height"))) return null
        }
        if (area.width() < 100 || area.height() < 100) return null
        val profile = TouchProfile(pkg, AndroidDevice.version(service, pkg), Build.MANUFACTURER, Build.MODEL,
            metrics.widthPixels, metrics.heightPixels, wm.defaultDisplay.rotation, metrics.densityDpi,
            service.resources.configuration.fontScale, service.resources.configuration.locales.toLanguageTags(),
            area.left, area.top, area.width(), area.height(), androidSdk = Build.VERSION.SDK_INT)
        val anchor = anchors[pkg]
        if (anchor != null) {
            val base = anchor.profile
            val restored = profile.copy(left = base.left, top = base.top, width = base.width, height = base.height)
            if (TouchPolicy.compatibility(base, restored).isEmpty() && TouchViewportPolicy.isInsetDialog(
                    TouchViewport(base.left, base.top, base.width, base.height),
                    TouchViewport(profile.left, profile.top, profile.width, profile.height))) return restored
        }
        // Only establish an app anchor from an app occupying the complete safe display area.
        // Dialog-only initial screens still work, but cannot redefine an existing full-app binding.
        if (profile.width == metrics.widthPixels && profile.height > metrics.heightPixels * .75)
            anchors[pkg] = Anchor(profile)
        return profile
    }
}
