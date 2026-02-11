package data

import api.MusicApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import model.Song
import model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.Platform
import utils.FileStore
import kotlinx.browser.window
import io.ktor.http.*

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""
(url, filename) => {
    fetch(url)
        .then(response => {
            if (!response.ok) throw new Error('Network response was not ok');
            return response.blob();
        })
        .then(blob => {
            const blobUrl = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.style.display = 'none';
            a.href = blobUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(blobUrl);
            setTimeout(() => document.body.removeChild(a), 100);
        })
        .catch(error => {
            console.error('Download error:', error);
            alert('下载失败: ' + error.message);
        });
}
""")
private external fun triggerDownload(url: String, filename: String)

class ApiMusicRepository(
    private val api: MusicApi
) : MusicRepository {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    private val _playlists = MutableStateFlow<List<Playlist>>(listOf(createDefaultPlaylist()))

    private fun createDefaultPlaylist() = Playlist(
        id = "default",
        name = "我的收藏",
        songCount = 0,
        cover = null,
        isDefault = 1,
        createdAt = 0.0
    )

    override fun getLocalSongs(): Flow<List<Song>> = _songs

    override fun getFavorites(): Flow<Set<String>> = _favorites

    override fun getAllPlaylists(): Flow<List<Playlist>> = _playlists

    override fun getSongsInPlaylist(playlistId: String): Flow<List<Song>> = flow {
        try {
            val songIds = if (playlistId == "default") _favorites.value else api.getPlaylistSongs(playlistId).toSet()
            val allSongs = _songs.value.takeIf { it.isNotEmpty() } ?: api.getMusicList()
            emit(allSongs.filter { it.id in songIds })
        } catch (e: Exception) {
            Platform.logger.e("ApiMusicRepository", "获取歌单内容失败", e)
            emit(emptyList())
        }
    }

    override suspend fun addFavorite(id: String) {
        _favorites.value += id
        updateDefaultPlaylistCount()
    }

    override suspend fun removeFavorite(id: String) {
        _favorites.value -= id
        updateDefaultPlaylistCount()
    }

    private fun updateDefaultPlaylistCount() {
        val current = _playlists.value.toMutableList()
        val index = current.indexOfFirst { it.id == "default" }
        if (index != -1) {
            current[index] = current[index].copy(songCount = _favorites.value.size)
            _playlists.value = current
        }
    }

    override suspend fun sync() {
        refreshAll()
    }

    override suspend fun syncPlaylists() {
        refreshPlaylists()
    }

    private suspend fun refreshAll() {
        refreshSongs()
        refreshPlaylists()
        refreshFavorites()
    }

    private suspend fun refreshSongs() {
        try {
            val songs = api.getMusicList()
            _songs.value = songs
        } catch (e: Exception) {
            Platform.logger.e("ApiMusicRepository", "刷新歌曲列表失败", e)
            throw e
        }
    }

    private suspend fun refreshPlaylists() {
        try {
            val playlists = api.getFavoritePlaylists()
            val hasDefault = playlists.any { it.id == "default" }
            _playlists.value = if (hasDefault) playlists else listOf(createDefaultPlaylist().copy(songCount = _favorites.value.size)) + playlists
        } catch (e: Exception) {
            Platform.logger.e("ApiMusicRepository", "刷新歌单失败", e)
            // 失败时也保证默认歌单存在
            if (_playlists.value.isEmpty()) {
                _playlists.value = listOf(createDefaultPlaylist().copy(songCount = _favorites.value.size))
            }
        }
    }

    private suspend fun refreshFavorites() {
        try {
            val favIds = api.getPlaylistSongs("default").toSet()
            _favorites.value = favIds
            updateDefaultPlaylistCount()
        } catch (e: Exception) {
            Platform.logger.e("ApiMusicRepository", "刷新收藏夹失败", e)
        }
    }

    override suspend fun ensureDefaultPlaylistExists() {
        if (_playlists.value.none { it.id == "default" }) {
            _playlists.value = listOf(createDefaultPlaylist()) + _playlists.value
        }
    }

    override suspend fun ensureCoverDownloaded(song: Song) {
        if (FileStore.getCoverPath(song.id) == null) {
            try {
                var targetUrl = song.albumArt
                if (targetUrl == null) {
                    val response = api.getAlbumArt(
                        title = song.title ?: "",
                        artist = song.artist ?: "",
                        filename = song.filename ?: ""
                    )
                    if (response.success && response.albumArt != null) {
                        targetUrl = response.albumArt
                    }
                }
                if (targetUrl != null) {
                    val bytes = api.downloadFile(targetUrl)
                    FileStore.saveCover(song.id, bytes)
                }
            } catch (e: Exception) {
                Platform.logger.e("ApiMusicRepository", "封面加载失败: ${song.title}", e)
            }
        }
    }

    override suspend fun ensureLyricsDownloaded(song: Song) {
        if (FileStore.readLyrics(song.id) == null) {
            try {
                val response = api.getLyrics(
                    title = song.title ?: "",
                    artist = song.artist ?: ""
                )
                if (response.success && response.lyrics != null) {
                    FileStore.saveLyrics(song.id, response.lyrics)
                }
            } catch (e: Exception) {
                Platform.logger.e("ApiMusicRepository", "歌词加载失败: ${song.title}", e)
            }
        }
    }

    override fun downloadMusic(song: Song): DownloadResult {
        try {
            val encodedId = song.id.encodeURLParameter()
            val baseUrl = Platform.config.getBaseUrl()
            var songUrl = "$baseUrl/api/music/play/$encodedId"
            val hash = Platform.config.getPasswordHash()
            if (hash != null) {
                songUrl += "?auth=$hash"
            }
            
            // 使用 JS Interop 触发强制下载
            val downloadName = "${song.artist} - ${song.title}.${song.filename?.substringAfterLast('.', "mp3") ?: "mp3"}"
            triggerDownload(songUrl, downloadName)
            
            Platform.logger.i("ApiMusicRepository", "触发浏览器强制下载: ${song.title}")
            return DownloadResult.STARTED
        } catch (e: Exception) {
            Platform.logger.e("ApiMusicRepository", "浏览器下载启动失败", e)
            return DownloadResult.ERROR
        }
    }

    override suspend fun deleteLocalAudio(songId: String) {
        // No-op for Wasm
    }
}
