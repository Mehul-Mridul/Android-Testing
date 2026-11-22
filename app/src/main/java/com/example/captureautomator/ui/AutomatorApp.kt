package com.example.captureautomator.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.captureautomator.automation.*
import com.example.captureautomator.core.*
import com.example.captureautomator.data.*
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class Page { HOME, CREATE, RECORD, LIBRARY, EDIT, HISTORY, BATCH, SETTINGS, DEVICE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomatorApp(activity: MainActivity, repository: PresetRepository) {
    val scope = rememberCoroutineScope()
    val rows by repository.presets.collectAsState(initial = emptyList())
    val runs by repository.executions.collectAsState(initial = emptyList())
    val draft by RecordingSession.state.collectAsState()
    val execution by ExecutionController.state.collectAsState()
    val batch by BatchController.state.collectAsState()
    val calibration by TouchCalibration.state.collectAsState()
    val touchStatus by TouchRecorder.status.collectAsState()
    var page by remember { mutableStateOf(Page.HOME) }
    var edit by remember { mutableStateOf<Preset?>(null) }
    var notice by remember { mutableStateOf("") }
    var exportData by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportData) }
                    ?: error("Cannot write destination")
            } }.onFailure { notice = "Export failed: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Cannot read document")
                }
                repository.importJson(json)
            }.onSuccess { notice = "Imported preset. Known Camera actions use runtime state checks; review any flagged steps." }
                .onFailure { notice = "Import rejected: ${it.message}" }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notice = "Check that full photo/video access and notifications were granted"
    }
    fun requestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 34) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(perms)
    }
    LaunchedEffect(calibration.message, calibration.savedPresetId) {
        if (!calibration.running && calibration.message.isNotBlank()) notice = calibration.message
        if (calibration.savedPresetId != null) { edit = null; page = Page.LIBRARY }
    }
    LaunchedEffect(draft?.active) {
        if (draft != null && draft?.active == false && page == Page.HOME) page = Page.RECORD
    }
    MaterialTheme {
        Scaffold(topBar = {
            TopAppBar(title = { Text("3P Capture Automator") }, navigationIcon = {
                if (page != Page.HOME) TextButton(onClick = { page = Page.HOME }) { Text("‹ Home") }
            })
        }, bottomBar = {
            if (notice.isNotBlank()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { notice = "" }) { Text("Dismiss") }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {
                when (page) {
                    Page.HOME -> HomePage(draft?.active == true, execution, batch,
                        go = { page = it }, onResumeRecord = { page = Page.RECORD })
                    Page.CREATE -> CreatePage(
                        activity,
                        onPermission = ::requestPermissions,
                        onBegin = { name, apps, media, mode ->
                            val service = AutomationAccessibilityService.instance
                            if (execution.running || batch.running || draft?.active == true || calibration.running)
                                notice = "Stop the current recording or playback before starting another session"
                            else if (service == null) notice = "Enable the accessibility service in Settings first"
                            else if (!NotificationsAvailable(activity)) {
                                notice = "Grant notification permission before starting a visible recording"
                                requestPermissions()
                            } else {
                                RecordingSession.begin(activity, name, apps, media, mode)
                                service.showIndicator(true, name)
                                page = Page.RECORD
                                if (!AndroidDevice.launch(activity, apps.first().packageName)) {
                                    notice = "Cannot launch app. Open the selected app manually."
                                }
                            }
                        })
                    Page.RECORD -> RecordingPage(draft, onPause = {
                        if (draft?.paused == true) RecordingSession.resume(activity) else RecordingSession.pause(activity)
                    }, onStop = {
                        RecordingSession.stop(activity)
                        AutomationAccessibilityService.instance?.clearIndicator()
                    }, onDiscard = {
                        RecordingSession.discard(activity)
                        AutomationAccessibilityService.instance?.clearIndicator()
                        page = Page.HOME
                    }, onReview = {
                        edit = RecordingSession.toPreset()
                        if (edit != null) page = Page.EDIT
                    })
                    Page.LIBRARY -> LibraryPage(rows, onEdit = { row ->
                        runCatching { Codec.parsePreset(row.json) }.onSuccess { edit = it; page = Page.EDIT }
                            .onFailure { notice = "Invalid preset: ${it.message}" }
                    }, onRun = { row ->
                        runCatching { Codec.parsePreset(row.json) }.onSuccess {
                            ExecutionController.run(activity, it, repository)
                            page = Page.HOME
                        }.onFailure { notice = it.message.orEmpty() }
                    }, onDuplicate = { row -> scope.launch { repository.duplicate(row.id) } },
                    onDelete = { row -> scope.launch { repository.delete(row.id) } },
                    onExport = { row ->
                        exportData = row.json
                        exportLauncher.launch("${row.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")
                    }, onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) })
                    Page.EDIT -> edit?.let { preset ->
                        PresetEditor(preset, onCalibrate = { candidate ->
                            val service = AutomationAccessibilityService.instance
                            if (service == null) notice = "Enable Accessibility before touch calibration"
                            else if (execution.running || batch.running || draft?.active == true) notice = "Stop the active session before calibration"
                            else runCatching { TouchCalibration.start(service, candidate, repository) }
                                .onFailure { notice = it.message.orEmpty() }
                        }, onSave = { updated ->
                            scope.launch {
                                runCatching { repository.save(updated) }
                                    .onSuccess {
                                        if (draft?.active == false) RecordingSession.discard(activity)
                                        edit = null; page = Page.LIBRARY
                                    }.onFailure { notice = "Save failed: ${it.message}" }
                            }
                        }, onCancel = { page = Page.LIBRARY })
                    }
                    Page.HISTORY -> HistoryPage(runs)
                    Page.BATCH -> BatchPage(rows, batch, onRun = { selected, stopOnFail ->
                        val presets = selected.mapNotNull { id -> rows.find { it.id == id } }
                            .mapNotNull { runCatching { Codec.parsePreset(it.json) }.getOrNull() }
                        if (presets.size == selected.size) BatchController.run(activity, presets, repository, stopOnFail)
                        else notice = "A selected preset could not be loaded"
                    })
                    Page.SETTINGS -> SettingsPage(activity, onPermission = ::requestPermissions)
                    Page.DEVICE -> DevicePage(activity)
                }
            }
        }
    }
}

