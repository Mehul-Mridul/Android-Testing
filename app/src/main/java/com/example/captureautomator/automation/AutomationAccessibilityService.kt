package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.captureautomator.ui.MainActivity
import com.example.captureautomator.core.PlaybackMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class AutomationAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AutomationAccessibilityService? = null
            private set
        const val RECORD_NOTIFICATION_ID = 801
        private const val CHANNEL = "automation_session"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Authorized automation sessions", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val draft = RecordingSession.state.value ?: return
        if (!draft.active || draft.paused || draft.apps.none { it.packageName == event.packageName?.toString() }) return
        if (draft.playbackMode == PlaybackMode.RECORDED_TOUCHES) return
        try { RecordingSession.onEvent(this, event, rootInActiveWindow) } catch (_: Exception) {
            // Third-party accessibility hierarchies may contain invalid/stale nodes.
        }
    }

    override fun onInterrupt() {
        if (RecordingSession.state.value?.active == true) RecordingSession.stop(this)
        TouchRecorder.stop(); TouchCalibration.cancel(false); TouchStartGate.cancel()
        ExecutionController.stop(); BatchController.stop()
    }
    override fun onDestroy() {
        onInterrupt()
        clearIndicator()
        instance = null
        super.onDestroy()
    }

    fun showIndicator(recording: Boolean, name: String) {
        val open = Intent(this, MainActivity::class.java).apply {
            action = "com.example.captureautomator.OPEN_SESSION"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val stop = Intent(this, MainActivity::class.java).apply {
            action = if (recording) "com.example.captureautomator.STOP_RECORDING"
                     else "com.example.captureautomator.STOP_EXECUTION"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(this, 10, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPending = PendingIntent.getActivity(this, 11, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (recording) "3P recording active" else "3P test running")
            .setContentText(name)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(RECORD_NOTIFICATION_ID, notification)
    }

    fun clearIndicator() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(RECORD_NOTIFICATION_ID)
    }

    fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()
    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow
    fun currentWindowBounds(): Rect {
        val rect = Rect()
        rootInActiveWindow?.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            val metrics = resources.displayMetrics
            rect.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return rect
    }

    /** Screenshot only if the platform permits; FLAG_SECURE content is not bypassed. */
    suspend fun captureEvidence(file: File): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val deferred = CompletableDeferred<Boolean>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val buffer: HardwareBuffer = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val mutable = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        file.parentFile?.mkdirs()
                        val success = mutable != null && file.outputStream().use { stream ->
                            mutable.compress(Bitmap.CompressFormat.PNG, 95, stream)
                        }
                        mutable?.recycle()
                        bitmap?.recycle()
                        buffer.close()
                        deferred.complete(success)
                    } catch (_: Exception) { deferred.complete(false) }
                }
                override fun onFailure(errorCode: Int) { deferred.complete(false) }
            })
        return withTimeoutOrNull(4_000) { deferred.await() } ?: false
    }
}
