package com.example.captureautomator.core

object PresetValidation {
    fun errors(preset: Preset): List<String> {
        val errors = mutableListOf<String>()
        if (preset.schemaVersion !in 1..2) errors += "Unsupported preset schema version"
        if (TouchPolicy.isTouch(preset)) {
            if (preset.schemaVersion != 2) errors += "Recorded touches require schema 2"
            val profiles = TouchPolicy.profiles(preset)
            if (profiles.isEmpty()) errors += "Touch preset needs a recording or calibration profile"
            profiles.forEach { errors += TouchPolicy.profileErrors(it) }
            if (profiles.map { it.packageName }.toSet() != preset.apps.map { it.packageName }.toSet() ||
                profiles.map { it.packageName }.distinct().size != profiles.size)
                errors += "Each selected application needs exactly one touch profile"
            var activeApp: String? = preset.apps.firstOrNull()?.packageName
            preset.steps.forEach { step ->
                if (step.kind == ActionKind.LAUNCH_APP) activeApp = step.packageName
                if (step.kind in setOf(ActionKind.RECORDED_TOUCH, ActionKind.PRESS_BACK) &&
                    (step.packageName == null || step.packageName != activeApp))
                    errors += "Touch application transitions require an explicit Launch app step"
            }
            if (preset.steps.none { it.kind == ActionKind.RECORDED_TOUCH }) errors += "Record at least one touch gesture"
            if (preset.steps.any { it.kind !in TouchPolicy.allowed }) errors += "Touch presets contain unsupported semantic actions"
            if (preset.steps.any { it.packageName != null && preset.apps.none { app -> app.packageName == it.packageName } })
                errors += "Every recorded touch action must target a selected application"
            if (preset.steps.sumOf { it.touchStroke?.points?.size ?: 0 } > 20_000) errors += "Touch preset has too many samples"
        } else if (preset.steps.any { it.kind == ActionKind.RECORDED_TOUCH }) errors += "Recorded touches require Recorded Touches mode"
        if (preset.name.isBlank()) errors += "Preset requires a name"
        if (preset.apps.isEmpty()) errors += "At least one target application is required"
        if (preset.apps.any { it.packageName.isBlank() }) errors += "Target package cannot be blank"
        if (preset.steps.none { it.kind != ActionKind.VERIFY_MEDIA && it.kind != ActionKind.END_RUN })
            errors += "Workflow must contain executable capture/navigation actions"
        if (preset.steps.dropLast(1).any { it.kind == ActionKind.END_RUN })
            errors += "END_RUN may only be the final step"
        if (preset.media.photos < 0 || preset.media.videos < 0) errors += "Negative media counts are invalid"
        if (preset.media.videoDurationMs < 0) errors += "Negative video duration is invalid"
        if (preset.media.durationToleranceMs < 0) errors += "Negative duration tolerance is invalid"
        if (preset.steps.map { it.id }.distinct().size != preset.steps.size) errors += "Duplicate step IDs"
        preset.steps.forEachIndexed { index, step ->
            val prefix = "Step ${index + 1}"
            if (step.kind == ActionKind.RECORDED_TOUCH) errors += TouchPolicy.errors(step.touchStroke).map { "$prefix: $it" }
            if (step.label.isBlank()) errors += "$prefix has no label"
            if (step.timeoutMs !in 500L..120_000L) errors += "$prefix has invalid timeout"
            if (step.maxAttempts !in 1..5) errors += "$prefix has invalid retry count"
            if (step.interActionDelayMs !in 0L..300_000L) errors += "$prefix has invalid recorded delay (0–300000ms)"
            if (step.kind == ActionKind.WAIT_MS && step.waitMs !in 0L..300_000L) errors += "$prefix has invalid wait"
            if (step.kind == ActionKind.ENTER_TEXT && step.authorizedTestText.isNullOrBlank())
                errors += "$prefix needs explicit tester-supplied text"
            val locator = step.locator
            if (locator != null) {
                listOf(locator.normalizedX, locator.normalizedY, locator.endNormalizedX, locator.endNormalizedY)
                    .filterNotNull().forEach { if (it !in 0f..1f) errors += "$prefix has invalid normalized coordinates" }
            }
            val needsLocator = step.kind in setOf(ActionKind.TAP, ActionKind.LONG_PRESS,
                ActionKind.DOUBLE_TAP, ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO,
                ActionKind.STOP_VIDEO, ActionKind.WAIT_FOR_ELEMENT, ActionKind.WAIT_FOR_ELEMENT_GONE,
                ActionKind.ASSERT_ELEMENT, ActionKind.ENTER_TEXT)
            if (needsLocator && locator == null) errors += "$prefix needs a locator"
            if (step.kind == ActionKind.SWIPE && (locator?.normalizedX == null || locator.normalizedY == null ||
                        locator.endNormalizedX == null || locator.endNormalizedY == null))
                errors += "$prefix needs swipe start/end coordinates"
        }
        return errors
    }

