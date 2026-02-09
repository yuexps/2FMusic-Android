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
        
        // 1. 优先使用数据库已记录的本地路径（如果已同步）
        song.localCoverPath?.let { path ->
            // 如果存的是纯文件名，转为绝对路径；如果已经是绝对路径则保留
            val absolutePath = if (path.contains("/") || path.contains("\\")) path else FileStore.getLocalPath(path)
            
            absolutePath?.let { ap ->
                val normalized = ap.replace("\\", "/")
                return if (normalized.startsWith("/")) "file://$normalized" else "file:///$normalized"
            }
        }
        
        // 2. 备选检查标准路径 (如果数据库还没更新 localCoverPath 字段，但文件已存在)
        val fileName = "cover_${song.id}.jpg"
        FileStore.getCoverPath(song.id)?.let { ap ->
            val normalizedPath = ap.replace("\\", "/")
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
