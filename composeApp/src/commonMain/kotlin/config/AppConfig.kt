package config

import model.PlaybackStateData

interface AppConfig {
    fun initialize(context: Any? = null)
    fun getBaseUrl(): String
    fun setBaseUrl(url: String)
    fun getPassword(): String?
    fun setPassword(password: String?)
    fun getPasswordHash(): String?
    fun savePlaybackState(state: PlaybackStateData)
    fun loadPlaybackState(): PlaybackStateData?
}

expect object ConfigManager : AppConfig {
    override fun initialize(context: Any?)
    override fun getBaseUrl(): String
    override fun setBaseUrl(url: String)
    override fun getPassword(): String?
    override fun setPassword(password: String?)
    override fun getPasswordHash(): String?
    override fun savePlaybackState(state: PlaybackStateData)
    override fun loadPlaybackState(): PlaybackStateData?
}
