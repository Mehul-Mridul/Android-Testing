package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.captureautomator.core.PlaybackTiming

object RecentAppsCleanup {
    private val launchers = setOf("com.android.systemui", "com.android.launcher", "com.android.launcher3",
        "com.bbk.launcher2", "com.vivo.launcher", "com.sec.android.app.launcher", "com.miui.home",
        "com.google.android.apps.nexuslauncher", "com.oplus.launcher")
    private val clearIds = setOf("clear_all", "clear_all_button", "clearAnimView", "clear_button")
    private val clearLabels = setOf("clear all", "close all", "clear all apps", "close all apps")

    suspend fun clear(service: AutomationAccessibilityService, timing: PlaybackTiming): String {
        timing.waitMs(0)
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS))
            return "RECENTS_UNAVAILABLE: Android did not open Recents"
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            timing.waitMs(150)
            // Landscape taskbars can own activeRoot while the overview is in another visible window.
            val roots = (service.windows.mapNotNull { it.root } + listOfNotNull(service.currentRoot()))
                .filter { it.packageName?.toString() in launchers }
            val matches = mutableSetOf<AccessibilityNodeInfo>()
            var empty = false
            for (root in roots) {
                val pkg = root.packageName.toString()
                val overview = root.findAccessibilityNodeInfosByViewId("$pkg:id/overview_panel").any { it.isVisibleToUser }
                val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                var visited = 0
                while (queue.isNotEmpty() && visited++ < 1500) {
                    val node = queue.removeFirst()
                    val id = node.viewIdResourceName?.substringAfter(":id/")
                    val label = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase(java.util.Locale.ROOT)
                    if (node.packageName?.toString() == pkg && node.isVisibleToUser && node.isEnabled) {
                        if (label in setOf("no recent apps", "no recent items")) empty = true
                        if (id in clearIds || label in clearLabels || (overview && id == "action_clean")) {
                            var target: AccessibilityNodeInfo? = node
                            repeat(4) {
                                val candidate = target
                                if (candidate != null && !candidate.isClickable) target = candidate.parent
                            }
                            val bounds = Rect().also { target?.getBoundsInScreen(it) }
                            val density = service.resources.displayMetrics.density
                            if (target?.packageName?.toString() == pkg && target?.isClickable == true &&
                                target?.isVisibleToUser == true && target?.isEnabled == true &&
                                bounds.width() in 1..(400 * density).toInt() &&
                                bounds.height() in 1..(100 * density).toInt()) matches += checkNotNull(target)
                        }
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                }
            }
            if (matches.size > 1) return "RECENTS_UNCERTAIN: multiple clear controls"
            val button = matches.singleOrNull()
            if (button == null) {
                if (empty) return "RECENTS_ALREADY_EMPTY: overview reports no recent apps"
                continue
            }
            timing.waitMs(0)
            if (!button.refresh() || !button.isVisibleToUser) continue
            if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return "RECENTS_UNAVAILABLE: clear action was not accepted"
            // Let the removal animation finish before Home or another launch can interrupt it.
            val finish = SystemClock.elapsedRealtime() + 2_000
            while (SystemClock.elapsedRealtime() < finish) {
                timing.waitMs(100)
                if (!button.refresh() || !button.isVisibleToUser) break
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return "RECENTS_CLEAR_REQUESTED: clear accepted; Android can retain protected tasks"
        }
        return "RECENTS_UNAVAILABLE: no supported visible Clear all control"
    }
}
