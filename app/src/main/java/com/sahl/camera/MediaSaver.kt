package com.sahl.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaSaver {
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun buildPhotoFilename(now: Date = Date()): String = "IMG_${fileDateFormat.format(now)}"
    fun buildVideoFilename(now: Date = Date()): String = "VID_${fileDateFormat.format(now)}"

    fun createImageOutputOptions(
        context: Context,
        folder: SaveFolder,
        metadata: ImageCapture.Metadata
    ): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, buildPhotoFilename())
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, folder.relativePath)
            }
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).setMetadata(metadata).build()
    }

    fun createVideoOutputOptions(context: Context, folder: SaveFolder): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, buildVideoFilename())
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, folder.relativePath)
            }
        }
        return MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
    }

    data class LatestMedia(val uri: Uri, val isVideo: Boolean, val thumbnail: Bitmap?)

    fun loadLatestMedia(context: Context, folder: SaveFolder): LatestMedia? {
        return try {
            val cr = context.contentResolver
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DATE_ADDED
            )
            val selection =
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?) AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                "${folder.relativePath}%"
            )
            cr.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC LIMIT 1"
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(0)
                val type = c.getInt(1)
                val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = ContentUris.withAppendedId(baseUri, id)
                val thumb = try {
                    cr.loadThumbnail(uri, Size(160, 160), null)
                } catch (_: Exception) {
                    null
                }
                LatestMedia(uri, isVideo, thumb)
            }
        } catch (_: Exception) {
            null
        }
    }

    class ShutterAudio {
        private var sound: MediaActionSound? = null

        fun preload() {
            try {
                sound = MediaActionSound().apply {
                    load(MediaActionSound.SHUTTER_CLICK)
                    load(MediaActionSound.START_VIDEO_RECORDING)
                    load(MediaActionSound.STOP_VIDEO_RECORDING)
                }
            } catch (_: Exception) { }
        }

        fun playShutter(enabled: Boolean) {
            if (!enabled) return
            try { sound?.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) { }
        }

        fun playVideoStart(enabled: Boolean) {
            if (!enabled) return
            try { sound?.play(MediaActionSound.START_VIDEO_RECORDING) } catch (_: Exception) { }
        }

        fun playVideoStop(enabled: Boolean) {
            if (!enabled) return
            try { sound?.play(MediaActionSound.STOP_VIDEO_RECORDING) } catch (_: Exception) { }
        }

        fun release() {
            try { sound?.release() } catch (_: Exception) { }
            sound = null
        }
    }
}
