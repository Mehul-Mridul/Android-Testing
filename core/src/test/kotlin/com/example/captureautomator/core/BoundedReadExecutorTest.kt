package com.example.captureautomator.core

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedReadExecutorTest {
    @Test fun `cancel releases caller even if native read ignores interrupts`() = runBlocking {
        val reads=BoundedReadExecutor();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val job=launch { reads.read { entered.countDown();while(release.count>0) { try { release.await() }catch(_:InterruptedException){} };42 } }
            withContext(Dispatchers.IO) { assertTrue(entered.await(3,TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { release.countDown();reads.close() }
    }
    @Test fun `late cancelled read cannot deliver a result into a restarted run`() = runBlocking {
        val reads=BoundedReadExecutor();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var delivered=false
        try {
            val job=launch { reads.read { entered.countDown();while(release.count>0) { try { release.await() }catch(_:InterruptedException){} };1 };delivered=true }
            withContext(Dispatchers.IO) { assertTrue(entered.await(3,TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertEquals(7,reads.read {7})
            release.countDown();delay(50);assertFalse(delivered)
        } finally { release.countDown();reads.close() }
    }
}
