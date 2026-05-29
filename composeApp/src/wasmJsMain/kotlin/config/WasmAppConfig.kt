package config

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set
import model.PlaybackStateData
import kotlinx.serialization.json.Json

import utils.Platform

class WasmAppConfig : AppConfig {
    companion object {
        private const val KEY_BASE_URL = "app_base_url"
        private const val KEY_PASSWORD = "app_password"
        private const val KEY_PLAYBACK_STATE = "playback_state"
        private const val DEFAULT_BASE_URL = "http://localhost:23237"
    }
    
    private var cachedHash: String? = null
    
    override fun initialize(context: Any?) {
        // No-op for Wasm
    }
    
    override fun getBaseUrl(): String {
        return localStorage[KEY_BASE_URL] ?: DEFAULT_BASE_URL
    }
    
    override fun setBaseUrl(url: String) {
        localStorage[KEY_BASE_URL] = url.trimEnd('/')
    }
    
    override fun getPassword(): String? {
        return localStorage[KEY_PASSWORD]
    }
    
    override fun setPassword(password: String?) {
        cachedHash = null // 清除缓存
        if (password.isNullOrBlank()) {
            localStorage.removeItem(KEY_PASSWORD)
        } else {
            localStorage[KEY_PASSWORD] = password
        }
    }

    override fun getPasswordHash(): String? {
        val password = getPassword() ?: return null
        if (cachedHash == null) {
            cachedHash = utils.Sha256.hash(password)
        }
        return cachedHash
    }

    override fun savePlaybackState(state: PlaybackStateData) {
        try {
            val json = Json.encodeToString(state)
            localStorage[KEY_PLAYBACK_STATE] = json
        } catch (e: Exception) {
            Platform.logger.e("WasmAppConfig", "保存播放状态失败", e)
        }
    }

    override fun loadPlaybackState(): PlaybackStateData? {
        val json = localStorage[KEY_PLAYBACK_STATE] ?: return null
        return try {
            Json.decodeFromString<PlaybackStateData>(json)
        } catch (_: Exception) {
            null
        }
    }
}
