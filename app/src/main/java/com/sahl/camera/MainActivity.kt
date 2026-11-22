package com.sahl.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: GridOverlayView
    private lateinit var cameraEngine: CameraEngine
    private val shutterAudio = MediaSaver.ShutterAudio()

    private lateinit var permissionOverlay: LinearLayout
    private lateinit var viewfinderFrame: FrameLayout
    private lateinit var bottomContainer: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var btnFlash: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnTimer: TextView
    private lateinit var btnSettings: TextView

    private lateinit var timerCountdownText: TextView
    private lateinit var recordingBadge: TextView
    private lateinit var exposureContainer: LinearLayout
    private lateinit var exposureSeek: SeekBar

    private lateinit var zoomBar: LinearLayout
    private lateinit var zoomUltra: TextView
    private lateinit var zoom1x: TextView
    private lateinit var zoom2x: TextView

    private lateinit var tabPhoto: TextView
    private lateinit var tabVideo: TextView
    private lateinit var btnThumbnail: ImageView
    private lateinit var btnShutter: View
    private lateinit var shutterInner: View
    private lateinit var btnPauseResume: TextView
    private lateinit var btnFlip: TextView

    private var isVideoMode = false
    private var isRecording = false
    private var isPaused = false
    private var lastMediaUri: Uri? = null
    private var lastMediaIsVideo = false
    private var countDownTimer: CountDownTimer? = null

    private var isExternalImageIntent = false
    private var isExternalVideoIntent = false
    private var externalOutputUri: Uri? = null

    private var minZoom = 1f
    private var maxZoom = 4f
    private var exposureMin = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            permissionOverlay.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        prefs = Prefs(this)
        shutterAudio.preload()
        UpdatePrompt.checkOnStart(this)

        val action = intent?.action
        isExternalImageIntent = action == MediaStore.ACTION_IMAGE_CAPTURE
        isExternalVideoIntent = action == MediaStore.ACTION_VIDEO_CAPTURE
        if (isExternalVideoIntent || action == MediaStore.INTENT_ACTION_VIDEO_CAMERA) {
            isVideoMode = true
        }
        @Suppress("DEPRECATION")
        externalOutputUri = intent?.getParcelableExtra(MediaStore.EXTRA_OUTPUT)

        buildUi()

        cameraEngine = CameraEngine(this, this, previewView).apply {
            onZoomRangeChanged = { minZ, maxZ, curZ ->
                minZoom = minZ
                maxZoom = maxZ
                updateZoomPills(curZ)
            }
            onExposureRangeChanged = { minI, maxI, curI ->
                exposureMin = minI
                val span = (maxI - minI).coerceAtLeast(0)
                if (span > 0) {
                    exposureSeek.max = span
                    exposureSeek.progress = (curI - minI).coerceIn(0, span)
                    exposureContainer.visibility = View.VISIBLE
                } else {
                    exposureContainer.visibility = View.GONE
                }
            }
            onRecordingStateChanged = { rec, paused, sec ->
                isRecording = rec
                isPaused = paused
                updateRecordingUi(sec)
            }
            onExtensionsProbed = {
                refreshTopBarLabels()
            }
            onError = { msg ->
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        if (hasCameraPermission()) {
            permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            permissionOverlay.visibility = View.VISIBLE
            requestNeededPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        overlayView.gridMode = prefs.gridMode
        if (hasCameraPermission()) {
            refreshThumbnail()
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        shutterAudio.release()
        cameraEngine.release()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (!hasAudioPermission()) perms.add(Manifest.permission.RECORD_AUDIO)
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startCamera() {
        cameraEngine.start(
            photoMode = prefs.photoMode,
            videoResolution = prefs.videoResolution,
            flashMode = prefs.flashMode,
            videoMode = isVideoMode,
            onReady = {
                refreshTopBarLabels()
                refreshThumbnail()
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val accent = Color.parseColor("#0E6B4E")

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(mainColumn, FrameLayout.LayoutParams(-1, -1))

        viewfinderFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Viewfinder using SurfaceView (PERFORMANCE) + FIT_CENTER so preview matches saved photo 1:1
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        viewfinderFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        overlayView = GridOverlayView(this).apply {
            gridMode = prefs.gridMode
        }
        viewfinderFrame.addView(overlayView, FrameLayout.LayoutParams(-1, -1))

        // Pinch-to-zoom + Tap-to-focus + Double-tap flip
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (cameraEngine.getCurrentZoom() * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                cameraEngine.setZoomRatio(next)
                updateZoomPills(next)
                return true
            }
        })
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                overlayView.showFocusRing(e.x, e.y)
                cameraEngine.tapToFocus(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isRecording) {
                    cameraEngine.flipCamera(prefs.photoMode, prefs.videoResolution, prefs.flashMode)
                    refreshTopBarLabels()
                }
                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
            true
        }

        // Top Bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding((14 * dp).toInt(), (40 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }

        fun topPill(initial: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = initial
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 32, 32, 32))
                cornerRadius = 18 * dp
            }
            setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * dp).toInt() }
            setOnClickListener { onClick() }
        }

        btnFlash = topPill("") {
            prefs.flashMode = (prefs.flashMode + 1) % 3
            cameraEngine.setFlashMode(prefs.flashMode)
            refreshTopBarLabels()
        }
        btnQuality = topPill("") {
            if (!isVideoMode) {
                cyclePhotoMode()
            }
        }
        btnTimer = topPill("") {
            if (!isVideoMode) {
                prefs.timerSeconds = when (prefs.timerSeconds) {
                    0 -> 3
                    3 -> 10
                    else -> 0
                }
                refreshTopBarLabels()
            }
        }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        btnSettings = topPill("⚙") {
            if (!isRecording) startActivity(Intent(this, SettingsActivity::class.java))
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }

        topBar.addView(btnFlash)
        topBar.addView(btnQuality)
        topBar.addView(btnTimer)
        topBar.addView(spacer)
        topBar.addView(btnSettings)
        val screenW = resources.displayMetrics.widthPixels
        val fourByThreeH = (screenW * 4f / 3f).toInt()
        mainColumn.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        mainColumn.addView(viewfinderFrame, LinearLayout.LayoutParams(-1, fourByThreeH))

        // Recording Timer Badge
        recordingBadge = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 210, 35, 35))
                cornerRadius = 14 * dp
            }
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
        }
        root.addView(recordingBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (96 * dp).toInt()
        })

        // Timer Countdown Center Text
        timerCountdownText = TextView(this).apply {
            visibility = View.GONE
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setShadowLayer(12f, 0f, 4f, Color.BLACK)
        }
        root.addView(timerCountdownText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Exposure Slider (compact horizontal pill above zoom bar)
        exposureContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 0, 0, 0))
                cornerRadius = 16 * dp
            }
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
        }
        val sunIcon = TextView(this).apply {
            text = "☀"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, (6 * dp).toInt(), 0)
        }
        exposureSeek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams((130 * dp).toInt(), -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        cameraEngine.userAdjustedExposure = true
                        cameraEngine.setExposureIndex(exposureMin + progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        exposureContainer.addView(sunIcon)
        exposureContainer.addView(exposureSeek)

        // Bottom Controls Container
        bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt())
        }

        // Zoom Pills
        zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun zoomPill(label: String, targetZoom: () -> Float): TextView = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 35, 35, 35))
                cornerRadius = 16 * dp
            }
            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt() }
            setOnClickListener {
                val z = targetZoom().coerceIn(minZoom, maxZoom)
                cameraEngine.setZoomRatio(z)
                updateZoomPills(z)
            }
        }
        zoomUltra = zoomPill("0.6×") { minZoom }
        zoom1x = zoomPill("1×") { 1f }
        zoom2x = zoomPill("2×") { 2f }
        zoomBar.addView(zoomUltra)
        zoomBar.addView(zoom1x)
        zoomBar.addView(zoom2x)

        val viewfinderControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        viewfinderControls.addView(exposureContainer, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (6 * dp).toInt() })
        viewfinderControls.addView(zoomBar)
        viewfinderFrame.addView(
            viewfinderControls,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (10 * dp).toInt()
            }
        )

        // Photo / Video Mode Tabs
        val modeTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (2 * dp).toInt(), 0, (14 * dp).toInt())
        }
        fun modeTab(label: String, video: Boolean): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener {
                if (isRecording || isVideoMode == video) return@setOnClickListener
                if (video && !hasAudioPermission()) {
                    requestNeededPermissions()
                }
                isVideoMode = video
                cameraEngine.start(prefs.photoMode, prefs.videoResolution, prefs.flashMode, isVideoMode) {
                    refreshTopBarLabels()
                }
                refreshModeTabs()
            }
        }
        tabPhoto = modeTab(getString(R.string.photo), false)
        tabVideo = modeTab(getString(R.string.video), true)
        if (!isExternalImageIntent && !isExternalVideoIntent) {
            modeTabs.addView(tabPhoto)
            modeTabs.addView(tabVideo)
            bottomContainer.addView(modeTabs)
        }

        // Shutter Row: [Thumbnail]  [Shutter]  [Flip / Pause]
        val shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        btnThumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 50, 50, 50))
                setStroke((2 * dp).toInt(), Color.WHITE)
            }
            clipToOutline = true
            visibility = if (isExternalImageIntent || isExternalVideoIntent) View.INVISIBLE else View.VISIBLE
            setOnClickListener { openLastMedia() }
        }
        val thumbSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(btnThumbnail, FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.CENTER))
        }

        val shutterOuter = FrameLayout(this).apply {
            val size = (78 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke((4 * dp).toInt(), Color.WHITE)
            }
        }
        shutterInner = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        shutterOuter.addView(shutterInner, FrameLayout.LayoutParams((62 * dp).toInt(), (62 * dp).toInt(), Gravity.CENTER))
        btnShutter = shutterOuter
        btnShutter.setOnClickListener { onShutterPressed() }

        val rightSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        btnFlip = TextView(this).apply {
            text = "↺"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 50, 50, 50))
            }
            setOnClickListener {
                if (!isRecording) {
                    cameraEngine.flipCamera(prefs.photoMode, prefs.videoResolution, prefs.flashMode)
                    refreshTopBarLabels()
                }
            }
        }
        btnPauseResume = TextView(this).apply {
            visibility = View.GONE
            text = "⏸"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 50, 50, 50))
            }
            setOnClickListener { cameraEngine.togglePauseRecording(isPaused) }
        }
        rightSlot.addView(btnFlip, FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.CENTER))
        rightSlot.addView(btnPauseResume, FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.CENTER))

        shutterRow.addView(thumbSlot)
        shutterRow.addView(shutterOuter)
        shutterRow.addView(rightSlot)
        bottomContainer.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))

        mainColumn.addView(bottomContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        // Permission Overlay
        permissionOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111315"))
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
            visibility = View.GONE
        }
        permissionOverlay.addView(TextView(this).apply {
            text = getString(R.string.permission_needed)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * dp).toInt())
        })
        permissionOverlay.addView(Button(this).apply {
            text = getString(R.string.grant_permission)
            setBackgroundColor(accent)
            setTextColor(Color.WHITE)
            setOnClickListener { requestNeededPermissions() }
        })
        root.addView(permissionOverlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        refreshTopBarLabels()
        refreshModeTabs()
    }

    private fun cyclePhotoMode() {
        val available = mutableListOf(PhotoQualityMode.QUALITY, PhotoQualityMode.HDR)
        if (cameraEngine.isNightSupported) available.add(PhotoQualityMode.NIGHT)
        available.add(PhotoQualityMode.FAST)
        val idx = available.indexOf(prefs.photoMode)
        val next = available[(idx + 1) % available.size]
        prefs.photoMode = next
        cameraEngine.bindUseCases(photoMode = next, videoResolution = prefs.videoResolution, flashMode = prefs.flashMode)
        refreshTopBarLabels()
    }

    private fun refreshTopBarLabels() {
        btnFlash.text = when {
            isVideoMode && prefs.flashMode == 1 -> "⚡ ${getString(R.string.flash_torch)}"
            prefs.flashMode == 1 -> "⚡ ${getString(R.string.flash_on)}"
            prefs.flashMode == 2 -> "⚡ ${getString(R.string.flash_off)}"
            else -> "⚡ ${getString(R.string.flash_auto)}"
        }
        btnQuality.visibility = if (isVideoMode) View.GONE else View.VISIBLE
        btnTimer.visibility = if (isVideoMode) View.GONE else View.VISIBLE

        btnQuality.text = when (prefs.photoMode) {
            PhotoQualityMode.FAST -> getString(R.string.mode_fast)
            PhotoQualityMode.QUALITY -> getString(R.string.mode_quality)
            PhotoQualityMode.HDR -> getString(R.string.mode_hdr)
            PhotoQualityMode.NIGHT -> getString(R.string.mode_night)
        }
        btnTimer.text = when (prefs.timerSeconds) {
            3 -> "⏱ ${getString(R.string.timer_3s)}"
            10 -> "⏱ ${getString(R.string.timer_10s)}"
            else -> "⏱ ${getString(R.string.timer_off)}"
        }
    }

    private fun refreshModeTabs() {
        val accent = Color.parseColor("#0E6B4E")
        tabPhoto.setTextColor(if (!isVideoMode) Color.WHITE else Color.argb(150, 255, 255, 255))
        tabVideo.setTextColor(if (isVideoMode) Color.WHITE else Color.argb(150, 255, 255, 255))
        tabPhoto.background = if (!isVideoMode) GradientDrawable().apply { setColor(accent); cornerRadius = 40f } else null
        tabVideo.background = if (isVideoMode) GradientDrawable().apply { setColor(accent); cornerRadius = 40f } else null

        (shutterInner.background as? GradientDrawable)?.setColor(if (isVideoMode) Color.parseColor("#E53935") else Color.WHITE)

        val screenW = resources.displayMetrics.widthPixels
        if (isVideoMode) {
            viewfinderFrame.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            bottomContainer.layoutParams = LinearLayout.LayoutParams(-1, -2)
        } else {
            viewfinderFrame.layoutParams = LinearLayout.LayoutParams(-1, (screenW * 4f / 3f).toInt())
            bottomContainer.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
    }

    private fun updateZoomPills(currentZoom: Float) {
        val accent = Color.parseColor("#0E6B4E")
        zoomUltra.visibility = if (minZoom < 0.85f) View.VISIBLE else View.GONE
        zoom2x.visibility = if (maxZoom >= 2f) View.VISIBLE else View.GONE

        val activeBg = Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent))
        val idleBg = Color.argb(140, 35, 35, 35)
        (zoomUltra.background as? GradientDrawable)?.setColor(if (currentZoom < 0.85f) activeBg else idleBg)
        (zoom1x.background as? GradientDrawable)?.setColor(if (currentZoom in 0.85f..1.6f) activeBg else idleBg)
        (zoom2x.background as? GradientDrawable)?.setColor(if (currentZoom > 1.6f) activeBg else idleBg)
    }

    private fun updateRecordingUi(durationSec: Long) {
        if (isRecording) {
            recordingBadge.visibility = View.VISIBLE
            val mins = durationSec / 60
            val secs = durationSec % 60
            recordingBadge.text = String.format("%02d:%02d", mins, secs)
            btnFlip.visibility = View.GONE
            btnPauseResume.visibility = View.VISIBLE
            btnPauseResume.text = if (isPaused) "▶" else "⏸"
            topBar.visibility = View.INVISIBLE
            (shutterInner.background as? GradientDrawable)?.apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(Color.parseColor("#E53935"))
            }
        } else {
            recordingBadge.visibility = View.GONE
            btnFlip.visibility = View.VISIBLE
            btnPauseResume.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            (shutterInner.background as? GradientDrawable)?.apply {
                shape = GradientDrawable.OVAL
                setColor(if (isVideoMode) Color.parseColor("#E53935") else Color.WHITE)
            }
        }
    }

    private fun onShutterPressed() {
        if (!hasCameraPermission()) {
            requestNeededPermissions()
            return
        }
        if (isVideoMode) {
            if (!isRecording) shutterAudio.playVideoStart(prefs.shutterSound)
            else shutterAudio.playVideoStop(prefs.shutterSound)

            cameraEngine.startOrStopRecording(
                folder = prefs.saveFolder,
                withAudio = hasAudioPermission(),
                onVideoSaved = { uri ->
                    if (isExternalVideoIntent) {
                        setResult(RESULT_OK, Intent().setData(uri))
                        finish()
                    } else {
                        updateThumbnailFromUri(uri, true)
                    }
                },
                onFailed = { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
            return
        }

        val delaySec = prefs.timerSeconds
        if (delaySec > 0 && countDownTimer == null) {
            timerCountdownText.visibility = View.VISIBLE
            countDownTimer = object : CountDownTimer(delaySec * 1000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    timerCountdownText.text = ((millisUntilFinished / 1000L) + 1L).toString()
                }

                override fun onFinish() {
                    countDownTimer = null
                    timerCountdownText.visibility = View.GONE
                    executePhotoCapture()
                }
            }.start()
        } else {
            executePhotoCapture()
        }
    }

    private fun executePhotoCapture() {
        overlayView.triggerShutterBlink()
        shutterAudio.playShutter(prefs.shutterSound)

        val outUri = externalOutputUri
        if (isExternalImageIntent && outUri != null) {
            cameraEngine.takePhotoToUri(
                outputUri = outUri,
                mirrorFront = prefs.mirrorFront,
                onSaved = {
                    setResult(RESULT_OK)
                    finish()
                },
                onFailed = { msg ->
                    Toast.makeText(this, "${getString(R.string.capture_failed)}: $msg", Toast.LENGTH_SHORT).show()
                }
            )
            return
        }

        val loc = if (prefs.saveLocation) getLastKnownLocation() else null
        val onSaved: (Uri) -> Unit = { uri ->
            if (isExternalImageIntent) {
                setResult(RESULT_OK, Intent().setData(uri))
                finish()
            } else {
                updateThumbnailFromUri(uri, false)
            }
        }
        val onFailed: (String) -> Unit = { msg ->
            Toast.makeText(this, "${getString(R.string.capture_failed)}: $msg", Toast.LENGTH_SHORT).show()
        }

        if (prefs.photoMode == PhotoQualityMode.HDR) {
            cameraEngine.takeHdrPhoto(prefs.saveFolder, prefs.mirrorFront, loc, onSaved, onFailed)
        } else {
            cameraEngine.takePhoto(prefs.saveFolder, prefs.mirrorFront, loc, onSaved, onFailed)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        return lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    private fun updateThumbnailFromUri(uri: Uri, isVideo: Boolean) {
        lastMediaUri = uri
        lastMediaIsVideo = isVideo
        Thread {
            val bmp = MediaSaver.loadThumbnailForUri(this, uri)
            runOnUiThread {
                if (bmp != null) btnThumbnail.setImageBitmap(bmp)
                else refreshThumbnail()
            }
        }.start()
    }

    private fun refreshThumbnail() {
        Thread {
            val latest = MediaSaver.loadLatestMedia(this, prefs.saveFolder)
            runOnUiThread {
                if (latest != null) {
                    lastMediaUri = latest.uri
                    lastMediaIsVideo = latest.isVideo
                    btnThumbnail.setImageBitmap(latest.thumbnail)
                }
            }
        }.start()
    }

    private fun openLastMedia() {
        val uri = lastMediaUri ?: return
        val mime = if (lastMediaIsVideo) "video/*" else "image/*"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(viewIntent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.no_gallery_app), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onShutterPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
