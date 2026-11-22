package com.sahl.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        prefs = Prefs(this)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bg = if (dark) Color.parseColor("#0B110F") else Color.parseColor("#F7F5EE")
        val cardBg = if (dark) Color.parseColor("#131C19") else Color.WHITE
        val textPrimary = if (dark) Color.WHITE else Color.parseColor("#111C18")
        val textSecondary = if (dark) Color.parseColor("#9AA3AB") else Color.parseColor("#5C666F")
        val accent = Color.parseColor("#0E6B4E")
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }

        // Top bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(textPrimary)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        header.addView(backBtn)
        header.addView(title)
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = 22 * dp
            }
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (14 * dp).toInt() }
            layoutParams = lp
        }

        fun divider(): View = View(this).apply {
            setBackgroundColor(if (dark) Color.parseColor("#2B3035") else Color.parseColor("#EAECEF"))
            layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt())
        }

        fun choiceRow(label: String, currentValue: () -> String, onClick: (TextView) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            }
            val tvTitle = TextView(this).apply {
                text = label
                textSize = 15.5f
                setTextColor(textPrimary)
            }
            val tvVal = TextView(this).apply {
                text = currentValue()
                textSize = 13f
                setTextColor(accent)
            }
            row.addView(tvTitle)
            row.addView(tvVal)
            row.setOnClickListener { onClick(tvVal) }
            return row
        }

        fun switchRow(label: String, initial: Boolean, onChanged: (Boolean) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 15.5f
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val sw = Switch(this).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChanged(checked) }
            }
            row.addView(tv)
            row.addView(sw)
            return row
        }

        // Card 1: Capture & Video
        val cardCapture = card()
        cardCapture.addView(choiceRow(
            getString(R.string.setting_capture_mode),
            {
                if (prefs.photoMode == PhotoQualityMode.FAST) getString(R.string.setting_capture_fast)
                else getString(R.string.setting_capture_quality)
            }
        ) { tvVal ->
            val items = arrayOf(getString(R.string.setting_capture_fast), getString(R.string.setting_capture_quality))
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_capture_mode)
                .setItems(items) { _, which ->
                    prefs.photoMode = if (which == 0) PhotoQualityMode.FAST else PhotoQualityMode.QUALITY
                    tvVal.text = items[which]
                }.show()
        })
        cardCapture.addView(divider())
        cardCapture.addView(choiceRow(
            getString(R.string.setting_video_quality),
            {
                when (prefs.videoResolution) {
                    VideoResolution.HD_720P -> getString(R.string.video_720p)
                    VideoResolution.FHD_1080P -> getString(R.string.video_1080p)
                    VideoResolution.UHD_4K -> getString(R.string.video_4k)
                }
            }
        ) { tvVal ->
            val items = arrayOf(getString(R.string.video_720p), getString(R.string.video_1080p), getString(R.string.video_4k))
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_video_quality)
                .setItems(items) { _, which ->
                    prefs.videoResolution = VideoResolution.fromId(which)
                    tvVal.text = items[which]
                }.show()
        })
        cardCapture.addView(divider())
        cardCapture.addView(choiceRow(
            getString(R.string.setting_grid),
            {
                when (prefs.gridMode) {
                    GridMode.OFF -> getString(R.string.grid_off)
                    GridMode.THREE_BY_THREE -> getString(R.string.grid_3x3)
                    GridMode.GOLDEN_RATIO -> getString(R.string.grid_golden)
                }
            }
        ) { tvVal ->
            val items = arrayOf(getString(R.string.grid_off), getString(R.string.grid_3x3), getString(R.string.grid_golden))
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_grid)
                .setItems(items) { _, which ->
                    prefs.gridMode = GridMode.fromId(which)
                    tvVal.text = items[which]
                }.show()
        })
        content.addView(cardCapture)

        // Card 2: Behavior & Privacy
        val cardBehavior = card()
        cardBehavior.addView(switchRow(getString(R.string.setting_mirror_front), prefs.mirrorFront) {
            prefs.mirrorFront = it
        })
        cardBehavior.addView(divider())
        cardBehavior.addView(switchRow(getString(R.string.setting_shutter_sound), prefs.shutterSound) {
            prefs.shutterSound = it
        })
        cardBehavior.addView(divider())
        cardBehavior.addView(switchRow(getString(R.string.setting_save_location), prefs.saveLocation) { checked ->
            if (checked && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 201)
            }
            prefs.saveLocation = checked
        })
        cardBehavior.addView(divider())
        cardBehavior.addView(choiceRow(
            getString(R.string.setting_save_folder),
            {
                if (prefs.saveFolder == SaveFolder.SAHL_CAMERA) getString(R.string.folder_sahl)
                else getString(R.string.folder_camera)
            }
        ) { tvVal ->
            val items = arrayOf(getString(R.string.folder_sahl), getString(R.string.folder_camera))
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_save_folder)
                .setItems(items) { _, which ->
                    prefs.saveFolder = SaveFolder.fromId(which)
                    tvVal.text = items[which]
                }.show()
        })
        content.addView(cardBehavior)

        // Card 3: Updates & Version
        val cardAbout = card()
        val updateRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
        }
        val updateTitle = TextView(this).apply {
            text = Updater.checkText(this@SettingsActivity)
            textSize = 15.5f
            setTextColor(textPrimary)
        }
        val updateSub = TextView(this).apply {
            text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
            textSize = 13f
            setTextColor(textSecondary)
        }
        updateRow.addView(updateTitle)
        updateRow.addView(updateSub)
        updateRow.setOnClickListener {
            updateSub.text = Updater.checkingText(this)
            Thread {
                val rel = Updater.fetchNewer()
                runOnUiThread {
                    if (rel != null) {
                        updateSub.text = Updater.availableText(this, rel)
                        AlertDialog.Builder(this)
                            .setTitle(Updater.availableText(this, rel))
                            .setMessage(rel.notes.ifBlank { Updater.updateNowText(this) })
                            .setPositiveButton(Updater.updateNowText(this)) { _, _ ->
                                Updater.downloadAndInstall(this, rel)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        updateSub.text = Updater.upToDateText(this)
                        Toast.makeText(this, Updater.upToDateText(this), Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        cardAbout.addView(updateRow)
        content.addView(cardAbout)

        val goldHdr = if (dark) Color.parseColor("#F5C84C") else Color.parseColor("#B88922")
        val cardUnion = card()
        cardUnion.addView(TextView(this).apply {
            text = if (resources.configuration.locales[0].language == "ar") "اتحاد سهل" else "Sahl Union"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(goldHdr)
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        })
        cardUnion.addView(SahlUnionApps.strip(this, textSecondary))
        content.addView(cardUnion)

        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
