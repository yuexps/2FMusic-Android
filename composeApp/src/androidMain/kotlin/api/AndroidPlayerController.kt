package api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import config.ConfigManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import model.PlayMode
import model.PlaybackState
import model.Song
import utils.Sha256
import utils.CoverUtil

object AndroidPlayerController : PlayerController {
    private var _player: ExoPlayer? = null
    val player: ExoPlayer
        get() = _player ?: throw IllegalStateException("AndroidPlayerController 未初始化。")

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
            
        val baseDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            
        ConfigManager.getPasswordHash()?.let { hash ->
            baseDataSourceFactory.setDefaultRequestProperties(mapOf("X-Password" to hash))
        }
        
        // 使用 DefaultDataSource.Factory 自动分发协议 (file://, http:// 等)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, baseDataSourceFactory)
        
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(dataSourceFactory)
            
        _player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
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
                        val song = currentSong.value
                        var errorMessage = "播放失败: ${error.message}"

                        // 简单的逻辑判断：如果歌曲没有本地路径且报错，通常是因为离线/网络问题
                        if (song != null) {
                            if (song.localAudioPath == null) {
                                errorMessage = "歌曲 [${song.title}] 未下载，且无法连接服务器。"
                            } else {
                                errorMessage = "播放本地歌曲 [${song.title}] 失败，文件可能已损坏。"
                            }
                        }

                        utils.Logger.e("Player", "播放器错误: 代码=${error.errorCode}, 消息=${error.message}", error)
                        
                        // 发送 Toast 和通知栏提醒
                        utils.Toast.show(errorMessage)
                        utils.NotificationHelper.showMessage(
                            id = song?.id?.hashCode() ?: 999,
                            title = "播放异常",
                            content = errorMessage
                        )

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

    private fun createMediaItem(song: Song, verbose: Boolean = false): MediaItem {
        val baseUrl = ConfigManager.getBaseUrl()
        val auth = getAuthParam()
        
        var uri: Uri? = null
        song.localAudioPath?.let { path ->
            if (verbose) utils.Logger.i("Audio", "数据库中有本地路径: $path")
            utils.FileStore.getLocalPath(path)?.let { absPath ->
                uri = Uri.fromFile(java.io.File(absPath))
                if (verbose) utils.Logger.i("Audio", "文件存在于: $absPath。使用本地 URI。")
            } ?: run {
                if (verbose) utils.Logger.i("Audio", "未在预期位置找到文件，路径: $path")
            }
        }
        
        if (uri == null) {
            val url = "$baseUrl/api/music/play/${song.id}?$auth"
            uri = Uri.parse(url)
            if (verbose) utils.Logger.i("Audio", "回退到远程 URL: $url")
        }
        
        val mimeType = when {
            song.filename?.endsWith(".mp3", ignoreCase = true) == true -> MimeTypes.AUDIO_MPEG
            song.filename?.endsWith(".flac", ignoreCase = true) == true -> MimeTypes.AUDIO_FLAC
            song.filename?.endsWith(".wav", ignoreCase = true) == true -> MimeTypes.AUDIO_WAV
            song.filename?.endsWith(".ogg", ignoreCase = true) == true -> MimeTypes.AUDIO_OGG
            song.filename?.endsWith(".m4a", ignoreCase = true) == true -> MimeTypes.AUDIO_MP4
            else -> null
        }
        
        val albumArtUrl = CoverUtil.getCoverUrl(song)

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title ?: song.filename)
            .setArtist(song.artist ?: "未知艺术家")
            .setAlbumTitle(song.album ?: "")
            .setArtworkUri(albumArtUrl?.let { Uri.parse(it) })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
            
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id)
            .setMimeType(mimeType)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun play(song: Song) {
        utils.Logger.i("Audio", "play(song): ${song.title} (ID: ${song.id})")
        val index = _currentPlaylist.indexOfFirst { it.id == song.id }
        if (index != -1) {
            utils.Logger.i("Player", "在现有播放列表中找到索引 $index")
            
            // 关键：检查当前播放队列里的 MediaItem 是否需要更新（例如从远程 URL 变为本地路径）
            val newMediaItem = createMediaItem(song, verbose = true)
            val currentMediaItem = try { player.getMediaItemAt(index) } catch (e: Exception) { null }
            
            if (currentMediaItem != null && newMediaItem.localConfiguration?.uri != currentMediaItem.localConfiguration?.uri) {
                utils.Logger.i("Audio", "正在将索引 $index 处的媒体项更新为本地化 URI。")
                player.replaceMediaItem(index, newMediaItem)
            }
            
            playAtIndex(index)
        } else {
            utils.Logger.i("Player", "不在播放列表中，设为单曲播放")
            val mediaItem = createMediaItem(song, verbose = true)
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
                context.startForegroundService(intent)
            } catch (e: Exception) {
                utils.Logger.e("Player", "启动服务失败", e)
            }
        }
    }
    private fun ensurePrepared() {
        if (player.playbackState == Player.STATE_IDLE) {
            utils.Logger.i("Player", "播放器处于 IDLE 状态，执行 prepare()")
            player.prepare()
        }
    }

    override fun pause() {
        player.pause()
        saveState()
    }

    override fun resume() {
        ensurePrepared()
        player.play()
        updateService()
        saveState()
    }

    override fun stop() {
        player.stop()
    }

    override fun next() {
        utils.Logger.i("Player", "next() 被调用。hasNext=${player.hasNextMediaItem()}, 列表大小=${_currentPlaylist.size}")
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else if (_currentPlaylist.isNotEmpty()) {
            // 列表结尾, 循环回第一首
            utils.Logger.i("Player", "next() 循环回到索引 0")
            player.seekTo(0, 0)
        }
        ensurePrepared()
        player.play() // 显式调用播放
        updateService()
        saveState()
    }

    override fun previous() {
        utils.Logger.i("Player", "previous() 被调用。hasPrevious=${player.hasPreviousMediaItem()}, 列表大小=${_currentPlaylist.size}")
        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        } else if (_currentPlaylist.isNotEmpty()) {
            // 列表开头, 循环到最后一首
            utils.Logger.i("Player", "previous() 循环到索引 ${_currentPlaylist.size - 1}")
            player.seekTo(_currentPlaylist.size - 1, 0)
        }
        ensurePrepared()
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
        val oldIds = _currentPlaylist.map { it.id }
        val newIds = songs.map { it.id }
        
        if (oldIds == newIds) {
            // 列表 ID 没变，检查是否有歌曲从未下载变成已下载，进行热替换
            songs.forEachIndexed { index, song ->
                val oldSong = _currentPlaylist[index]
                if (song.localAudioPath != oldSong.localAudioPath) {
                    utils.Logger.i("Audio", "索引 $index 处的歌曲 [${song.title}] 路径已更新，执行热替换。")
                    val newItem = createMediaItem(song)
                    player.replaceMediaItem(index, newItem)
                }
            }
            _currentPlaylist = songs
            (playlist as MutableStateFlow<List<Song>>).value = songs
            return
        }

        utils.Logger.i("Audio", "setPlaylist: 包含 ${songs.size} 首歌曲")
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
            ensurePrepared()
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
                        if ((utils.getTimeMillis() % 10000L) < 500L) {
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
