import com.example.captureautomator.core.*
fun main() {
    val p = Preset(name = "Native camera", apps = listOf(AppSpec("com.sec.android.app.camera", "Camera")),
        steps = listOf(AutomationStep(label = "Launch", kind = ActionKind.LAUNCH_APP,
            packageName = "com.sec.android.app.camera")))
    check(PresetValidation.errors(p).isEmpty())
    check(PresetValidation.canExecute(p).any { it.contains("Capture/count mismatch") })
    check(Verdict.outcome(listOf(StepResult("1", "Click", true, "OK", 15)),
        MediaResult(2, 1, true), p.media) == Outcome.PASS)
    check(Verdict.outcome(emptyList(), MediaResult(2, 0, false), p.media) != Outcome.PASS)
    check(PresetValidation.errors(p.copy(steps = listOf(AutomationStep(label = "Tap", kind = ActionKind.TAP)))).isNotEmpty())
    val locator = UiLocator(resourceId = "pkg:id/shutter", normalizedX = .15f, normalizedY = .8f)
    check(SelectorPolicy.uniqueBestIndex(listOf(UiCandidate("pkg:id/shutter", visible = true),
        UiCandidate("pkg:id/other")), locator) == 0)
    check(SelectorPolicy.uniqueBestIndex(listOf(UiCandidate("pkg:id/shutter"),
        UiCandidate("pkg:id/shutter")), locator) == null)
    check(SelectorPolicy.confidence(UiCandidate(), UiLocator(normalizedX = .4f, normalizedY = .5f)) == 0f)
    check(SelectorPolicy.confidence(UiCandidate(contentDescription = "SAVE"),
        UiLocator(contentDescription = "save")) >= .90f)
    println("PASS: pure JVM model, schema validation, safe verdicts, invalid locator rejection")
}
