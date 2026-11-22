package com.sahl.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Memory-efficient 3-frame Exposure Fusion (Mertens-inspired):
 * Combines 0 EV (midtones), -EV (highlights), and +EV (shadows) sequentially
 * so only one decoded Bitmap is in memory at a time.
 */
object HdrProcessor {

    data class RawFrame(
        val jpegBytes: ByteArray,
        val rotationDegrees: Int,
        val mirrorHorizontal: Boolean
    )

    fun imageProxyToRawFrame(image: ImageProxy, mirrorHorizontal: Boolean): RawFrame {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rotation = image.imageInfo.rotationDegrees
        image.close()
        return RawFrame(bytes, rotation, mirrorHorizontal)
    }

    /**
     * Pure-Kotlin pixel fusion on equal-sized ARGB arrays.
     * Weights each pixel by how well-exposed its luminance is (Gaussian centered at 0.5).
     */
    fun fusePixels(frames: List<IntArray>, width: Int, height: Int): IntArray {
        val count = width * height
        val rAcc = FloatArray(count)
        val gAcc = FloatArray(count)
        val bAcc = FloatArray(count)
        val wAcc = FloatArray(count)

        // Precompute well-exposedness lookup table for luminance 0..255
        val lut = FloatArray(256) { luma ->
            val norm = luma / 255f
            val diff = norm - 0.5f
            // Sigma = 0.25 -> keeps midtones strong, suppresses clipped 0 or 255
            exp(-(diff * diff) / (2f * 0.25f * 0.25f)) + 0.01f
        }

        for (pixels in frames) {
            for (i in 0 until count) {
                val c = pixels[i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val luma = (77 * r + 150 * g + 29 * b) ushr 8
                val w = lut[luma]
                rAcc[i] += r * w
                gAcc[i] += g * w
                bAcc[i] += b * w
                wAcc[i] += w
            }
        }

        val out = IntArray(count)
        for (i in 0 until count) {
            val w = wAcc[i].coerceAtLeast(0.0001f)
            val r = (rAcc[i] / w).toInt().coerceIn(0, 255)
            val g = (gAcc[i] / w).toInt().coerceIn(0, 255)
            val b = (bAcc[i] / w).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    fun fuseAndSave(
        context: Context,
        rawFrames: List<RawFrame>,
        folder: SaveFolder,
        location: Location?
    ): Uri? {
        if (rawFrames.isEmpty()) return null

        // Decode first frame to determine target dimensions (cap at ~16MP for speed/RAM)
        val firstBmp = decodeTransformed(rawFrames[0], maxPixels = 16_000_000) ?: return null
        val w = firstBmp.width
        val h = firstBmp.height
        val count = w * h

        val rAcc = FloatArray(count)
        val gAcc = FloatArray(count)
        val bAcc = FloatArray(count)
        val wAcc = FloatArray(count)
        val lut = FloatArray(256) { luma ->
            val norm = luma / 255f
            val diff = norm - 0.5f
            exp(-(diff * diff) / (2f * 0.25f * 0.25f)) + 0.01f
        }

        val rowPixels = IntArray(count)
        accumulateBitmap(firstBmp, rowPixels, rAcc, gAcc, bAcc, wAcc, lut)
        firstBmp.recycle()

        for (idx in 1 until rawFrames.size) {
            val bmp = decodeTransformed(rawFrames[idx], maxPixels = 16_000_000) ?: continue
            val scaled = if (bmp.width != w || bmp.height != h) {
                Bitmap.createScaledBitmap(bmp, w, h, true).also { bmp.recycle() }
            } else {
                bmp
            }
            accumulateBitmap(scaled, rowPixels, rAcc, gAcc, bAcc, wAcc, lut)
            scaled.recycle()
        }

        for (i in 0 until count) {
            val wt = wAcc[i].coerceAtLeast(0.0001f)
            val r = (rAcc[i] / wt).toInt().coerceIn(0, 255)
            val g = (gAcc[i] / wt).toInt().coerceIn(0, 255)
            val b = (bAcc[i] / wt).toInt().coerceIn(0, 255)
            rowPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val outBmp = Bitmap.createBitmap(rowPixels, w, h, Bitmap.Config.ARGB_8888)
        return saveBitmapToMediaStore(context, outBmp, folder).also {
            outBmp.recycle()
        }
    }

    private fun accumulateBitmap(
        bmp: Bitmap,
        pixels: IntArray,
        rAcc: FloatArray,
        gAcc: FloatArray,
        bAcc: FloatArray,
        wAcc: FloatArray,
        lut: FloatArray
    ) {
        val w = bmp.width
        val h = bmp.height
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val count = w * h
        for (i in 0 until count) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val luma = (77 * r + 150 * g + 29 * b) ushr 8
            val wt = lut[luma]
            rAcc[i] += r * wt
            gAcc[i] += g * wt
            bAcc[i] += b * wt
            wAcc[i] += wt
        }
    }

    private fun decodeTransformed(frame: RawFrame, maxPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, bounds)
        val total = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        var sample = 1
        while (total / (sample * sample) > maxPixels) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, opts) ?: return null

        if (frame.rotationDegrees == 0 && !frame.mirrorHorizontal) return bmp
        val m = Matrix()
        if (frame.rotationDegrees != 0) m.postRotate(frame.rotationDegrees.toFloat())
        if (frame.mirrorHorizontal) m.postScale(-1f, 1f)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun saveBitmapToMediaStore(context: Context, bmp: Bitmap, folder: SaveFolder): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MediaSaver.buildPhotoFilename() + "_HDR")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, folder.relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { os ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 98, os)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                cr.update(uri, done, null, null)
            }
            uri
        } catch (_: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) { }
            null
        }
    }
}
