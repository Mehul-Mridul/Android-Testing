package com.example.captureautomator.core

import kotlin.math.abs

object TouchPolicy {
    const val MAX_POINTS = 256
    const val MAX_DURATION_MS = 10_000L
    val allowed = setOf(ActionKind.LAUNCH_APP, ActionKind.WAIT_FOR_APP, ActionKind.WAIT_MS,
        ActionKind.RECORDED_TOUCH, ActionKind.PRESS_BACK, ActionKind.VERIFY_MEDIA, ActionKind.END_RUN)

    fun profiles(preset: Preset): List<TouchProfile> =
        preset.touchProfiles?.takeIf { it.isNotEmpty() } ?: listOfNotNull(preset.touchProfile)

    fun profile(preset: Preset, pkg: String?) = profiles(preset).firstOrNull { it.packageName == pkg }

    fun isTouch(preset: Preset) = preset.playbackMode == PlaybackMode.RECORDED_TOUCHES

    fun errors(stroke: TouchStroke?): List<String> {
        if (stroke == null) return listOf("Recorded touch has no path")
        val errors = mutableListOf<String>()
        if (stroke.durationMs !in 1..MAX_DURATION_MS) errors += "Touch duration must be 1–10000ms"
        if (stroke.points.size !in 1..MAX_POINTS) errors += "Touch path must have 1–256 points"
        if (stroke.points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f })
            errors += "Touch points must remain within the recorded application area"
        if (stroke.points.firstOrNull()?.timeMs != 0L ||
            stroke.points.any { it.timeMs !in 0..stroke.durationMs } ||
            stroke.points.zipWithNext().any { (a, b) -> a.timeMs > b.timeMs })
            errors += "Touch timestamps must be monotonic and within duration"
        return errors
    }

    fun profileErrors(p: TouchProfile?): List<String> {
        if (p == null) return listOf("Touch preset needs a recording or calibration profile")
        return buildList {
            if (p.packageName.isBlank() || p.appVersion.isBlank() || p.manufacturer.isBlank() || p.model.isBlank())
                add("Touch profile has incomplete app/device identity")
            if (p.screenWidth <= 0 || p.screenHeight <= 0 || p.width < 2 || p.height < 2 || p.androidSdk < 29 ||
                p.left < 0 || p.top < 0 || p.left.toLong() + p.width > p.screenWidth ||
                p.top.toLong() + p.height > p.screenHeight || p.rotation !in 0..3 || p.displayId != 0 ||
                !p.fontScale.isFinite() || p.fontScale <= 0 || p.densityDpi <= 0)
                add("Touch profile has invalid screen/window geometry")
        }
    }

    /** No silent cross-device scaling. Calibration creates a new profile after visual review. */
    fun compatibility(recorded: TouchProfile, current: TouchProfile): List<String> = buildList {
        if (recorded.packageName != current.packageName) add("Wrong target app")
        if (recorded.appVersion != current.appVersion) add("Target app version changed; calibrate a copy")
        if (recorded.model != current.model || recorded.manufacturer != current.manufacturer)
            add("Different device; calibrate a copy before replay")
        if (recorded.screenWidth != current.screenWidth || recorded.screenHeight != current.screenHeight ||
            recorded.rotation != current.rotation || recorded.displayId != current.displayId ||
            recorded.androidSdk != current.androidSdk ||
            recorded.densityDpi != current.densityDpi || abs(recorded.fontScale - current.fontScale) > .001f ||
            recorded.localeTags != current.localeTags || recorded.left != current.left || recorded.top != current.top ||
            recorded.width != current.width || recorded.height != current.height)
            add("Screen, orientation, language, font or app window changed; restore it or calibrate a copy")
    }

    fun map(point: TouchPoint, profile: TouchProfile): Pair<Float, Float> =
        profile.left + point.x * (profile.width - 1) to profile.top + point.y * (profile.height - 1)

    /** Moving an endpoint adjusts the whole path smoothly without silently clipping samples. */
    fun moveEndpoint(stroke: TouchStroke, end: Boolean, x: Float, y: Float): TouchStroke? {
        if (!x.isFinite() || !y.isFinite()) return null
        val endpoint = if (end) stroke.points.last() else stroke.points.first()
        val stationary = stroke.points.all { abs(it.x - endpoint.x) < .004f && abs(it.y - endpoint.y) < .004f }
        val points = stroke.points.map { point ->
            val weight = if (stationary) 1f else {
                val progress = point.timeMs.toFloat() / stroke.durationMs
                if (end) progress else 1f - progress
            }
            point.copy(x = point.x + (x - endpoint.x) * weight, y = point.y + (y - endpoint.y) * weight)
        }
        return stroke.copy(points = points).takeIf { errors(it).isEmpty() }
    }

    fun calibratedCopy(preset: Preset, current: TouchProfile, strokes: List<TouchStroke>): Preset {
        return calibratedCopy(preset, listOf(current), strokes)
    }

    fun calibratedCopy(preset: Preset, profiles: List<TouchProfile>, strokes: List<TouchStroke>): Preset {
        require(isTouch(preset))
        require(profiles.isNotEmpty() && profiles.all { profileErrors(it).isEmpty() })
        require(profiles.map { it.packageName }.toSet() == preset.apps.map { it.packageName }.toSet())
        require(profiles.map { it.packageName }.distinct().size == profiles.size)
        val current = profiles.first()
        require(strokes.size == preset.steps.count { it.kind == ActionKind.RECORDED_TOUCH })
        require(strokes.all { errors(it).isEmpty() })
        var index = 0
        val copy = preset.copy(id = java.util.UUID.randomUUID().toString(), name = preset.name + " (calibrated copy)",
            apps = preset.apps.map { app -> app.copy(recordedVersion = profiles.single { it.packageName == app.packageName }.appVersion) },
            recordedDevice = preset.recordedDevice.copy(androidSdk = current.androidSdk, manufacturer = current.manufacturer, model = current.model,
                widthPx = current.screenWidth, heightPx = current.screenHeight, densityDpi = current.densityDpi),
            touchProfile = current, touchProfiles = profiles, createdAtEpochMs = System.currentTimeMillis(), modifiedAtEpochMs = System.currentTimeMillis(),
            steps = preset.steps.map { step -> step.copy(id = java.util.UUID.randomUUID().toString(),
                needsReview = false, classificationUncertain = false, touchWindow = null,
                touchStroke = if (step.kind == ActionKind.RECORDED_TOUCH) strokes[index++] else step.touchStroke) })
        require(PresetValidation.canExecute(copy).isEmpty()) { PresetValidation.canExecute(copy).joinToString("; ") }
        return copy
    }
}
