package com.example.captureautomator.automation

import android.app.KeyguardManager
import android.graphics.Rect
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

/** Explicit offline fixtures and native-camera test only. Never saves or rewrites user presets. */
class BatchHandoffDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var service: AutomationAccessibilityService
    private suspend fun until(timeout:Long=20000,check:()->Boolean) {
        withTimeout(timeout) { while(!check()) delay(50) }
    }
    private suspend fun counter(pkg:String): Preset {
        assertTrue(AndroidDevice.launch(context,pkg));until { service.activePackage()==pkg };delay(700)
        val profile=checkNotNull(TouchEnvironment.current(service,pkg))
        val button=service.currentRoot()!!.findAccessibilityNodeInfosByText("INCREMENT MAIN").single { it.isVisibleToUser && it.isClickable }
        val b=Rect().also(button::getBoundsInScreen)
        return Preset(schemaVersion=2,name="Batch-065-counter",apps=listOf(AppSpec(pkg,"Offline counter",AndroidDevice.version(context,pkg))),
            recordedDevice=AndroidDevice.profile(context),media=MediaExpectation(0,0),playbackMode=PlaybackMode.RECORDED_TOUCHES,
            touchProfile=profile,touchProfiles=listOf(profile),clearRecentsAfterRun=true,
            steps=listOf(AutomationStep(label="Open counter",kind=ActionKind.LAUNCH_APP,packageName=pkg),
                AutomationStep(label="Recorded counter tap",kind=ActionKind.RECORDED_TOUCH,packageName=pkg,
                    interActionDelayMs=800,maxAttempts=1,expectsPopup=false,expectsKeyboard=false,
                    touchStroke=TouchStroke(listOf(TouchPoint((b.exactCenterX()-profile.left)/(profile.width-1),
                        (b.exactCenterY()-profile.top)/(profile.height-1),0)),30)),
                AutomationStep(label="Verify media",kind=ActionKind.VERIFY_MEDIA,packageName=pkg)))
    }
    private suspend fun camera():Preset {
        val pkg=CameraPolicy.PACKAGE
        assertTrue(AndroidDevice.launch(context,pkg));until { service.activePackage()==pkg };delay(1200)
        assertTrue(CameraUi(service,PlaybackTiming {}).ensureMode(false,10000).first);delay(500)
        val profile=checkNotNull(TouchEnvironment.current(service,pkg))
        val shutter=service.currentRoot()!!.findAccessibilityNodeInfosByViewId(CameraPolicy.SHUTTER).single { it.isVisibleToUser && it.isEnabled }
        val b=Rect().also(shutter::getBoundsInScreen)
        return Preset(schemaVersion=2,name="Batch-065-native-photo",apps=listOf(AppSpec(pkg,"Native camera",AndroidDevice.version(context,pkg))),
            recordedDevice=AndroidDevice.profile(context),media=MediaExpectation(1,0),playbackMode=PlaybackMode.RECORDED_TOUCHES,
            touchProfile=profile,touchProfiles=listOf(profile),clearRecentsAfterRun=true,
            steps=listOf(AutomationStep(label="Open native camera",kind=ActionKind.LAUNCH_APP,packageName=pkg),
                AutomationStep(label="Recorded shutter touch",kind=ActionKind.RECORDED_TOUCH,packageName=pkg,
                    interActionDelayMs=1200,maxAttempts=1,expectsPopup=false,expectsKeyboard=false,
                    touchStroke=TouchStroke(listOf(TouchPoint((b.exactCenterX()-profile.left)/(profile.width-1),
                        (b.exactCenterY()-profile.top)/(profile.height-1),0)),30)),
                AutomationStep(label="Verify saved photo",kind=ActionKind.VERIFY_MEDIA,packageName=pkg)))
    }
    private suspend fun batch(items:List<Preset>,repository:PresetRepository,stopOnFail:Boolean):BatchUiState {
        AndroidDevice.launch(context,context.packageName);delay(500)
        BatchController.run(context,items.mapIndexed { i,p -> p.copy(id=UUID.randomUUID().toString(),name=p.name+" item "+(i+1)) },repository,stopOnFail)
        assertTrue(BatchController.state.value.error,BatchController.state.value.running)
        until(160000) { !BatchController.state.value.running }
        return BatchController.state.value
    }
    @Test fun cameraHandoffAndThirdPreset()=runBlocking {
        withContext(Dispatchers.Main) {
            until(600000) { AutomationAccessibilityService.instance!=null && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            service=checkNotNull(AutomationAccessibilityService.instance)
            val repository=PresetRepository(context);val original=repository.presets.first().associate {it.id to it.json}
            val one=counter("com.example.touchfixture.one");val two=counter("com.example.touchfixture.two");val photo=camera()
            val cases=mutableListOf<Map<String,Any>>()
            fun save() {File(context.filesDir,"device-batch-handoff-065.json").writeText(Codec.gson.toJson(cases))}
            try {
                var s=batch(listOf(one,photo,two,one),repository,false)
                assertEquals(Codec.gson.toJson(s),4,s.reports.size);assertTrue(Codec.gson.toJson(s),s.reports.all {it.outcome==Outcome.PASS})
                assertEquals("COMPLETED",s.phase)
                cases+=mapOf("case" to "counter camera third fourth presets", "result" to "PASS","state" to s);save()

                // Actual photo is saved, but deliberately request two: failure must not suppress item 3.
                val mismatch=photo.copy(media=MediaExpectation(2,0))
                s=batch(listOf(one,mismatch,two,one),repository,false)
                assertEquals(Codec.gson.toJson(s),4,s.reports.size)
                assertEquals(Outcome.FAIL,s.reports[1].outcome)
                assertTrue(Codec.gson.toJson(s),s.reports[1].media.savedMediaStable)
                assertNull(s.reports[1].handoffBlockedReason)
                assertEquals(Outcome.PASS,s.reports[2].outcome);assertEquals(Outcome.PASS,s.reports[3].outcome)
                assertEquals("COMPLETED_WITH_ERRORS",s.phase)
                cases+=mapOf("case" to "camera verification mismatch continues third fourth", "result" to "PASS","state" to s);save()

                s=batch(listOf(one,mismatch,two),repository,true)
                assertEquals(2,s.reports.size);assertEquals(1,s.skipped)
                assertEquals("STOPPED_ON_FAILURE",s.phase);assertTrue(s.error.contains("Stop on failure"))
                cases+=mapOf("case" to "explicit stop on failure shows reason and skips third", "result" to "PASS","state" to s);save()

                val notReady=two.copy(name="Batch-065-no-dispatch",media=MediaExpectation(1,0),steps=two.steps.map {
                    if(it.kind==ActionKind.RECORDED_TOUCH) it.copy(expectsKeyboard=true,timeoutMs=300) else it
                })
                s=batch(listOf(one,notReady,two),repository,false)
                assertEquals(3,s.reports.size);assertEquals(Outcome.FAIL,s.reports[1].outcome)
                assertNull(s.reports[1].handoffBlockedReason);assertEquals(Outcome.PASS,s.reports[2].outcome)
                cases+=mapOf("case" to "readiness rejection without dispatch permits third preset", "result" to "PASS","state" to s);save()

                // Check native video completion and the next preset, including duration verification.
                val pkg=CameraPolicy.PACKAGE
                val video=Preset(name="Batch-065-native-video",apps=photo.apps,media=MediaExpectation(0,1),
                    steps=listOf(AutomationStep(label="Open camera",kind=ActionKind.LAUNCH_APP,packageName=pkg),
                        AutomationStep(label="Start video",kind=ActionKind.START_VIDEO,packageName=pkg,locator=UiLocator(resourceId=CameraPolicy.SHUTTER),timeoutMs=10000),
                        AutomationStep(label="Stop video",kind=ActionKind.STOP_VIDEO,packageName=pkg,locator=UiLocator(resourceId=CameraPolicy.SHUTTER),timeoutMs=10000),
                        AutomationStep(label="Verify video",kind=ActionKind.VERIFY_MEDIA,packageName=pkg)))
                s=batch(listOf(one,video,two),repository,false)
                assertEquals(Codec.gson.toJson(s),3,s.reports.size)
                assertTrue(Codec.gson.toJson(s),s.reports.all {it.outcome==Outcome.PASS})
                cases+=mapOf("case" to "native video stop verification then third preset", "result" to "PASS","state" to s);save()
                assertEquals(original,repository.presets.first().associate {it.id to it.json})
                cases+=mapOf("case" to "original presets unchanged", "result" to "PASS","count" to original.size);save()
            } finally { if(BatchController.state.value.running) {BatchController.stop();until { !BatchController.state.value.running }} }
        }
    }
    @Test fun prepareNormalHandoff()=runBlocking {
        withContext(Dispatchers.Main) {
            until(600000) { AutomationAccessibilityService.instance!=null && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            service=checkNotNull(AutomationAccessibilityService.instance)
            val file=File(context.filesDir,"normal-smoke-presets-065.json")
            check(!file.exists()) { "A prior temporary setup is already present; remove its known IDs first" }
            val repository=PresetRepository(context)
            val originals=repository.presets.first().associate {it.id to it.json}
            val one=counter("com.example.touchfixture.one")
            val two=counter("com.example.touchfixture.two")
            val photo=camera() // Leave the native app in Photo before the prepared touch batch.
            val items=listOf(one.copy(id=UUID.randomUUID().toString(),name="065 Test A counter",folder="065 temporary tests"),
                photo.copy(id=UUID.randomUUID().toString(),name="065 Test B native photo",folder="065 temporary tests"),
                two.copy(id=UUID.randomUUID().toString(),name="065 Test C counter",folder="065 temporary tests"))
            for(preset in items) repository.save(preset)
            assertEquals(originals,repository.presets.first().filter {it.id in originals}.associate {it.id to it.json})
            file.writeText(Codec.gson.toJson(mapOf("ids" to items.map {it.id},"names" to items.map {it.name},"originalCount" to originals.size)))
            AndroidDevice.launch(context,context.packageName)
            Unit
        }
    }

}
