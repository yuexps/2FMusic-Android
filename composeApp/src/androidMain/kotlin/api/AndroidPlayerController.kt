package api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import config.ConfigManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import model.PlayMode
import model.PlaybackState
import model.Song
import utils.Sha256
import androidx.media3.common.util.UnstableApi

@UnstableApi
object AndroidPlayerController : PlayerController {
    private var _player: ExoPlayer? = null
    val player: ExoPlayer
        get() = _player ?: throw IllegalStateException("AndroidPlayerController is not initialized.")

    private var isInitialized = false
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    fun initialize(context: Context) {
        if (isInitialized) return
        this.appContext = context.applicationContext
        val appContext = this.appContext!!
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
            
        _player = ExoPlayer.Builder(appContext)
            .setAudioAttributes(audioAttributes, true) // true means handle audio focus automatically
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val newState = when (state) {
                            Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                            Player.STATE_READY -> if (this@apply.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
                            Player.STATE_ENDED -> PlaybackState.IDLE
                            Player.STATE_IDLE -> PlaybackState.IDLE
                            else -> PlaybackState.IDLE
                        }
                        (this@AndroidPlayerController.playbackState as MutableStateFlow<PlaybackState>).value = newState
                        
                        if (state == Player.STATE_READY) {
                            (this@AndroidPlayerController.duration as MutableStateFlow<Long>).value = this@apply.duration
                            startProgressTracker()
                        } else {
                            stopProgressTracker()
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        val state = this@apply.playbackState
                        val newState = if (state == Player.STATE_READY) {
                            if (playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
                        } else {
                            this@AndroidPlayerController.playbackState.value
                        }
                        (this@AndroidPlayerController.playbackState as MutableStateFlow<PlaybackState>).value = newState
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val songId = mediaItem?.mediaId
                        val song = _currentPlaylist.find { it.id == songId }
                        (this@AndroidPlayerController.currentSong as MutableStateFlow<Song?>).value = song
                        val index = _currentPlaylist.indexOfFirst { it.id == songId }
                        (this@AndroidPlayerController.currentIndex as MutableStateFlow<Int>).value = index
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        (this@AndroidPlayerController.playbackState as MutableStateFlow<PlaybackState>).value = PlaybackState.ERROR
                        stopProgressTracker()
                    }
                })
            }
        
        // 设置初始播放模式
        setPlayMode(PlayMode.LIST_LOOP)
        
        // 恢复上次播放状态
        restoreState()

