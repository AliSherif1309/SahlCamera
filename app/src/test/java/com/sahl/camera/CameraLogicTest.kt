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
    fun hdEnhancerAdaptsToSceneBrightness() {
        assertEquals(1.0f, HdEnhancer.computeAdaptiveGamma(170f), 0.001f)
        assertTrue(HdEnhancer.computeAdaptiveGamma(120f) < 0.95f)

        val darkFrame = IntArray(9) { 0xFF606060.toInt() }
        val enhanced = HdEnhancer.enhancePixels(darkFrame, 3, 3)
        val r = (enhanced[4] ushr 16) and 0xFF
        assertTrue("Expected dark midtone (96) to lift, got $r", r > 96)
    }
}
