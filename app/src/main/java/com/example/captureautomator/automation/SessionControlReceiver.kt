package com.example.captureautomator.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit notification Stop; does not reopen/recreate an Activity with a stale Stop intent. */
class SessionControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.example.captureautomator.STOP_EXECUTION" -> {
                ExecutionController.stop(); BatchController.stop(); TouchCalibration.cancel(false)
            }
            "com.example.captureautomator.STOP_RECORDING" -> {
                RecordingSession.stop(context)
                AutomationAccessibilityService.instance?.clearIndicator()
            }
        }
    }
}
