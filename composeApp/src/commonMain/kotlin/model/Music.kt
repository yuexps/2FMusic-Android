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
    val isDefault: Int = 0,
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
    val success: Boolean,
    val lyrics: String? = null,
    val message: String? = null
)

@Serializable
data class MountPoint(
    val path: String = "",
    @SerialName("created_at")
    val createdAt: Double? = null
)

@Serializable
data class AlbumArtResponse(
    val success: Boolean,
    @SerialName("album_art")
    val albumArt: String? = null,
    val error: String? = null
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

