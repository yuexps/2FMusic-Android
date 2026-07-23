package utils

import model.Song

object CoverUtil {
    /**
     * 获取封面 URL 或本地路径
     * 逻辑：优先尝试本地缓存 -> 远程服务器
     */
    fun getCoverUrl(song: Song?): String? {
        if (song == null) return null
        
        // 1. 优先使用数据库已记录的本地路径（如果已同步）
        // 注意：Wasm 端内存文件系统不存储本地，故跳过本地路径检查
        if (!Platform.isWasm) {
            song.localCoverPath?.let { path ->
                // FileStore.getLocalPath 现在已经能自动识别绝对/相对路径了，直接调用即可
                FileStore.getLocalPath(path)?.let { ap ->
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
        }

        // 2. 备选远程服务器
        return song.albumArt?.let { artPath ->
            val hash = Platform.config.getPasswordHash()
            val separator = if (artPath.contains("?")) "&" else "?"
            val authSuffix = if (hash != null) "${separator}auth=$hash" else ""
            
            if (artPath.startsWith("http://", ignoreCase = true) || artPath.startsWith("https://", ignoreCase = true)) {
                artPath
            } else {
                val baseUrl = Platform.config.getBaseUrl()
                val normalizedArt = if (artPath.startsWith("/")) artPath else "/$artPath"
                "$baseUrl$normalizedArt$authSuffix"
            }
        }
    }
}
