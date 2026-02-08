package api

import hideLoading
import io.ktor.http.*
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import model.PlayMode
import model.PlaybackState
import model.Song
import org.w3c.dom.HTMLAudioElement

class WasmPlayerController : PlayerController {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val audio = window.document.createElement("audio") as HTMLAudioElement
    
    override val currentSong = MutableStateFlow<Song?>(null)
    override val playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playMode = MutableStateFlow(PlayMode.LIST_LOOP)
    override val progress = MutableStateFlow(0f)
    override val duration = MutableStateFlow(0L)
    override val currentPosition = MutableStateFlow(0L)
    
    override val playlist = MutableStateFlow<List<Song>>(emptyList())
    override val currentIndex = MutableStateFlow(-1)

    init {
        setupListeners()
        startProgressUpdater()
    }

    private fun setupListeners() {
        audio.addEventListener("play") {
            playbackState.value = PlaybackState.PLAYING
            hideLoading()
        }
        audio.addEventListener("pause") {
            playbackState.value = PlaybackState.PAUSED
        }
        audio.addEventListener("ended") {
            next()
        }
        audio.addEventListener("loadedmetadata") {
            duration.value = (audio.duration * 1000).toLong()
        }
        audio.addEventListener("error") {
            println("[WasmPlayer] Audio error occurred")
            playbackState.value = PlaybackState.ERROR
        }
    }

    private fun startProgressUpdater() {
        scope.launch {
            while (true) {
                if (playbackState.value == PlaybackState.PLAYING) {
                    val current = (audio.currentTime * 1000).toLong()
                    currentPosition.value = current
                    if (duration.value > 0) {
                        progress.value = current.toFloat() / duration.value
                    }
                }
                delay(500)
            }
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    override fun play(song: Song) {
        currentSong.value = song
        // 使用正确的播放端点并对 ID 进行编码
        val encodedId = song.id.encodeURLParameter()
        val baseUrl = config.ConfigManager.getBaseUrl()
        var songUrl = "$baseUrl/api/music/play/$encodedId"
        val hash = config.ConfigManager.getPasswordHash()
        if (hash != null) {
             songUrl += "?auth=$hash"
        }
        
        println("[WasmPlayer] Playing: $songUrl")
        audio.src = songUrl
        audio.play()
        
        // 更新当前索引
        val index = playlist.value.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentIndex.value = index
        }
    }

    override fun pause() {
        audio.pause()
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    override fun resume() {
        audio.play()
    }

    override fun stop() {
        audio.pause()
        audio.currentTime = 0.0
        playbackState.value = PlaybackState.IDLE
    }

    override fun next() {
        if (playlist.value.isEmpty()) return
        currentIndex.value = (currentIndex.value + 1) % playlist.value.size
        play(playlist.value[currentIndex.value])
    }

    override fun previous() {
        if (playlist.value.isEmpty()) return
        currentIndex.value = if (currentIndex.value <= 0) playlist.value.size - 1 else currentIndex.value - 1
        play(playlist.value[currentIndex.value])
    }

    override fun seekTo(position: Long) {
        audio.currentTime = position / 1000.0
    }

    override fun setPlayMode(mode: PlayMode) {
        playMode.value = mode
    }

    override fun setPlaylist(songs: List<Song>) {
        playlist.value = songs
    }

    override fun playAtIndex(index: Int) {
        if (index in playlist.value.indices) {
            currentIndex.value = index
            play(playlist.value[index])
        }
    }
}

actual val GlobalPlayerController: PlayerController = WasmPlayerController()
