package com.example.captureautomator.core

import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BatchHandoffTest {
    private fun report(media: MediaResult=MediaResult(), block: String?=null, outcome: Outcome=Outcome.FAIL) =
        ExecutionReport(presetId="camera",presetName="Camera",device=DeviceProfile(),startedAt=0,endedAt=1,
            outcome=outcome,results=emptyList(),media=media,handoffBlockedReason=block)
    @Test fun `verification deadline is not itself an unsafe capture state`() {
        val r=report(MediaResult(1,0,true,listOf("Expected two photos","Media verification deadline reached"),
            savedMediaStable=true))
        assertFalse(BatchPolicy.decide(r,false).stop)
        assertTrue(BatchPolicy.decide(r,true).stop)
        assertFalse(BatchPolicy.decide(r,true).unsafe)
    }
    @Test fun `pending and unreadable saves block regardless of stop on fail`() {
        for(m in listOf(MediaResult(pendingTargetFiles=1),MediaResult(unreadableTargetFiles=1),MediaResult())) {
            val reason=BatchPolicy.saveBlockReason(m,MediaExpectation(1,0),true)
            assertNotNull(reason)
            for(stop in listOf(true,false)) assertTrue(BatchPolicy.decide(report(m,reason),stop).unsafe)
        }
    }
    @Test fun `stable wrong count and wrong duration remain failures but permit safe handoff`() {
        val m=MediaResult(1,0,true,listOf("Wrong duration"),saveEvidence=listOf("v:finalized-video"),savedMediaStable=true)
        assertNull(BatchPolicy.saveBlockReason(m,MediaExpectation(1,1),true))
        assertEquals(Outcome.FAIL,Verdict.outcome(emptyList(),m,MediaExpectation(1,1)))
        assertFalse(BatchPolicy.decide(report(m),false).stop)
    }
    @Test fun `missing capture in unknown camera state cannot be skipped as a count mismatch`() {
        val m=MediaResult(1,0,true,saveEvidence=listOf("p:one"),savedMediaStable=true)
        assertNotNull(BatchPolicy.saveBlockReason(m,MediaExpectation(2,0),true))
        assertNull(BatchPolicy.saveBlockReason(m,MediaExpectation(2,0),true,appIdleConfirmed=true))
    }
    @Test fun `preflight failure without actions may continue but cancellation never does`() {
        assertNull(BatchPolicy.saveBlockReason(MediaResult(),MediaExpectation(1,0),false))
        assertFalse(BatchPolicy.decide(report(),false).stop)
        assertTrue(BatchPolicy.decide(report(outcome=Outcome.CANCELLED),false).unsafe)
    }
    @Test fun `third and fourth presets run after second camera verification mismatch`() = runTest {
        val calls=mutableListOf<Int>()
        BatchRunLoop<Int,ExecutionReport>(before={true},execute={ n -> calls+=n;report(outcome=if(n==2)Outcome.FAIL else Outcome.PASS) },
            onResult={},save={},stopAfter={BatchPolicy.decide(it,false).stop},after={},onStopped={},onFailure={throw it},onIdle={}).run(listOf(1,2,3,4))
        assertEquals(listOf(1,2,3,4),calls)
    }
    @Test fun `slow night capture remains blocked until stable finalized media appears`() = runTest {
        val m=pollMedia(PlaybackTiming {},MediaExpectation(1,0),scan={
            if(currentTime<28_000) MediaResult(pendingTargetFiles=1,issues=listOf("Saving"))
            else MediaResult(1,0,true,mediaEvidence=listOf("photo"),saveEvidence=listOf("file:size"))
        },maxScans=61,intervalMs=1000)
        assertTrue(m.savedMediaStable);assertTrue(currentTime>=30_000)
        assertNull(BatchPolicy.saveBlockReason(m,MediaExpectation(1,0),true))
    }
    @Test fun `excess finalized media reports failure promptly without waiting full deadline`() = runTest {
        val m=pollMedia(PlaybackTiming {},MediaExpectation(1,0),scan={
            MediaResult(2,0,true,listOf("Extra photo"),mediaEvidence=listOf("one","two"))
        },maxScans=61,intervalMs=1000)
        assertEquals(2000L,currentTime);assertTrue(m.savedMediaStable)
        assertEquals(Outcome.FAIL,Verdict.outcome(emptyList(),m,MediaExpectation(1,0)))
    }
    @Test fun `changing size or pending media cannot be mistaken for stable saved media`() = runTest {
        var n=0
        val m=pollMedia(PlaybackTiming {},MediaExpectation(2,0),scan={
            n++;MediaResult(1,0,true,listOf("Saving"),pendingTargetFiles=1,saveEvidence=listOf("size:$n"))
        },maxScans=5,intervalMs=100)
        assertFalse(m.savedMediaStable)
        assertNotNull(BatchPolicy.saveBlockReason(m,MediaExpectation(2,0),true))
    }
}
