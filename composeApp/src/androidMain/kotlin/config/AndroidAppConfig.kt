package config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import model.PlaybackStateData
import kotlinx.serialization.json.Json

class AndroidAppConfig : AppConfig {
    companion object {
        private const val PREFS_NAME = "2fmusic_prefs"
        private const val KEY_BASE_URL = "app_base_url"
        private const val KEY_PASSWORD = "app_password"
        private const val KEY_PLAYBACK_STATE = "playback_state"
        private const val DEFAULT_BASE_URL = "http://192.168.31.254:23237"
    }
    
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var appStorageDir: String = ""
    
    override fun initialize(context: Any?) {
        if (context is Context) {
            appContext = context.applicationContext
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            recalculateStorageDir()
        }
    }

    private fun recalculateStorageDir() {
        val context = appContext ?: return
        val type = getStorageType()
        appStorageDir = if (type == 1) {
            val extMusic = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).absolutePath
            "$extMusic/2FMusic"
        } else {
            context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        }
    }
    
    private fun getPrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException("AndroidAppConfig not initialized with Context")
    }
    
    override fun getBaseUrl(): String {
        return getPrefs().getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }
    
    override fun setBaseUrl(url: String) {
        getPrefs().edit { putString(KEY_BASE_URL, url.trimEnd('/')) }
    }
    
    private var cachedHash: String? = null

    override fun getPassword(): String? {
        return getPrefs().getString(KEY_PASSWORD, null)
    }

    override fun setPassword(password: String?) {
        cachedHash = null // 清除缓存
        getPrefs().edit { putString(KEY_PASSWORD, password) }
    }

    override fun getPasswordHash(): String? {
        val password = getPassword() ?: return null
        if (cachedHash == null) {
            cachedHash = utils.Sha256.hash(password)
        }
        return cachedHash
    }

    override fun savePlaybackState(state: PlaybackStateData) {
        val json = Json.encodeToString(state)
        getPrefs().edit { putString(KEY_PLAYBACK_STATE, json) }
    }

    override fun loadPlaybackState(): PlaybackStateData? {
        val json = getPrefs().getString(KEY_PLAYBACK_STATE, null) ?: return null
        return try {
            Json.decodeFromString<PlaybackStateData>(json)
        } catch (_: Exception) {
            null
        }
    }

    override fun getLyricFontSize(): Float {
        return getPrefs().getFloat("lyric_font_size", 20f)
    }

    override fun setLyricFontSize(size: Float) {
        getPrefs().edit { putFloat("lyric_font_size", size) }
    }

    override fun getLyricTranslationMode(): Int {
        return getPrefs().getInt("lyric_translation_mode", 1)
    }

    override fun setLyricTranslationMode(mode: Int) {
        getPrefs().edit { putInt("lyric_translation_mode", mode) }
    }

    override fun getShowLyricsInNotification(): Boolean {
        return getPrefs().getBoolean("show_lyrics_in_notification", true)
    }

    override fun setShowLyricsInNotification(show: Boolean) {
        getPrefs().edit { putBoolean("show_lyrics_in_notification", show) }
    }

    override fun getDynamicColor(): Boolean {
        return getPrefs().getBoolean("dynamic_color", true)
    }

    override fun setDynamicColor(enable: Boolean) {
        getPrefs().edit { putBoolean("dynamic_color", enable) }
    }

    override fun getStorageType(): Int {
        return getPrefs().getInt("app_storage_type", 0)
    }

    override fun setStorageType(type: Int) {
        getPrefs().edit { putInt("app_storage_type", type) }
        recalculateStorageDir()
        // 热更新物理数据存储路径
        utils.FileStore.initialize(appStorageDir)
    }

    override fun getStorageDirPath(): String {
        return appStorageDir
    }
}
