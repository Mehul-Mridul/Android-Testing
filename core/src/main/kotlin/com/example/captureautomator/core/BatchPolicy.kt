package com.example.captureautomator.core

/** Batch owns cleanup. Never mutate the saved preset or run its standalone cleanup mid-sequence. */
object BatchPolicy {
    fun forExecution(preset: Preset): Preset = preset.copy(clearRecentsAfterRun = false, requireTouchStart = false)
}
