package api

import config.ConfigManager
import io.ktor.client.* 
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import model.*

class MusicApi {
    private val baseUrl: String
        get() = ConfigManager.getBaseUrl()

    private val client = httpClient() {
        expectSuccess = true // 强制校验 HTTP 状态码，401 等错误将抛出 ClientRequestException

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 5000
            connectTimeoutMillis = 3000
            socketTimeoutMillis = 5000
        }

        // 在所有请求中自动添加 X-Password header
        defaultRequest {
            val hash = ConfigManager.getPasswordHash()
            if (hash != null) {
                header("X-Password", hash)
            }
        }
    }

    private fun <T> ApiResponse<T>.ensureSuccess(): T? {
        if (!success) {
            throw Exception(message ?: "Unknown API error")
        }
        return data
    }

    // --- 音乐库 ---
    suspend fun getMusicList(): List<Song> {
        val response: ApiResponse<List<Song>> = client.get("$baseUrl/api/music").body()
        return response.ensureSuccess() ?: emptyList()
    }

    suspend fun deleteFile(songId: String): ApiResponse<Unit> {
        return client.delete("$baseUrl/api/music/delete/${songId.encodeURLParameter()}").body()
    }

    suspend fun importPath(path: String): ApiResponse<Unit> {
        return client.post("$baseUrl/api/music/import_path") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("path" to path))
        }.body()
    }

    // --- 歌词 & 封面 ---
    suspend fun getLyrics(title: String, artist: String): LyricsResponse {
        val response: LyricsResponse = client.get("$baseUrl/api/music/lyrics") {
            parameter("title", title)
            parameter("artist", artist)
        }.body()
        if (!response.success) throw Exception(response.message ?: "Lyrics fetch failed")
        return response
    }

    suspend fun getAlbumArt(title: String, artist: String, filename: String): model.AlbumArtResponse {
        return client.get("$baseUrl/api/music/album-art") {
            parameter("title", title)
            parameter("artist", artist)
            parameter("filename", filename)
        }.body()
    }

    // --- 挂载点 ---
    suspend fun getMountPoints(): List<MountPoint> {
        val response: ApiResponse<List<MountPoint>> = client.get("$baseUrl/api/mount_points").body()
        return response.data ?: emptyList()
    }

    suspend fun addMountPoint(path: String): ApiResponse<Unit> {
        return client.post("$baseUrl/api/mount_points") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("path" to path))
        }.body()
    }

    // --- 收藏夹 ---
    suspend fun getFavoritePlaylists(): List<Playlist> {
        val response: ApiResponse<List<Playlist>> = client.get("$baseUrl/api/favorite_playlists").body()
        return response.ensureSuccess() ?: emptyList()
    }

    suspend fun getPlaylistSongs(playlistId: String): List<String> {
        val response: ApiResponse<List<String>> = client.get("$baseUrl/api/favorite_playlists/${playlistId.encodeURLParameter()}/songs").body()
        return response.data ?: emptyList()
    }

    suspend fun addFavorite(songId: String, playlistId: String = "default"): ApiResponse<Unit> {
        return client.post("$baseUrl/api/favorites") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("song_id" to songId, "playlist_id" to playlistId))
        }.body()
    }

    suspend fun removeFavorite(songId: String, playlistId: String = "default"): ApiResponse<Unit> {
        return client.delete("$baseUrl/api/favorites") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("song_id" to songId, "playlist_id" to playlistId))
        }.body()
    }

    // --- 系统 ---
    suspend fun getSystemStatus(): SystemStatus {
        return client.get("$baseUrl/api/system/status").body()
    }

    suspend fun downloadFile(url: String, onProgress: ((bytesSentTotal: Long, contentLength: Long) -> Unit)? = null): ByteArray {
        val fullUrl = if (url.startsWith("http")) url else "$baseUrl$url"
        val response = client.get(fullUrl) {
            timeout {
                requestTimeoutMillis = 300000 // 5 分钟下载时长限制
                connectTimeoutMillis = 15000  // 15 秒连接超时
                socketTimeoutMillis = 60000   // 1 分钟无数据传输限制
            }
            onDownload { bytesSentTotal, contentLength ->
                onProgress?.invoke(bytesSentTotal, contentLength ?: 0L)
            }
        }
        return response.body()
    }

}
