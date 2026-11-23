package com.example.captureautomator.core

import kotlinx.coroutines.*

/** Owns sequencing, not an Activity. A cancelled engine may return a partial report;
 * never treat that return as permission to start another preset or cleanup. */
class BatchRunLoop<P, R>(
    private val before: suspend () -> Boolean,
    private val execute: suspend (P) -> R,
    private val onResult: (R) -> Unit,
    private val save: suspend (R) -> Unit,
    private val stopAfter: (R) -> Boolean,
    private val after: suspend () -> Unit,
    private val onStopped: () -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val onIdle: () -> Unit,
    private val saveTimeoutMs: Long = 3000
) {
    suspend fun run(items: List<P>) {
        try {
            currentCoroutineContext().ensureActive()
            if (!before()) return
            for (item in items) {
                currentCoroutineContext().ensureActive()
                val result = execute(item)
                onResult(result)
                // Preserve a partial report after Stop, but bound database wait time.
                withContext(NonCancellable) {
                    try { withTimeout(saveTimeoutMs) { save(result) } }
                    catch (e: TimeoutCancellationException) {
                        throw IllegalStateException("Saving the preset report timed out", e)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (stopAfter(result)) break
            }
            currentCoroutineContext().ensureActive()
            after()
        } catch (_: CancellationException) {
            onStopped()
        } catch (e: Exception) {
            onFailure(e)
        } finally {
            // Deliberately non-suspending. Cancellation / archive I/O must not skip unlock.
            onIdle()
        }
    }
}

data class BatchCheckpoint(
    val sessionId: String,
    val active: Boolean,
    val phase: String,
    val position: Int,
    val total: Int,
    val completedReportIds: List<String> = emptyList()
) {
    /** Recovery only describes interruption; it never returns a preset to execute. */
    fun interruptionMessage(): String? = if (active)
        "Previous batch was interrupted during $phase ($position/$total). " +
            "No captures were replayed automatically. Check the target app before starting a new batch."
        else null
}
