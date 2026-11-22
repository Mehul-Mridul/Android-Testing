package com.example.captureautomator.automation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.captureautomator.core.*
import com.example.captureautomator.data.Codec
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Recording only observes authorized packages during an explicitly started session. */
data class RecordingDraft(
    val name: String,
    val apps: List<AppSpec>,
    val device: DeviceProfile,
    val media: MediaExpectation,
    val startedAt: Long,
    val steps: List<AutomationStep> = emptyList(),
    val active: Boolean = true,
    val paused: Boolean = false,
    val playbackMode: PlaybackMode? = PlaybackMode.SMART_CONTROLS,
    val touchProfile: TouchProfile? = null,
    val recordingIssue: String? = null,
    val touchProfiles: List<TouchProfile>? = null,
    val activeTouchPackage: String? = null
)

object RecordingSession {
    private val mutable = MutableStateFlow<RecordingDraft?>(null)
    val state: StateFlow<RecordingDraft?> = mutable
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var lastEventAt = 0L
    private var lastPackage: String? = null
    private var cameraTracker = CameraRecordingTracker()
    private var recordedVideoStartedAt: Long? = null
    private val observeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    fun begin(context: Context, name: String, apps: List<AppSpec>, media: MediaExpectation,
              mode: PlaybackMode = PlaybackMode.SMART_CONTROLS) {
        require(name.isNotBlank() && apps.size == 1)
        check(mutable.value?.active != true) { "Another recording is active" }
        lastEventAt = 0L
        lastPackage = null
        cameraTracker = CameraRecordingTracker()
        recordedVideoStartedAt = null
        mutable.value = RecordingDraft(name, apps, AndroidDevice.profile(context), media,
            System.currentTimeMillis(), steps = listOf(AutomationStep(
                label = "Launch ${apps.first().displayName}", kind = ActionKind.LAUNCH_APP,
                packageName = apps.first().packageName)), playbackMode = mode, activeTouchPackage = apps.first().packageName)
        persist(context)
        observeJob?.cancel()
        if (mode == PlaybackMode.RECORDED_TOUCHES) {
            TouchRecorder.start(checkNotNull(AutomationAccessibilityService.instance))
            return
        }
        observeJob = observeScope.launch {
            while (mutable.value?.active == true) {
                val current = mutable.value
                if (current?.paused == false && current.apps.any { it.packageName == CameraPolicy.PACKAGE }) {
                    AutomationAccessibilityService.instance?.let { service ->
                        val state = CameraUi.state(service.currentRoot())
                        recordCameraTransition(context, state)
                    }
                }
                delay(120)
            }
        }
    }

    private fun recordCameraTransition(context: Context, state: CameraState) {
        val transition = cameraTracker.observe(state) ?: return
        val now = System.currentTimeMillis()
        if (transition == ActionKind.START_VIDEO) recordedVideoStartedAt = now
        val recordedDuration = if (transition == ActionKind.STOP_VIDEO)
            recordedVideoStartedAt?.let { now - it } ?: 0 else 0
        val mode = transition in setOf(ActionKind.SELECT_PHOTO_MODE, ActionKind.SELECT_VIDEO_MODE)
        addStep(context, AutomationStep(label = CameraPolicy.label(transition), kind = transition,
            packageName = CameraPolicy.PACKAGE, locator = if (mode) CameraPolicy.modeLocator(transition == ActionKind.SELECT_VIDEO_MODE)
                else UiLocator(resourceId = CameraPolicy.SHUTTER), capturedAtEpochMs = now,
            interActionDelayMs = if (transition == ActionKind.STOP_VIDEO || lastEventAt == 0L) 0 else now - lastEventAt,
            recordedVideoDurationMs = recordedDuration))
        if (transition == ActionKind.STOP_VIDEO) recordedVideoStartedAt = null
        lastEventAt = now
    }

