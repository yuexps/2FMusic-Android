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
import okio.Path.Companion.toPath
import okio.buffer

class WasmPlayerController : PlayerController {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audio = window.document.createElement("audio") as HTMLAudioElement
    private var currentBlobUrl: String? = null
    private val fs = utils.platformFileSystem
    
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
            utils.Logger.i("WasmPlayer", "音频播放结束，准备下一曲")
            next()
        }
        audio.addEventListener("loadedmetadata") {
            duration.value = (audio.duration * 1000).toLong()
            utils.Logger.i("WasmPlayer", "元数据加载完成，时长: ${duration.value}ms")
        }
        audio.addEventListener("error") {
            val song = currentSong.value
            val error = audio.error
            val msg = "播放器错误: 代码=${error?.code}"
            utils.Logger.e("WasmPlayer", msg)
            
            val displayMessage = if (song != null) {
                "播放歌曲 [${song.title}] 失败"
            } else {
                "播放异常"
            }
            
            utils.Toast.show(displayMessage)
            utils.NotificationHelper.showMessage(
                id = song?.id?.hashCode() ?: 999,
                title = "播放失败",
                content = displayMessage
            )
            
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
        
        // 停止之前的播放并释放资源
        revokeBlobUrl()
        audio.pause()

        val fileName = song.localAudioPath
        val localPath = fileName?.let { utils.FileStore.getLocalPath(it) }
        
        if (localPath != null) {
            try {
                utils.Logger.i("WasmPlayer", "尝试播放本地文件: $localPath")
                val path = localPath.toPath()
                if (fs.exists(path)) {
                    val source = fs.source(path).buffer()
                    val bytes = try {
                        source.readByteArray()
                    } finally {
                        source.close()
                    }
                    val mimeType = getMimeType(song.filename) ?: "audio/mpeg"
                    val blobUrl = jsCreateBlobUrl(bytes.toJsArray(), mimeType)
                    currentBlobUrl = blobUrl
                    utils.Logger.i("WasmPlayer", "使用 Blob URL 播放: $blobUrl")
                    audio.src = blobUrl
                } else {
                    utils.Logger.i("WasmPlayer", "本地路径存在但文件不存在: $localPath，回推到远程")
                    audio.src = getRemoteUrl(song)
                }
            } catch (e: Exception) {
                utils.Logger.e("WasmPlayer", "读取本地文件失败: ${song.title}", e)
                audio.src = getRemoteUrl(song)
            }
        } else {
            audio.src = getRemoteUrl(song)
        }

        audio.play()
        
        // 更新当前索引
        val index = playlist.value.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentIndex.value = index
        }
    }

    private fun getRemoteUrl(song: Song): String {
        val encodedId = song.id.encodeURLParameter()
        val baseUrl = config.ConfigManager.getBaseUrl()
        var songUrl = "$baseUrl/api/music/play/$encodedId"
        val hash = config.ConfigManager.getPasswordHash()
        if (hash != null) {
            songUrl += "?auth=$hash"
        }
        utils.Logger.i("WasmPlayer", "使用远程 URL 播放: $songUrl")
        return songUrl
    }

    private fun getMimeType(filename: String?): String? {
        return when {
            filename?.endsWith(".mp3", ignoreCase = true) == true -> "audio/mpeg"
            filename?.endsWith(".flac", ignoreCase = true) == true -> "audio/flac"
            filename?.endsWith(".wav", ignoreCase = true) == true -> "audio/wav"
            filename?.endsWith(".ogg", ignoreCase = true) == true -> "audio/ogg"
            filename?.endsWith(".m4a", ignoreCase = true) == true -> "audio/mp4"
            else -> null
        }
    }

    private fun revokeBlobUrl() {
        currentBlobUrl?.let { url ->
            utils.Logger.i("WasmPlayer", "释放 Blob URL: $url")
            jsRevokeObjectURL(url)
            currentBlobUrl = null
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

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(url) => URL.revokeObjectURL(url)")
external fun jsRevokeObjectURL(url: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(data, mimeType) => URL.createObjectURL(new Blob([data], { type: mimeType }))")
external fun jsCreateBlobUrl(data: JsAny, mimeType: String): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(size) => new Int8Array(size)")
external fun jsCreateInt8Array(size: Int): JsAny

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(array, index, value) => { array[index] = value; }")
external fun jsSetInt8ArrayElement(array: JsAny, index: Int, value: Byte)

@OptIn(ExperimentalWasmJsInterop::class)
private fun ByteArray.toJsArray(): JsAny {
    val size = this.size
    val jsArray = jsCreateInt8Array(size)
    for (i in 0 until size) {
        jsSetInt8ArrayElement(jsArray, i, this[i])
    }
    return jsArray
}

actual val GlobalPlayerController: PlayerController = WasmPlayerController()
