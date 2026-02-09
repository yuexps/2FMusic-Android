package data

import api.MusicApi
import database.MusicDb
import database.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import model.Song
import model.Playlist
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import database.DatabaseDriverFactory
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import utils.Toast
import utils.NotificationHelper

class MusicRepository(
    private val api: MusicApi,
    driverFactory: DatabaseDriverFactory
) {
    private val database = MusicDb(driverFactory.createDriver())
    private val queries = database.musicDbQueries

    /**
     * 获取本地所有歌曲的 Flow 流
     */
    fun getLocalSongs(): Flow<List<SongEntity>> {
        return queries.getAllSongs().asFlow().mapToList(Dispatchers.Default)
    }

    /**
     * 获取收藏列表的 Flow 流
     */
    /**
     * 获取收藏列表的 Flow 流 (适配新表，指向 default 歌单)
     */
    fun getFavorites(): Flow<Set<String>> {
        // 使用 getPlaylistSongIds 查询 default 歌单
        return queries.getPlaylistSongIds("default").asFlow().mapToList(Dispatchers.Default).map { it.toSet() }
    }

    /**
     * 获取收藏歌曲详情的 Flow 流 (适配新表)
     */
    fun getFavoriteSongs(): Flow<List<Song>> {
        return getSongsInPlaylist("default")
    }

    /**
     * 获取所有歌单 (本地缓存)
     */
    fun getAllPlaylists(): Flow<List<Playlist>> {
        return queries.getAllPlaylists().asFlow().mapToList(Dispatchers.Default).map { entities ->
            entities.map { entity ->
                Playlist(
                    id = entity.id,
                    name = entity.name,
                    songCount = entity.songCount?.toInt() ?: 0,
                    cover = entity.cover,
                    isDefault = entity.isDefault?.toInt() ?: 0,
                    createdAt = entity.createdAt
                )
            }
        }
    }

    /**
     * 获取指定歌单内的歌曲 (本地缓存)
     */
    fun getSongsInPlaylist(playlistId: String): Flow<List<Song>> {
        return queries.getSongsInPlaylist(playlistId).asFlow().mapToList(Dispatchers.Default).map { entities ->
            entities.map { entity ->
                Song(
                    id = entity.id,
                    path = entity.path,
                    filename = entity.filename,
                    title = entity.title,
                    artist = entity.artist,
                    album = entity.album,
                    mtime = entity.mtime,
                    size = entity.size,
                    hasCover = if (entity.hasCover == 1L) 1 else 0,
                    albumArt = entity.albumArt,
                    localCoverPath = entity.localCoverPath,
                    localLyricsPath = entity.localLyricsPath,
                    localAudioPath = entity.localAudioPath
                )
            }
        }
    }

    suspend fun addFavorite(id: String) = withContext(Dispatchers.Default) {
        queries.transaction {
            queries.addSongToPlaylist("default", id)
            queries.refreshPlaylistCount("default")
        }
    }

    suspend fun removeFavorite(id: String) = withContext(Dispatchers.Default) {
        queries.transaction {
            queries.removeSongFromPlaylist("default", id)
            queries.refreshPlaylistCount("default")
        }
    }

    /**
     * 将远程收藏状态同步到本地数据库
     */
    /**
     * 将远程收藏状态同步到本地数据库 (废弃，保留空实现或兼容)
     * 新逻辑集成在 syncPlaylists 中
     */
    suspend fun updateLocalFavorites(ids: Set<String>) = withContext(Dispatchers.Default) {
        // 兼容旧调用：同步到 default 歌单
        queries.transaction {
            queries.removeAllSongsFromPlaylist("default")
            ids.forEach { queries.addSongToPlaylist("default", it) }
        }
    }

    /**
     * 核心同步逻辑：本地差分更新
     */
    suspend fun sync() = withContext(Dispatchers.Default) {
        try {
            // 1. 获取服务器最新版本
            val status = api.getSystemStatus()
            val remoteVersion = status.libraryVersion.toString()
            
            // 2. 检查本地版本
            val localVersion = queries.getMetadata("last_library_version").executeAsOneOrNull()?.value_
            
            if (localVersion == remoteVersion) {
                println("[MusicRepository] 库版本一致，跳过全量同步")
                return@withContext
            }

            println("[MusicRepository] 版本变更: $localVersion -> $remoteVersion, 开始差分同步...")

            // 3. 拉取全量列表
            val remoteSongs = api.getMusicList()
            val remoteIds = remoteSongs.map { it.id }.toSet()

            // 4. 开启事务进行同步
            queries.transaction {
                // a. 插入/更新所有远程歌曲到本地
                remoteSongs.forEach { song ->
                    // 检索本地是否已有对应的记录，以保留本地路径
                    val existing = queries.getSongById(song.id).executeAsOneOrNull()
                    
                    queries.insertSong(
                        id = song.id,
                        path = song.path,
                        filename = song.filename,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        mtime = song.mtime,
                        size = song.size,
                        hasCover = if (song.hasCover != 0) 1L else 0L,
                        albumArt = song.albumArt,
                        localCoverPath = existing?.localCoverPath, // 保留本地路径
                        localLyricsPath = existing?.localLyricsPath, // 保留本地路径
                        localAudioPath = existing?.localAudioPath // 保留本地下载路径
                    )
                }

                // b. 删除本地有但远程没有的 ID
                val allLocalIds = queries.getAllSongs().executeAsList().map { it.id }.toSet()
                val toDelete = allLocalIds - remoteIds
                
                toDelete.forEach { id ->
                    queries.deleteSongById(id)
                }
            }
            
            // 5. 更新本地版本戳
            queries.insertMetadata("last_library_version", remoteVersion)
            println("[MusicRepository] 数据同步完成，当前版本: $remoteVersion")

            // 6. 后台同步媒体文件
            syncMedia(remoteSongs)

            // 7. 同步所有歌单
            syncPlaylists()

        } catch (e: Exception) {
            println("[MusicRepository] 同步失败: ${e.message}")
            throw e
        }
    }

    /**
     * 同步歌单及其歌曲
     */
    suspend fun syncPlaylists() = withContext(Dispatchers.Default) {
        try {
            println("[MusicRepository] 开始同步歌单...")
            
            // 1. 获取自定义歌单列表
            val playlists = api.getFavoritePlaylists()
            
            // 2. 获取默认收藏夹歌曲 (API 支持 getPlaylistSongs("default"))
            val defaultFavIds = try {
                api.getPlaylistSongs("default")
            } catch (e: Exception) {
                emptyList()
            }

            queries.transaction {
                queries.deleteAllPlaylists()
                
                // 3. 插入默认歌单
                queries.insertPlaylist(
                    id = "default",
                    name = "我的收藏",
                    songCount = defaultFavIds.size.toLong(),
                    cover = null, // 可以后续优化为取第一首歌封面
                    isDefault = 1,
                    createdAt = 0.0
                )
                
                // 4. 插入自定义歌单
                playlists.filter { it.id != "default" }.forEach { p ->
                    queries.insertPlaylist(
                        id = p.id,
                        name = p.name,
                        songCount = p.songCount.toLong(),
                        cover = p.cover,
                        isDefault = 0,
                        createdAt = p.createdAt
                    )
                }
            }
            
            // 5. 同步默认收藏夹歌曲
            syncPlaylistSongs("default", "我的收藏", defaultFavIds)

            // 6. 同步每个自定义歌单的歌曲
            playlists.forEach { playlist ->
                // 跳过 default，以防 API 返回列表里也有 default
                if (playlist.id != "default") {
                    try {
                        val songIds = api.getPlaylistSongs(playlist.id)
                        syncPlaylistSongs(playlist.id, playlist.name, songIds)
                    } catch (e: Exception) {
                        println("[MusicRepository] 歌单 [${playlist.name}] 同步失败: ${e.message}")
                    }
                }
            }
            println("[MusicRepository] 歌单同步完成")
        } catch (e: Exception) {
            println("[MusicRepository] 歌单列表获取失败: ${e.message}")
        }
    }

    /**
     * 确保默认歌单存在 (占位)
     */
    suspend fun ensureDefaultPlaylistExists() = withContext(Dispatchers.Default) {
        val count = queries.getAllPlaylists().executeAsList().size
        if (count == 0) {
            queries.insertPlaylist(
                id = "default",
                name = "我的收藏",
                songCount = 0,
                cover = null,
                isDefault = 1,
                createdAt = 0.0
            )
            println("[MusicRepository] 创建默认收藏夹占位")
        }
    }

    private fun syncPlaylistSongs(playlistId: String, playlistName: String, songIds: List<String>) {
        queries.transaction {
            queries.removeAllSongsFromPlaylist(playlistId)
            songIds.forEach { songId ->
                // 检查歌曲是否存在于 SongEntity，避免 FK 约束失败
                if (queries.getSongById(songId).executeAsOneOrNull() != null) {
                    queries.addSongToPlaylist(playlistId, songId)
                }
            }
        }
    }

    /**
     * 静默同步封面和歌词
     */
    private suspend fun syncMedia(songs: List<Song>) = withContext(Dispatchers.Default) {
        songs.forEach { song ->
            var updatedCoverPath: String? = null
            var updatedLyricsPath: String? = null
            var needsUpdate = false

            // 1. 同步封面 - 仅检查是否存在，不主动下载
            if (utils.FileStore.getCoverPath(song.id) != null) {
                updatedCoverPath = "cover_${song.id}.jpg"
            }

            // 2. 同步歌词 - 仅检查是否存在，不主动下载
            if (utils.FileStore.readLyrics(song.id) != null) {
                updatedLyricsPath = "lyrics_${song.id}.lrc"
            }

            // 3. 同步数据库状态
            queries.updateCoverAndLyrics(
                localCoverPath = updatedCoverPath,
                localLyricsPath = updatedLyricsPath,
                id = song.id
            )
        }
    }

    /**
     * 确保封面已持久化下载 (播放时触发)
     */
    suspend fun ensureCoverDownloaded(song: Song) = withContext(Dispatchers.Default) {
        if (utils.FileStore.getCoverPath(song.id) == null) {
            utils.FileStore.log("[Cover] 开始验证封面: ${song.title}")
            try {
                // 1. 尝试直接从 Song 对象获取已有链接
                var targetUrl = song.albumArt
                
                if (targetUrl != null) {
                    utils.FileStore.log("[Cover] 使用歌曲元数据中的链接: $targetUrl")
                } else {
                    // 2. 如果元数据没有链接，尝试通过接口查找
                    utils.FileStore.log("[Cover] 元数据无链接，调用接口查找...")
                    val response = api.getAlbumArt(
                        title = song.title ?: "",
                        artist = song.artist ?: "",
                        filename = song.filename ?: ""
                    )
                    utils.FileStore.log("[Cover] 接口返回: success=${response.success}, url=${response.albumArt}")
                    if (response.success && response.albumArt != null) {
                        targetUrl = response.albumArt
                    }
                }

                if (targetUrl != null) {
                    val bytes = api.downloadFile(targetUrl)
                    utils.FileStore.log("[Cover] 下载数据成功: ${bytes.size} bytes")
                    utils.FileStore.saveCover(song.id, bytes)
                    queries.updateCoverPath("cover_${song.id}.jpg", song.id)
                    utils.FileStore.log("[Cover] 持久化及数据库更新完成")
                } else {
                    utils.FileStore.log("[Cover] 未找到可用封面链接")
                }
            } catch (e: Exception) {
                utils.FileStore.log("[Cover] 异常: ${e.message}")
                println("[MusicRepository] 封面持久化失败: ${song.title}, error: ${e.message}")
            }
        }
    }

    /**
     * 确保歌词已持久化下载 (播放时触发)
     */
    suspend fun ensureLyricsDownloaded(song: Song) = withContext(Dispatchers.Default) {
        if (utils.FileStore.readLyrics(song.id) == null) {
            utils.FileStore.log("[Lyrics] 开始验证歌词: ${song.title}")
            try {
                val response = api.getLyrics(
                    title = song.title ?: "",
                    artist = song.artist ?: ""
                )
                utils.FileStore.log("[Lyrics] 接口返回: success=${response.success}, hasContent=${response.lyrics != null}")
                
                if (response.success && response.lyrics != null) {
                    utils.FileStore.saveLyrics(song.id, response.lyrics)
                    queries.updateLyricsPath("lyrics_${song.id}.lrc", song.id)
                    utils.FileStore.log("[Lyrics] 持久化及数据库更新完成")
                }
            } catch (e: Exception) {
                utils.FileStore.log("[Lyrics] 异常: ${e.message}")
                println("[MusicRepository] 歌词持久化失败: ${song.title}, error: ${e.message}")
            }
        }
    }

    private val downloadScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    enum class DownloadResult {
        STARTED,
        EXISTS,
        ERROR
    }

    fun downloadMusic(song: Song): DownloadResult {
        val fileName = "audio_${song.id}.mp3"
        
        // 检查是否已存在
        if (utils.FileStore.getLocalPath(fileName) != null) {
            queries.updateAudioPath(fileName, song.id)
            return DownloadResult.EXISTS
        }

        downloadScope.launch {
            try {
                // 获取下载 URL
                val notificationId = song.id.hashCode()
                
                // 下载 (api.downloadFile 内部会自动处理 header auth)
                val bytes = api.downloadFile("/api/music/play/${song.id}") { sent, total ->
                     if (total > 0) {
                         val progress = (sent * 100 / total).toInt()
                         utils.NotificationHelper.showProgress(
                             id = notificationId,
                             title = "正在下载: ${song.title}",
                             content = "$progress%",
                             progress = progress,
                             max = 100
                         )
                     }
                }
                
                // 下载完成，取消进度通知
                utils.NotificationHelper.cancel(notificationId)
                utils.Toast.show("${song.title} 下载完成")
                
                // 保存
                utils.FileStore.saveFile(fileName, bytes)
                
                // 更新数据库
                queries.updateAudioPath(fileName, song.id)
                println("[MusicRepository] 歌曲下载完成: ${song.title}")
            } catch (e: Exception) {
                println("[MusicRepository] 歌曲下载失败: ${e.message}")
                e.printStackTrace()
                utils.Toast.show("${song.title} 下载失败")
                utils.NotificationHelper.cancel(song.id.hashCode())
            }
        }
        
        return DownloadResult.STARTED
    }

    suspend fun deleteLocalAudio(songId: String) = withContext(Dispatchers.Default) {
        val song: SongEntity? = queries.getSongById(songId).executeAsOneOrNull()
        song?.localAudioPath?.let { path ->
            utils.FileStore.deleteFile(path)
        }
        queries.updateAudioPath(null, songId)
    }
}
