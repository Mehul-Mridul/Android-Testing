package com.example.captureautomator.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.captureautomator.automation.AutomationAccessibilityService
import com.example.captureautomator.automation.BatchController
import com.example.captureautomator.automation.ExecutionController
import com.example.captureautomator.automation.RecordingSession
import com.example.captureautomator.automation.TouchCalibration
import com.example.captureautomator.data.PresetRepository

class MainActivity : ComponentActivity() {
    private val repository by lazy { PresetRepository(applicationContext) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordingSession.recover(this)
        processControlIntent(intent)
        setContent { AutomatorApp(this, repository) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processControlIntent(intent)
    }
    private fun processControlIntent(intent: Intent?) {
        when (intent?.action) {
            "com.example.captureautomator.STOP_RECORDING" -> {
                RecordingSession.stop(this)
                AutomationAccessibilityService.instance?.clearIndicator()
            }
            "com.example.captureautomator.STOP_EXECUTION" -> {
                ExecutionController.stop(); BatchController.stop(); TouchCalibration.cancel(false)
            }
        }
    }
}
