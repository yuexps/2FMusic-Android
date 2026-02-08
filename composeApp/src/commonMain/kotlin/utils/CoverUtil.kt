package utils

import model.Song
import config.ConfigManager

object CoverUtil {
    /**
     * 获取封面 URL 或本地路径
     * 逻辑：优先尝试本地缓存 -> 远程服务器
     */
    fun getCoverUrl(song: Song?): String? {
        if (song == null) return null
        
        // 1. 优先检查本地缓存
        val fileName = "cover_${song.id}.jpg"
        val localPath = FileStore.getLocalPath(fileName)
        if (localPath != null) {
            // 在 Desktop/Windows 上，ImageLoader 通常需要 file:// 前缀来识别本地文件
            // 对路径进行标准化处理（替换反斜杠为正斜杠）
            val normalizedPath = localPath.replace("\\", "/")
            return if (normalizedPath.startsWith("/")) "file://$normalizedPath" else "file:///$normalizedPath"
        }

        // 2. 备选远程服务器
        return song.albumArt?.let { artPath ->
            val hash = ConfigManager.getPasswordHash()
            val separator = if (artPath.contains("?")) "&" else "?"
            val authSuffix = if (hash != null) "${separator}auth=$hash" else ""
            
            if (artPath.startsWith("http")) {
                artPath
            } else {
                "${ConfigManager.getBaseUrl()}$artPath$authSuffix"
            }
        }
    }
}
