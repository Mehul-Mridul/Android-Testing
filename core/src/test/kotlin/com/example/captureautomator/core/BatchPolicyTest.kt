package com.example.captureautomator.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BatchPolicyTest {
    @Test fun `standalone cleanup flag cannot change batch preparation or saved settings`() {
        for (flag in listOf(true, false, null)) {
            val saved = Preset(name = "One app", apps = listOf(AppSpec("test.app", "Test")),
                clearRecentsAfterRun = flag, requireTouchStart = true)
            val prepared = BatchPolicy.forExecution(saved)
            assertEquals(false, prepared.clearRecentsAfterRun)
            assertEquals(false, prepared.requireTouchStart)
            assertEquals(flag, saved.clearRecentsAfterRun)
            assertEquals(true, saved.requireTouchStart)
            assertEquals(saved.id, prepared.id)
            assertEquals(saved.steps, prepared.steps)
        }
    }
}
