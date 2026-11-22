package com.sahl.camera

import androidx.camera.core.ImageCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CameraLogicTest {
    @Test
    fun enumLookupsFallbackGracefully() {
        assertEquals(PhotoQualityMode.FAST, PhotoQualityMode.fromId(0))
        assertEquals(PhotoQualityMode.HDR, PhotoQualityMode.fromId(2))
        assertEquals(PhotoQualityMode.FAST, PhotoQualityMode.fromId(999))

        assertEquals(GridMode.OFF, GridMode.fromId(0))
        assertEquals(GridMode.THREE_BY_THREE, GridMode.fromId(1))
        assertEquals(GridMode.OFF, GridMode.fromId(-1))

        assertEquals(VideoResolution.FHD_1080P, VideoResolution.fromId(1))
        assertEquals(VideoResolution.UHD_4K, VideoResolution.fromId(2))
        assertEquals(VideoResolution.FHD_1080P, VideoResolution.fromId(42))

        assertEquals(SaveFolder.SAHL_CAMERA, SaveFolder.fromId(0))
        assertEquals("DCIM/SahlCamera", SaveFolder.SAHL_CAMERA.relativePath)
        assertEquals("DCIM/Camera", SaveFolder.DCIM_CAMERA.relativePath)
    }

    @Test
    fun flashModeMappingMatchesCameraX() {
        assertEquals(ImageCapture.FLASH_MODE_AUTO, CameraEngine.mapFlashMode(0))
        assertEquals(ImageCapture.FLASH_MODE_ON, CameraEngine.mapFlashMode(1))
        assertEquals(ImageCapture.FLASH_MODE_OFF, CameraEngine.mapFlashMode(2))
        assertEquals(ImageCapture.FLASH_MODE_AUTO, CameraEngine.mapFlashMode(99))
    }

    @Test
    fun mediaFilenamesHaveExpectedPrefixes() {
        val now = Date(1700000000000L)
        val photo = MediaSaver.buildPhotoFilename(now)
        val video = MediaSaver.buildVideoFilename(now)
        assertTrue(photo.startsWith("IMG_"))
        assertTrue(video.startsWith("VID_"))
        assertTrue(photo.length > 10)
        assertTrue(video.length > 10)
    }

    @Test
    fun hdrFusionPrefersWellExposedPixels() {
        val clippedWhite = intArrayOf(0xFFFFFFFF.toInt())
        val wellExposed = intArrayOf(0xFF808080.toInt())
        val clippedBlack = intArrayOf(0xFF000000.toInt())
        val fused = HdrProcessor.fusePixels(listOf(clippedWhite, wellExposed, clippedBlack), 1, 1)
        val r = (fused[0] ushr 16) and 0xFF
        // Midtone (128) should dominate over clipped white (255) and clipped black (0)
        assertTrue("Expected fused midtone near 128, got $r", r in 115..140)
    }
}
