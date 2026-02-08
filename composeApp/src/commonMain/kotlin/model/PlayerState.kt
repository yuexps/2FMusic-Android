package model

import kotlinx.serialization.Serializable

@Serializable
enum class PlayMode {
    LIST_LOOP,
    RANDOM,
    SINGLE_LOOP
}

@Serializable
enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

@Serializable
data class PlaybackStateData(
    val currentSongId: String? = null,
    val position: Long = 0L,
    val playlist: List<Song> = emptyList(),
    val playMode: PlayMode = PlayMode.LIST_LOOP
)
