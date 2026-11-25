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
        enum class QrType { URL, WIFI, TEL, MAILTO, SMS, GEO, TEXT }

        data class QrParsed(
            val type: QrType,
            val raw: String,
            val summary: String,
            val wifiSsid: String? = null,
            val wifiPassword: String? = null
        )

        fun isUrl(raw: String): Boolean {
            val t = raw.trim().lowercase()
            return t.startsWith("http://") || t.startsWith("https://")
        }

        fun parseQrSummary(raw: String): String = parse(raw).summary

        fun parse(raw: String): QrParsed {
            val trimmed = raw.trim()
            val lower = trimmed.lowercase()
            return when {
                lower.startsWith("http://") || lower.startsWith("https://") -> {
                    val short = if (trimmed.length > 42) trimmed.take(40) + "…" else trimmed
                    QrParsed(QrType.URL, trimmed, short)
                }
                lower.startsWith("wifi:") -> {
                    val ssid = Regex("S:([^;]+)").find(trimmed)?.groupValues?.getOrNull(1)
                    val pass = Regex("P:([^;]+)").find(trimmed)?.groupValues?.getOrNull(1)
                    val summary = if (!ssid.isNullOrBlank()) "Wi-Fi: $ssid" else "Wi-Fi"
                    QrParsed(QrType.WIFI, trimmed, summary, wifiSsid = ssid, wifiPassword = pass)
                }
                lower.startsWith("tel:") -> {
                    QrParsed(QrType.TEL, trimmed, trimmed.removePrefix("tel:").removePrefix("TEL:"))
                }
                lower.startsWith("mailto:") -> {
                    QrParsed(QrType.MAILTO, trimmed, trimmed.substringAfter(":").substringBefore("?"))
                }
                lower.startsWith("smsto:") || lower.startsWith("sms:") -> {
                    QrParsed(QrType.SMS, trimmed, trimmed.substringAfter(":").substringBefore(":"))
                }
                lower.startsWith("geo:") -> {
                    QrParsed(QrType.GEO, trimmed, trimmed.substringAfter(":").substringBefore("?"))
                }
                else -> {
                    val short = if (trimmed.length > 42) trimmed.take(40) + "…" else trimmed
                    QrParsed(QrType.TEXT, trimmed, short)
                }
            }
        }
    }
}
