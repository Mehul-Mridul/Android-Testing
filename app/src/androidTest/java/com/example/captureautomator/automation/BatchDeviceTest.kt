package com.example.captureautomator.automation

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.captureautomator.core.*
import com.example.captureautomator.data.Codec
import com.example.captureautomator.data.PresetRepository
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit test-APK only. Uses an isolated counter fixture, never a user's social account.
 * Does not obtain UiAutomation or suppress the production Accessibility service. */
class BatchDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val cases = mutableListOf<Map<String, Any>>()
    private suspend fun until(timeout: Long = 45000, condition: () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(50) }
    }
    private suspend fun connected(): AutomationAccessibilityService {
        until(600000) { AutomationAccessibilityService.instance != null }
        return checkNotNull(AutomationAccessibilityService.instance)
    }
    private suspend fun fixture(repository: PresetRepository): Preset {
        val pkg = "com.example.touchfixture.two"
        val service = connected()
        assertTrue(AndroidDevice.launch(context, pkg))
        until { service.activePackage() == pkg }
        delay(1000)
        val profile = checkNotNull(TouchEnvironment.current(service, pkg))
        val button = checkNotNull(service.currentRoot()).findAccessibilityNodeInfosByText("INCREMENT MAIN")
            .single { it.isVisibleToUser && it.isClickable }
        val bounds = android.graphics.Rect().also(button::getBoundsInScreen)
        val point = TouchPoint((bounds.centerX() - profile.left).toFloat() / (profile.width - 1),
            (bounds.centerY() - profile.top).toFloat() / (profile.height - 1), 0)
        return Preset(schemaVersion = 2, name = "Batch-063-device-fixture",
            apps = listOf(AppSpec(pkg, "Isolated counter fixture", "1")),
            recordedDevice = AndroidDevice.profile(context), media = MediaExpectation(photos = 0, videos = 0),
            playbackMode = PlaybackMode.RECORDED_TOUCHES, touchProfile = profile,
            touchProfiles = listOf(profile), clearRecentsAfterRun = true,
            steps = listOf(
                AutomationStep(label = "Launch counter fixture", kind = ActionKind.LAUNCH_APP, packageName = pkg),
                AutomationStep(label = "Recorded tap", kind = ActionKind.RECORDED_TOUCH, packageName = pkg,
                    touchStroke = TouchStroke(listOf(point), 1), interActionDelayMs = 4000,
                    maxAttempts = 1, expectsPopup = false),
                AutomationStep(label = "Verify captured media", kind = ActionKind.VERIFY_MEDIA, packageName = pkg)))
    }
    private suspend fun launchBatch(base: Preset, repository: PresetRepository, size: Int = 3): String {
        assertFalse(BatchController.state.value.running)
        AndroidDevice.launch(context, context.packageName)
        delay(600)
        BatchController.run(context, (1..size).map { base.copy(id = UUID.randomUUID().toString(),
            name = "Batch-063-item-$it") }, repository, true)
        val id = BatchController.state.value.sessionId
        assertTrue(BatchController.state.value.error, BatchController.state.value.running)
        return id
    }
    private suspend fun stopped(case: String, began: Long) {
        until(4500) { !BatchController.state.value.running }
        val state = BatchController.state.value
        assertFalse(state.paused); assertFalse(state.stopping); assertTrue(state.cancelled)
        assertTrue(state.reports.size <= 1)
        val value = mapOf("case" to case, "sessionId" to state.sessionId, "phase" to state.phase,
            "stopLatencyMs" to SystemClock.elapsedRealtime() - began,
            "reports" to state.reports.map { it.outcome.name }, "result" to "PASS")
        cases += value
        File(context.filesDir, "device-batch-063.json").writeText(Codec.gson.toJson(cases))
    }

    @Test fun stopRestartAndTaskRemoval() = runBlocking {
        withContext(Dispatchers.Main) {
            connected()
            val repository = PresetRepository(context)
            val beforePresets = repository.presets.first().associate { it.id to it.json }
            val base = fixture(repository)
            launchBatch(base, repository)
            until { BatchController.state.value.phase == "STARTUP_CLEANUP" }
            var began = SystemClock.elapsedRealtime(); BatchController.stop()
            stopped("stop during startup cleanup", began)

            launchBatch(base, repository)
            until { BatchController.state.value.current.contains("Recorded tap") }
            began = SystemClock.elapsedRealtime(); BatchController.stop()
            stopped("stop during recorded delay", began)

            launchBatch(base, repository)
            until { BatchController.state.value.current.contains("Recorded tap") }
            BatchController.pause(); delay(300)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val notification = notifications.activeNotifications.single { it.id == AutomationAccessibilityService.RECORD_NOTIFICATION_ID }.notification
            assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            began = SystemClock.elapsedRealtime()
            notification.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
            stopped("notification Stop while paused", began)

            launchBatch(base, repository)
            until { BatchController.state.value.current.contains("Verify captured media") }
            began = SystemClock.elapsedRealtime(); BatchController.stop()
            stopped("stop during media verification", began)

            launchBatch(base, repository, 1)
            until { BatchController.state.value.phase == "FINAL_CLEANUP" }
            began = SystemClock.elapsedRealtime(); BatchController.stop()
            stopped("stop during final cleanup", began)

            launchBatch(base, repository)
            until { BatchController.state.value.current.contains("Recorded tap") }
            began = SystemClock.elapsedRealtime()
            checkNotNull(AutomationAccessibilityService.instance).onInterrupt()
            stopped("accessibility interruption", began)

            val resumed = launchBatch(base, repository, 2)
            until { BatchController.state.value.current.contains("Recorded tap") }
            BatchController.pause(); delay(400)
            assertTrue(BatchController.state.value.paused)
            BatchController.resume()
            until(45000) { !BatchController.state.value.running }
            assertEquals("", BatchController.state.value.error)
            assertEquals(2, BatchController.state.value.reports.size)
            assertTrue(BatchController.state.value.reports.all { it.outcome == Outcome.PASS })
            cases += mapOf("case" to "pause then resume two presets", "sessionId" to resumed, "result" to "PASS")

            // Simulate Samsung removing Automator's own Activity/task while a batch runs.
            val id = launchBatch(base, repository)
            until { BatchController.state.value.current.contains("Recorded tap") }
            val service = AutomationAccessibilityService.instance
            val manager = context.getSystemService(ActivityManager::class.java)
            val tasks = manager.appTasks.filter { it.taskInfo.baseIntent.component?.packageName == context.packageName }
            assertTrue("Automator task must exist before removal", tasks.isNotEmpty())
            tasks.forEach { it.finishAndRemoveTask() }
            delay(500)
            assertSame(service, AutomationAccessibilityService.instance)
            assertTrue(BatchController.state.value.running)
            until(60000) { !BatchController.state.value.running }
            val state = BatchController.state.value
            assertEquals("", state.error)
            assertEquals(3, state.reports.size)
            assertTrue(state.reports.all { it.outcome == Outcome.PASS })
            assertEquals(2, state.cleanupDetails.size)
            assertTrue(state.cleanupDetails.all { it.contains("RECENTS_CLEAR_REQUESTED") || it.contains("RECENTS_ALREADY_EMPTY") })
            assertTrue(state.reports.all { it.notes.isBlank() })
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == AutomationAccessibilityService.RECORD_NOTIFICATION_ID })
            cases += mapOf("case" to "three presets after own task removal", "sessionId" to id,
                "reports" to state.reports.map { it.outcome.name }, "cleanup" to state.cleanupDetails, "result" to "PASS")
            assertEquals(beforePresets, repository.presets.first().associate { it.id to it.json })
            cases += mapOf("case" to "original presets preserved", "result" to "PASS")
            File(context.filesDir, "device-batch-063.json").writeText(Codec.gson.toJson(cases))
        }
    }

    /** Creates only two labelled temporary fixture presets for testing the normal APK
     * without instrumentation keeping its process alive. Host removes these via app UI. */
    @Test fun prepareNormalApkSmoke() = runBlocking {
        withContext(Dispatchers.Main) {
            connected()
            val repository = PresetRepository(context)
            val original = repository.presets.first().associate { it.id to it.json }
            val base = fixture(repository)
            val added = listOf("Diagnostic-batch-063-A", "Diagnostic-batch-063-B").map { name ->
                base.copy(id = UUID.randomUUID().toString(), name = name, folder = "Temporary device tests")
            }
            added.forEach { repository.save(it) }
            val remaining = repository.presets.first { rows -> rows.size == original.size + 2 }
            assertEquals(original, remaining.filter { it.id in original }.associate { it.id to it.json })
            File(context.filesDir, "device-normal-smoke-presets.json").writeText(Codec.gson.toJson(added))
        }
    }

    /** Explicitly selected by the host, which intentionally kills the test process once ready.
     * An instrumentation abort is expected for this scenario, not a failed normal matrix. */
    @Test fun controlledProcessDeath() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            connected()
            val repository = PresetRepository(context)
            val base = fixture(repository)
            val waiting = base.copy(steps = base.steps.toMutableList().apply {
                add(1, AutomationStep(label = "Controlled process death wait", kind = ActionKind.WAIT_MS, waitMs = 60000))
            })
            val id = launchBatch(waiting, repository)
            until { BatchController.state.value.current.contains("Controlled process death wait") }
            File(context.filesDir, "device-batch-ready-to-kill.json").writeText(Codec.gson.toJson(mapOf("sessionId" to id, "phase" to BatchController.state.value.phase)))
            awaitCancellation()
        }
    }
}
