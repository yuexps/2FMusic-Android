package config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import model.PlaybackStateData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object ConfigManager : AppConfig {
    private const val PREFS_NAME = "2fmusic_prefs"
    private const val KEY_BASE_URL = "app_base_url"
    private const val KEY_PASSWORD = "app_password"
    private const val KEY_PLAYBACK_STATE = "playback_state"
    private const val DEFAULT_BASE_URL = "http://localhost:23237"
    
    private var prefs: SharedPreferences? = null
    
    actual override fun initialize(context: Any?) {
        if (context is Context) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    private fun getPrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException("ConfigManager not initialized with Context")
    }
    
    actual override fun getBaseUrl(): String {
        return getPrefs().getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }
    
    actual override fun setBaseUrl(url: String) {
        getPrefs().edit { putString(KEY_BASE_URL, url.trimEnd('/')) }
    }
    
    private var cachedHash: String? = null

    actual override fun getPassword(): String? {
        return getPrefs().getString(KEY_PASSWORD, null)
    }

    actual override fun setPassword(password: String?) {
        cachedHash = null // 清除缓存
        getPrefs().edit { putString(KEY_PASSWORD, password) }
    }

    actual override fun getPasswordHash(): String? {
        val password = getPassword() ?: return null
        if (cachedHash == null) {
            cachedHash = utils.Sha256.hash(password)
        }
        return cachedHash
    }

    actual override fun savePlaybackState(state: PlaybackStateData) {
        val json = Json.encodeToString(state)
        getPrefs().edit { putString(KEY_PLAYBACK_STATE, json) }
    }

    actual override fun loadPlaybackState(): PlaybackStateData? {
        val json = getPrefs().getString(KEY_PLAYBACK_STATE, null) ?: return null
        return try {
            Json.decodeFromString<PlaybackStateData>(json)
        } catch (e: Exception) {
            null
        }
    }
}
