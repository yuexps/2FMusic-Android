package api

import kotlinx.coroutines.flow.StateFlow
import model.PlayMode
import model.PlaybackState
import model.Song

interface PlayerController {
    val currentSong: StateFlow<Song?>
    val playbackState: StateFlow<PlaybackState>
    val playMode: StateFlow<PlayMode>
    val progress: StateFlow<Float> // 0.0 to 1.0
    val duration: StateFlow<Long> // in ms
    val currentPosition: StateFlow<Long> // in ms
    val playlist: StateFlow<List<Song>>
    val currentIndex: StateFlow<Int>

    fun play(song: Song)
    fun pause()
    fun resume()
    fun stop()
    fun next()
    fun previous()
    fun seekTo(position: Long)
    fun setPlayMode(mode: PlayMode)
    fun setPlaylist(songs: List<Song>)
    fun playAtIndex(index: Int)

    // 均衡器相关音效控制
    fun isEqualizerSupported(): Boolean
    fun isEqualizerEnabled(): Boolean
    fun setEqualizerEnabled(enabled: Boolean)
    fun getEqualizerBands(): List<String>
    fun getEqualizerBandLevels(): List<Int>
    fun setEqualizerBandLevel(band: Int, level: Int)
}


