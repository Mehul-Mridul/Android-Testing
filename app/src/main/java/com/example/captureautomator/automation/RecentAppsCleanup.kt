package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.captureautomator.core.PlaybackTiming

/** Opt-in visible Recents UI only. Removing tasks does not force-stop Android processes. */
object RecentAppsCleanup {
    suspend fun clear(service: AutomationAccessibilityService, timing: PlaybackTiming): String {
        timing.waitMs(0)
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS))
            return "RECENTS_UNAVAILABLE: open Recents and clear manually"
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (SystemClock.elapsedRealtime() < deadline) {
            timing.waitMs(150)
            val root = service.currentRoot() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            // Never inspect an arbitrary app for a similarly named button.
            if (pkg !in setOf("com.android.systemui", "com.android.launcher", "com.android.launcher3",
                    "com.bbk.launcher2", "com.vivo.launcher", "com.sec.android.app.launcher",
                    "com.miui.home", "com.google.android.apps.nexuslauncher", "com.oplus.launcher")) continue
            val matches = mutableListOf<AccessibilityNodeInfo>()
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var count = 0
            while (queue.isNotEmpty() && count++ < 1500) {
                val node = queue.removeFirst()
                val id = node.viewIdResourceName?.substringAfter(":id/")
                val label = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase()
                val vivoClean = pkg == "com.android.launcher3" && id == "action_clean" &&
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/overview_panel").any { it.isVisibleToUser }
                if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    (vivoClean || id in setOf("clear_all", "clear_all_button", "clearAnimView", "clear_button") ||
                        label in setOf("clear all", "close all", "clear all apps", "close all apps"))) matches += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
            if (matches.size > 1) return "RECENTS_UNCERTAIN: multiple clear controls; close manually"
            val button = matches.singleOrNull() ?: continue
            timing.waitMs(0)
            if (service.activePackage() != pkg || !button.refresh() || !button.isVisibleToUser)
                return "RECENTS_CHANGED: no clear action sent"
            val accepted = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (accepted) {
                timing.waitMs(250)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            return if (accepted) "RECENTS_CLEAR_REQUESTED: Android accepted the clear action; process termination is not verified"
                else "RECENTS_UNAVAILABLE: clear action was not accepted"
        }
        return "RECENTS_UNAVAILABLE: no supported Clear all control; close recent apps manually"
    }
}
