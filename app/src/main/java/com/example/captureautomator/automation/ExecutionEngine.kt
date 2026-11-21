package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import com.example.captureautomator.core.*
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID
import android.util.Log
import com.google.gson.Gson

class ExecutionEngine(
    private val context: Context,
    private val service: AutomationAccessibilityService,
    private val onProgress: (Int, AutomationStep, List<StepResult>) -> Unit,
    private val gate: suspend () -> Unit
) {
    private val timing = PlaybackTiming(gate)
    private val ui = UiResolver(service, timing)
    private val camera = CameraUi(service, timing)
    private var lastMediaScan: MediaResult? = null
    private val touchStartAccepted = mutableSetOf<String>()
    private val runId = UUID.randomUUID().toString()
    private val runStarted = SystemClock.elapsedRealtime()
    private val events = mutableListOf<RunEvent>()
    private val logJson = Gson()

    private fun event(name: String, step: AutomationStep? = null, attempt: Int? = null,
                      values: Map<String, String> = emptyMap()) {
        val entry = RunEvent(runId, SystemClock.elapsedRealtime() - runStarted, name, step?.id, attempt, values)
        events += entry
        Log.i("CaptureRun", logJson.toJson(entry))
    }
    private val verifier = MediaVerifier(context)
    private var screenBeforeLastAction = ""
    private var startedVideoAtElapsedMs: Long? = null

    suspend fun execute(preset: Preset, resumeIndex: Int = 0): ExecutionReport {
        val startedAt = System.currentTimeMillis()
        event("RUN_STARTED", values = mapOf("presetId" to preset.id))
        return try {
            executeRun(preset, resumeIndex, startedAt)
        } catch (_: CancellationException) {
            // Also correlate cancellation during preflight/baseline, before the step loop exists.
            event("RUN_CANCELLED", values = mapOf("phase" to "preflight"))
            report(preset, startedAt, Outcome.CANCELLED, emptyList(),
                MediaResult(issues = listOf("Stopped during preflight; no capture action executed")),
                resumeIndex, "Cancelled before playback")
        }
    }

    private suspend fun executeRun(preset: Preset, resumeIndex: Int, startedAt: Long): ExecutionReport {
        val stepResults = mutableListOf<StepResult>()
        val violations = PresetValidation.canExecute(preset)
        if (violations.isNotEmpty()) return report(preset, startedAt, Outcome.FAIL, stepResults,
            MediaResult(issues = violations), resumeIndex, violations.joinToString("; "))
        if (resumeIndex != 0) return report(preset, startedAt, Outcome.PARTIAL, stepResults,
            MediaResult(issues = listOf("Resuming media runs requires a preserved baseline; restart from step 1")),
            resumeIndex, "Resume from nonzero step is intentionally disabled to prevent media misattribution")
        for (app in preset.apps) {
            if (AndroidDevice.version(context, app.packageName) == "not installed") {
                return report(preset, startedAt, Outcome.FAIL, stepResults,
                    MediaResult(issues = listOf("Missing installed app: ${app.packageName}")), 0, "Preflight")
            }
        }
        if (TouchPolicy.isTouch(preset)) {
            for (binding in TouchPolicy.profiles(preset)) {
            if (binding.model != android.os.Build.MODEL || binding.manufacturer != android.os.Build.MANUFACTURER ||
                binding.appVersion != AndroidDevice.version(context, binding.packageName))
                return report(preset, startedAt, Outcome.FAIL, stepResults, MediaResult(issues = listOf(
                    "Different device or app version; use Calibrate touch copy before replay")), 0, "Touch compatibility preflight")
            }
        }
        gate()
        val baseline = verifier.baseline()
        event("BASELINE", values = mapOf("ids" to baseline.ids.size.toString(),
            "permission" to baseline.fullPermission.toString(), "readable" to baseline.readable.toString()))
        if (!baseline.fullPermission || !baseline.readable) return report(preset, startedAt,
            Outcome.PARTIAL, stepResults, MediaResult(issues = listOf(
                "Cannot establish a complete media baseline; grant full shared photo/video permission")), 0,
            "Media permissions/preflight blocked execution")
        val mismatches = preset.apps.mapNotNull { app ->
            val installed = AndroidDevice.version(context, app.packageName)
            if (app.recordedVersion != "unknown" && installed != app.recordedVersion)
                "${app.displayName} updated ${app.recordedVersion} -> $installed; selectors checked at runtime"
            else null
        }
        var segmentBase = baseline
        var segmentStart = startedAt
        var segmentPackage: String? = null
        var touched = false
        var switchBlocked = false
        var media: MediaResult? = null
        try {
            for ((index, savedStep) in preset.steps.withIndex()) {
                val knownCameraAction = (savedStep.packageName ?: preset.apps.firstOrNull()?.packageName) == CameraPolicy.PACKAGE &&
                    savedStep.kind in setOf(ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO,
                        ActionKind.SELECT_PHOTO_MODE, ActionKind.SELECT_VIDEO_MODE)
                val step = if (knownCameraAction) savedStep.copy(label = CameraPolicy.label(savedStep.kind)) else savedStep
                gate()
                onProgress(index, step, stepResults.toList())
                val began = SystemClock.elapsedRealtime()
                event("STEP_STARTED", step, values = mapOf("kind" to step.kind.name,
                    "recordedDelayMs" to step.interActionDelayMs.toString()))
                val capture = step.kind in setOf(ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO, ActionKind.RECORDED_TOUCH)
                if (step.kind == ActionKind.STOP_VIDEO && startedVideoAtElapsedMs == null) {
                    stepResults += StepResult(step.id, step.label, false,
                        "No preceding START_VIDEO gesture completed", 0)
                    break
                }
                var result = runPlaybackStep(timing, step, beforeAttempts = {
                    if (step.kind == ActionKind.STOP_VIDEO &&
                        (step.packageName ?: preset.apps.firstOrNull()?.packageName) != CameraPolicy.PACKAGE) {
                        // Recorded delay already elapsed. Wait only the remaining minimum duration.
                        val videoStart = checkNotNull(startedVideoAtElapsedMs)
                        timing.waitMs((preset.media.videoDurationMs -
                            (SystemClock.elapsedRealtime() - videoStart)).coerceAtLeast(0))
                    }
                }) { attempt ->
                    event("ACTION_ATTEMPT", step, attempt)
                    val actionResult = try {
                        gate()
                        val previous = segmentPackage
                        if (step.kind == ActionKind.LAUNCH_APP && previous != null && previous != step.packageName && touched) {
                            val minimum = step.minimumSavedBeforeLaunch ?: if (preset.media.photos + preset.media.videos > 0) 1 else 0
                            if (minimum > 0) {
                                val confirmed = verifier.waitForSaved(segmentBase, preset.media, previous, segmentStart, minimum, timing)
                                event("APP_SAVE_BARRIER", step, values = mapOf("required" to minimum.toString(), "found" to confirmed.toString()))
                                switchBlocked = confirmed < minimum
                            }
                        }
                        if (switchBlocked) false to "App switch withheld: previous app has not saved the required readable media"
                        else {
                            if (step.kind == ActionKind.LAUNCH_APP) {
                                segmentBase = verifier.baseline()
                                segmentStart = System.currentTimeMillis()
                                segmentPackage = step.packageName
                                touched = false
                            }
                            if (capture || step.kind == ActionKind.TAP) touched = true
                            perform(step, preset)
                        }
                    }
                    catch (e: CancellationException) {
                        event("ACTION_CANCELLED", step, attempt,
                            mapOf("captureMayHaveBeenDispatched" to capture.toString(), "mediaConfirmed" to "false"))
                        throw e
                    }
                    catch (e: Exception) { false to "Action error: ${e.javaClass.simpleName}" }
                    event(if (capture) "CAPTURE_GESTURE_RESULT" else "ACTION_RESULT", step, attempt,
                        mapOf("completed" to actionResult.first.toString(), "mediaConfirmed" to "false",
                            "status" to if (capture) actionResult.second.substringBefore(':') else step.kind.name))
                    actionResult
                }
                if (!TouchPolicy.isTouch(preset)) event("CAMERA_STATE", step,
                    values = CameraUi.observe(service.currentRoot()).evidence + ("observed" to camera.state().name))
                if (result.first && step.kind == ActionKind.START_VIDEO)
                    startedVideoAtElapsedMs = camera.recordingStartedAtElapsedMs ?: SystemClock.elapsedRealtime()
                if (result.first && step.kind == ActionKind.STOP_VIDEO)
                    startedVideoAtElapsedMs = null
                if (step.kind == ActionKind.VERIFY_MEDIA) {
                    media = verifyMedia(baseline, preset, startedAt)
                    result = true to
                        "MEDIA_VERIFICATION: ${media.photosFound} photos, ${media.videosFound} videos; " +
                        "complete=${media.verificationComplete}; ${media.issues.joinToString("; ")}"
                }
                var screenshot: String? = null
                if (!result.first && context.getSharedPreferences("automation", Context.MODE_PRIVATE)
                        .getBoolean("diagnostic_screenshots", false)) {
                    val file = File(context.filesDir, "diagnostics/$runId/${step.id}.png")
                    if (service.captureEvidence(file)) screenshot = file.absolutePath
                }
                stepResults += StepResult(step.id, step.label, result.first, result.second,
                    SystemClock.elapsedRealtime() - began, screenshot)
                onProgress(index, step, stepResults.toList())
                event("STEP_FINISHED", step, values = mapOf("success" to result.first.toString()))
                if (!result.first || step.kind == ActionKind.END_RUN) break
            }
            // An explicit verification is authoritative; otherwise verify even after a failed gesture.
            if (media == null) media = verifyMedia(baseline, preset, startedAt)
        } catch (_: CancellationException) {
            event("RUN_CANCELLED", values = mapOf("videoMayStillBeRecording" to if (TouchPolicy.isTouch(preset)) "unknown-touch-replay" else (startedVideoAtElapsedMs != null).toString()))
            return report(preset, startedAt, Outcome.CANCELLED, stepResults,
                media ?: lastMediaScan?.copy(verificationComplete = false, issues = lastMediaScan!!.issues + "Verification interrupted by tester") ?: MediaResult(issues = listOf("Stopped by tester; no further gestures. Check Camera manually if video may be recording.")),
                0, "Cancelled; capture state may be uncertain")
        }
        val verdict = Verdict.outcome(stepResults, media, preset.media)
        val outcome = if (stepResults.size != preset.steps.size && verdict == Outcome.PASS) Outcome.FAIL else verdict
        fun returnToAutomator() = AndroidDevice.launch(context, context.packageName)
        val returned = returnToAutomator() && ui.waitForApp(context.packageName, 5000)
        var cleanup = if (returned) "" else "Could not return to Automator"
        if (preset.clearRecentsAfterRun == true) {
            cleanup = if (returned && !switchBlocked) {
                try { RecentAppsCleanup.clear(service, timing) }
                catch (_: CancellationException) { "RECENTS_CANCELLED: no further cleanup actions" }
                catch (_: Exception) { "RECENTS_UNAVAILABLE: close recent apps manually" }
            } else "RECENTS_SKIPPED: app return or save barrier incomplete"
            returnToAutomator()
            event("RECENTS_CLEANUP", values = mapOf("status" to cleanup.substringBefore(':')))
        }
        return report(preset, startedAt, outcome, stepResults, media, 0,
            (mismatches + cleanup).filter { it.isNotBlank() }.joinToString("; "))
    }

    private fun report(preset: Preset, started: Long, outcome: Outcome, steps: List<StepResult>,
                       media: MediaResult, resume: Int, note: String): ExecutionReport {
        event("RUN_FINISHED", values = mapOf("outcome" to outcome.name))
        return ExecutionReport(id = runId,
            presetId = preset.id, presetName = preset.name, device = AndroidDevice.profile(context),
            startedAt = started, endedAt = System.currentTimeMillis(), outcome = outcome,
            results = steps, media = media, resumedFromStep = resume, notes = note,
            diagnostics = events.toList())
    }

    private suspend fun verifyMedia(baseline: MediaVerifier.Snapshot, preset: Preset, started: Long): MediaResult {
        event("MEDIA_VERIFICATION_STARTED")
        val result = verifier.poll(baseline, preset.media, preset.apps.map { it.packageName }.toSet(),
            started, timing) { scan, snapshot ->
            lastMediaScan = snapshot
            val photos = snapshot.photosFound
            val videos = snapshot.videosFound
            val complete = snapshot.verificationComplete
            event("MEDIA_SCAN", values = mapOf("scan" to scan.toString(), "photos" to photos.toString(),
                "videos" to videos.toString(), "complete" to complete.toString()))
        }
        event("MEDIA_VERIFICATION_FINISHED", values = mapOf("photos" to result.photosFound.toString(),
            "videos" to result.videosFound.toString(), "complete" to result.verificationComplete.toString()))
        return result
    }

    private suspend fun prepareTouch(preset: Preset, pkg: String?, timeoutMs: Long): Pair<TouchProfile?, String> {
        val binding = TouchPolicy.profile(preset, pkg) ?: return null to "TOUCH_PROFILE_MISSING"
        var current: TouchProfile? = null
        var stable = false
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            gate()
            val frame = TouchEnvironment.current(service, binding.packageName)
            if (frame != null && frame == current) { stable = true; break }
            current = frame
            timing.waitMs(120)
        }
        if (!stable || current == null) return null to "TOUCH_NOT_READY: selected app must be foreground; keyboard/editable fields and system UI are excluded"
        val errors = TouchPolicy.compatibility(binding, current)
        if (errors.isNotEmpty()) return null to "TOUCH_INCOMPATIBLE: ${errors.joinToString("; ")}"
        if (binding.packageName !in touchStartAccepted && preset.requireTouchStart != false) {
            event("TOUCH_START_CHECKPOINT")
            if (!TouchStartGate.await(service, binding)) return null to "TOUCH_START_TIMEOUT: no gesture sent"
            gate()
            touchStartAccepted += binding.packageName
            timing.waitMs(120) // Let the checkpoint overlay leave the input window list before injection.
        }
        val fresh = TouchEnvironment.current(service, binding.packageName)
        if (fresh == null || TouchPolicy.compatibility(binding, fresh).isNotEmpty())
            return null to "TOUCH_WINDOW_CHANGED: no gesture sent"
        return fresh to "Touch geometry confirmed"
    }

    private fun forbiddenPublication(step: AutomationStep): Boolean {
        val pkg = step.packageName.orEmpty()
        if (pkg !in setOf("com.instagram.android", "com.facebook.katana", "com.whatsapp")) return false
        val label = listOfNotNull(step.label, step.locator?.text, step.locator?.contentDescription)
            .joinToString(" ").lowercase()
        return Regex("\\b(publish|post|share|send|your story)\\b")
            .containsMatchIn(label)
    }

    private suspend fun perform(step: AutomationStep, preset: Preset): Pair<Boolean, String> {
        if (forbiddenPublication(step)) return false to "Blocked publishing/sending action by safety policy"
        val pkg = step.packageName ?: preset.apps.firstOrNull()?.packageName
        if (step.kind == ActionKind.LAUNCH_APP) {
            if (pkg == null) return false to "No package specified"
            val opened = AndroidDevice.launch(context, pkg)
            return (opened && ui.waitForApp(pkg, step.timeoutMs)) to "Launch and wait for $pkg"
        }
        if (step.kind == ActionKind.WAIT_FOR_APP) {
            if (pkg == null) return false to "No package specified"
            return ui.waitForApp(pkg, step.timeoutMs) to "Wait for $pkg"
        }
        // Every UI action is scoped to its selected target app, never a random foreground screen.
        if (step.kind !in setOf(ActionKind.PRESS_BACK, ActionKind.PRESS_HOME, ActionKind.WAIT_MS,
                ActionKind.VERIFY_MEDIA, ActionKind.END_RUN) &&
            (pkg == null || pkg !in preset.apps.map { it.packageName } ||
                !ui.waitForApp(pkg, step.timeoutMs))) {
            return false to "Wrong foreground app; expected $pkg, saw ${service.activePackage()}"
        }
        return when (step.kind) {
            ActionKind.RECORDED_TOUCH -> {
                val prepared = prepareTouch(preset, pkg, step.timeoutMs)
                val frame = prepared.first
                if (frame == null) false to prepared.second else {
                    val stroke = checkNotNull(step.touchStroke)
                    event("TOUCH_REPLAY", step, values = mapOf("points" to stroke.points.size.toString(),
                        "durationMs" to stroke.durationMs.toString()))
                    ui.replayTouch(stroke, frame, ready = {
                        val current = TouchEnvironment.current(service, frame.packageName)
                        current != null && TouchPolicy.compatibility(frame, current).isEmpty()
                    })
                }
            }
            ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO -> {
                if (pkg == null) false to "No target package"
                else if (pkg == CameraPolicy.PACKAGE) camera.capture(step.kind, step.timeoutMs, preset.media.videoDurationMs)
                else ui.capture(step.locator, pkg, step.timeoutMs)
            }
            ActionKind.SELECT_PHOTO_MODE, ActionKind.SELECT_VIDEO_MODE -> {
                if (pkg != CameraPolicy.PACKAGE) false to "Camera mode adapter is unavailable for this package"
                else camera.ensureMode(step.kind == ActionKind.SELECT_VIDEO_MODE, step.timeoutMs)
            }
            ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.DOUBLE_TAP -> {
                screenBeforeLastAction = ui.activeFingerprint()
                ui.tap(step.locator, when (step.kind) {
                    ActionKind.LONG_PRESS -> "LONG_PRESS"
                    ActionKind.DOUBLE_TAP -> "DOUBLE_TAP"
                    else -> "TAP"
                }, step.gestureDurationMs)
            }
            ActionKind.SCROLL -> ui.scroll(step.locator) to "Scroll accessible scroll container"
            ActionKind.SWIPE -> ui.swipe(step.locator, step.gestureDurationMs) to "Approved normalized swipe"
            ActionKind.ENTER_TEXT -> ui.enterText(step.locator, step.authorizedTestText) to "Set explicit test data (not logged)"
            ActionKind.PRESS_BACK -> {
                if (TouchPolicy.isTouch(preset) && prepareTouch(preset, pkg, step.timeoutMs).first == null)
                    false to "TOUCH_BACK_BLOCKED: foreground/geometry not ready"
                else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) to "Back"
            }
            ActionKind.PRESS_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) to "Home"
            ActionKind.WAIT_FOR_ELEMENT -> ui.waitForElement(step.locator, true, step.timeoutMs) to "Wait for element"
            ActionKind.WAIT_FOR_ELEMENT_GONE -> ui.waitForElement(step.locator, false, step.timeoutMs) to "Wait for element to disappear"
            ActionKind.ASSERT_ELEMENT -> ui.visibleElement(step.locator) to "Assert target visible"
            ActionKind.WAIT_FOR_SCREEN_CHANGE -> ui.waitForScreenChange(screenBeforeLastAction,
                step.timeoutMs) to "Wait for screen change"
            ActionKind.WAIT_MS -> { timing.waitMs(step.waitMs); true to "Waited ${step.waitMs}ms" }
            ActionKind.TAKE_SCREENSHOT -> {
                val file = File(context.filesDir, "evidence/${preset.id}/${System.currentTimeMillis()}.png")
                service.captureEvidence(file) to "Explicit screenshot ${file.absolutePath}"
            }
            ActionKind.VERIFY_MEDIA -> true to "MediaStore verification scheduled"
            ActionKind.END_RUN -> true to "End run"
            ActionKind.LAUNCH_APP, ActionKind.WAIT_FOR_APP -> false to "Unexpected app action"
        }
    }
}
