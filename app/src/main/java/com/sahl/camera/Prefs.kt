package com.sahl.camera

import android.content.Context
import android.content.SharedPreferences

enum class PhotoQualityMode(val id: Int) {
    FAST(0),
    QUALITY(1),
    HDR(2),
    NIGHT(3);

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: FAST
    }
}

enum class GridMode(val id: Int) {
    OFF(0),
    THREE_BY_THREE(1),
    GOLDEN_RATIO(2);

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: OFF
    }
}

enum class VideoResolution(val id: Int) {
    HD_720P(0),
    FHD_1080P(1),
    UHD_4K(2);

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: FHD_1080P
    }
}

enum class SaveFolder(val id: Int, val relativePath: String) {
    SAHL_CAMERA(0, "DCIM/SahlCamera"),
    DCIM_CAMERA(1, "DCIM/Camera");

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: SAHL_CAMERA
    }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("sahl_camera_prefs", Context.MODE_PRIVATE)

    var photoMode: PhotoQualityMode
        get() = PhotoQualityMode.fromId(sp.getInt("photo_mode", PhotoQualityMode.FAST.id))
        set(v) = sp.edit().putInt("photo_mode", v.id).apply()

    var videoResolution: VideoResolution
        get() = VideoResolution.fromId(sp.getInt("video_res", VideoResolution.FHD_1080P.id))
        set(v) = sp.edit().putInt("video_res", v.id).apply()

    var gridMode: GridMode
        get() = GridMode.fromId(sp.getInt("grid_mode", GridMode.OFF.id))
        set(v) = sp.edit().putInt("grid_mode", v.id).apply()

    var mirrorFront: Boolean
        get() = sp.getBoolean("mirror_front", true)
        set(v) = sp.edit().putBoolean("mirror_front", v).apply()

    var shutterSound: Boolean
        get() = sp.getBoolean("shutter_sound", true)
        set(v) = sp.edit().putBoolean("shutter_sound", v).apply()

    var saveLocation: Boolean
        get() = sp.getBoolean("save_location", false)
        set(v) = sp.edit().putBoolean("save_location", v).apply()

    var saveFolder: SaveFolder
        get() = SaveFolder.fromId(sp.getInt("save_folder", SaveFolder.SAHL_CAMERA.id))
        set(v) = sp.edit().putInt("save_folder", v.id).apply()

    var flashMode: Int
        get() = sp.getInt("flash_mode", 0) // 0 = Auto, 1 = On, 2 = Off
        set(v) = sp.edit().putInt("flash_mode", v).apply()

    var timerSeconds: Int
        get() = sp.getInt("timer_seconds", 0) // 0, 3, 10
        set(v) = sp.edit().putInt("timer_seconds", v).apply()
}
