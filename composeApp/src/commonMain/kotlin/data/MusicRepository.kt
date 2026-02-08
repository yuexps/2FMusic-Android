package data

import api.MusicApi
import database.MusicDb
import database.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import model.Song
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import database.DatabaseDriverFactory

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
        return queries.getAllSongs().asFlow().mapToList(Dispatchers.IO)
    }

    /**
     * 核心同步逻辑：本地差分更新
     */
    suspend fun sync() = withContext(Dispatchers.IO) {
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
                        localCoverPath = null, // 后续由 FileStore 填充
                        localLyricsPath = null
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

        } catch (e: Exception) {
            println("[MusicRepository] 同步失败: ${e.message}")
            throw e
        }
    }

    /**
     * 静默同步封面和歌词
     */
    private suspend fun syncMedia(songs: List<Song>) = withContext(Dispatchers.IO) {
        songs.forEach { song ->
            var updatedCoverPath: String? = null
            var updatedLyricsPath: String? = null
            var needsUpdate = false

            // 1. 同步封面
            if (song.hasCover != 0 && song.albumArt != null) {
                val fileName = "cover_${song.id}.jpg"
                if (utils.FileStore.getLocalPath(fileName) == null) {
                    try {
                        val bytes = api.downloadFile(song.albumArt)
                        utils.FileStore.saveFile(fileName, bytes)
                        updatedCoverPath = fileName
                        needsUpdate = true
                    } catch (e: Exception) {
                        println("[MusicRepository] 封面下载失败: ${song.title}")
                    }
                } else {
                    updatedCoverPath = fileName
                }
            }

            // 2. 同步歌词
            if (utils.FileStore.readLyrics(song.id) == null) {
                try {
                    val query = "?title=${song.title ?: ""}&artist=${song.artist ?: ""}"
                    val response = api.getLyrics(query)
                    if (response.success && response.lyrics != null) {
                        utils.FileStore.saveLyrics(song.id, response.lyrics)
                        updatedLyricsPath = "lyrics_${song.id}.lrc"
                        needsUpdate = true
                    }
                } catch (e: Exception) {
                    // 歌词下载失败不视为致命错误，可能真的没歌词
                }
            } else {
                updatedLyricsPath = "lyrics_${song.id}.lrc"
            }

            // 3. 如果路径有变，更新数据库（或者只要是同步过程中发现有本地文件就更新一下确保一致性）
            if (needsUpdate) {
                queries.updateLocalPaths(
                    localCoverPath = updatedCoverPath,
                    localLyricsPath = updatedLyricsPath,
                    id = song.id
                )
            }
        }
    }
}
