package config

interface AppConfig {
    fun initialize(context: Any? = null)
    fun getBaseUrl(): String
    fun setBaseUrl(url: String)
    fun getPassword(): String?
    fun setPassword(password: String?)
    fun getPasswordHash(): String?
    fun savePlaybackState(state: model.PlaybackStateData)
    fun loadPlaybackState(): model.PlaybackStateData?
}