    fun pause(context: Context) { mutable.value?.let { mutable.value = it.copy(paused = true) }; persist(context) }
    fun resume(context: Context) { cameraTracker = CameraRecordingTracker(); TouchRecorder.resetDelay(); mutable.value?.let { mutable.value = it.copy(paused = false) }; persist(context) }
    fun stop(context: Context) { observeJob?.cancel(); mutable.value?.let { mutable.value = it.copy(active = false, paused = false) }; TouchRecorder.stop(); persist(context) }
    fun discard(context: Context) { observeJob?.cancel(); TouchRecorder.stop(); mutable.value = null; persist(context) }

    fun touchProfile(context: Context, profile: TouchProfile, previousSaved: Int? = null) {
        val draft = mutable.value ?: return
        if (!draft.active || draft.playbackMode != PlaybackMode.RECORDED_TOUCHES ||
            draft.apps.none { it.packageName == profile.packageName }) return
        val profiles = draft.touchProfiles ?: listOfNotNull(draft.touchProfile)
        val updated = if (profiles.none { it.packageName == profile.packageName }) profiles + profile else profiles
        val previous = draft.activeTouchPackage ?: draft.apps.first().packageName
        val transition = if (previous != profile.packageName) listOf(AutomationStep(
            label = "Launch ${draft.apps.first { it.packageName == profile.packageName }.displayName}",
            kind = ActionKind.LAUNCH_APP, packageName = profile.packageName, minimumSavedBeforeLaunch = previousSaved, maxAttempts = 1)) else emptyList()
        mutable.value = draft.copy(touchProfile = draft.touchProfile ?: profile, touchProfiles = updated,
            activeTouchPackage = profile.packageName, steps = draft.steps + transition)
        if (previous != profile.packageName || updated != profiles) persist(context)
    }
    fun recordTouch(context: Context, stroke: TouchStroke, delayMs: Long, popup: Boolean?): String? {
        val draft = mutable.value ?: return null
        if (!draft.active || draft.paused || draft.playbackMode != PlaybackMode.RECORDED_TOUCHES) return null
        if (draft.steps.size >= 1000 || draft.steps.sumOf { it.touchStroke?.points?.size ?: 0 } + stroke.points.size > 20_000) {
            stopWithIssue(context, "Touch recording reached its step/sample limit; this gesture was not forwarded")
            return null
        }
        val step = AutomationStep(label = if (stroke.points.size > 1) "Recorded swipe" else if (stroke.durationMs >= 500) "Recorded hold" else "Recorded tap",
            kind = ActionKind.RECORDED_TOUCH, packageName = draft.activeTouchPackage ?: draft.apps.first().packageName,
            touchStroke = stroke, expectsPopup = popup, maxAttempts = 1, interActionDelayMs = delayMs,
            capturedAtEpochMs = System.currentTimeMillis(), needsReview = true)
        addStep(context, step)
        return step.id
    }
    fun touchCompleted(context: Context, id: String, completed: Boolean) {
        mutable.value?.let { draft -> mutable.value = draft.copy(steps = draft.steps.map {
            if (it.id == id) it.copy(needsReview = !completed) else it
        }) }
        persist(context)
    }
    fun recordTouchBack(context: Context, delayMs: Long) {
        val draft = mutable.value ?: return
        if (draft.active && !draft.paused) addStep(context, AutomationStep(label = "Recorded Back",
            kind = ActionKind.PRESS_BACK, packageName = draft.activeTouchPackage ?: draft.apps.first().packageName,
            maxAttempts = 1, interActionDelayMs = delayMs))
    }
    fun stopWithIssue(context: Context, issue: String) {
        mutable.value?.let { mutable.value = it.copy(recordingIssue = issue) }
        stop(context)
    }

    /** If process death occurred, the draft can be reviewed but NEVER resumes monitoring itself. */
    fun recover(context: Context) {
        if (mutable.value != null) return
        val json = File(context.filesDir, "draft.json").takeIf { it.exists() }?.readText() ?: return
        runCatching {
            val draft = Codec.gson.fromJson(json, RecordingDraft::class.java)
            if (draft != null) mutable.value = draft.copy(active = false, paused = false)
        }
    }

