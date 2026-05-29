package model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
data class Song(
    val id: String = "",
    val filename: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val mtime: Double? = null,
    val size: Long? = null,
    @SerialName("album_art")
    val albumArt: String? = null,
    val localCoverPath: String? = null,
    val localLyricsPath: String? = null,
    val localAudioPath: String? = null
)


@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val cover: String? = null,
    @SerialName("is_default")
    val isDefault: Int? = 0,
    @SerialName("song_count")
    val songCount: Int = 0,
    @SerialName("created_at")
    val createdAt: Double? = null
)


@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null
)

@Serializable
data class LyricsResponse(
    val lyrics: String? = null,
    val success: Boolean = true
)


@Serializable
data class AlbumArtResponse(
    @SerialName("album_art")
    val albumArt: String? = null,
    val success: Boolean = true
)


@Serializable
data class SystemStatus(
    @SerialName("is_scraping")
    val isScraping: Boolean = false,
    @SerialName("library_version")
    val libraryVersion: Double = 0.0,
    @SerialName("music_count")
    val musicCount: Int = 0,
    @SerialName("playlist_count")
    val playlistCount: Int = 0,
    val version: String = "",
    val platform: String = ""
)

@Serializable
data class NeteaseSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String? = null,
    val duration: Double? = null,
    @SerialName("is_vip")
    val isVip: Boolean = false,
    val level: String? = null,
    @SerialName("max_level")
    val maxLevel: String? = null,
    val size: Long? = null
)

@Serializable
data class NeteaseQrCode(
    val unikey: String,
    val qrimg: String
)

@Serializable
data class NeteaseLoginStatus(
    @SerialName("logged_in")
    val loggedIn: Boolean,
    val nickname: String? = null,
    @SerialName("user_id")
    val userId: Long? = null,
    val avatar: String? = null,
    @SerialName("is_vip")
    val isVip: Boolean = false
)

@Serializable
data class NeteaseConfig(
    @SerialName("download_dir")
    val downloadDir: String,
    @SerialName("api_base")
    val apiBase: String,
    @SerialName("max_concurrent")
    val maxConcurrent: Int? = null,
    val quality: String? = null
)

@Serializable
data class NeteaseResolveResult(
    val type: String, // "playlist" | "song"
    val id: String,
    val name: String? = null,
    val data: List<NeteaseSong> = emptyList()
)

@Serializable
data class NeteaseContainerStatus(
    @SerialName("docker_installed")
    val dockerInstalled: Boolean,
    @SerialName("container_exists")
    val containerExists: Boolean,
    @SerialName("container_running")
    val containerRunning: Boolean
)

@Serializable
data class NeteaseInstallStatus(
    val status: String,
    val progress: Int,
    val step: String? = null,
    val error: String? = null
)

@Serializable
data class NeteaseDownloadTask(
    @SerialName("task_id")
    val taskId: String
)

@Serializable
data class NeteaseTaskDetail(
    @SerialName("task_id")
    val taskId: String,
    val status: String,
    val progress: Int,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("completed_at")
    val completedAt: Long? = null,
    val message: String? = null
)

@Serializable
data class PlayHistory(
    val id: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("album_art")
    val albumArt: String? = null,
    @SerialName("play_count")
    val playCount: Int = 0,
    @SerialName("last_played")
    val lastPlayed: Long = 0L
)

@Serializable
data class PreferenceValue(
    val value: String
)

// 广播事件模型
@Serializable
data class LibraryChangedEvent(
    @SerialName("library_version")
    val libraryVersion: Double
)

@Serializable
data class ScanStatusEvent(
    val scanning: Boolean,
    @SerialName("is_scraping")
    val isScraping: Boolean,
    val total: Int,
    val processed: Int,
    val failed: Int,
    @SerialName("current_file")
    val currentFile: String? = null,
    @SerialName("current_path")
    val currentPath: String? = null,
    @SerialName("library_version")
    val libraryVersion: Double
)

@Serializable
data class DownloadStatusEvent(
    @SerialName("task_id")
    val taskId: String,
    val status: String,
    val progress: Int,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("completed_at")
    val completedAt: Long? = null,
    val message: String? = null
)

@Serializable
data class NeteaseLoginStatusEvent(
    val key: String,
    val status: String,
    val message: String? = null
)


