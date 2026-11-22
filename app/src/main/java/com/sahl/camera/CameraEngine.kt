package com.sahl.camera

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.net.Uri
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set
    var isVideoMode: Boolean = false
        private set
    var isHdrSupported: Boolean = false
        private set
    var isNightSupported: Boolean = false
        private set

    var onZoomRangeChanged: ((minZoom: Float, maxZoom: Float, currentZoom: Float) -> Unit)? = null
    var onExposureRangeChanged: ((minIndex: Int, maxIndex: Int, currentIndex: Int) -> Unit)? = null
    var onRecordingStateChanged: ((isRecording: Boolean, isPaused: Boolean, durationSec: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start(
        photoMode: PhotoQualityMode,
        videoResolution: VideoResolution,
        flashMode: Int,
        videoMode: Boolean,
        onReady: () -> Unit = {}
    ) {
        isVideoMode = videoMode
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindUseCases(provider, photoMode, videoResolution, flashMode)
                onReady()

                // Probe vendor extensions asynchronously without blocking first frame
                if (extensionsManager == null) {
                    val extFuture = ExtensionsManager.getInstanceAsync(context, provider)
                    extFuture.addListener({
                        try {
                            extensionsManager = extFuture.get()
                            refreshExtensionSupport()
                        } catch (_: Exception) { }
                    }, mainExecutor)
                } else {
                    refreshExtensionSupport()
                }
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "Camera init failed")
            }
        }, mainExecutor)
    }

    private fun refreshExtensionSupport() {
        val ext = extensionsManager ?: return
        val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        isHdrSupported = try { ext.isExtensionAvailable(baseSelector, ExtensionMode.HDR) } catch (_: Exception) { false }
        isNightSupported = try { ext.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT) } catch (_: Exception) { false }
    }

    fun resolveEffectiveSelector(photoMode: PhotoQualityMode): CameraSelector {
        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (isVideoMode) return base
        val ext = extensionsManager ?: return base
        val mode = when (photoMode) {
            PhotoQualityMode.HDR -> ExtensionMode.HDR
            PhotoQualityMode.NIGHT -> ExtensionMode.NIGHT
            else -> return base
        }
        return try {
            if (ext.isExtensionAvailable(base, mode)) {
                ext.getExtensionEnabledCameraSelector(base, mode)
            } else {
                base
            }
        } catch (_: Exception) {
            base
        }
    }

    fun bindUseCases(
        provider: ProcessCameraProvider = cameraProvider ?: return,
        photoMode: PhotoQualityMode,
        videoResolution: VideoResolution,
        flashMode: Int
    ) {
        val selector = resolveEffectiveSelector(photoMode)
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        provider.unbindAll()

        if (isVideoMode) {
            val quality = when (videoResolution) {
                VideoResolution.HD_720P -> Quality.HD
                VideoResolution.FHD_1080P -> Quality.FHD
                VideoResolution.UHD_4K -> Quality.UHD
            }
            val selectorQuality = QualitySelector.fromOrderedList(
                listOf(quality, Quality.FHD, Quality.HD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(selectorQuality)
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc
            imageCapture = null
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
        } else {
            val captureMode = selectCaptureMode(provider, selector, photoMode)
            val ic = ImageCapture.Builder()
                .setCaptureMode(captureMode)
                .setFlashMode(mapFlashMode(flashMode))
                .setTargetRotation(rotation)
                .setJpegQuality(if (photoMode == PhotoQualityMode.FAST) 92 else 98)
                .build()
            imageCapture = ic
            videoCapture = null
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, ic)
        }

        val info: CameraInfo? = camera?.cameraInfo
        if (info != null) {
            val zoom = info.zoomState.value
            if (zoom != null) {
                onZoomRangeChanged?.invoke(zoom.minZoomRatio, zoom.maxZoomRatio, zoom.zoomRatio)
            }
            val exp = info.exposureState
            if (exp.isExposureCompensationSupported) {
                val range = exp.exposureCompensationRange
                onExposureRangeChanged?.invoke(range.lower, range.upper, exp.exposureCompensationIndex)
            } else {
                onExposureRangeChanged?.invoke(0, 0, 0)
            }
        }
        refreshExtensionSupport()
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalZeroShutterLag::class)
    private fun selectCaptureMode(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        photoMode: PhotoQualityMode
    ): Int {
        if (photoMode != PhotoQualityMode.FAST) {
            return ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
        return try {
            val info = provider.availableCameraInfos.firstOrNull { selector.filter(listOf(it)).isNotEmpty() }
            if (info != null && info.isZslSupported) {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            } else {
                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            }
        } catch (_: Exception) {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }

    fun flipCamera(photoMode: PhotoQualityMode, videoResolution: VideoResolution, flashMode: Int) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindUseCases(photoMode = photoMode, videoResolution = videoResolution, flashMode = flashMode)
    }

    fun setFlashMode(flashMode: Int) {
        if (isVideoMode) {
            camera?.cameraControl?.enableTorch(flashMode == 1)
        } else {
            imageCapture?.flashMode = mapFlashMode(flashMode)
        }
    }

    fun setZoomRatio(ratio: Float) {
        val zoom = camera?.cameraInfo?.zoomState?.value ?: return
        val clamped = ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(clamped)
    }

    fun getCurrentZoom(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun setExposureIndex(index: Int) {
        val exp = camera?.cameraInfo?.exposureState ?: return
        if (!exp.isExposureCompensationSupported) return
        val clamped = index.coerceIn(exp.exposureCompensationRange.lower, exp.exposureCompensationRange.upper)
        camera?.cameraControl?.setExposureCompensationIndex(clamped)
    }

    fun tapToFocus(x: Float, y: Float) {
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun takePhoto(
        folder: SaveFolder,
        mirrorFront: Boolean,
        location: Location?,
        onSaved: (Uri) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val ic = imageCapture ?: return
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (lensFacing == CameraSelector.LENS_FACING_FRONT) && mirrorFront
            if (location != null) this.location = location
        }
        val outputOptions = MediaSaver.createImageOutputOptions(context, folder, metadata)
        ic.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri
                    if (uri != null) onSaved(uri) else onFailed("Empty URI")
                }

                override fun onError(exception: ImageCaptureException) {
                    onFailed(exception.message ?: "Capture error")
                }
            }
        )
    }

    fun takePhotoToUri(
        outputUri: Uri,
        mirrorFront: Boolean,
        onSaved: (Uri) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val ic = imageCapture ?: return
        val os = try {
            context.contentResolver.openOutputStream(outputUri)
        } catch (e: Exception) {
            onFailed(e.message ?: "Cannot open output URI")
            return
        }
        if (os == null) {
            onFailed("Cannot open output stream")
            return
        }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (lensFacing == CameraSelector.LENS_FACING_FRONT) && mirrorFront
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(os).setMetadata(metadata).build()
        ic.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try { os.close() } catch (_: Exception) { }
                    onSaved(outputUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    try { os.close() } catch (_: Exception) { }
                    onFailed(exception.message ?: "Capture error")
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun startOrStopRecording(
        folder: SaveFolder,
        withAudio: Boolean,
        onVideoSaved: (Uri) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val current = activeRecording
        if (current != null) {
            current.stop()
            activeRecording = null
            return
        }
        val vc = videoCapture ?: return
        val outputOptions = MediaSaver.createVideoOutputOptions(context, folder)
        var pending = vc.output.prepareRecording(context, outputOptions)
        if (withAudio) {
            pending = pending.withAudioEnabled()
        }
        activeRecording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onRecordingStateChanged?.invoke(true, false, 0L)
                is VideoRecordEvent.Pause -> onRecordingStateChanged?.invoke(true, true, TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos))
                is VideoRecordEvent.Resume -> onRecordingStateChanged?.invoke(true, false, TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos))
                is VideoRecordEvent.Status -> onRecordingStateChanged?.invoke(true, false, TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos))
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    onRecordingStateChanged?.invoke(false, false, 0L)
                    if (event.hasError()) {
                        onFailed("Video error: ${event.error}")
                    } else {
                        onVideoSaved(event.outputResults.outputUri)
                    }
                }
            }
        }
    }

    fun togglePauseRecording(currentlyPaused: Boolean) {
        if (currentlyPaused) activeRecording?.resume() else activeRecording?.pause()
    }

    fun isRecording(): Boolean = activeRecording != null

    fun release() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
    }

    companion object {
        fun mapFlashMode(flashMode: Int): Int = when (flashMode) {
            1 -> ImageCapture.FLASH_MODE_ON
            2 -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
    }
}