    fun replaceSteps(context: Context, steps: List<AutomationStep>) {
        mutable.value?.let { mutable.value = it.copy(steps = steps) }
        persist(context)
    }

    fun toPreset(): Preset? = mutable.value?.takeIf { !it.active }?.let { draft ->
        Preset(schemaVersion = if (draft.playbackMode == PlaybackMode.RECORDED_TOUCHES) 2 else 1,
            name = draft.name, apps = draft.apps, recordedDevice = draft.device,
            media = draft.media, steps = draft.steps + AutomationStep(label = "Verify captured media",
                kind = ActionKind.VERIFY_MEDIA, packageName = draft.apps.first().packageName), createdAtEpochMs = draft.startedAt,
            playbackMode = draft.playbackMode ?: PlaybackMode.SMART_CONTROLS, touchProfile = draft.touchProfile,
            touchProfiles = draft.touchProfiles ?: listOfNotNull(draft.touchProfile))
    }

    private fun persist(context: Context) {
        val appContext = context.applicationContext
        persistScope.launch {
            writeMutex.withLock {
                val snapshot = mutable.value
                val file = File(appContext.filesDir, "draft.json")
                if (snapshot == null) file.delete()
                else {
                    val temp = File(appContext.filesDir, "draft.json.tmp")
                    temp.writeText(Codec.gson.toJson(snapshot))
                    if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
                }
            }
        }
    }

