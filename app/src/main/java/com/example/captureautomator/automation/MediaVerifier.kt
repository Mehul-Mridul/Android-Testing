package com.example.captureautomator.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.captureautomator.core.MediaExpectation
import com.example.captureautomator.core.MediaResult
import com.example.captureautomator.core.PlaybackTiming
import com.example.captureautomator.core.pollMedia
import com.example.captureautomator.core.isNewRunMedia
import kotlinx.coroutines.withTimeoutOrNull
import android.os.CancellationSignal
import com.example.captureautomator.core.BoundedReadExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Only validates accessible, newly-created shared media; private app stores are UNVERIFIABLE. */
class MediaVerifier(private val context: Context) {
    companion object { private val reads = BoundedReadExecutor() }
    private fun checkRead(signal: CancellationSignal) {
        signal.throwIfCanceled()
        if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Media read cancelled")
    }
    private suspend fun <T> read(action: (CancellationSignal) -> T): T? = withTimeoutOrNull(8000) {
        val signal=CancellationSignal()
        reads.read(onCancel = { signal.cancel() }) { action(signal) }
    }
    /** At most 61 scans / 60s of polling delay, with a hard coroutine deadline.
     * Each native/provider read also has an 8s bound and cancellation signal.
     * The baseline is immutable and captured before any action. Never recapture it on retries.
     */
    suspend fun poll(baseline: Snapshot, expected: MediaExpectation, targetPackages: Set<String>,
                     startedAt: Long, timing: PlaybackTiming,
                     onScan: (Int, MediaResult) -> Unit): MediaResult {
        return pollMedia(timing, expected, maxScans = 61, intervalMs = 1000,
            scan = { verify(baseline, expected, targetPackages, startedAt) },
            onScan = { index, result -> onScan(index, result) })
    }

    suspend fun waitForSaved(base: Snapshot, expected: MediaExpectation, pkg: String, since: Long,
                             minimum: Int, timing: PlaybackTiming): Int {
        return withTimeoutOrNull(65_500) {
        var previous = emptyList<String>()
        var stable = 0
        repeat(61) {
            timing.waitMs(0)
            val evidence = verify(base, expected, setOf(pkg), since).mediaEvidence.sorted()
            stable = if (evidence.size >= minimum && evidence == previous) stable + 1 else 0
            if (stable >= 2) return@withTimeoutOrNull evidence.size
            previous = evidence
            if (it < 60) timing.waitMs(1000)
        }
        0
        } ?: 0
    }

    data class MediaFile(
        val id: Long,
        val isVideo: Boolean,
        val uri: Uri,
        val addedSeconds: Long,
        val size: Long,
        val mime: String,
        val ownerPackage: String?,
        val pending: Boolean
    )
    data class Snapshot(val ids: Set<String>, val fullPermission: Boolean, val readable: Boolean)

    private fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED

    /** A partial Android 14+ photo grant is NOT full-library verification. */
    fun fullMediaPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    suspend fun baseline(): Snapshot {
        val full=fullMediaPermission()
        return try { read { signal ->
            val files=runCatching { queryAll(includePending=true,signal=signal) }
            Snapshot(files.getOrNull()?.map { key(it) }?.toSet().orEmpty(),full,files.isSuccess)
        } ?: Snapshot(emptySet(),full,false) }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(_: Exception) { Snapshot(emptySet(),full,false) }
    }

