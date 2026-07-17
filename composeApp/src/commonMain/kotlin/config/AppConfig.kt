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
    fun getLyricFontSize(): Float = 20f
    fun setLyricFontSize(size: Float) {}
    fun getLyricTranslationMode(): Int = 1 // 0: 隐藏, 1: 仅当前行, 2: 全局双语
    fun setLyricTranslationMode(mode: Int) {}
    fun getShowLyricsInNotification(): Boolean = true
    fun setShowLyricsInNotification(show: Boolean) {}
    fun getDynamicColor(): Boolean = true
    fun setDynamicColor(enable: Boolean) {}
    fun getStorageType(): Int = 0 // 0: 内部存储, 1: 外部存储
    fun setStorageType(type: Int) {}
    fun getStorageDirPath(): String = ""
}
