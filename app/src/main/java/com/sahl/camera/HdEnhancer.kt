package com.sahl.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Fast, single-frame HD image enhancer:
 *  1. Measures scene luminance so bright scenes stay untouched (no blown highlights)
 *     while darker indoor scenes get an adaptive shadow/midtone lift.
 *  2. Applies a gentle S-curve + subtle 3x3 clarity/sharpening pass for crisp detail.
 */
object HdEnhancer {

    fun extractBytesAndClose(image: ImageProxy): Pair<ByteArray, Int> {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rotation = image.imageInfo.rotationDegrees
        image.close()
        return bytes to rotation
    }

    /**
     * Returns 1.0f for bright scenes (meanLuma >= 155) and smoothly lifts
     * darker indoor scenes down to gamma 0.84f.
     */
    fun computeAdaptiveGamma(meanLuma: Float): Float {
        if (meanLuma >= 155f) return 1.0f
        val t = ((155f - meanLuma) / 65f).coerceIn(0f, 1f)
        return 1.0f - t * 0.16f // 1.00 -> 0.84
    }

    fun enhancePixels(pixels: IntArray, width: Int, height: Int): IntArray {
        val count = width * height
        if (count == 0) return pixels

        // 1. Sample luminance to adaptively choose tone lift
        var lumaSum = 0L
        var sampleCount = 0
        val step = (count / 20_000).coerceAtLeast(1)
        var idx = 0
        while (idx < count) {
            val c = pixels[idx]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            lumaSum += (77 * r + 150 * g + 29 * b) ushr 8
            sampleCount++
            idx += step
        }
        val meanLuma = if (sampleCount > 0) lumaSum.toFloat() / sampleCount else 155f
        val gamma = computeAdaptiveGamma(meanLuma)

        // 2. Build highlight-safe tone LUT
        val toneLut = IntArray(256) { v ->
            val x = v / 255f
            val lifted = if (gamma < 0.999f) x.toDouble().pow(gamma.toDouble()).toFloat() else x
            // Subtle S-curve contrast (15% blend)
            val sCurve = lifted * lifted * (3f - 2f * lifted)
            val blended = lifted * 0.85f + sCurve * 0.15f
            (blended * 255f).toInt().coerceIn(0, 255)
        }

        // Apply tone curve in-place first
        for (i in 0 until count) {
            val c = pixels[i]
            val r = toneLut[(c ushr 16) and 0xFF]
            val g = toneLut[(c ushr 8) and 0xFF]
            val b = toneLut[c and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // 3. Subtle 3x3 unsharp-mask clarity on interior pixels (amount = 0.22)
        if (width < 3 || height < 3) return pixels
        val out = pixels.copyOf()
        for (y in 1 until height - 1) {
            val row = y * width
            val prevRow = row - width
            val nextRow = row + width
            for (x in 1 until width - 1) {
                val i = row + x
                val c = pixels[i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF

                val up = pixels[prevRow + x]
                val dn = pixels[nextRow + x]
                val lf = pixels[i - 1]
                val rt = pixels[i + 1]

                val avgR = (((up ushr 16) and 0xFF) + ((dn ushr 16) and 0xFF) + ((lf ushr 16) and 0xFF) + ((rt ushr 16) and 0xFF)) ushr 2
                val avgG = (((up ushr 8) and 0xFF) + ((dn ushr 8) and 0xFF) + ((lf ushr 8) and 0xFF) + ((rt ushr 8) and 0xFF)) ushr 2
                val avgB = ((up and 0xFF) + (dn and 0xFF) + (lf and 0xFF) + (rt and 0xFF)) ushr 2

                val nr = (r + ((r - avgR) * 22 / 100)).coerceIn(0, 255)
                val ng = (g + ((g - avgG) * 22 / 100)).coerceIn(0, 255)
                val nb = (b + ((b - avgB) * 22 / 100)).coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    fun decodeQuickThumbnail(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean
    ): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
        if (rotationDegrees == 0 && !mirrorHorizontal) return decoded
        val m = Matrix()
        if (rotationDegrees != 0) m.postRotate(rotationDegrees.toFloat())
        if (mirrorHorizontal) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    fun enhanceAndSave(
        context: Context,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        folder: SaveFolder
    ): Uri? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val total = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        var sample = 1
        while (total / (sample * sample) > 16_000_000L) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null

        val oriented = if (rotationDegrees != 0 || mirrorHorizontal) {
            val m = Matrix()
            if (rotationDegrees != 0) m.postRotate(rotationDegrees.toFloat())
            if (mirrorHorizontal) m.postScale(-1f, 1f)
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val w = oriented.width
        val h = oriented.height
        val pixels = IntArray(w * h)
        oriented.getPixels(pixels, 0, w, 0, 0, w, h)
        oriented.recycle()

        val enhanced = enhancePixels(pixels, w, h)
        val outBmp = Bitmap.createBitmap(enhanced, w, h, Bitmap.Config.ARGB_8888)
        return saveBitmapToMediaStore(context, outBmp, folder).also {
            outBmp.recycle()
        }
    }

    private fun saveBitmapToMediaStore(context: Context, bmp: Bitmap, folder: SaveFolder): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MediaSaver.buildPhotoFilename())
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
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
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
