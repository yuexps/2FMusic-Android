package data

import api.MusicApi
import api.GlobalState
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import utils.IODispatcher
import utils.getTimeMillis

class SqlMusicRepository(
    private val api: MusicApi,
    driverFactory: DatabaseDriverFactory
) : MusicRepository {
    private val database = MusicDb(driverFactory.createDriver())
    private val queries = database.musicDbQueries
    private val syncMutex = Mutex()
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(IODispatcher)

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
        return queries.getSongsInPlaylist(playlistId)
            .asFlow()
            .mapToList(IODispatcher)
            .map { entities ->
                entities.map { it.toModel() }
            }
            .onStart {
                repositoryScope.launch {
                    try {
                        val songIds = api.getPlaylistSongs(playlistId)
                        syncPlaylistSongs(playlistId, songIds)
                        queries.refreshPlaylistCount(playlistId)
                    } catch (e: Exception) {
                        Platform.logger.e("SqlMusicRepository", "按需同步歌单歌曲失败: $playlistId", e)
                    }
                }
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
                // 在跟服务器同步前，先自检同步本地物理文件与数据库的一致性
                scanAndSyncLocalFiles()

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

                // 仅检索必要的 ID 和本地物理路径信息，降低堆内存大实体分配开销
                val allLocalPaths = queries.getAllSongPaths().executeAsList()
                val localIds = allLocalPaths.map { it.id }.toSet()
                val toDeleteIds = localIds - remoteIds

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

                    toDeleteIds.forEach { id ->
                        queries.deleteSongById(id)
                    }
                }

                // 在数据库大事务外部，安全且非阻塞地执行过期本地文件物理擦除，保证数据一致性并释放数据库写锁
                toDeleteIds.forEach { id ->
                    val pathRecord = allLocalPaths.find { it.id == id }
                    cleanupLocalFiles(id, pathRecord?.localAudioPath, pathRecord?.localCoverPath, pathRecord?.localLyricsPath)
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
            // 已移除其它非默认歌单的瀑布式循环拉取，改在 getSongsInPlaylist 进行按需惰性加载。
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
        if (utils.Platform.hasStoragePermission?.invoke() == false) return@withContext
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
        if (utils.Platform.hasStoragePermission?.invoke() == false) return@withContext
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
    private val downloadSemaphore = Semaphore(3) // 限制最大并发下载数为 3

    override fun downloadMusic(song: Song): DownloadResult {
        // 前置权限拦截：如果未授权，主动触发弹窗申请流程并中止当前下载
        if (utils.Platform.hasStoragePermission?.invoke() == false) {
            GlobalState.updateShowStoragePermissionDialog(true)
            Platform.toast.show("需要存储权限以保存歌曲，请授予权限后再试")
            return DownloadResult.ERROR
        }

        val fileName = "audio/audio_${song.id}.mp3"
        if (utils.FileStore.getLocalPath(fileName) != null) {
            queries.updateAudioPath(fileName, song.id)
            return DownloadResult.EXISTS
        }

        downloadScope.launch {
            val notificationId = song.id.hashCode()
            Platform.notification.showProgress(id = notificationId, title = "准备下载: ${song.title}", content = "正在排队中...", progress = 0, max = 0)
            
            downloadSemaphore.withPermit {
                try {
                    Platform.notification.showProgress(id = notificationId, title = "正在下载: ${song.title}", content = "正在连接服务器...", progress = 0, max = 0)
                    var lastNotifyTime = 0L
                    api.downloadFileAsStream("/api/music/play/${song.id}", fileName) { sent, total ->
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
                    queries.updateAudioPath(fileName, song.id)
                    Platform.notification.showProgress(id = notificationId, title = "下载完成: ${song.title}", content = "文件已保存至本地", progress = 100, max = 100, ongoing = false)
                    Platform.toast.show("${song.title} 下载完成")
                } catch (e: Exception) {
                    Platform.logger.e("SqlMusicRepository", "下载/保存异常: ${e.message}", e)
                    Platform.toast.show("${song.title} 下载失败")
                    Platform.notification.cancel(song.id.hashCode())
                }
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

    override suspend fun scanAndSyncLocalFiles() = withContext(Dispatchers.Default) {
        // 前置权限拦截：若无存储权限，严禁执行物理文件校验，防止误清空数据库中已下载的路径记录
        if (utils.Platform.hasStoragePermission?.invoke() == false) {
            Platform.logger.i("SqlMusicRepository", "无存储权限，跳过物理缓存文件双向同步")
            return@withContext
        }

        try {
            Platform.logger.i("SqlMusicRepository", "开始本地缓存文件与数据库双向扫描同步...")

            // 1. 校验并清理物理丢失的媒体文件索引记录
            val allLocalPaths = queries.getAllSongPaths().executeAsList()
            queries.transaction {
                allLocalPaths.forEach { record ->
                    // 校验音频
                    record.localAudioPath?.let { audio ->
                        if (utils.FileStore.getLocalPath(audio) == null) {
                            queries.updateAudioPath(localAudioPath = null, id = record.id)
                            Platform.logger.i("SqlMusicRepository", "歌曲 ID ${record.id} 本地音频物理丢失，已重置数据库")
                        }
                    }
                    // 校验封面
                    record.localCoverPath?.let { cover ->
                        if (utils.FileStore.getLocalPath(cover) == null) {
                            queries.updateCoverPath(localCoverPath = null, id = record.id)
                            Platform.logger.i("SqlMusicRepository", "歌曲 ID ${record.id} 本地封面物理丢失，已重置数据库")
                        }
                    }
                    // 校验歌词
                    record.localLyricsPath?.let { lyrics ->
                        if (utils.FileStore.getLocalPath(lyrics) == null) {
                            queries.updateLyricsPath(localLyricsPath = null, id = record.id)
                            Platform.logger.i("SqlMusicRepository", "歌曲 ID ${record.id} 本地歌词物理丢失，已重置数据库")
                        }
                    }
                }
            }

            // 2. 扫描并自动关联未索引的物理常规缓存资源
            val physicalAudios = utils.FileStore.listPhysicalFiles("audio")
            val physicalCovers = utils.FileStore.listPhysicalFiles("cover")
            val physicalLyrics = utils.FileStore.listPhysicalFiles("lyrics")

            queries.transaction {
                // 自动关联物理音频
                physicalAudios.forEach { fileName ->
                    val songId = extractIdFromFileName(fileName, "audio_", ".mp3")
                    if (songId != null) {
                        val existing = queries.getSongById(songId).executeAsOneOrNull()
                        if (existing != null && existing.localAudioPath == null) {
                            queries.updateAudioPath(localAudioPath = "audio/$fileName", id = songId)
                            Platform.logger.i("SqlMusicRepository", "关联补齐本地音频文件: $fileName")
                        }
                    }
                }

                // 自动关联物理封面
                physicalCovers.forEach { fileName ->
                    val songId = extractIdFromFileName(fileName, "cover_", ".jpg")
                    if (songId != null) {
                        val existing = queries.getSongById(songId).executeAsOneOrNull()
                        if (existing != null && existing.localCoverPath == null) {
                            queries.updateCoverPath(localCoverPath = "cover/$fileName", id = songId)
                            Platform.logger.i("SqlMusicRepository", "关联补齐本地封面文件: $fileName")
                        }
                    }
                }

                // 自动关联物理歌词
                physicalLyrics.forEach { fileName ->
                    val songId = extractIdFromFileName(fileName, "lyrics_", ".lrc")
                    if (songId != null) {
                        val existing = queries.getSongById(songId).executeAsOneOrNull()
                        if (existing != null && existing.localLyricsPath == null) {
                            queries.updateLyricsPath(localLyricsPath = "lyrics/$fileName", id = songId)
                            Platform.logger.i("SqlMusicRepository", "关联补齐本地歌词文件: $fileName")
                        }
                    }
                }
            }
            Platform.logger.i("SqlMusicRepository", "本地缓存与数据库双向扫描同步完成")
        } catch (e: Exception) {
            Platform.logger.e("SqlMusicRepository", "本地文件双向同步发生异常", e)
        }
    }

    private fun extractIdFromFileName(name: String, prefix: String, suffix: String): String? {
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) return null
        return name.substring(prefix.length, name.length - suffix.length)
    }

    override fun getPlayHistory(): Flow<List<Song>> {
        return queries.getLocalPlayHistory()
            .asFlow()
            .mapToList(IODispatcher)
            .map { list ->
                list.map { entity ->
                    Song(
                        id = entity.id,
                        filename = entity.filename,
                        title = entity.title,
                        artist = entity.artist,
                        album = entity.album,
                        mtime = entity.playTime.toDouble(),
                        size = entity.size,
                        albumArt = entity.albumArt,
                        localCoverPath = entity.localCoverPath,
                        localLyricsPath = entity.localLyricsPath,
                        localAudioPath = entity.localAudioPath
                    )
                }
            }
            .onStart {
                repositoryScope.launch {
                    try {
                        val historyList = api.getHistory()
                        queries.transaction {
                            queries.deleteAllPlayHistory()
                            historyList.forEach { history ->
                                val songDto = history.song
                                val existing = queries.getSongById(songDto.id).executeAsOneOrNull()
                                if (existing == null) {
                                    queries.insertSong(
                                        id = songDto.id,
                                        filename = songDto.filename,
                                        title = songDto.title,
                                        artist = songDto.artist,
                                        album = songDto.album,
                                        mtime = songDto.mtime,
                                        size = songDto.size,
                                        albumArt = songDto.albumArt,
                                        localCoverPath = null,
                                        localLyricsPath = null,
                                        localAudioPath = null
                                    )
                                }
                                queries.insertPlayHistory(songDto.id, history.time)
                            }
                        }
                    } catch (e: Exception) {
                        Platform.logger.e("SqlMusicRepository", "按需同步播放记录失败", e)
                    }
                }
            }
    }

    override suspend fun removeHistory(songId: String, playTime: Long) {
        withContext(IODispatcher) {
            try {
                queries.deletePlayHistory(songId)
            } catch (e: Exception) {
                Platform.logger.e("SqlMusicRepository", "本地删除播放记录失败", e)
            }
            api.removeHistory(songId, playTime)
        }
    }

    override suspend fun clearHistory() {
        withContext(IODispatcher) {
            try {
                queries.deleteAllPlayHistory()
            } catch (e: Exception) {
                Platform.logger.e("SqlMusicRepository", "本地清空播放记录失败", e)
            }
            api.clearHistory()
        }
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
