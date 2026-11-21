package com.example.captureautomator.automation

import androidx.test.platform.app.InstrumentationRegistry
import com.example.captureautomator.core.*
import com.example.captureautomator.data.Codec
import com.example.captureautomator.data.PresetRepository
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicitly invoked device tests, shipped only in the separate test APK. */
class CameraDeviceTest {
    @Test fun inspectWithoutProductionService() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        AndroidDevice.launch(context, CameraPolicy.PACKAGE)
        delay(1000)
        fun all(): List<android.view.accessibility.AccessibilityNodeInfo> {
            val found = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                if (depth > 40 || n.packageName?.toString() != CameraPolicy.PACKAGE) return
                found += n
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            automation.rootInActiveWindow?.let { walk(it, 0) }
            return found
        }
        fun tap(node: android.view.accessibility.AccessibilityNodeInfo) {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(),
                    action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(event, true))
                event.recycle()
            }
        }
        tap(all().single { it.contentDescription?.toString() == "VideoMode" })
        delay(1000)
        assertEquals(CameraState.VIDEO_READY, CameraUi.state(automation.rootInActiveWindow))
        tap(all().single { it.viewIdResourceName == CameraPolicy.SHUTTER })
        try {
            delay(1500)
            val controls = all().filter { node -> node.isVisibleToUser &&
                listOf("shutter", "record", "time", "stop", "pause").any { node.viewIdResourceName.orEmpty().contains(it, true) }
            }.map { mapOf("id" to it.viewIdResourceName, "description" to it.contentDescription?.toString()) }
            File(context.filesDir, "device-recording-controls.json").writeText(Codec.gson.toJson(controls))
            assertEquals(CameraState.RECORDING, CameraUi.state(automation.rootInActiveWindow))
        } finally {
            tap(all().single { it.viewIdResourceName == CameraPolicy.SHUTTER })
            delay(1000)
        }
        assertEquals(CameraState.VIDEO_READY, CameraUi.state(automation.rootInActiveWindow))
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun service(): AutomationAccessibilityService {
        // Installation/instrumentation can require the user to reconnect the existing service.
        repeat(1200) {
            AutomationAccessibilityService.instance?.let { return it }
            delay(100)
        }
        error("Enable Accessibility before running physical camera tests")
    }

    private fun step(kind: ActionKind) = AutomationStep(label = CameraPolicy.label(kind), kind = kind,
        packageName = CameraPolicy.PACKAGE, locator = if (kind in setOf(ActionKind.TAKE_PHOTO,
            ActionKind.START_VIDEO, ActionKind.STOP_VIDEO)) UiLocator(resourceId = CameraPolicy.SHUTTER) else null,
        maxAttempts = 1, timeoutMs = 8000)

    @Test fun inspectRecordingState() = runBlocking {
        withContext(Dispatchers.Main) {
            val service = service()
            val timing = PlaybackTiming { currentCoroutineContext().ensureActive() }
            val ui = UiResolver(service, timing)
            val camera = CameraUi(service, timing)
            AndroidDevice.launch(context, CameraPolicy.PACKAGE)
            assertTrue(ui.waitForApp(CameraPolicy.PACKAGE, 8000))
            assertTrue(camera.ensureMode(true, 8000).toString(), camera.ensureMode(true, 8000).first)
            service.showIndicator(false, "Explicit camera calibration test")
            val gesture = ui.capture(UiLocator(resourceId = CameraPolicy.SHUTTER), CameraPolicy.PACKAGE, 8000)
            assertTrue(gesture.toString(), gesture.first)
            try {
                delay(1000)
                val root = service.currentRoot()
                val controls = mutableListOf<Map<String, String>>()
                fun walk(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    if (depth > 30) return
                    val id = node.viewIdResourceName.orEmpty()
                    if (node.packageName?.toString() == CameraPolicy.PACKAGE && node.isVisibleToUser &&
                        listOf("shutter", "record", "time", "stop", "pause").any { id.contains(it, true) })
                        controls += mapOf("id" to id, "description" to node.contentDescription?.toString().orEmpty())
                    for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
                }
                root?.let { walk(it, 0) }
                File(context.filesDir, "device-recording-controls.json").writeText(Codec.gson.toJson(controls))
                assertEquals("Known recording control state required", CameraState.RECORDING, camera.state())
            } finally {
                // One explicit stop gesture after the one explicit start; never a retry loop.
                ui.capture(UiLocator(resourceId = CameraPolicy.SHUTTER), CameraPolicy.PACKAGE, 8000)
                service.clearIndicator()
            }
        }
    }

    @Test fun photoVideoPhotoMediaSmoke() = runBlocking {
        withContext(Dispatchers.Main) {
            val service = service()
            service.showIndicator(false, "Explicit photo/video/photo smoke test")
            try {
                val repository = PresetRepository(context)
                val kinds = listOf(ActionKind.LAUNCH_APP, ActionKind.TAKE_PHOTO,
                    ActionKind.SELECT_VIDEO_MODE, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO,
                    ActionKind.SELECT_PHOTO_MODE, ActionKind.TAKE_PHOTO, ActionKind.VERIFY_MEDIA)
                val preset = Preset(name = "0.3 device smoke - photo video photo",
                    apps = listOf(AppSpec(CameraPolicy.PACKAGE, "Camera", AndroidDevice.version(context, CameraPolicy.PACKAGE))),
                    media = MediaExpectation(2, 1), steps = kinds.map(::step))
                repository.save(preset)
                val report = ExecutionEngine(context, service, { _, _, _ -> },
                    { currentCoroutineContext().ensureActive() }).execute(preset)
                repository.saveReport(report)
                File(context.filesDir, "device-smoke-report.json").writeText(Codec.gson.toJson(report))
                assertEquals(Codec.gson.toJson(report), Outcome.PASS, report.outcome)
            } finally { service.clearIndicator() }
        }
    }

    @Test fun recorderClassifiesStopAndPersistsReview() = runBlocking {
        withContext(Dispatchers.Main) {
            val service = service()
            val timing = PlaybackTiming { currentCoroutineContext().ensureActive() }
            val camera = CameraUi(service, timing)
            AndroidDevice.launch(context, CameraPolicy.PACKAGE)
            assertTrue(UiResolver(service, timing).waitForApp(CameraPolicy.PACKAGE, 8000))
            assertTrue(camera.ensureMode(false, 8000).first)
            service.showIndicator(true, "Explicit recorder verification")
            RecordingSession.begin(context, "0.3 recorded camera smoke", listOf(
                AppSpec(CameraPolicy.PACKAGE, "Camera", AndroidDevice.version(context, CameraPolicy.PACKAGE))),
                MediaExpectation(1, 1))
            try {
                delay(400)
                assertTrue(camera.capture(ActionKind.TAKE_PHOTO, 8000).first)
                delay(700)
                assertTrue(camera.ensureMode(true, 8000).first)
                assertTrue(camera.capture(ActionKind.START_VIDEO, 8000).first)
                delay(5000)
                assertTrue(camera.capture(ActionKind.STOP_VIDEO, 8000).first)
                delay(500)
            } finally { RecordingSession.stop(context); service.clearIndicator() }
            val preset = checkNotNull(RecordingSession.toPreset())
            File(context.filesDir, "device-recorded-preset.json").writeText(Codec.export(preset))
            assertEquals(1, preset.steps.count { it.kind == ActionKind.TAKE_PHOTO })
            assertEquals(1, preset.steps.count { it.kind == ActionKind.START_VIDEO })
            assertEquals(1, preset.steps.count { it.kind == ActionKind.STOP_VIDEO })
            assertTrue(PresetValidation.canExecute(preset).toString(), PresetValidation.canExecute(preset).isEmpty())
            val repository = PresetRepository(context)
            repository.save(preset)
            val reloaded = checkNotNull(repository.load(preset.id))
            assertTrue(reloaded.steps.none { it.needsReview || it.classificationUncertain })
            RecordingSession.discard(context)
        }
    }
}
