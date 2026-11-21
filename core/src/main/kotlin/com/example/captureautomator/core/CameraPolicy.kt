package com.example.captureautomator.core

enum class CameraState { UNKNOWN, PHOTO_READY, VIDEO_READY, RECORDING }

/** This adapter is deliberately scoped to the inspected OEM camera, not arbitrary camera apps. */
object CameraPolicy {
    const val PACKAGE = "com.android.camera"
    const val SHUTTER = "$PACKAGE:id/shutter_button"
    const val MODE_STRIP = "$PACKAGE:id/mode_indicator_text_list"

    fun state(description: String?): CameraState {
        val label = description.orEmpty().lowercase().trim()
        return when {
            Regex("\\b(stop recording|stop video)\\b").containsMatchIn(label) -> CameraState.RECORDING
            Regex("\\b(start recording|record video|start video)\\b").containsMatchIn(label) -> CameraState.VIDEO_READY
            Regex("\\b(take photo|take picture|capture photo)\\b").containsMatchIn(label) -> CameraState.PHOTO_READY
            else -> CameraState.UNKNOWN
        }
    }

    fun required(kind: ActionKind): CameraState = when (kind) {
        ActionKind.TAKE_PHOTO, ActionKind.SELECT_PHOTO_MODE -> CameraState.PHOTO_READY
        ActionKind.START_VIDEO, ActionKind.SELECT_VIDEO_MODE -> CameraState.VIDEO_READY
        ActionKind.STOP_VIDEO -> CameraState.RECORDING
        else -> CameraState.UNKNOWN
    }

    fun label(kind: ActionKind): String = when (kind) {
        ActionKind.TAKE_PHOTO -> "Take photo"
        ActionKind.START_VIDEO -> "Start video"
        ActionKind.STOP_VIDEO -> "Stop video"
        ActionKind.SELECT_PHOTO_MODE -> "Select Photo mode"
        ActionKind.SELECT_VIDEO_MODE -> "Select Video mode"
        else -> kind.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    fun safeImportedStep(step: AutomationStep): Boolean {
        if (step.packageName != PACKAGE) return false
        return when (step.kind) {
            ActionKind.LAUNCH_APP, ActionKind.WAIT_FOR_APP, ActionKind.WAIT_MS,
            ActionKind.VERIFY_MEDIA, ActionKind.END_RUN -> true
            ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO -> step.locator?.resourceId == SHUTTER
            ActionKind.SELECT_PHOTO_MODE -> step.locator?.contentDescription == "PhotoMode"
            ActionKind.SELECT_VIDEO_MODE -> step.locator?.contentDescription == "VideoMode"
            else -> false
        }
    }

    fun modeLocator(video: Boolean) = UiLocator(contentDescription = if (video) "VideoMode" else "PhotoMode")

    /** Copy-only repair: keeps capture kinds, replaces broken mode-container navigation and
     * uses configured video duration. Never guesses that a second START means STOP.
     */
    fun repairCopy(preset: Preset): Preset {
        require(preset.apps.size == 1 && preset.apps.single().packageName == PACKAGE)
        require(preset.steps.count { it.kind == ActionKind.START_VIDEO } == preset.steps.count { it.kind == ActionKind.STOP_VIDEO }) {
            "Start/stop counts differ; action kinds must be corrected before creating a repair copy"
        }
        val steps = preset.steps.mapIndexed { index, step ->
            val container = step.locator?.resourceId?.contains("side_button_recycler") == true ||
                step.locator?.resourceId == MODE_STRIP
            val nextCapture = preset.steps.drop(index + 1).firstOrNull {
                it.kind in setOf(ActionKind.START_VIDEO, ActionKind.TAKE_PHOTO)
            }?.kind
            val kind = if (container && nextCapture != null) {
                if (nextCapture == ActionKind.START_VIDEO) ActionKind.SELECT_VIDEO_MODE else ActionKind.SELECT_PHOTO_MODE
            } else step.kind
            val known = step.locator?.resourceId == SHUTTER && kind in setOf(
                ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO)
            val mode = kind in setOf(ActionKind.SELECT_PHOTO_MODE, ActionKind.SELECT_VIDEO_MODE)
            step.copy(id = java.util.UUID.randomUUID().toString(), kind = kind,
                label = if (known || mode) label(kind) else step.label,
                locator = if (mode) modeLocator(kind == ActionKind.SELECT_VIDEO_MODE)
                    else if (known) UiLocator(resourceId = SHUTTER) else step.locator,
                interActionDelayMs = if (kind == ActionKind.STOP_VIDEO) 0 else step.interActionDelayMs,
                classificationUncertain = if (known || mode) false else step.classificationUncertain,
                needsReview = if (known || mode) false else step.needsReview)
        }
        val copy = preset.copy(id = java.util.UUID.randomUUID().toString(), name = preset.name + " (camera repair)",
            createdAtEpochMs = System.currentTimeMillis(), modifiedAtEpochMs = System.currentTimeMillis(), steps = steps)
        require(PresetValidation.canExecute(copy).isEmpty()) { PresetValidation.canExecute(copy).joinToString("; ") }
        return copy
    }
}

/** Records observed transitions, rather than treating a post-stop Start label as a new start.
 * UNKNOWN observations are ignored; repeated window/click events cannot duplicate transitions.
 */
class CameraRecordingTracker {
    var previous = CameraState.UNKNOWN
        private set
    fun observe(state: CameraState): ActionKind? {
        if (state == CameraState.UNKNOWN || state == previous) return null
        val before = previous
        previous = state
        return when {
            before == CameraState.UNKNOWN -> null
            state == CameraState.RECORDING -> ActionKind.START_VIDEO
            before == CameraState.RECORDING && state == CameraState.VIDEO_READY -> ActionKind.STOP_VIDEO
            state == CameraState.PHOTO_READY -> ActionKind.SELECT_PHOTO_MODE
            state == CameraState.VIDEO_READY -> ActionKind.SELECT_VIDEO_MODE
            else -> null
        }
    }
}
