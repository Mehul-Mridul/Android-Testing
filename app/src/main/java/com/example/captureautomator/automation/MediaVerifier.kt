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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.example.captureautomator.core.pollMedia
import com.example.captureautomator.core.isNewRunMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Only validates accessible, newly-created shared media; private app stores are UNVERIFIABLE. */
class MediaVerifier(private val context: Context) {
    /** At most 31 scans / 15s of polling delay, with a 20s wall-clock coroutine deadline.
     * The baseline is immutable and captured before any action. Never recapture it on retries.
     */
    suspend fun poll(baseline: Snapshot, expected: MediaExpectation, targetPackages: Set<String>,
                     startedAt: Long, timing: PlaybackTiming,
                     onScan: (Int, Int, Int, Boolean) -> Unit): MediaResult {
        return pollMedia(timing, expected,
            scan = { verify(baseline, expected, targetPackages, startedAt) },
            onScan = { index, result -> onScan(index, result.photosFound, result.videosFound, result.verificationComplete) })
    }

    data class MediaFile(
        val id: Long,
        val isVideo: Boolean,
        val uri: Uri,
        val addedSeconds: Long,
        val size: Long,
        val mime: String,
        val ownerPackage: String?
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

    suspend fun baseline(): Snapshot = withContext(Dispatchers.IO) {
        val full = fullMediaPermission()
        val files = runCatching { queryAll(includePending = true) }
        Snapshot(files.getOrNull()?.map { key(it) }?.toSet().orEmpty(), full, files.isSuccess)
    }

    suspend fun verify(
        baseline: Snapshot,
        expected: MediaExpectation,
        targetPackages: Set<String>,
        startedAtEpochMs: Long
    ): MediaResult = withContext(Dispatchers.IO) {
        if (!baseline.fullPermission || !fullMediaPermission()) return@withContext MediaResult(
            verificationComplete = false,
            issues = listOf("Full photo/video access not granted; partial selection cannot verify new third-party captures"))
        if (!baseline.readable) return@withContext MediaResult(verificationComplete = false,
            issues = listOf("Could not read the pre-run media inventory"))
        val all = runCatching { queryAll() }.getOrElse {
            return@withContext MediaResult(verificationComplete = false,
                issues = listOf("MediaStore scan failed: ${it.javaClass.simpleName}"))
        }
        // DATE_ADDED is seconds: reject files that predate the run, even when missing from baseline.
        val startSeconds = TimeUnit.MILLISECONDS.toSeconds(startedAtEpochMs)
        val recent = all.filter { isNewRunMedia(key(it), baseline.ids, it.addedSeconds, startSeconds) }
        val issues = mutableListOf<String>()
        val evidence = mutableListOf<String>()
        var fullyAttributable = true
        var validPhotos = 0
        var validVideos = 0
        for (file in recent) {
            currentCoroutineContext().ensureActive()
            if (file.size <= 0) { issues += "Zero-byte file: ${file.uri}"; continue }
            val owner = file.ownerPackage
            if (owner == null || owner !in targetPackages) {
                fullyAttributable = false
                issues += "Cannot establish target-app ownership for: ${file.uri} (reported owner: ${owner ?: "unknown"})"
            }
            val validator = if (file.isVideo) validateVideo(file, expected) else validatePhoto(file)
            if (validator != null) {
                issues += "${file.uri}: $validator"
            } else {
                if (file.isVideo) validVideos++ else validPhotos++
                evidence += "${if (file.isVideo) "VIDEO" else "PHOTO"} ${file.uri} owner=$owner size=${file.size}"
            }
        }
        if (validPhotos != expected.photos) issues += "Expected ${expected.photos} photos; found $validPhotos valid new photos"
        if (validVideos != expected.videos) issues += "Expected ${expected.videos} videos; found $validVideos valid new videos"
        MediaResult(validPhotos, validVideos, fullyAttributable,
            issues.distinct(), evidence)
    }

    private fun key(file: MediaFile): String = "${if (file.isVideo) "v" else "p"}:${file.id}"

    private fun queryAll(includePending: Boolean = false): List<MediaFile> =
        query(false, includePending) + query(true, includePending)

    @Suppress("DEPRECATION")
    private fun query(video: Boolean, includePending: Boolean): List<MediaFile> {
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
            "${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf(earliest.toString()), null)?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val idxDate = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val idxSize = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val idxMime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val idxOwner = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val idxPending = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            while (cursor.moveToNext()) {
                if (!includePending && cursor.getInt(idxPending) != 0) continue
                val id = cursor.getLong(idxId)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                results += MediaFile(id, video, uri, cursor.getLong(idxDate),
                    cursor.getLong(idxSize), cursor.getString(idxMime).orEmpty(),
                    cursor.getString(idxOwner))
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
