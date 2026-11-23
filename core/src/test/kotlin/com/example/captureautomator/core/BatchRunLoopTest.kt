package com.example.captureautomator.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchRunLoopTest {
    private class Rig {
        val calls = mutableListOf<String>()
        var running = true
        var stopped = false
        var failure: Exception? = null
        var before: suspend () -> Boolean = { calls += "before"; true }
        var execute: suspend (Int) -> Int = { calls += "run$it"; it }
        var save: suspend (Int) -> Unit = { calls += "save$it" }
        var after: suspend () -> Unit = { calls += "after" }
        var stopAfter: (Int) -> Boolean = { false }
        fun loop() = BatchRunLoop(before, execute, { calls += "result$it" }, save, stopAfter, after,
            { stopped = true }, { failure = it }, { running = false; calls += "idle" }, 100)
    }

    @Test fun `normal sequence completes every item and cleanup`() = runTest {
        val r = Rig(); r.loop().run(listOf(1, 2, 3))
        assertEquals(listOf("before", "run1", "result1", "save1", "run2", "result2", "save2",
            "run3", "result3", "save3", "after", "idle"), r.calls)
        assertFalse(r.running); assertNull(r.failure)
    }
    @Test fun `stop during initial cleanup releases ownership`() = runTest {
        val r = Rig(); r.before = { awaitCancellation() }
        val j = launch { r.loop().run(listOf(1, 2)) }; runCurrent(); j.cancelAndJoin()
        assertTrue(r.stopped); assertFalse(r.running); assertEquals(listOf("idle"), r.calls)
    }
    @Test fun `engine swallowing cancellation cannot launch next item or final cleanup`() = runTest {
        val r = Rig(); r.execute = { n ->
            r.calls += "run$n"
            try { awaitCancellation() } catch (_: CancellationException) { n }
        }
        val j = launch { r.loop().run(listOf(1, 2, 3)) }; runCurrent(); j.cancelAndJoin()
        assertEquals(listOf("before", "run1", "result1", "save1", "idle"), r.calls)
        assertTrue(r.stopped); assertFalse(r.running)
    }
    @Test fun `stop while paused is cancellable and next batch runs`() = runTest {
        val r = Rig(); r.execute = { while (true) delay(100); @Suppress("UNREACHABLE_CODE") 0 }
        val j = launch { r.loop().run(listOf(1, 2)) }; runCurrent(); j.cancelAndJoin()
        assertFalse(r.running)
        r.running = true; r.execute = { it }; r.loop().run(listOf(3))
        assertTrue(r.calls.contains("save3")); assertFalse(r.running)
    }
    @Test fun `stop during final cleanup releases ownership`() = runTest {
        val r = Rig(); r.after = { r.calls += "after"; awaitCancellation() }
        val j = launch { r.loop().run(listOf(1)) }; runCurrent(); j.cancelAndJoin()
        assertTrue(r.stopped); assertFalse(r.running); assertEquals("idle", r.calls.last())
    }
    @Test fun `storage failure cannot strand running flag`() = runTest {
        val r = Rig(); r.save = { throw java.io.IOException("disk full") }
        r.loop().run(listOf(1, 2)); assertFalse(r.running); assertNotNull(r.failure)
        assertFalse(r.calls.contains("run2")); assertFalse(r.calls.contains("after"))
    }
    @Test fun `storage timeout cannot strand running flag`() = runTest {
        val r = Rig(); r.save = { awaitCancellation() }
        r.loop().run(listOf(1, 2)); assertFalse(r.running)
        assertTrue(r.failure?.message.orEmpty().contains("timed out"))
    }
    @Test fun `stop on failure true and false sequence independently`() = runTest {
        for (stop in listOf(true, false)) {
            val r = Rig(); r.stopAfter = { stop }; r.loop().run(listOf(1, 2))
            assertEquals(!stop, r.calls.contains("run2")); assertFalse(r.running)
        }
    }
    @Test fun `startup cleanup refusal and foreground failure release ownership`() = runTest {
        val r = Rig(); r.before = { false }; r.loop().run(listOf(1)); assertFalse(r.running)
        val denied = Rig(); denied.before = { throw SecurityException("foreground denied") }
        denied.loop().run(listOf(1)); assertFalse(denied.running); assertNotNull(denied.failure)
    }
    @Test fun `cancellation during report save is acknowledged before next preset`() = runTest {
        val r = Rig(); r.save = { delay(50); r.calls += "saved" }
        val j = launch { r.loop().run(listOf(1, 2)) }; runCurrent(); j.cancel(); advanceUntilIdle()
        assertTrue(r.calls.contains("saved")); assertFalse(r.calls.contains("run2")); assertFalse(r.running)
    }
    @Test fun `repeated stop restart cycles never retain ownership`() = runTest {
        repeat(20) {
            val r = Rig(); r.execute = { awaitCancellation() }
            val j = launch { r.loop().run(listOf(1, 2)) }; runCurrent(); j.cancel(); j.cancel(); j.join()
            assertFalse(r.running)
            val next = Rig(); next.loop().run(listOf(3, 4)); assertFalse(next.running)
            assertTrue(next.calls.contains("run4"))
        }
    }
    @Test fun `process recovery describes startup and mid batch interruption without replay`() {
        for (phase in listOf("STARTUP_CLEANUP", "RUNNING_PRESET", "FINAL_CLEANUP", "SAVING_REPORT")) {
            val c = BatchCheckpoint("id", true, phase, 1, 3, listOf("report1"))
            assertTrue(c.interruptionMessage()!!.contains("No captures were replayed automatically"))
            assertNull(c.copy(active = false).interruptionMessage())
        }
    }
}
