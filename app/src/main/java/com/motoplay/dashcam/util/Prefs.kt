package com.motoplay.dashcam.util
import android.content.Context
import android.content.SharedPreferences
/**
 * 轻量配置存储。
 */
object Prefs {
    private const val NAME = "motoplay_prefs"
    private lateinit var sp: SharedPreferences
    // 默认值
    const val KEY_SEGMENT_MINUTES = "segment_minutes"
    const val KEY_MAX_STORAGE_GB = "max_storage_gb"
    const val KEY_RECORD_AUDIO = "record_audio"
    const val KEY_USE_FRONT_CAMERA = "use_front_camera"
    const val KEY_WALLPAPER_PATH = "wallpaper_path"
    const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    const val KEY_WATERMARK_ENABLED = "watermark_enabled"
    const val KEY_WEATHER_CITY = "weather_city"
    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }
    fun getInt(key: String, def: Int): Int = sp.getInt(key, def)
    fun setInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    fun getBoolean(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    fun setBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    fun getString(key: String, def: String?): String? = sp.getString(key, def)
    fun setString(key: String, value: String?) = sp.edit().putString(key, value).apply()
    /** 分段时长（分钟），默认 5 分钟 */
    val segmentMinutes: Int get() = getInt(KEY_SEGMENT_MINUTES, 5).coerceIn(1, 30)
    /** 存储上限（GB），默认 100GB */
    val maxStorageGb: Int get() = getInt(KEY_MAX_STORAGE_GB, 100).coerceIn(1, 1024)
    /** 是否录音，默认开启 */
    val recordAudio: Boolean get() = getBoolean(KEY_RECORD_AUDIO, true)
    /** 是否尝试前置摄像头（双摄），默认开启 */
    val useFrontCamera: Boolean get() = getBoolean(KEY_USE_FRONT_CAMERA, true)
    /** 壁纸路径 */
    var wallpaperPath: String?
        get() = getString(KEY_WALLPAPER_PATH, null)
        set(value) = setString(KEY_WALLPAPER_PATH, value)
    /** 开机自启，默认开启 */
    val autoStartOnBoot: Boolean get() = getBoolean(KEY_AUTO_START_ON_BOOT, true)
    /** 水印开关，默认开启 */
    val watermarkEnabled: Boolean get() = getBoolean(KEY_WATERMARK_ENABLED, true)
    /** 天气城市，默认北京 */
    val weatherCity: String get() = getString(KEY_WEATHER_CITY, "北京") ?: "北京"
}