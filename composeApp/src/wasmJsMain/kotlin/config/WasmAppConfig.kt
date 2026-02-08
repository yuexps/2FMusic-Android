package config

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set
import model.PlaybackStateData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

actual object ConfigManager : AppConfig {
    private const val KEY_BASE_URL = "app_base_url"
    private const val KEY_PASSWORD = "app_password"
    private const val KEY_PLAYBACK_STATE = "playback_state"
    private const val DEFAULT_BASE_URL = "http://localhost:23237"
    
    private var cachedHash: String? = null
    
    actual override fun initialize(context: Any?) {
        // No-op for Wasm
    }
    
    actual override fun getBaseUrl(): String {
        return localStorage[KEY_BASE_URL] ?: DEFAULT_BASE_URL
    }
    
    actual override fun setBaseUrl(url: String) {
        localStorage[KEY_BASE_URL] = url.trimEnd('/')
    }
    
    actual override fun getPassword(): String? {
        return localStorage[KEY_PASSWORD]
    }
    
    actual override fun setPassword(password: String?) {
        cachedHash = null // 清除缓存
        if (password.isNullOrBlank()) {
            localStorage.removeItem(KEY_PASSWORD)
        } else {
            localStorage[KEY_PASSWORD] = password
        }
    }

    actual override fun getPasswordHash(): String? {
        val password = getPassword() ?: return null
        if (cachedHash == null) {
            cachedHash = utils.Sha256.hash(password)
        }
        return cachedHash
    }

    actual override fun savePlaybackState(state: PlaybackStateData) {
        try {
            val json = Json.encodeToString(state)
            localStorage[KEY_PLAYBACK_STATE] = json
        } catch (e: Exception) {
            println("[WasmAppConfig] Failed to save playback state: ${e.message}")
        }
    }

    actual override fun loadPlaybackState(): PlaybackStateData? {
        val json = localStorage[KEY_PLAYBACK_STATE] ?: return null
        return try {
            Json.decodeFromString<PlaybackStateData>(json)
        } catch (e: Exception) {
            null
        }
    }
}
