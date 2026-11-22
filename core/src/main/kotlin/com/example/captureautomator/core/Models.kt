package com.example.captureautomator.core

import java.util.UUID

/** Pure-JVM, device-independent preset schema. All secrets are intentionally excluded. */
data class DeviceProfile(
    val manufacturer: String = "unknown",
    val model: String = "unknown",
    val androidSdk: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val densityDpi: Int = 0
)

data class AppSpec(
    val packageName: String,
    val displayName: String,
    val recordedVersion: String = "unknown"
)

enum class ActionKind {
    LAUNCH_APP, WAIT_FOR_APP, TAP, LONG_PRESS, DOUBLE_TAP, SCROLL,
    SWIPE, ENTER_TEXT, PRESS_BACK, PRESS_HOME, WAIT_FOR_ELEMENT,
    WAIT_FOR_ELEMENT_GONE, WAIT_FOR_SCREEN_CHANGE, WAIT_MS,
    TAKE_PHOTO, START_VIDEO, STOP_VIDEO, VERIFY_MEDIA,
    TAKE_SCREENSHOT, ASSERT_ELEMENT, END_RUN, SELECT_PHOTO_MODE, SELECT_VIDEO_MODE, RECORDED_TOUCH
}

enum class PlaybackMode { SMART_CONTROLS, RECORDED_TOUCHES }

data class TouchPoint(val x: Float, val y: Float, val timeMs: Long)
data class TouchStroke(val points: List<TouchPoint>, val durationMs: Long)

/** Display/window geometry only; contains no pixels, text, keyboard data or UI selectors. */
data class TouchProfile(
    val packageName: String,
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val rotation: Int,
    val densityDpi: Int,
    val fontScale: Float,
    val localeTags: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val displayId: Int = 0,
    val androidSdk: Int = 0
)

/** Normalized coordinates refer to the visible target application window, not the entire display. */
data class UiLocator(
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val className: String? = null,
    val normalizedX: Float? = null,
    val normalizedY: Float? = null,
    val endNormalizedX: Float? = null,
    val endNormalizedY: Float? = null,
    val allowCoordinateFallback: Boolean = false
)

data class AutomationStep(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: ActionKind,
    val packageName: String? = null,
    val locator: UiLocator? = null,
    val waitMs: Long = 0,
    val timeoutMs: Long = 8_000,
    val gestureDurationMs: Long = 300,
    val maxAttempts: Int = 2,
    /** This is explicitly entered by the tester. External keyboard contents are NEVER recorded. */
    val authorizedTestText: String? = null,
    val needsReview: Boolean = false,
    val capturedAtEpochMs: Long? = null,
    val interActionDelayMs: Long = 0,
    /** Heuristic recording classification must be explicitly reviewed before playback. */
    val classificationUncertain: Boolean = false,
    /** Reference timing only; known camera STOP is scheduled from the configured media duration. */
    val recordedVideoDurationMs: Long = 0,
    val expectsPopup: Boolean? = null,
    val minimumSavedBeforeLaunch: Int? = null,
    val touchStroke: TouchStroke? = null
)

data class MediaExpectation(
    val photos: Int = 2,
    val videos: Int = 1,
    val videoDurationMs: Long = 5_000,
    val durationToleranceMs: Long = 2_000
)

data class Preset(
    val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val folder: String = "General",
    val apps: List<AppSpec>,
    val recordedDevice: DeviceProfile = DeviceProfile(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val modifiedAtEpochMs: Long = System.currentTimeMillis(),
    val media: MediaExpectation = MediaExpectation(),
    val steps: List<AutomationStep> = emptyList(),
    /** Nullable for old Gson schema-1 records, whose absent field must mean Smart Controls. */
    val playbackMode: PlaybackMode? = PlaybackMode.SMART_CONTROLS,
    val touchProfile: TouchProfile? = null,
    val requireTouchStart: Boolean? = true,
    val touchProfiles: List<TouchProfile>? = null,
    /** Explicit, visible Recents cleanup after a successful run only. Never force-stops processes. */
    val clearRecentsAfterRun: Boolean? = false
)

enum class Outcome { PASS, FAIL, PARTIAL, CANCELLED }

data class StepResult(
    val stepId: String,
    val label: String,
    val success: Boolean,
    val message: String,
    val elapsedMs: Long,
    val screenshotFile: String? = null
)

data class MediaResult(
    val photosFound: Int = 0,
    val videosFound: Int = 0,
    val verificationComplete: Boolean = false,
    val issues: List<String> = emptyList(),
    val mediaEvidence: List<String> = emptyList()
)

data class ExecutionReport(
    val id: String = UUID.randomUUID().toString(),
    val presetId: String,
    val presetName: String,
    val device: DeviceProfile,
    val startedAt: Long,
    val endedAt: Long,
    val outcome: Outcome,
    val results: List<StepResult>,
    val media: MediaResult,
    val resumedFromStep: Int = 0,
    val notes: String = "",
    val diagnostics: List<RunEvent> = emptyList()
)

/** Whitelisted machine-readable diagnostics; no selectors, screen text, or entered text. */
data class RunEvent(
    val runId: String,
    val elapsedMs: Long,
    val event: String,
    val stepId: String? = null,
    val attempt: Int? = null,
    val values: Map<String, String> = emptyMap()
)
