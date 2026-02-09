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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import utils.IODispatcher
import utils.getTimeMillis

class MusicRepository(
    private val api: MusicApi,
    driverFactory: DatabaseDriverFactory
) {
    private val database = MusicDb(driverFactory.createDriver())
    private val queries = database.musicDbQueries
    private val syncMutex = Mutex()

    /**
     * 获取本地所有歌曲的 Flow 流
     */
    fun getLocalSongs(): Flow<List<SongEntity>> {
        return queries.getAllSongs().asFlow().mapToList(IODispatcher)
    }

    /**
     * 获取收藏列表的 Flow 流
     */
    /**
     * 获取收藏列表的 Flow 流 (适配新表，指向 default 歌单)
     */
    fun getFavorites(): Flow<Set<String>> {
        // 使用 getPlaylistSongIds 查询 default 歌单
        return queries.getPlaylistSongIds("default").asFlow().mapToList(IODispatcher).map { it.toSet() }
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
        return queries.getSongsInPlaylist(playlistId).asFlow().mapToList(IODispatcher).map { entities ->
            entities.map { entity ->
                Song(
                    id = entity.id,
                    filename = entity.filename,
                    title = entity.title,
                    artist = entity.artist,
                    album = entity.album,
                    mtime = entity.mtime,
                    size = entity.size,
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
     * 核心同步逻辑：本地差分更新 (增加并发锁)
     */
    suspend fun sync() = syncMutex.withLock {
        withContext(Dispatchers.Default) {
            try {
            // 1. 获取服务器最新版本
            val status = api.getSystemStatus()
            val remoteVersion = status.libraryVersion.toString()
            
            // 2. 检查本地版本
            val localVersion = queries.getMetadata("last_library_version").executeAsOneOrNull()?.value_
            
            if (localVersion == remoteVersion) {
                utils.Logger.i("MusicRepository", "库版本一致，跳过全量同步")
                return@withContext
            }

            utils.Logger.i("MusicRepository", "版本变更: $localVersion -> $remoteVersion, 开始差分同步...")

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
                        filename = song.filename,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        mtime = song.mtime,
                        size = song.size,
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
            utils.Logger.i("MusicRepository", "数据同步完成，当前版本: $remoteVersion")

            // 6. 后台同步媒体文件
            syncMedia(remoteSongs)

            // 7. 同步所有歌单
            internalSyncPlaylists()

        } catch (e: Exception) {
            utils.Logger.e("MusicRepository", "同步失败", e)
            throw e
            }
        }
    }

    /**
     * 同步歌单及其歌曲 (增加并发锁)
     */
    suspend fun syncPlaylists() = syncMutex.withLock {
        internalSyncPlaylists()
    }

    private suspend fun internalSyncPlaylists() = withContext(IODispatcher) {
        try {
            utils.Logger.i("MusicRepository", "开始同步歌单列表...")
            
            // 1. 获取自定义歌单列表
            val playlists = api.getFavoritePlaylists()
            utils.Logger.i("MusicRepository", "获取到远程歌单列表，共 ${playlists.size} 个歌单")
            
            // 2. 获取默认收藏夹歌曲 (API 支持 getPlaylistSongs("default"))
            val defaultFavIds = try {
                api.getPlaylistSongs("default")
            } catch (_: Exception) {
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
            syncPlaylistSongs("default", defaultFavIds)
            utils.Logger.i("MusicRepository", "歌单 [我的收藏] 同备完成，包含 ${defaultFavIds.size} 首歌曲")

            // 6. 同步每个自定义歌单的歌曲
            playlists.forEach { playlist ->
                // 跳过 default，以防 API 返回列表里也有 default
                if (playlist.id != "default") {
                    try {
                        val songIds = api.getPlaylistSongs(playlist.id)
                        syncPlaylistSongs(playlist.id, songIds)
                        utils.Logger.i("MusicRepository", "歌单 [${playlist.name}] 同步完成，包含 ${songIds.size} 首歌曲")
                    } catch (e: Exception) {
                        utils.Logger.e("MusicRepository", "歌单 [${playlist.name}] 同步失败", e)
                    }
                }
            }
            utils.Logger.i("MusicRepository", "所有歌单同步工作已完成")
        } catch (e: Exception) {
            utils.Logger.e("MusicRepository", "歌单列表同步过程中发生异常", e)
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
            utils.Logger.i("MusicRepository", "创建默认收藏夹占位")
        }
    }

    private fun syncPlaylistSongs(playlistId: String, songIds: List<String>) {
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
            utils.Logger.i("Cover", "开始验证封面: ${song.title}")
            try {
                // 1. 尝试直接从 Song 对象获取已有链接
                var targetUrl = song.albumArt
                
                if (targetUrl != null) {
                    utils.Logger.i("Cover", "使用歌曲元数据中的链接: $targetUrl")
                } else {
                    // 2. 如果元数据没有链接，尝试通过接口查找
                    utils.Logger.i("Cover", "元数据无链接，调用接口查找...")
                    val response = api.getAlbumArt(
                        title = song.title ?: "",
                        artist = song.artist ?: "",
                        filename = song.filename ?: ""
                    )
                    utils.Logger.i("Cover", "接口返回: 成功=${response.success}, 链接=${response.albumArt}")
                    if (response.success && response.albumArt != null) {
                        targetUrl = response.albumArt
                    }
                }

                if (targetUrl != null) {
                    val bytes = api.downloadFile(targetUrl)
                    val sizeMB = (bytes.size * 10 / 1024 / 1024).toDouble() / 10.0
                    utils.Logger.i("Cover", "封面下载成功: [${song.title}], 大小: ${sizeMB}MB")
                    utils.FileStore.saveCover(song.id, bytes)
                    queries.updateCoverPath("cover_${song.id}.jpg", song.id)
                    utils.Logger.i("Cover", "封面持久化及数据库更新完成: [${song.title}]")
                } else {
                    utils.Logger.i("Cover", "未找到歌曲 [${song.title}] 的可用封面链接")
                }
            } catch (e: Exception) {
                utils.Logger.e("Cover", "封面持久化失败: ${song.title}", e)
            }
        }
    }

    /**
     * 确保歌词已持久化下载 (播放时触发)
     */
    suspend fun ensureLyricsDownloaded(song: Song) = withContext(Dispatchers.Default) {
        if (utils.FileStore.readLyrics(song.id) == null) {
            utils.Logger.i("Lyrics", "开始验证歌词: ${song.title}")
            try {
                val response = api.getLyrics(
                    title = song.title ?: "",
                    artist = song.artist ?: ""
                )
                utils.Logger.i("Lyrics", "接口返回: 成功=${response.success}, 是否有内容=${response.lyrics != null}")
                
                if (response.success && response.lyrics != null) {
                    utils.FileStore.saveLyrics(song.id, response.lyrics)
                    queries.updateLyricsPath("lyrics_${song.id}.lrc", song.id)
                    utils.Logger.i("Lyrics", "歌词持久化及数据库更新完成: [${song.title}]")
                }
            } catch (e: Exception) {
                utils.Logger.e("Lyrics", "歌词持久化失败: ${song.title}", e)
            }
        }
    }

    private val downloadScope = kotlinx.coroutines.CoroutineScope(IODispatcher + kotlinx.coroutines.SupervisorJob())

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
            utils.Logger.i("Audio", "开始下载: ${song.title} (ID: ${song.id})")
            try {
                // 获取下载 URL
                val notificationId = song.id.hashCode()
                
                // 立即开启一个初始通知，告知用户下载已经开始
                utils.NotificationHelper.showProgress(
                    id = notificationId,
                    title = "准备下载: ${song.title}",
                    content = "正在连接服务器...",
                    progress = 0,
                    max = 0 // 初始显示模糊进度条
                )
                
                var lastNotifyTime = 0L
                // 下载 (api.downloadFile 内部会自动处理 header auth)
                val bytes = api.downloadFile("/api/music/play/${song.id}") { sent, total ->
                     val now = getTimeMillis()
                     val isFinished = total > 0 && sent == total
                     
                     // 节流处理：每 200ms 更新一次通知，或者是最后一次更新
                     val shouldNotify = isFinished || ((now - lastNotifyTime) >= 200L)
                     if (shouldNotify) {
                         lastNotifyTime = now
                         if (total > 0) {
                             val progress = (sent * 100 / total).toInt()
                             // 使用 0.1MB 精度显示
                             val sentMB = (sent * 10 / 1024 / 1024).toDouble() / 10.0
                             val totalMB = (total * 10 / 1024 / 1024).toDouble() / 10.0
                             utils.NotificationHelper.showProgress(
                                 id = notificationId,
                                 title = "正在下载: ${song.title}",
                                 content = "$progress% (${sentMB}MB / ${totalMB}MB)",
                                 progress = progress,
                                 max = 100
                             )
                         } else {
                             // 未知大小时显示模糊进度条，统一使用 MB
                             val sentMB = (sent * 10 / 1024 / 1024).toDouble() / 10.0
                             utils.NotificationHelper.showProgress(
                                 id = notificationId,
                                 title = "正在下载: ${song.title}",
                                 content = "已接收: ${sentMB}MB",
                                 progress = 0,
                                 max = 0
                             )
                         }
                     }
                }
                
                val sizeMB = (bytes.size * 10 / 1024 / 1024).toDouble() / 10.0
                utils.Logger.i("Audio", "音频下载成功: [${song.title}], 大小: ${sizeMB}MB，准备保存...")
                // 保存
                utils.FileStore.saveFile(fileName, bytes)
                
                // 更新数据库
                queries.updateAudioPath(fileName, song.id)
                utils.Logger.i("Audio", "音频保存并更新数据库完成: [${song.title}] ($fileName)")
                
                // 成功后显示完成状态
                utils.NotificationHelper.showProgress(
                    id = notificationId,
                    title = "下载完成: ${song.title}",
                    content = "文件已保存至本地",
                    progress = 100,
                    max = 100,
                    ongoing = false
                )
                utils.Toast.show("${song.title} 下载完成")
            } catch (e: Exception) {
                utils.Logger.e("Audio", "下载/保存异常: ${e.message}", e)
                e.printStackTrace()
                utils.Toast.show("${song.title} 下载失败")
                utils.NotificationHelper.cancel(song.id.hashCode())
            }
        }
        
        return DownloadResult.STARTED
    }

    suspend fun deleteLocalAudio(songId: String) = withContext(IODispatcher) {
        val song: SongEntity? = queries.getSongById(songId).executeAsOneOrNull()
        song?.localAudioPath?.let { path ->
            utils.FileStore.deleteFile(path)
        }
        queries.updateAudioPath(null, songId)
    }
}