private fun NotificationsAvailable(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context,
        Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable private fun Heading(title: String, subtitle: String = "") {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
}

@Composable private fun MainNav(label: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable private fun HomePage(recording: Boolean, execution: ExecutionUiState,
                                 batch: BatchUiState, go: (Page) -> Unit, onResumeRecord: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("QA camera workflow automation", "Record → review → save → replay → verify")
        if (recording) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("Recording active", fontWeight = FontWeight.Bold)
                Button(onClick = onResumeRecord) { Text("Open recording controls") }
            } }
        }
        if (execution.running || execution.report != null || execution.error.isNotBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(if (execution.running) "RUNNING: ${execution.presetName}" else "Latest execution")
                Text("Step ${execution.stepIndex}/${execution.totalSteps}: ${execution.currentStep}")
                if (execution.running) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (execution.paused) ExecutionController.resume()
                        else ExecutionController.pause() }) { Text(if (execution.paused) "Resume" else "Pause") }
                    OutlinedButton(onClick = ExecutionController::stop) { Text("Stop") }
                }
                execution.report?.let { Text("Result: ${it.outcome} | ${it.media.photosFound} photos, ${it.media.videosFound} videos") }
                if (execution.error.isNotBlank()) Text(execution.error)
            } }
            Spacer(Modifier.height(8.dp))
        }
        if (batch.running) Text("Batch running: ${batch.position}/${batch.total} — ${batch.current}")
        MainNav("Record new automation", { go(Page.CREATE) })
        MainNav("Saved presets", { go(Page.LIBRARY) })
        MainNav("Run automation (select a preset)", { go(Page.LIBRARY) })
        MainNav("Batch execution", { go(Page.BATCH) })
        MainNav("Execution history", { go(Page.HISTORY) })
        MainNav("Device information", { go(Page.DEVICE) })
        MainNav("Settings and permissions", { go(Page.SETTINGS) })
        Spacer(Modifier.height(12.dp))
        Text("Only explicitly selected apps are observed during a visible recording session.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun CreatePage(activity: MainActivity, onPermission: () -> Unit,
                                   onBegin: (String, List<AppSpec>, MediaExpectation, PlaybackMode) -> Unit) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var photoCount by remember { mutableStateOf("1") }
    var videoCount by remember { mutableStateOf("0") }
    var durationSeconds by remember { mutableStateOf("5") }
    var selected by remember { mutableStateOf<String?>(null) }
    val apps = remember { AndroidDevice.installedLaunchableApps(activity) }
    Column {
        Heading("New touch recording", "Choose one app. Use Batch to play several presets in sequence.")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Preset name") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(photoCount, { photoCount = it }, label = { Text("Photos") }, modifier = Modifier.weight(1f))
            OutlinedTextField(videoCount, { videoCount = it }, label = { Text("Videos") }, modifier = Modifier.weight(1f))
            OutlinedTextField(durationSeconds, { durationSeconds = it }, label = { Text("Seconds") }, modifier = Modifier.weight(1f))
        }
        OutlinedButton(onClick = onPermission) { Text("Grant media + notification permissions") }
        OutlinedTextField(query, { query = it }, label = { Text("Find installed application") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Selected: " + (apps.find { it.packageName == selected }?.displayName ?: "None"))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(apps.filter { it.displayName.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().clickable {
                    selected = if (selected == app.packageName) null else app.packageName
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected == app.packageName, onCheckedChange = null)
                    Column {
                        Text(app.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${app.packageName} · ${app.recordedVersion}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = {
            val chosen = listOf(apps.singleOrNull { it.packageName == selected } ?: return@Button)
            onBegin(name, chosen, MediaExpectation(photoCount.toIntOrNull() ?: 2,
                videoCount.toIntOrNull() ?: 1, (durationSeconds.toLongOrNull() ?: 5L) * 1000), PlaybackMode.RECORDED_TOUCHES)
        }, enabled = name.isNotBlank() && selected != null, modifier = Modifier.fillMaxWidth()) {
            Text("Start touch recording")
        }
        Text("Wait for Ready after each touch. Include local-save popup responses in your recording.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun RecordingPage(draft: RecordingDraft?, onPause: () -> Unit,
                                     onStop: () -> Unit, onDiscard: () -> Unit, onReview: () -> Unit) {
    Column {
        Heading(if (draft?.active == true) "Recording session" else "Recording review",
            if (draft?.playbackMode == PlaybackMode.RECORDED_TOUCHES) "Recorded touches: paths and timing, no camera-mode inference."
            else "Camera-like custom controls may need assisted editing.")
        if (draft == null) {
            Text("No active or saved draft.")
            return@Column
        }
        Text(draft.name, fontWeight = FontWeight.Bold)
        if (draft.playbackMode == PlaybackMode.RECORDED_TOUCHES) {
            val status by TouchRecorder.status.collectAsState()
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        draft.recordingIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("${draft.steps.size} observed steps · ${draft.apps.joinToString { it.displayName }}")
        if (draft.active) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPause) { Text(if (draft.paused) "Resume" else "Pause") }
            Button(onClick = onStop) { Text("Stop") }
        } else Button(onClick = onReview) { Text("Review and edit captured steps") }
        OutlinedButton(onClick = onDiscard) { Text("Discard draft") }
        LazyColumn {
            itemsIndexed(draft.steps) { index, step ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("${index + 1}. ${step.label}")
                    Text("${step.kind} ${if (step.needsReview) "· NEEDS REVIEW" else ""}",
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable private fun LibraryPage(rows: List<PresetRow>, onEdit: (PresetRow) -> Unit,
                                    onRun: (PresetRow) -> Unit, onDuplicate: (PresetRow) -> Unit,
                                    onDelete: (PresetRow) -> Unit, onExport: (PresetRow) -> Unit,
                                    onImport: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<PresetRow?>(null) }
    Column {
        Heading("Preset library", "Persistent Room storage · JSON import/export")
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search presets") })
        OutlinedButton(onClick = onImport) { Text("Import JSON") }
        LazyColumn {
            items(rows.filter { it.name.contains(query, true) || it.folder.contains(query, true) }) { row ->
                ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.name, fontWeight = FontWeight.Bold)
                        Text(row.folder, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { onRun(row) }) { Text("Run") }
                            OutlinedButton(onClick = { onEdit(row) }) { Text("Edit") }
                            OutlinedButton(onClick = { onDuplicate(row) }) { Text("Copy") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onExport(row) }) { Text("Export") }
                            TextButton(onClick = { deleteCandidate = row }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    deleteCandidate?.let { row ->
        AlertDialog(onDismissRequest = { deleteCandidate = null }, title = { Text("Delete preset?") },
            text = { Text(row.name) }, confirmButton = { TextButton(onClick = {
                onDelete(row); deleteCandidate = null
            }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } })
    }
}

@Composable private fun PresetEditor(preset: Preset, onSave: (Preset) -> Unit, onCalibrate: (Preset) -> Unit, onCancel: () -> Unit) {
    var current by remember(preset.id) { mutableStateOf(preset) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var editingName by remember { mutableStateOf(current.name) }
    var editingFolder by remember { mutableStateOf(current.folder) }
    var addNew by remember { mutableStateOf(false) }
    var repairError by remember { mutableStateOf("") }
    val validation = PresetValidation.errors(current.copy(name = editingName, folder = editingFolder))
    val playbackIssues = PresetValidation.playbackErrors(current)
    Column {
        Heading("Preset editor", if (TouchPolicy.isTouch(current)) "Recorded touches: calibration is required after changing device or layout."
            else "Use semantic selectors; coordinate fallback requires explicit approval.")
        if (TouchPolicy.isTouch(current)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(current.requireTouchStart != false, { current = current.copy(requireTouchStart = it) })
                Text("Check starting screen for each app", style = MaterialTheme.typography.bodySmall)
            }
            Text("Coordinates cannot recognise the starting screen. Prepare the app before immediate replay.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onCalibrate(current.copy(name = editingName, folder = editingFolder)) }) {
                Text("Calibrate touch copy")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(current.clearRecentsAfterRun == true, { current = current.copy(clearRecentsAfterRun = it) })
            Text("Clear ALL recent apps after verification", style = MaterialTheme.typography.bodySmall)
        }
        Text("Returns to Automator first. Cleanup is skipped on Stop or an incomplete app switch.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(editingName, { editingName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(editingFolder, { editingFolder = it }, label = { Text("Folder") }, modifier = Modifier.fillMaxWidth())
        Text("Expected ${current.media.photos} photos, ${current.media.videos} videos of ${current.media.videoDurationMs / 1000}s",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(current.media.photos.toString(), { value -> value.toIntOrNull()?.let {
                current = current.copy(media = current.media.copy(photos = it))
            } }, label = { Text("Photos") }, modifier = Modifier.weight(1f))
            OutlinedTextField(current.media.videos.toString(), { value -> value.toIntOrNull()?.let {
                current = current.copy(media = current.media.copy(videos = it))
            } }, label = { Text("Videos") }, modifier = Modifier.weight(1f))
        }
        if (playbackIssues.isNotEmpty()) Text("Playback blocked: ${playbackIssues.first()}", color = MaterialTheme.colorScheme.error)
        if (validation.isNotEmpty()) Text("Validation: ${validation.first()}", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(current.copy(name = editingName, folder = editingFolder,
                modifiedAtEpochMs = System.currentTimeMillis())) }, enabled = validation.isEmpty()) { Text("Save preset changes") }
            OutlinedButton(onClick = { addNew = true }) { Text("Add step") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (!TouchPolicy.isTouch(current) && current.apps.size == 1 && current.apps.single().packageName == CameraPolicy.PACKAGE) {
            OutlinedButton(onClick = {
                runCatching { CameraPolicy.repairCopy(current) }.onSuccess(onSave)
                    .onFailure { repairError = it.message.orEmpty() }
            }) { Text("Save camera repair copy") }
            Text("Creates a separate copy with verified mode actions and configured video duration.", style = MaterialTheme.typography.bodySmall)
            if (repairError.isNotBlank()) Text(repairError, color = MaterialTheme.colorScheme.error)
        }
        Text("Unreviewed: ${current.steps.count { it.needsReview }}. Known Camera captures do not require repeated review. Save preset changes after editing.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn {
            itemsIndexed(current.steps) { index, step ->
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${index + 1}. ${if (step.kind in setOf(ActionKind.TAKE_PHOTO, ActionKind.START_VIDEO, ActionKind.STOP_VIDEO, ActionKind.SELECT_PHOTO_MODE, ActionKind.SELECT_VIDEO_MODE)) CameraPolicy.label(step.kind) else step.label}", fontWeight = FontWeight.SemiBold)
                        Text("${step.kind} · ${step.packageName ?: "default app"}${if (step.needsReview) " · REVIEW" else ""}",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            TextButton(onClick = { selectedIndex = index }) { Text("Edit") }
                            TextButton(onClick = {
                                if (index > 0) current = current.copy(steps = current.steps.toMutableList().apply {
                                    add(index - 1, removeAt(index))
                                })
                            }, enabled = index > 0) { Text("↑") }
                            TextButton(onClick = {
                                if (index < current.steps.lastIndex) current = current.copy(steps = current.steps.toMutableList().apply {
                                    add(index + 1, removeAt(index))
                                })
                            }, enabled = index < current.steps.lastIndex) { Text("↓") }
                            TextButton(onClick = { current = current.copy(steps = current.steps.filterIndexed { i, _ -> i != index }) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }
    }
    val dialogStep = if (addNew) AutomationStep(label = "Wait for camera", kind = ActionKind.WAIT_MS,
        waitMs = 1000L, packageName = current.apps.firstOrNull()?.packageName)
    else selectedIndex?.let { current.steps.getOrNull(it) }
    dialogStep?.let { step ->
        StepEditorDialog(step, onDismiss = { addNew = false; selectedIndex = null }, onUpdate = { updated ->
            current = if (addNew) current.copy(steps = current.steps + updated)
                else current.copy(steps = current.steps.mapIndexed { i, s -> if (i == selectedIndex) updated else s })
            addNew = false; selectedIndex = null
        })
    }
}

@Composable private fun StepEditorDialog(step: AutomationStep, onDismiss: () -> Unit,
                                         onUpdate: (AutomationStep) -> Unit) {
    var label by remember(step.id) { mutableStateOf(step.label) }
    var kind by remember(step.id) { mutableStateOf(step.kind) }
    var packageName by remember(step.id) { mutableStateOf(step.packageName.orEmpty()) }
    var rid by remember(step.id) { mutableStateOf(step.locator?.resourceId.orEmpty()) }
    var desc by remember(step.id) { mutableStateOf(step.locator?.contentDescription.orEmpty()) }
    var text by remember(step.id) { mutableStateOf(step.locator?.text.orEmpty()) }
    var clazz by remember(step.id) { mutableStateOf(step.locator?.className.orEmpty()) }
    var x by remember(step.id) { mutableStateOf(step.locator?.normalizedX?.toString().orEmpty()) }
    var y by remember(step.id) { mutableStateOf(step.locator?.normalizedY?.toString().orEmpty()) }
    var ex by remember(step.id) { mutableStateOf(step.locator?.endNormalizedX?.toString().orEmpty()) }
    var ey by remember(step.id) { mutableStateOf(step.locator?.endNormalizedY?.toString().orEmpty()) }
    var useCoords by remember(step.id) { mutableStateOf(step.locator?.allowCoordinateFallback ?: false) }
    var waitMs by remember(step.id) { mutableStateOf(step.waitMs.toString()) }
    var timeoutMs by remember(step.id) { mutableStateOf(step.timeoutMs.toString()) }
    var recordedDelay by remember(step.id) { mutableStateOf(step.interActionDelayMs.toString()) }
    var attempts by remember(step.id) { mutableStateOf(step.maxAttempts.toString()) }
    var authorizedText by remember(step.id) { mutableStateOf(step.authorizedTestText.orEmpty()) }
    var minimum by remember(step.id) { mutableStateOf(step.minimumSavedBeforeLaunch?.toString().orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit automation step") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Action description") })
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(kind.name) }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        ActionKind.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.name) }, onClick = { kind = option; label = CameraPolicy.label(option); expanded = false })
                        }
                    }
                }
                OutlinedTextField(packageName, { packageName = it }, label = { Text("Target package") })
                if (kind == ActionKind.LAUNCH_APP) OutlinedTextField(minimum, { if (it.isEmpty() || it.toIntOrNull() in 0..1000) minimum = it }, label = { Text("Files to wait for in previous app") }, supportingText = { Text("Default: one for capture workflows. Enter 0 for navigation only.") })
                if (kind == ActionKind.RECORDED_TOUCH) Text("Recorded path: ${step.touchStroke?.points?.size ?: 0} points, ${step.touchStroke?.durationMs ?: 0}ms. Use Calibrate touch copy to move its points.")
                if (kind != ActionKind.RECORDED_TOUCH) {
                OutlinedTextField(rid, { rid = it }, label = { Text("Resource ID (highest priority)") })
                OutlinedTextField(desc, { desc = it }, label = { Text("Content description") })
                OutlinedTextField(text, { text = it }, label = { Text("Accessible visible text") })
                OutlinedTextField(clazz, { clazz = it }, label = { Text("Widget class (optional filter)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useCoords, { useCoords = it })
                    Text("Allow normalized coordinate fallback (risk: layout changes)",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (useCoords || kind == ActionKind.SWIPE) {
                    OutlinedTextField(x, { x = it }, label = { Text("X (0–1)") })
                    OutlinedTextField(y, { y = it }, label = { Text("Y (0–1)") })
                    if (kind == ActionKind.SWIPE) {
                        OutlinedTextField(ex, { ex = it }, label = { Text("Swipe end X") })
                        OutlinedTextField(ey, { ey = it }, label = { Text("Swipe end Y") })
                    }
                }
                }
                if (kind == ActionKind.ENTER_TEXT) OutlinedTextField(authorizedText, { authorizedText = it },
                    label = { Text("Explicit non-sensitive test string") })
                Text("Apply confirms the selected action kind. A video gesture does not prove recording started.")
                OutlinedTextField(recordedDelay, { recordedDelay = it }, label = { Text("Recorded pre-action delay (ms)") })
                OutlinedTextField(waitMs, { waitMs = it }, label = { Text("Wait milliseconds") })
                OutlinedTextField(timeoutMs, { timeoutMs = it }, label = { Text("Timeout milliseconds") })
                OutlinedTextField(attempts, { attempts = it }, label = { Text("Max attempts") })
            }
        }, confirmButton = { TextButton(onClick = {
            val loc = UiLocator(rid.ifBlank { null }, desc.ifBlank { null }, text.ifBlank { null },
                clazz.ifBlank { null }, x.toFloatOrNull(), y.toFloatOrNull(),
                ex.toFloatOrNull(), ey.toFloatOrNull(), useCoords)
            val requiresLocator = kind in setOf(ActionKind.TAP, ActionKind.TAKE_PHOTO,
                ActionKind.START_VIDEO, ActionKind.STOP_VIDEO, ActionKind.LONG_PRESS,
                ActionKind.DOUBLE_TAP, ActionKind.ENTER_TEXT, ActionKind.ASSERT_ELEMENT,
                ActionKind.WAIT_FOR_ELEMENT, ActionKind.WAIT_FOR_ELEMENT_GONE)
            val identified = !loc.resourceId.isNullOrBlank() || !loc.contentDescription.isNullOrBlank() ||
                !loc.text.isNullOrBlank() || (useCoords && loc.normalizedX != null && loc.normalizedY != null)
            onUpdate(step.copy(minimumSavedBeforeLaunch = minimum.toIntOrNull(), label = label, kind = kind, packageName = packageName.ifBlank { null },
                locator = loc, waitMs = waitMs.toLongOrNull() ?: 0,
                timeoutMs = timeoutMs.toLongOrNull() ?: 8000,
                maxAttempts = attempts.toIntOrNull() ?: 2,
                authorizedTestText = authorizedText.ifBlank { null },
                interActionDelayMs = recordedDelay.toLongOrNull() ?: -1,
                classificationUncertain = false, needsReview = requiresLocator && !identified))
        }) { Text("Apply step changes") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun HistoryPage(rows: List<ExecutionRow>) {
    var selected by remember { mutableStateOf<ExecutionReport?>(null) }
    Column {
        Heading("Execution history", "Persisted reports with step-level evidence")
        LazyColumn {
            items(rows) { row ->
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable {
                    selected = runCatching { Codec.gson.fromJson(row.reportJson, ExecutionReport::class.java) }.getOrNull()
                }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.presetName, fontWeight = FontWeight.Bold)
                        Text("${row.outcome} · ${java.text.DateFormat.getDateTimeInstance().format(row.startedAt)}")
                    }
                }
            }
        }
    }
    selected?.let { report ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("${report.outcome}: ${report.presetName}") },
            text = { Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Text("${report.device.manufacturer} ${report.device.model} | Android API ${report.device.androidSdk}")
                Text("Media: ${report.media.photosFound} photos, ${report.media.videosFound} videos")
                Text("Verified: ${report.media.verificationComplete}")
                Text("Run ID: ${report.id}")
                Text("Gesture completion does not confirm capture. Media evidence is listed separately.")
                report.media.issues.forEach { Text("Issue: $it") }
                report.media.mediaEvidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (report.notes.isNotBlank()) Text(report.notes)
                report.results.forEach { step ->
                    HorizontalDivider()
                    Text("${if (step.success) "✓" else "✗"} ${step.label}")
                    Text(step.message, style = MaterialTheme.typography.bodySmall)
                    if (step.screenshotFile != null) Text("Diagnostic: ${step.screenshotFile}")
                }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
}

@Composable private fun BatchPage(rows: List<PresetRow>, state: BatchUiState,
                                  onRun: (List<String>, Boolean) -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    var stopOnFail by remember { mutableStateOf(true) }
    Column {
        Heading("Batch execution", "Select presets in playback order. Start each app on its recorded screen. Batch clears Recents before and after playback.")
        Text("${state.position}/${state.total} ${state.current}")
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        if (state.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (state.paused) BatchController.resume() else BatchController.pause() }) {
                    Text(if (state.paused) "Resume" else "Pause")
                }
                OutlinedButton(onClick = BatchController::stop) { Text("Stop") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(stopOnFail, { stopOnFail = it })
                Text("Stop batch when a preset fails or is unverifiable")
            }
            Button(onClick = { onRun(selected.toList(), stopOnFail) }, enabled = selected.isNotEmpty()) {
                Text("Start prepared batch (${selected.size})")
            }
        }
        if (state.reports.isNotEmpty()) {
            Text("Results: ${state.reports.joinToString { "${it.presetName}: ${it.outcome}" }}")
        }
        LazyColumn {
            items(rows, key = { it.id }) { row ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.running) {
                    if (!selected.remove(row.id)) selected.add(row.id)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(row.id in selected, onCheckedChange = null)
                    if (row.id in selected) Text("${selected.indexOf(row.id) + 1}. ")
                    Text(row.name)
                }
            }
        }
    }
}

@Composable private fun SettingsPage(activity: MainActivity, onPermission: () -> Unit) {
    val preferences = remember { activity.getSharedPreferences("automation", android.content.Context.MODE_PRIVATE) }
    var screenshots by remember { mutableStateOf(preferences.getBoolean("diagnostic_screenshots", false)) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Settings & permissions")
        Button(onClick = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text("Open Android Accessibility settings")
        }
        Button(onClick = onPermission) { Text("Request full photo/video and notification access") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(screenshots, { new ->
                screenshots = new
                preferences.edit().putBoolean("diagnostic_screenshots", new).apply()
            })
            Text("Save diagnostic screenshots on failures (local-only)")
        }
        Text("Recording is visibly initiated, restricted to selected apps and does not store typed contents. " +
            "Android screenshots may be blocked by secure windows; no bypass is attempted.")
        Text("All presets and run reports remain on the device unless exported manually.")
    }
}

@Composable private fun DevicePage(activity: MainActivity) {
    val d = remember { AndroidDevice.profile(activity) }
    val media = remember { MediaVerifier(activity) }
    Column {
        Heading("Device information")
        Text("Manufacturer: ${d.manufacturer}")
        Text("Model: ${d.model}")
        Text("Android API: ${d.androidSdk}")
        Text("Display: ${d.widthPx} × ${d.heightPx}, density ${d.densityDpi} dpi")
        Text("Accessibility service: ${if (AutomationAccessibilityService.instance != null) "Connected" else "Not connected"}")
        Text("Full shared media access: ${if (media.fullMediaPermission()) "Granted" else "Missing / partial"}")
    }
}
