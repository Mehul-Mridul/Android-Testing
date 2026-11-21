package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import com.example.captureautomator.core.*
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

class ExecutionEngine(
    private val context: Context,
    private val service: AutomationAccessibilityService,
    private val onProgress: (Int, AutomationStep, List<StepResult>) -> Unit,
    private val gate: suspend () -> Unit
) {
    private val ui = UiResolver(service)
    private val verifier = MediaVerifier(context)
    private var screenBeforeLastAction = ""
    private var startedVideoAtElapsedMs: Long? = null

    suspend fun execute(preset: Preset, resumeIndex: Int = 0): ExecutionReport {
        val startedAt = System.currentTimeMillis()
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
        val baseline = verifier.baseline()
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
        var cancelled = false
        var media: MediaResult? = null
        for ((index, step) in preset.steps.withIndex()) {
            try { gate() } catch (_: CancellationException) { cancelled = true; break }
            onProgress(index, step, stepResults.toList())
            val began = SystemClock.elapsedRealtime()
            if (step.kind == ActionKind.STOP_VIDEO) {
                val startedVideo = startedVideoAtElapsedMs
                if (startedVideo == null) {
                    stepResults += StepResult(step.id, step.label, false,
                        "Refusing stop-video action: no confirmed START_VIDEO step", 0)
                    break
                }
                val remaining = preset.media.videoDurationMs -
                    (SystemClock.elapsedRealtime() - startedVideo)
                if (remaining > 0) delay(remaining)
            }
            val attemptLimit = if (step.kind in setOf(ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO,
                    ActionKind.STOP_VIDEO)) 1 else step.maxAttempts
            var result: Pair<Boolean, String> = false to "Not executed"
            for (attempt in 1..attemptLimit) {
                try { gate(); result = perform(step, preset, startedAt, baseline) }
                catch (_: CancellationException) { cancelled = true; break }
                catch (e: Exception) { result = false to "${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}" }
                if (result.first) break
                if (attempt != attemptLimit) delay(350L * attempt)
            }
            if (cancelled) break
            var screenshot: String? = null
            if (!result.first && context.getSharedPreferences("automation", Context.MODE_PRIVATE)
                    .getBoolean("diagnostic_screenshots", false)) {
                val file = File(context.filesDir, "diagnostics/${preset.id}/${System.currentTimeMillis()}.png")
                if (service.captureEvidence(file)) screenshot = file.absolutePath
            }
            stepResults += StepResult(step.id, step.label, result.first, result.second,
                SystemClock.elapsedRealtime() - began, screenshot)
            if (result.first && step.kind == ActionKind.START_VIDEO)
                startedVideoAtElapsedMs = SystemClock.elapsedRealtime()
            if (result.first && step.kind == ActionKind.STOP_VIDEO)
                startedVideoAtElapsedMs = null
            if (step.kind == ActionKind.VERIFY_MEDIA && result.first) {
                delay(1_500) // Allow MediaStore indexing after a gallery save.
                media = verifier.verify(baseline, preset.media, preset.apps.map { it.packageName }.toSet(), startedAt)
            }
            if (!result.first || step.kind == ActionKind.END_RUN) break
        }
        if (cancelled) return report(preset, startedAt, Outcome.CANCELLED, stepResults,
            media ?: MediaResult(issues = listOf("Execution stopped by tester")), 0, "Cancelled")
        if (media == null) {
            delay(1_500)
            media = verifier.verify(baseline, preset.media, preset.apps.map { it.packageName }.toSet(), startedAt)
        }
        val verdict = Verdict.outcome(stepResults, media, preset.media)
        val outcome = if (stepResults.size != preset.steps.size && verdict == Outcome.PASS) Outcome.FAIL else verdict
        return report(preset, startedAt, outcome, stepResults, media, 0,
            mismatches.joinToString("; "))
    }

    private fun report(preset: Preset, started: Long, outcome: Outcome, steps: List<StepResult>,
                       media: MediaResult, resume: Int, note: String) = ExecutionReport(
        presetId = preset.id, presetName = preset.name, device = AndroidDevice.profile(context),
        startedAt = started, endedAt = System.currentTimeMillis(), outcome = outcome,
        results = steps, media = media, resumedFromStep = resume, notes = note)

    private fun forbiddenPublication(step: AutomationStep): Boolean {
        val pkg = step.packageName.orEmpty()
        if (pkg !in setOf("com.instagram.android", "com.facebook.katana", "com.whatsapp")) return false
        val label = listOfNotNull(step.label, step.locator?.text, step.locator?.contentDescription)
            .joinToString(" ").lowercase()
        return Regex("\\b(publish|post|share|send|your story)\\b")
            .containsMatchIn(label)
    }

    private suspend fun perform(step: AutomationStep, preset: Preset, started: Long,
                                baseline: MediaVerifier.Snapshot): Pair<Boolean, String> {
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
            ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.DOUBLE_TAP,
            ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO -> {
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
            ActionKind.PRESS_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) to "Back"
            ActionKind.PRESS_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) to "Home"
            ActionKind.WAIT_FOR_ELEMENT -> ui.waitForElement(step.locator, true, step.timeoutMs) to "Wait for element"
            ActionKind.WAIT_FOR_ELEMENT_GONE -> ui.waitForElement(step.locator, false, step.timeoutMs) to "Wait for element to disappear"
            ActionKind.ASSERT_ELEMENT -> ui.visibleElement(step.locator) to "Assert target visible"
            ActionKind.WAIT_FOR_SCREEN_CHANGE -> ui.waitForScreenChange(screenBeforeLastAction,
                step.timeoutMs) to "Wait for screen change"
            ActionKind.WAIT_MS -> { delay(step.waitMs); true to "Waited ${step.waitMs}ms" }
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
