package com.example.captureautomator.core

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read-only native/provider work must not hold playback ownership if an OEM call ignores cancellation.
 * Fixed workers and bounded queues prevent repeated timeouts from creating unbounded background work.
 * A late result has no UI or playback callback and cannot dispatch any action. */
class BoundedReadExecutor : AutoCloseable {
    private fun pool(name: String) = ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(2),ThreadFactory { task -> Thread(task,name).apply { isDaemon=true } })
    private val workers=pool("capture-media-read")
    private val cancellations=pool("capture-media-cancel")
    suspend fun <T> read(onCancel: () -> Unit = {}, action: () -> T): T = suspendCancellableCoroutine { continuation ->
        val task=FutureTask<Unit> {
            if(continuation.isActive) {
                try { continuation.resume(action()) }
                catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        }
        continuation.invokeOnCancellation {
            task.cancel(true);workers.remove(task)
            // A provider's cancellation callback can itself block; never run it on the UI thread.
            try { cancellations.execute(onCancel) } catch(_: RejectedExecutionException) { /* bounded fallback */ }
        }
        if(continuation.isActive) {
            try { workers.execute(task) }
            catch(e: RejectedExecutionException) { if(continuation.isActive) continuation.resumeWithException(e) }
        }
    }
    override fun close() { workers.shutdownNow();cancellations.shutdownNow() }
}
