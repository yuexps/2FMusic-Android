package data

import kotlinx.coroutines.flow.Flow
import model.Song
import model.Playlist

enum class DownloadResult {
    STARTED,
    EXISTS,
    ERROR
}

interface MusicRepository {
    fun getLocalSongs(): Flow<List<Song>>
    fun getFavorites(): Flow<Set<String>>
    fun getAllPlaylists(): Flow<List<Playlist>>
    fun getSongsInPlaylist(playlistId: String): Flow<List<Song>>
    suspend fun addFavorite(id: String)
    suspend fun removeFavorite(id: String)
    suspend fun sync()
    suspend fun syncPlaylists()
    suspend fun ensureDefaultPlaylistExists()
    suspend fun ensureCoverDownloaded(song: Song)
    suspend fun ensureLyricsDownloaded(song: Song)
    fun downloadMusic(song: Song): DownloadResult
    suspend fun deleteLocalAudio(songId: String)
}