    fun onEvent(context: Context, event: AccessibilityEvent, appRoot: AccessibilityNodeInfo?) {
        val draft = mutable.value ?: return
        if (!draft.active || draft.paused) return
        if (draft.playbackMode == PlaybackMode.RECORDED_TOUCHES) return // Overlay coordinates only; no semantic/text collection.
        val pkg = event.packageName?.toString() ?: return
        if (draft.apps.none { it.packageName == pkg }) return
        if (pkg == context.packageName) return
        val now = System.currentTimeMillis()
        val eventType = event.eventType
        if (pkg == CameraPolicy.PACKAGE) {
            val observed = CameraUi.state(appRoot)
            recordCameraTransition(context, observed)
            val sourceId = event.source?.viewIdResourceName
            val sourceMode = event.source?.contentDescription?.toString()
            if (sourceId == CameraPolicy.SHUTTER) {
                if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && observed == CameraState.PHOTO_READY &&
                    (lastEventAt == 0L || now - lastEventAt >= 170)) {
                    addStep(context, AutomationStep(label = CameraPolicy.label(ActionKind.TAKE_PHOTO),
                        kind = ActionKind.TAKE_PHOTO, packageName = pkg,
                        locator = UiLocator(resourceId = CameraPolicy.SHUTTER), capturedAtEpochMs = now,
                        interActionDelayMs = if (lastEventAt == 0L) 0 else now - lastEventAt))
                    lastEventAt = now
                }
                // Video start/stop are emitted ONLY from observed transitions, never the post-click label.
                return
            }
            if (sourceId?.contains("side_button_recycler") == true || sourceId == CameraPolicy.MODE_STRIP ||
                sourceMode in setOf("PhotoMode", "VideoMode")) return
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (lastPackage != pkg) {
                lastPackage = pkg
                if (draft.steps.none { it.kind == ActionKind.LAUNCH_APP && it.packageName == pkg })
                    addStep(context, AutomationStep(label = "Launch $pkg", kind = ActionKind.LAUNCH_APP,
                        packageName = pkg, capturedAtEpochMs = now))
            }
            return
        }
        val node = event.source
        if (node?.isPassword == true) return
        val root = appRoot
        val rect = Rect().also { node?.getBoundsInScreen(it) }
        val appBounds = Rect().also { root?.getBoundsInScreen(it) }
        if (appBounds.isEmpty) {
            val metrics = context.resources.displayMetrics
            appBounds.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val hasCoords = !rect.isEmpty && appBounds.width() > 0 && appBounds.height() > 0
        val x = if (hasCoords) ((rect.centerX() - appBounds.left).toFloat() / appBounds.width()).coerceIn(0f, 1f) else null
        val y = if (hasCoords) ((rect.centerY() - appBounds.top).toFloat() / appBounds.height()).coerceIn(0f, 1f) else null
        val isEditable = node?.isEditable == true
        val locator = UiLocator(
            resourceId = node?.viewIdResourceName?.takeIf { it.isNotBlank() },
            contentDescription = if (isEditable) null else safeControlLabel(node?.contentDescription?.toString()),
            text = if (isEditable) null else safeControlLabel(node?.text?.toString()),
            className = node?.className?.toString(), normalizedX = x, normalizedY = y,
            // A recorded coordinate is a reference, NOT implicit consent to blind tapping.
            allowCoordinateFallback = false
        )
        val identifier = locator.contentDescription ?: locator.text ?: locator.resourceId ?: "unidentified control"
        val delta = if (lastEventAt == 0L) 0L else now - lastEventAt
        val action = when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> when {
                Regex("\\b(start recording|record video|start video)\\b", RegexOption.IGNORE_CASE).containsMatchIn(identifier) -> ActionKind.START_VIDEO
                Regex("\\b(stop recording|stop video)\\b", RegexOption.IGNORE_CASE).containsMatchIn(identifier) -> ActionKind.STOP_VIDEO
                Regex("\\b(take photo|capture photo|take picture)\\b", RegexOption.IGNORE_CASE).containsMatchIn(identifier) -> ActionKind.TAKE_PHOTO
                else -> ActionKind.TAP
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> ActionKind.LONG_PRESS
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> ActionKind.SCROLL
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (isEditable) ActionKind.ENTER_TEXT else return
            }
            else -> return
        }
        // Do not capture typed text, delete events, passwords, OTPs, or keyboard characters.
        // An editable text field is a manual-review placeholder only.
        if (action == ActionKind.ENTER_TEXT && draft.steps.lastOrNull()?.kind == ActionKind.ENTER_TEXT &&
            draft.steps.lastOrNull()?.locator?.resourceId == locator.resourceId) return
        if (action == ActionKind.TAP && delta < 170 && draft.steps.lastOrNull()?.locator == locator) return
        val classificationUncertain = action in setOf(ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO) ||
            (action in setOf(ActionKind.TAP, ActionKind.LONG_PRESS) &&
                Regex("(?i)(shutter|capture|record|video|photo|camera)").containsMatchIn(identifier))
        lastEventAt = now
        addStep(context, AutomationStep(label = when (action) {
            ActionKind.ENTER_TEXT -> "Enter authorized test data ($identifier)"
            ActionKind.SCROLL -> "Scroll $identifier"
            ActionKind.LONG_PRESS -> "Long press $identifier"
            ActionKind.TAKE_PHOTO -> "Capture photo ($identifier)"
            ActionKind.START_VIDEO -> "Start video ($identifier)"
            ActionKind.STOP_VIDEO -> "Stop video ($identifier)"
            else -> "Tap $identifier"
        }, kind = action, packageName = pkg, locator = locator, capturedAtEpochMs = now,
            interActionDelayMs = delta, classificationUncertain = classificationUncertain,
            needsReview = classificationUncertain || action == ActionKind.ENTER_TEXT ||
                (locator.resourceId == null && locator.contentDescription == null && locator.text == null)))
    }

    private fun safeControlLabel(raw: String?): String? {
        val label = raw?.trim()?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return null
        // Avoid persisting obvious identifiers or dynamic secret-like strings as UI selectors.
        if (label.contains('@') || Regex("\\b\\d{4,}\\b").containsMatchIn(label) ||
            Regex("(?i)(one.time|password|verification code|\\botp\\b|secret|token)").containsMatchIn(label)) return null
        return label
    }

    private fun addStep(context: Context, step: AutomationStep) {
        val current = mutable.value ?: return
        if (current.steps.size >= 1000) { stop(context); return }
        mutable.value = current.copy(steps = current.steps + step)
        persist(context)
    }
}
