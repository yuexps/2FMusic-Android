package data

import api.MusicApi
import database.MusicDb
import database.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import model.Song
import model.Playlist
import utils.Platform
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import database.DatabaseDriverFactory
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import utils.IODispatcher
import utils.getTimeMillis

class SqlMusicRepository(
    private val api: MusicApi,
    driverFactory: DatabaseDriverFactory
) : MusicRepository {
    private val database = MusicDb(driverFactory.createDriver())
    private val queries = database.musicDbQueries
    private val syncMutex = Mutex()

    override fun getLocalSongs(): Flow<List<Song>> {
        return queries.getAllSongs().asFlow().mapToList(IODispatcher).map { entities ->
            entities.map { it.toModel() }
        }
    }

    override fun getFavorites(): Flow<Set<String>> {
        return queries.getPlaylistSongIds("default").asFlow().mapToList(IODispatcher).map { it.toSet() }
    }

    override fun getAllPlaylists(): Flow<List<Playlist>> {
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

    override fun getSongsInPlaylist(playlistId: String): Flow<List<Song>> {
        return queries.getSongsInPlaylist(playlistId).asFlow().mapToList(IODispatcher).map { entities ->
            entities.map { it.toModel() }
        }
    }

    override suspend fun addFavorite(id: String) {
        addSongToPlaylist(id, "default")
    }

    override suspend fun removeFavorite(id: String) {
        removeSongFromPlaylist(id, "default")
    }

    override suspend fun createPlaylist(name: String): Playlist = withContext(IODispatcher) {
        try {
            val playlist = api.createPlaylist(name)
            queries.transaction {
                queries.insertPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    songCount = playlist.songCount.toLong(),
                    cover = playlist.cover,
                    isDefault = playlist.isDefault?.toLong() ?: 0L,
                    createdAt = playlist.createdAt
                )
            }
            return@withContext playlist
        } catch (e: Exception) {
            Platform.logger.e("SqlMusicRepository", "创建歌单失败", e)
            throw e
        }
    }

    override suspend fun deletePlaylist(playlistId: String) = withContext(IODispatcher) {
        if (playlistId == "default") return@withContext
        try {
            api.deletePlaylist(playlistId)
            queries.transaction {
                queries.deletePlaylist(playlistId)
                queries.removeAllSongsFromPlaylist(playlistId)
            }
        } catch (e: Exception) {
            Platform.logger.e("SqlMusicRepository", "删除歌单失败", e)
            throw e
        }
    }

    override suspend fun addSongToPlaylist(songId: String, playlistId: String) = withContext(IODispatcher) {
        try {
            api.addFavorite(songId, playlistId)
            queries.transaction {
                queries.addSongToPlaylist(playlistId, songId)
                queries.refreshPlaylistCount(playlistId)
            }
        } catch (e: Exception) {
            Platform.logger.e("SqlMusicRepository", "添加歌曲到歌单失败", e)
            throw e
        }
    }

    override suspend fun removeSongFromPlaylist(songId: String, playlistId: String) = withContext(IODispatcher) {
        try {
            api.removeFavorite(songId, playlistId)
            queries.transaction {
                queries.removeSongFromPlaylist(playlistId, songId)
                queries.refreshPlaylistCount(playlistId)
            }
        } catch (e: Exception) {
            Platform.logger.e("SqlMusicRepository", "从歌单移除歌曲失败", e)
            throw e
        }
    }

    override suspend fun batchMoveSongs(songIds: List<String>, fromPlaylistId: String, toPlaylistId: String) = withContext(IODispatcher) {
        try {
            api.batchMoveSongs(songIds, fromPlaylistId, toPlaylistId)
            queries.transaction {
                songIds.forEach { songId ->
                    queries.removeSongFromPlaylist(fromPlaylistId, songId)
                    queries.addSongToPlaylist(toPlaylistId, songId)
                }
                queries.refreshPlaylistCount(fromPlaylistId)
                queries.refreshPlaylistCount(toPlaylistId)
            }
        } catch (e: Exception) {
            Platform.logger.e("SqlMusicRepository", "批量转移歌曲失败", e)
            throw e
        }
    }

    override suspend fun sync() = syncMutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                val status = api.getSystemStatus()
                val remoteVersion = status.libraryVersion.toString()
                val localVersion = queries.getMetadata("last_library_version").executeAsOneOrNull()?.value_

                if (localVersion == remoteVersion) {
                    Platform.logger.i("SqlMusicRepository", "库版本一致，跳过全量同步")
                    return@withContext
                }

                Platform.logger.i("SqlMusicRepository", "版本变更: $localVersion -> $remoteVersion, 开始差分同步...")

                val remoteSongs = api.getMusicList()
                val remoteIds = remoteSongs.map { it.id }.toSet()

                queries.transaction {
                    remoteSongs.forEach { song ->
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
                            localCoverPath = existing?.localCoverPath,
                            localLyricsPath = existing?.localLyricsPath,
                            localAudioPath = existing?.localAudioPath
                        )
                    }

                    val allLocalSongs = queries.getAllSongs().executeAsList()
                    val toDeleteIds = allLocalSongs.map { it.id }.toSet() - remoteIds
                    
                    toDeleteIds.forEach { id ->
                        val song = allLocalSongs.find { it.id == id }
                        // 同步物理清理本地残留文件
                        cleanupLocalFiles(id, song?.localAudioPath, song?.localCoverPath, song?.localLyricsPath)
                        queries.deleteSongById(id)
                    }
                }

                queries.insertMetadata("last_library_version", remoteVersion)
                Platform.logger.i("SqlMusicRepository", "数据同步完成，当前版本: $remoteVersion")

                syncMedia(remoteSongs)
                internalSyncPlaylists()
            } catch (e: Exception) {
                Platform.logger.e("SqlMusicRepository", "同步失败", e)
                throw e
            }
        }
    }

    override suspend fun syncPlaylists() = syncMutex.withLock {
        internalSyncPlaylists()
    }

    private suspend fun internalSyncPlaylists() = withContext(IODispatcher) {
        try {
            Platform.logger.i("SqlMusicRepository", "开始同步歌单列表...")
            val playlists = api.getFavoritePlaylists()
            val defaultFavIds = try {
                api.getPlaylistSongs("default")
            } catch (_: Exception) {
                emptyList()
            }

            queries.transaction {
                queries.deleteAllPlaylists()
                queries.insertPlaylist(
                    id = "default",
                    name = "我的收藏",
                    songCount = defaultFavIds.size.toLong(),
                    cover = null,
                    isDefault = 1,
                    createdAt = 0.0
                )
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
            syncPlaylistSongs("default", defaultFavIds)
            playlists.forEach { playlist ->
                if (playlist.id != "default") {
                    try {
                        val songIds = api.getPlaylistSongs(playlist.id)
                        syncPlaylistSongs(playlist.id, songIds)
                    } catch (e: Exception) {
                        Platform.logger.e("SqlMusicRepository", "歌单 [${playlist.name}] 同步失败", e)
                    }
                }
            }
        } catch (e: Exception) {
            Platform.logger.e("SqlMusicRepository", "歌单列表同步过程中发生异常", e)
        }
    }

    override suspend fun ensureDefaultPlaylistExists() = withContext(Dispatchers.Default) {
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
        }
    }

    private fun syncPlaylistSongs(playlistId: String, songIds: List<String>) {
        queries.transaction {
            queries.removeAllSongsFromPlaylist(playlistId)
            songIds.forEach { songId ->
                if (queries.getSongById(songId).executeAsOneOrNull() != null) {
                    queries.addSongToPlaylist(playlistId, songId)
                }
            }
            queries.refreshPlaylistCount(playlistId)
        }
    }

    private suspend fun syncMedia(songs: List<Song>) = withContext(Dispatchers.Default) {
        songs.forEach { song ->
            var updatedCoverPath: String? = null
            var updatedLyricsPath: String? = null
            if (utils.FileStore.getCoverPath(song.id) != null) {
                updatedCoverPath = "cover/cover_${song.id}.jpg"
            }
            if (utils.FileStore.readLyrics(song.id) != null) {
                updatedLyricsPath = "lyrics/lyrics_${song.id}.lrc"
            }
            queries.updateCoverAndLyrics(
                localCoverPath = updatedCoverPath,
                localLyricsPath = updatedLyricsPath,
                id = song.id
            )
        }
    }

    override suspend fun ensureCoverDownloaded(song: Song) = withContext(Dispatchers.Default) {
        if (utils.FileStore.getCoverPath(song.id) == null) {
            try {
                var targetUrl = song.albumArt
                if (targetUrl == null) {
                    val response = api.getAlbumArt(
                        songId = song.id,
                        title = song.title ?: "",
                        artist = song.artist ?: "",
                        filename = song.filename ?: ""
                    )
                    if (response.albumArt != null) {
                        targetUrl = response.albumArt
                    }
                }
                if (targetUrl != null) {
                    val bytes = api.downloadFile(targetUrl)
                    utils.FileStore.saveCover(song.id, bytes)
                    queries.updateCoverPath("cover/cover_${song.id}.jpg", song.id)
                }
            } catch (e: Exception) {
                Platform.logger.e("SqlMusicRepository", "封面持久化失败: ${song.title}", e)
            }
        }
    }

    override suspend fun ensureLyricsDownloaded(song: Song) = withContext(Dispatchers.Default) {
        if (utils.FileStore.readLyrics(song.id) == null) {
            try {
                val response = api.getLyrics(
                    songId = song.id,
                    title = song.title ?: "",
                    artist = song.artist ?: "",
                    filename = song.filename ?: ""
                )
                if (response.lyrics != null) {
                    utils.FileStore.saveLyrics(song.id, response.lyrics)
                    queries.updateLyricsPath("lyrics/lyrics_${song.id}.lrc", song.id)
                }
            } catch (e: Exception) {
                Platform.logger.e("SqlMusicRepository", "歌词持久化失败: ${song.title}", e)
            }
        }
    }

    private val downloadScope = kotlinx.coroutines.CoroutineScope(IODispatcher + kotlinx.coroutines.SupervisorJob())

    override fun downloadMusic(song: Song): DownloadResult {
        val fileName = "audio/audio_${song.id}.mp3"
        if (utils.FileStore.getLocalPath(fileName) != null) {
            queries.updateAudioPath(fileName, song.id)
            return DownloadResult.EXISTS
        }

        downloadScope.launch {
            try {
                val notificationId = song.id.hashCode()
                Platform.notification.showProgress(id = notificationId, title = "准备下载: ${song.title}", content = "正在连接服务器...", progress = 0, max = 0)
                var lastNotifyTime = 0L
                val bytes = api.downloadFile("/api/music/play/${song.id}") { sent, total ->
                     val now = getTimeMillis()
                     val isFinished = total > 0 && sent == total
                     val shouldNotify = isFinished || ((now - lastNotifyTime) >= 200L)
                     if (shouldNotify) {
                         lastNotifyTime = now
                         if (total > 0) {
                             val progress = (sent * 100 / total).toInt()
                             val sentMB = (sent * 10 / 1024 / 1024).toDouble() / 10.0
                             val totalMB = (total * 10 / 1024 / 1024).toDouble() / 10.0
                             Platform.notification.showProgress(id = notificationId, title = "正在下载: ${song.title}", content = "$progress% (${sentMB}MB / ${totalMB}MB)", progress = progress, max = 100)
                         } else {
                             val sentMB = (sent * 10 / 1024 / 1024).toDouble() / 10.0
                             Platform.notification.showProgress(id = notificationId, title = "正在下载: ${song.title}", content = "已接收: ${sentMB}MB", progress = 0, max = 0)
                         }
                     }
                }
                utils.FileStore.saveFile(fileName, bytes)
                queries.updateAudioPath(fileName, song.id)
                Platform.notification.showProgress(id = notificationId, title = "下载完成: ${song.title}", content = "文件已保存至本地", progress = 100, max = 100, ongoing = false)
                Platform.toast.show("${song.title} 下载完成")
            } catch (e: Exception) {
                Platform.logger.e("SqlMusicRepository", "下载/保存异常: ${e.message}", e)
                Platform.toast.show("${song.title} 下载失败")
                Platform.notification.cancel(song.id.hashCode())
            }
        }
        return DownloadResult.STARTED
    }

    override suspend fun deleteLocalAudio(songId: String) = withContext(IODispatcher) {
        val song: SongEntity? = queries.getSongById(songId).executeAsOneOrNull()
        if (song != null) {
            cleanupLocalFiles(songId, song.localAudioPath, song.localCoverPath, song.localLyricsPath)
            
            // 更新数据库状态：本地音频已移除，但保留封面/歌词路径记录（如果物理清理失败或逻辑允许保留记录，这里我们选择全部置空以保持一致性）
            queries.updateAudioPath(null, songId)
            queries.updateCoverAndLyrics(null, null, songId)
        }
    }

    /**
     * 物理清理本地媒体资源文件
     */
    private fun cleanupLocalFiles(songId: String, audioPath: String?, coverPath: String?, lyricsPath: String?) {
        // 1. 清理音频文件
        audioPath?.let { path ->
            Platform.logger.i("SqlMusicRepository", "清理本地音频文件: $path")
            utils.FileStore.deleteFile(path)
        }
        
        // 2. 清理封面文件
        // 即使数据库记录为空，也尝试根据默认命名规范清理，确保万无一失
        val coverFile = coverPath ?: "cover/cover_$songId.jpg"
        Platform.logger.i("SqlMusicRepository", "尝试清理本地封面: $coverFile")
        utils.FileStore.deleteFile(coverFile)
        
        // 3. 清理歌词文件
        val lyricsFile = lyricsPath ?: "lyrics/lyrics_$songId.lrc"
        Platform.logger.i("SqlMusicRepository", "尝试清理本地歌词: $lyricsFile")
        utils.FileStore.deleteFile(lyricsFile)
    }

    override suspend fun clearMetadata(song: Song) {
        Platform.logger.i("SqlMusicRepository", "清理本地元数据缓存: ${song.title}")
        cleanupLocalFiles(song.id, audioPath = null, coverPath = song.localCoverPath, lyricsPath = song.localLyricsPath)
        queries.updateCoverAndLyrics(null, null, song.id)
    }

    private fun SongEntity.toModel(): Song {
        return Song(
            id = id,
            filename = filename,
            title = title,
            artist = artist,
            album = album,
            mtime = mtime,
            size = size,
            albumArt = albumArt,
            localCoverPath = localCoverPath,
            localLyricsPath = localLyricsPath,
            localAudioPath = localAudioPath
        )
    }
}
