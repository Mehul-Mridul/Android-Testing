package com.example.captureautomator.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ValidationTest {
    private val app = AppSpec("com.instagram.android", "Instagram")

    @Test fun `valid workflow passes schema check`() {
        val preset = Preset(name = "Sample", apps = listOf(app), steps = listOf(
            AutomationStep(label = "Launch", kind = ActionKind.LAUNCH_APP, packageName = app.packageName),
            AutomationStep(label = "Pause", kind = ActionKind.WAIT_MS, waitMs = 5_000)
        ))
        assertTrue(PresetValidation.errors(preset).isEmpty())
    }

    @Test fun `unsafe missing locator and sensitive text placeholder are rejected`() {
        val preset = Preset(name = "Unsafe", apps = listOf(app), steps = listOf(
            AutomationStep(label = "Tap", kind = ActionKind.TAP),
            AutomationStep(label = "Text", kind = ActionKind.ENTER_TEXT)
        ))
        assertEquals(
            listOf(
                "Step 1 needs a locator",
                "Step 2 needs explicit tester-supplied text",
                "Step 2 needs a locator"
            ),
            PresetValidation.errors(preset)
        )
    }

    @Test fun `unverifiable media never passes`() {
        val passedSteps = listOf(StepResult("x", "Shutter", true, "Tapped", 10))
        assertEquals(Outcome.PARTIAL, Verdict.outcome(passedSteps, MediaResult(), MediaExpectation()))
    }

    @Test fun `wrong media counts fail closed`() {
        val passedSteps = listOf(StepResult("x", "Shutter", true, "Tapped", 10))
        assertEquals(Outcome.FAIL, Verdict.outcome(passedSteps, MediaResult(2, 0, true), MediaExpectation()))
    }

    @Test fun `exact verified media passes`() {
        val passedSteps = listOf(StepResult("x", "Shutter", true, "Tapped", 10))
        assertEquals(Outcome.PASS, Verdict.outcome(passedSteps, MediaResult(2, 1, true), MediaExpectation()))
    }
}
