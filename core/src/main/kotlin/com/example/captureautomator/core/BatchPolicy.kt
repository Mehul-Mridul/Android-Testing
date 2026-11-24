package com.example.captureautomator.core

/** Batch owns cleanup; saved presets are never mutated. */
object BatchPolicy {
    fun forExecution(preset: Preset): Preset = preset.copy(clearRecentsAfterRun = false, requireTouchStart = false)
    data class Decision(val stop: Boolean, val unsafe: Boolean = false, val reason: String = "")
    fun decide(report: ExecutionReport, stopOnFail: Boolean): Decision {
        if (report.outcome == Outcome.CANCELLED) return Decision(true,true,"Preset was cancelled")
        report.handoffBlockedReason?.takeIf { it.isNotBlank() }?.let { return Decision(true,true,it) }
        if (stopOnFail && report.outcome != Outcome.PASS) {
            val detail = report.results.firstOrNull { !it.success }?.message
                ?: report.media.issues.firstOrNull() ?: report.notes.ifBlank { report.outcome.name }
            return Decision(true,false,"Stop on failure is enabled: $detail")
        }
        return Decision(false)
    }
    fun saveBlockReason(media: MediaResult, expected: MediaExpectation, touched: Boolean,
                        appIdleConfirmed: Boolean = false): String? = when {
        !touched -> null
        media.pendingTargetFiles > 0 -> "Target app still has pending media; wait for saving before switching apps"
        media.unreadableTargetFiles > 0 -> "Target media is not readable yet; inspect the capture app before switching"
        expected.photos + expected.videos > 0 && !media.savedMediaStable ->
            "No stable readable target-app save was confirmed; inspect the capture app and expected media counts"
        touched && !appIdleConfirmed &&
            (maxOf(media.photosFound,media.saveEvidence.count { it.startsWith("p:") }) < expected.photos ||
             maxOf(media.videosFound,media.saveEvidence.count { it.startsWith("v:") }) < expected.videos) ->
            "Expected captures are still missing and camera idle could not be confirmed; inspect the capture app"
        else -> null
    }
}
