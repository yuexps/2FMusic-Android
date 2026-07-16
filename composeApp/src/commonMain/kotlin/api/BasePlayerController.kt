package api
 
import kotlinx.coroutines.flow.MutableStateFlow
import model.PlayMode
import model.PlaybackState
import model.Song

abstract class BasePlayerController : PlayerController {
    override val currentSong = MutableStateFlow<Song?>(null)
    override val playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playMode = MutableStateFlow(PlayMode.LIST_LOOP)
    override val progress = MutableStateFlow(0f)
    override val duration = MutableStateFlow(0L)
    override val currentPosition = MutableStateFlow(0L)
    override val playlist = MutableStateFlow<List<Song>>(emptyList())
    override val currentIndex = MutableStateFlow(-1)

    override fun isEqualizerSupported(): Boolean = false
    override fun isEqualizerEnabled(): Boolean = false
    override fun setEqualizerEnabled(enabled: Boolean) {}
    override fun getEqualizerBands(): List<String> = emptyList()
    override fun getEqualizerBandLevels(): List<Int> = emptyList()
    override fun setEqualizerBandLevel(band: Int, level: Int) {}
    override fun getEstimatedShutdownTime(minutes: Int): String = "${minutes} 分钟后"
}