    fun playbackErrors(preset: Preset): List<String> {
        // Touches are intentionally unclassified. Actual media counts, not inferred action types,
        // determine their result. Geometry and path validation still apply in errors().
        if (TouchPolicy.isTouch(preset)) return preset.steps.filter { it.classificationUncertain }
            .map { "Uncertain recorded touch: ${it.label}" }
        val issues = mutableListOf<String>()
        val photos = preset.steps.count { it.kind == ActionKind.TAKE_PHOTO }
        val starts = preset.steps.count { it.kind == ActionKind.START_VIDEO }
        val stops = preset.steps.count { it.kind == ActionKind.STOP_VIDEO }
        if (photos != preset.media.photos || starts != preset.media.videos || stops != preset.media.videos)
            issues += "Capture/count mismatch: workflow has $photos photo, $starts video-start, $stops video-stop actions; expected ${preset.media.photos} photos and ${preset.media.videos} videos. Review generic taps; do not infer captures."
        var recordingPackage: String? = null
        for ((index, step) in preset.steps.withIndex()) {
            val pkg = step.packageName ?: preset.apps.firstOrNull()?.packageName.orEmpty()
            when (step.kind) {
                ActionKind.START_VIDEO -> {
                    if (recordingPackage != null) issues += "Step ${index + 1}: nested START_VIDEO"
                    recordingPackage = pkg
                }
                ActionKind.STOP_VIDEO -> {
                    if (recordingPackage == null) issues += "Step ${index + 1}: STOP_VIDEO without START_VIDEO"
                    else if (recordingPackage != pkg) issues += "Step ${index + 1}: video start/stop target differs"
                    recordingPackage = null
                }
                ActionKind.TAKE_PHOTO, ActionKind.SELECT_PHOTO_MODE, ActionKind.SELECT_VIDEO_MODE,
                ActionKind.VERIFY_MEDIA, ActionKind.END_RUN ->
                    if (recordingPackage != null) issues += "Step ${index + 1}: ${step.kind} while video may be recording"
                else -> Unit
            }
            if (step.classificationUncertain) issues += "Step ${index + 1}: uncertain capture classification; review action kind"
        }
        if (recordingPackage != null) issues += "Unclosed START_VIDEO: explicit STOP_VIDEO required"
        val lastCapture = preset.steps.indexOfLast { it.kind in setOf(ActionKind.TAKE_PHOTO, ActionKind.STOP_VIDEO) }
        if (preset.steps.withIndex().any { it.value.kind == ActionKind.VERIFY_MEDIA && it.index < lastCapture })
            issues += "VERIFY_MEDIA must follow all capture actions"
        return issues
    }

    fun canExecute(preset: Preset): List<String> = errors(preset) + playbackErrors(preset) +
        preset.steps.filter { it.needsReview }.map { "Unreviewed step: ${it.label}" }
}

/** Fail-closed accounting: a successful tap without readable matching media never means PASS. */
object Verdict {
    fun outcome(stepResults: List<StepResult>, media: MediaResult, expected: MediaExpectation): Outcome {
        if (stepResults.any { !it.success }) return Outcome.FAIL
        if (!media.verificationComplete) return Outcome.PARTIAL
        if (media.issues.isNotEmpty()) return Outcome.FAIL
        if (media.photosFound != expected.photos || media.videosFound != expected.videos) return Outcome.FAIL
        return Outcome.PASS
    }
}