        isInitialized = true
    }

    private var _currentPlaylist: List<Song> = emptyList()

    override val currentSong: StateFlow<Song?> = MutableStateFlow(null)
    override val playbackState: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.IDLE)
    override val playMode: StateFlow<PlayMode> = MutableStateFlow(PlayMode.LIST_LOOP)
    override val progress: StateFlow<Float> = MutableStateFlow(0f)
    override val duration: StateFlow<Long> = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = MutableStateFlow(0L)
    override val playlist: StateFlow<List<Song>> = MutableStateFlow(emptyList())
    override val currentIndex: StateFlow<Int> = MutableStateFlow(-1)

    private fun getAuthParam(): String {
        val password = ConfigManager.getPassword()
        return if (password != null) "auth=${Sha256.hash(password)}" else ""
    }

    private fun createMediaItem(song: Song): MediaItem {
        val baseUrl = ConfigManager.getBaseUrl()
        val auth = getAuthParam()
        val url = "$baseUrl/api/music/play/${song.id}?$auth"
        
        val albumArtUrlArray = song.albumArt?.let {
            val separator = if (it.contains("?")) "&" else "?"
            val authSuffix = if (auth.isNotEmpty()) "${separator}$auth" else ""
            if (it.startsWith("http")) it else "$baseUrl$it$authSuffix"
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title ?: song.filename)
            .setArtist(song.artist ?: "未知艺术家")
            .setAlbumTitle(song.album ?: "")
            .setArtworkUri(albumArtUrlArray?.let { Uri.parse(it) })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
            
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(song.id)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun play(song: Song) {
        val index = _currentPlaylist.indexOfFirst { it.id == song.id }
        if (index != -1) {
            println("[AndroidPlayerController] play(song) found in existing playlist at index $index")
            playAtIndex(index)
        } else {
            println("[AndroidPlayerController] play(song) not in playlist, setting as single item")
            val mediaItem = createMediaItem(song)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            // 更新当前队列为仅此一曲
            _currentPlaylist = listOf(song)
            (playlist as MutableStateFlow<List<Song>>).value = _currentPlaylist
        }
        updateService()
        saveState()
    }

    private fun updateService() {
        // 显式启动或唤醒服务，确保系统感知到媒体播放的“强势”存在
        val context = appContext ?: return
        _player?.let {
            try {
                val intent = Intent(context, Class.forName("top.msfxp.music.PlayerService"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                println("[AndroidPlayerController] Failed to start service: ${e.message}")
            }
        }
    }
    override fun pause() {
        player.pause()
        saveState()
    }

    override fun resume() {
        player.play()
        updateService()
        saveState()
    }

    override fun stop() {
        player.stop()
    }

    override fun next() {
        println("[AndroidPlayerController] next() called. hasNext=${player.hasNextMediaItem()}, playlistSize=${_currentPlaylist.size}")
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else if (_currentPlaylist.isNotEmpty()) {
            // 列表结尾, 循环回第一首
            println("[AndroidPlayerController] next() loop to index 0")
            player.seekTo(0, 0)
        }
        player.play() // 显式调用播放
        updateService()
        saveState()
    }

    override fun previous() {
        println("[AndroidPlayerController] previous() called. hasPrevious=${player.hasPreviousMediaItem()}, playlistSize=${_currentPlaylist.size}")
        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        } else if (_currentPlaylist.isNotEmpty()) {
            // 列表开头, 循环到最后一首
            println("[AndroidPlayerController] previous() loop to index ${_currentPlaylist.size - 1}")
            player.seekTo(_currentPlaylist.size - 1, 0)
        }
        player.play() // 显式调用播放
        updateService()
        saveState()
    }

    override fun seekTo(position: Long) {
        player.seekTo(position)
        saveState()
    }

    override fun setPlayMode(mode: PlayMode) {
        (playMode as MutableStateFlow<PlayMode>).value = mode
        when (mode) {
            PlayMode.LIST_LOOP -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }
            PlayMode.SINGLE_LOOP -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
            PlayMode.RANDOM -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
        }
    }

    override fun setPlaylist(songs: List<Song>) {
        _currentPlaylist = songs
        (playlist as MutableStateFlow<List<Song>>).value = songs
        val mediaItems = songs.map { createMediaItem(it) }
        player.setMediaItems(mediaItems)
        player.prepare()
        updateService()
        saveState()
    }

    override fun playAtIndex(index: Int) {
        if (index in _currentPlaylist.indices) {
            player.seekTo(index, 0)
            player.play()
            updateService()
            saveState()
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                    val pos = player.currentPosition
                    val dur = player.duration
                    if (dur > 0) {
                        (this@AndroidPlayerController.currentPosition as MutableStateFlow<Long>).value = pos
                        (this@AndroidPlayerController.progress as MutableStateFlow<Float>).value = pos.toFloat() / dur.toFloat()
                        
                        // 每 10 秒保存一次进度
                        if (System.currentTimeMillis() % 10000 < 500) {
                            saveState()
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        saveState()
        _player?.release()
        _player = null
        isInitialized = false
        progressJob?.cancel()
    }

    private fun saveState() {
        val currentSongId = currentSong.value?.id
        val pos = _player?.currentPosition ?: 0L
        val data = model.PlaybackStateData(
            currentSongId = currentSongId,
            position = pos,
            playlist = _currentPlaylist,
            playMode = playMode.value
        )
        ConfigManager.savePlaybackState(data)
    }

    private fun restoreState() {
        val data = ConfigManager.loadPlaybackState() ?: return
        
        // 1. 恢复播放模式
        setPlayMode(data.playMode)
        
        // 2. 恢复播放列表
        if (data.playlist.isNotEmpty()) {
            _currentPlaylist = data.playlist
            (playlist as MutableStateFlow<List<Song>>).value = _currentPlaylist
            val mediaItems = _currentPlaylist.map { createMediaItem(it) }
            player.setMediaItems(mediaItems)
            
            // 3. 恢复歌曲位置
            val index = _currentPlaylist.indexOfFirst { it.id == data.currentSongId }
            if (index != -1) {
                player.seekTo(index, data.position)
                player.prepare()
                // 此时不调用 play()，由用户手动开始
            }
        }
    }
}
