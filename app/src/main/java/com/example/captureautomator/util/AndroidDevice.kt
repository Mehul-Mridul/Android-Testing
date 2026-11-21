package com.example.captureautomator.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.captureautomator.core.AppSpec
import com.example.captureautomator.core.DeviceProfile

object AndroidDevice {
    fun profile(context: Context): DeviceProfile {
        val metrics = context.resources.displayMetrics
        return DeviceProfile(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    @Suppress("DEPRECATION")
    fun installedLaunchableApps(context: Context): List<AppSpec> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                AppSpec(pkg, info.loadLabel(pm).toString(), version(context, pkg))
            }.distinctBy { it.packageName }.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    @Suppress("DEPRECATION")
    fun version(context: Context, packageName: String): String = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "not installed" }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }
}
