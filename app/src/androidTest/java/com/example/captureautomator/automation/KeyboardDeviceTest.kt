package com.example.captureautomator.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.captureautomator.core.*
import com.example.captureautomator.data.Codec
import com.example.captureautomator.util.AndroidDevice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Test APK only. All test input is confined to an isolated, offline fixture. No UiAutomation. */
class KeyboardDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pkg = "com.example.touchfixture.keyboard"
    private lateinit var service: AutomationAccessibilityService
    private var stage = "begin"
    private suspend fun until(timeout: Long = 15000, check: () -> Boolean) {
        withTimeout(timeout) { while (!check()) delay(50) }
    }
    private fun root(): AccessibilityNodeInfo = checkNotNull(service.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg }
        .maxByOrNull { it.layer }?.root)
    private fun button(text: String): AccessibilityNodeInfo {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isVisibleToUser && n.isClickable && n.text?.toString()?.equals(text,true)==true) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return checkNotNull(find(root().apply { refresh() })) { "Fixture button unavailable: $text" }
    }
    private suspend fun tap(x: Float, y: Float) {
        val done = CompletableDeferred<Boolean>()
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x,y) },0,30)).build()
        assertTrue(service.dispatchGesture(gesture, object: AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done.complete(true) }
            override fun onCancelled(g: GestureDescription?) { done.complete(false) }
        }, Handler(Looper.getMainLooper())))
        assertTrue(withTimeout(4000) { done.await() })
    }
    private suspend fun tapButton(text: String) {
        until { runCatching { button(text) }.isSuccess }
        stage="tap fixture $text"
        val b = Rect().also(button(text)::getBoundsInScreen); tap(b.exactCenterX(),b.exactCenterY())
        delay(500)
    }
    private suspend fun control(text: String) {
        // Own overlays are not consistently returned in this OEM's Accessibility window tree.
        // Use the actual attached View bounds and a physical gesture, not a synthetic click.
        stage="control $text"
        val barField = TouchRecorder::class.java.getDeclaredField("bar").apply { isAccessible = true }
        val row = barField.get(TouchRecorder) as android.view.ViewGroup
        val button = (0 until row.childCount).map(row::getChildAt)
            .filterIsInstance<android.widget.Button>().single { it.text.toString()==text }
        val point=IntArray(2); button.getLocationOnScreen(point)
        tap(point[0]+button.width/2f,point[1]+button.height/2f)
        delay(250)
    }
    private suspend fun ready(keyboard: Boolean) {
        var since = 0L
        try {
            until {
                val ready = RecordingSession.state.value?.active == true && TouchRecorder.controlsVisible() &&
                    TouchEnvironment.keyboardVisible(service) == keyboard
                val now = android.os.SystemClock.elapsedRealtime()
                if (!ready) since = 0L else if (since == 0L) since = now
                ready && now-since >= 500
            }
        } catch (error: Exception) {
            val windows = service.windows.joinToString { "type=${it.type} focused=${it.isFocused} active=${it.isActive} pkg=${it.root?.packageName} visible=${it.root?.isVisibleToUser}" }
            File(context.filesDir,"device-keyboard-064-failure.json").writeText(Codec.gson.toJson(mapOf("stage" to stage,"draft" to RecordingSession.state.value,"windows" to windows)))
            throw AssertionError("stage=$stage keyboard=$keyboard controls=${TouchRecorder.controlsVisible()} status=${TouchRecorder.status.value} current=${TouchEnvironment.current(service,pkg)} windows=$windows",error)
        }
    }
    @Test fun recordKeyboardDismissAndReplay() = runBlocking {
        withContext(Dispatchers.Main) {
            until(600000) { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                AutomationAccessibilityService.instance != null }
            service = checkNotNull(AutomationAccessibilityService.instance)
            assertTrue(AndroidDevice.launch(context,pkg)); until { service.activePackage()==pkg };delay(800)
            if (TouchEnvironment.keyboardVisible(service)) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);delay(400) }
            // Original user draft is backed up and restored by the host after this test process ends.
            RecordingSession.begin(context,"Keyboard-064-fixture",listOf(AppSpec(pkg,"Offline keyboard fixture","1")),
                MediaExpectation(0,0),PlaybackMode.RECORDED_TOUCHES)
            service.showIndicator(true,"Explicit keyboard fixture test")
            val checks = mutableListOf<String>()
            try {
                ready(false)
                val initial = checkNotNull(RecordingSession.state.value?.touchProfile)
                tapButton("OPEN KEYBOARD"); ready(true)
                assertEquals(initial,RecordingSession.state.value?.touchProfile)
                checks += "controls and coordinate anchor survive keyboard opening"
                val beforeKeys=RecordingSession.state.value!!.steps.size
                val ime=TouchEnvironment.keyboardBounds(service).maxByOrNull { it.width.toLong()*it.height }!!
                val layout=checkNotNull(TouchEnvironment.inputLayout(service,initial))
                assertTrue(layout.captureAreas.all { area -> layout.keys.none { TouchInputPolicy.intersection(area,it)!=null } })
                // Touch the native keyboard itself, not inputText/ENTER_TEXT. No key value is stored.
                tap(ime.left+ime.width*.5f,ime.top+ime.height*.55f);delay(600)
                assertEquals(beforeKeys,RecordingSession.state.value!!.steps.size)
                checks += "native keyboard tap does not enter the touch recording"
                tapButton("DONE EDITING"); ready(false)
                assertEquals(true,RecordingSession.state.value!!.steps.last().expectsKeyboard)
                checks += "app button above keyboard records and closes keyboard"
                val field=root().findAccessibilityNodeInfosByText("Local test input").firstOrNull { it.isEditable }
                    ?: findEditable(root(),false)
                val b=Rect().also(checkNotNull(field)::getBoundsInScreen);tap(b.exactCenterX(),b.exactCenterY());ready(true)
                assertEquals(false,RecordingSession.state.value!!.steps.last().expectsKeyboard)
                control("Back");ready(false)
                assertTrue(RecordingSession.state.value!!.steps.any { it.kind==ActionKind.PRESS_BACK && it.expectsKeyboard==true })
                tapButton("INCREMENT COUNTER");ready(false)
                checks += "non-password focus tap and recorded keyboard Back allow later touches"
                tapButton("OPEN KEYBOARD");ready(true)
                control("Pause");until { RecordingSession.state.value?.paused==true && TouchRecorder.controlsVisible() }
                assertEquals(0,TouchRecorder.captureWindowCount())
                control("Resume");ready(true)
                checks += "Pause and Resume remain accessible with keyboard visible"
                control("Stop");until { RecordingSession.state.value?.active==false }
                assertNull(RecordingSession.state.value?.recordingIssue)
                val preset=checkNotNull(RecordingSession.toPreset()).copy(requireTouchStart=false)
                assertTrue(preset.description.contains("Keyboard input was not recorded"))
                assertTrue(preset.steps.filter { it.kind==ActionKind.RECORDED_TOUCH || it.kind==ActionKind.PRESS_BACK }.all { it.expectsKeyboard!=null && it.touchWindow!=null })
                assertTrue(preset.steps.none { it.kind==ActionKind.ENTER_TEXT })
                checks += "Stop retains draft with geometry-only keyboard state and no text actions"
                assertTrue(AndroidDevice.launch(context,pkg));delay(500)
                button("DONE EDITING").performAction(AccessibilityNodeInfo.ACTION_CLICK)
                until { !TouchEnvironment.keyboardVisible(service) };delay(300)
                val report=ExecutionEngine(context,service,{_,_,_->},{currentCoroutineContext().ensureActive()}).execute(preset)
                assertEquals(Codec.gson.toJson(report),Outcome.PASS,report.outcome)
                checks += "recorded focus keyboard Done Back and counter sequence replays successfully"
                File(context.filesDir,"device-keyboard-064-report.json").writeText(Codec.gson.toJson(report))
                RecordingSession.discard(context)
                // Start directly with IME visible, then test password passthrough and an input dialog.
                assertTrue(AndroidDevice.launch(context,pkg));delay(500)
                button("OPEN KEYBOARD").performAction(AccessibilityNodeInfo.ACTION_CLICK)
                until { TouchEnvironment.keyboardVisible(service) };delay(400)
                RecordingSession.begin(context,"Keyboard-064-start-open",listOf(AppSpec(pkg,"Offline keyboard fixture","1")),MediaExpectation(0,0),PlaybackMode.RECORDED_TOUCHES)
                ready(true)
                assertEquals(initial.width,RecordingSession.state.value!!.touchProfile!!.width)
                assertEquals(initial.height,RecordingSession.state.value!!.touchProfile!!.height)
                checks += "recording can start with an already-open keyboard"
                val profile=checkNotNull(RecordingSession.state.value?.touchProfile)
                val password=checkNotNull(findEditable(root(),true));val pb=Rect().also(password::getBoundsInScreen)
                val point=TouchPoint((pb.exactCenterX()-profile.left)/(profile.width-1),(pb.exactCenterY()-profile.top)/(profile.height-1),0)
                assertFalse(TouchEnvironment.avoidsInput(service,profile,TouchStroke(listOf(point),30)))
                val count=RecordingSession.state.value!!.steps.size
                tap(pb.exactCenterX(),pb.exactCenterY());delay(500)
                assertEquals(count,RecordingSession.state.value!!.steps.size)
                control("Back");ready(false)
                tapButton("OPEN INPUT DIALOG");ready(true)
                control("Back");ready(false)
                tapButton("DONE");delay(600)
                // Android can restore the underlying field's keyboard when an input dialog closes.
                if(TouchEnvironment.keyboardVisible(service)) { ready(true);control("Back") }
                ready(false)
                tapButton("INCREMENT COUNTER");ready(false)
                checks += "password field excluded and same-app keyboard dialog does not end recording"
                control("Stop");until { RecordingSession.state.value?.active==false }
                File(context.filesDir,"device-keyboard-064.json").writeText(Codec.gson.toJson(mapOf("checks" to checks,"result" to "PASS","recordedSteps" to preset.steps.size)))
            } finally { RecordingSession.discard(context);service.clearIndicator() }
        }
    }
    /** Host drives only a newly-created local Story editor; never posts/sends or types text. */
    @Test fun instagramKeyboardManual() = runBlocking {
        withContext(Dispatchers.Main) {
            until(600000) { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked && AutomationAccessibilityService.instance!=null }
            service=checkNotNull(AutomationAccessibilityService.instance)
            val instagram="com.instagram.android"
            val stop=File(context.filesDir,"keyboard-instagram-stop");stop.delete()
            RecordingSession.begin(context,"Instagram keyboard local check",listOf(AppSpec(instagram,"Instagram",AndroidDevice.version(context,instagram))),MediaExpectation(0,0),PlaybackMode.RECORDED_TOUCHES)
            service.showIndicator(true,"Explicit Instagram keyboard test")
            try {
                until(600000) { TouchRecorder.controlsVisible() && RecordingSession.state.value?.touchProfile!=null }
                val frame=checkNotNull(RecordingSession.state.value?.touchProfile)
                File(context.filesDir,"keyboard-instagram-ready.json").writeText("{\"ready\":true}")
                until(600000) { TouchEnvironment.keyboardVisible(service) && TouchRecorder.controlsVisible() }
                delay(600)
                assertTrue(TouchRecorder.controlsVisible())
                assertEquals(frame,RecordingSession.state.value?.touchProfile)
                val before=RecordingSession.state.value!!.steps.size
                File(context.filesDir,"keyboard-instagram-open.json").writeText(Codec.gson.toJson(mapOf("controlsVisible" to true,"coordinateFrameRetained" to true,"steps" to before)))
                until(600000) { stop.exists() }
                val draft=checkNotNull(RecordingSession.state.value)
                assertTrue(draft.active);assertNull(draft.recordingIssue)
                assertTrue(draft.keyboardSeen)
                assertTrue(draft.steps.any { it.kind==ActionKind.PRESS_BACK && it.expectsKeyboard==true })
                assertTrue(draft.steps.size>before)
                assertTrue(TouchRecorder.controlsVisible())
                File(context.filesDir,"device-instagram-keyboard-064.json").writeText(Codec.gson.toJson(mapOf("result" to "PASS","controlsSurvivedKeyboard" to true,"frameRetained" to true,"recordedKeyboardBack" to true,"continuedAfterKeyboard" to true,"steps" to draft.steps.size,"postedOrSent" to false,"typedText" to false)))
            } finally { RecordingSession.discard(context);service.clearIndicator();stop.delete() }
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo, password: Boolean): AccessibilityNodeInfo? {
        if(node.isVisibleToUser && node.isEditable && node.isPassword==password)return node
        for(i in 0 until node.childCount) node.getChild(i)?.let { child -> findEditable(child,password)?.let { return it } }
        return null
    }
}