    suspend fun verify(
        baseline: Snapshot,
        expected: MediaExpectation,
        targetPackages: Set<String>,
        startedAtEpochMs: Long
    ): MediaResult {
        return try { read { signal ->
        if (!baseline.fullPermission || !fullMediaPermission()) return@read MediaResult(
            verificationComplete = false,
            issues = listOf("Full photo/video access not granted; partial selection cannot verify new third-party captures"))
        if (!baseline.readable) return@read MediaResult(verificationComplete = false,
            issues = listOf("Could not read the pre-run media inventory"))
        val all = runCatching { queryAll(includePending=true,signal=signal) }.getOrElse {
            return@read MediaResult(verificationComplete = false,
                issues = listOf("MediaStore scan failed: ${it.javaClass.simpleName}"))
        }
        // DATE_ADDED is seconds: reject files that predate the run, even when missing from baseline.
        val startSeconds = TimeUnit.MILLISECONDS.toSeconds(startedAtEpochMs)
        val recent = all.filter { isNewRunMedia(key(it), baseline.ids, it.addedSeconds, startSeconds) }
        val issues = mutableListOf<String>()
        val evidence = mutableListOf<String>()
        val saveEvidence = mutableListOf<String>()
        var pending=0
        var unreadable=0
        var fullyAttributable = true
        var validPhotos = 0
        var validVideos = 0
        for (file in recent) {
            checkRead(signal)
            val owner = file.ownerPackage
            if (owner != null && owner !in targetPackages) continue
            if (owner == null || owner !in targetPackages) {
                fullyAttributable = false
                issues += "Cannot establish target-app ownership for: ${file.uri} (reported owner: ${owner ?: "unknown"})"
            }
            if (owner == null) continue
            if (file.pending) { pending++;issues += "Target media is still being saved";continue }
            if (file.size <= 0) { unreadable++; issues += "Empty target file: ${file.uri}"; continue }
            val validator = if (file.isVideo) validateVideo(file, expected) else validatePhoto(file)
            // A duration mismatch is a verification failure, not evidence of an unfinished save.
            val finalized = validator == null || validator.startsWith("Video duration ")
            if(finalized) saveEvidence += "${key(file)}:${file.size}"
            else unreadable++
            if (validator != null) {
                issues += "${file.uri}: $validator"
            } else {
                if (file.isVideo) validVideos++ else validPhotos++
                evidence += "${if (file.isVideo) "VIDEO" else "PHOTO"} ${file.uri} owner=$owner size=${file.size}"
            }
        }
        if (validPhotos != expected.photos) issues += "Expected ${expected.photos} photos; found $validPhotos valid new shared photos. Confirm each local save and the preset photo count"
        if (validVideos != expected.videos) issues += "Expected ${expected.videos} videos; found $validVideos valid new videos"
        MediaResult(validPhotos, validVideos, fullyAttributable,
            issues.distinct(), evidence, pendingTargetFiles=pending, unreadableTargetFiles=unreadable,
            saveEvidence=saveEvidence.sorted())
        } ?: MediaResult(issues=listOf("Media read timed out; capture state was not confirmed"),unreadableTargetFiles=1) }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { MediaResult(issues=listOf("Media read unavailable: ${e.javaClass.simpleName}"),unreadableTargetFiles=1) }
    }

    private fun key(file: MediaFile): String = "${if (file.isVideo) "v" else "p"}:${file.id}"

    private fun queryAll(includePending: Boolean = false, signal: CancellationSignal): List<MediaFile> =
        query(false, includePending,signal) + query(true, includePending,signal)

    @Suppress("DEPRECATION")
    private fun query(video: Boolean, includePending: Boolean, signal: CancellationSignal): List<MediaFile> {
        checkRead(signal)
        val baseCollection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val collection = if (includePending) MediaStore.setIncludePending(baseCollection) else baseCollection
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_PENDING
        )
        val results = mutableListOf<MediaFile>()
        // Pre-run inventory covers last 24 hours. Older files must not be attributed to the run.
        val earliest = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - 86_400
        context.contentResolver.query(collection, projection,
            "${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf(earliest.toString()), null,signal)?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val idxDate = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val idxSize = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val idxMime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val idxOwner = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val idxPending = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            while (cursor.moveToNext()) {
                checkRead(signal)
                if (!includePending && cursor.getInt(idxPending) != 0) continue
                val id = cursor.getLong(idxId)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                results += MediaFile(id, video, uri, cursor.getLong(idxDate),
                    cursor.getLong(idxSize), cursor.getString(idxMime).orEmpty(),
                    cursor.getString(idxOwner),cursor.getInt(idxPending)!=0)
            }
        } ?: throw IllegalStateException("MediaStore collection unavailable")
        return results
    }

    private fun validatePhoto(file: MediaFile): String? {
        return try {
        if (!file.mime.startsWith("image/")) return "Not a readable image MIME type"
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = context.contentResolver.openInputStream(file.uri) ?: return "Cannot open image"
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        // BitmapFactory returns null intentionally for inJustDecodeBounds=true.
        if (options.outWidth <= 0 || options.outHeight <= 0) "Invalid image dimensions" else null
    } catch (e: Exception) { "Image read failure: ${e.javaClass.simpleName}" }
    }

    private fun validateVideo(file: MediaFile, expectation: MediaExpectation): String? {
        return try {
        if (!file.mime.startsWith("video/")) return "Not a video MIME type"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, file.uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return "Missing video duration"
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: return "Missing video width"
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: return "Missing video height"
            if (duration <= 0 || width <= 0 || height <= 0) return "Invalid video metadata"
            if (abs(duration - expectation.videoDurationMs) > expectation.durationToleranceMs)
                "Video duration ${duration}ms differs from expected ${expectation.videoDurationMs}ms"
            else null
        } finally { retriever.release() }
    } catch (e: Exception) { "Video read failure: ${e.javaClass.simpleName}" }
    }
}
