package com.sahl.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class QrDecoder(
    private val onQrDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.AZTEC,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.CODE_128
                )
            )
        )
    }

    private var lastScanMs = 0L
    private var yBuffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastScanMs < 300L) {
            image.close()
            return
        }
        lastScanMs = now

        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height
            val needed = rowStride * h
            if (yBuffer.size < needed) {
                yBuffer = ByteArray(needed)
            }
            val remaining = buf.remaining().coerceAtMost(needed)
            buf.get(yBuffer, 0, remaining)

            val source = PlanarYUVLuminanceSource(
                yBuffer, rowStride, h, 0, 0, w, h, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            val text = result?.text?.trim()
            if (!text.isNullOrEmpty()) {
                onQrDetected(text)
            }
        } catch (_: Exception) {
            // NotFoundException on frames without QR codes is expected
        } finally {
            reader.reset()
            image.close()
        }
    }

    companion object {
        fun isUrl(raw: String): Boolean {
            val t = raw.trim().lowercase()
            return t.startsWith("http://") || t.startsWith("https://")
        }

        fun parseQrSummary(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
                val ssid = Regex("S:([^;]+)").find(trimmed)?.groupValues?.getOrNull(1)
                if (!ssid.isNullOrBlank()) return "Wi-Fi: $ssid"
            }
            return if (trimmed.length > 42) trimmed.take(40) + "…" else trimmed
        }
    }
}
