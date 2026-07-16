package api

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
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
import utils.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import model.PlayMode
import model.PlaybackState
import model.Song
import utils.CoverUtil
import androidx.core.net.toUri

@androidx.media3.common.util.UnstableApi
object AndroidPlayerController : BasePlayerController() {
    private var _player: ExoPlayer? = null
    val player: ExoPlayer
        get() = _player ?: throw IllegalStateException("AndroidPlayerController 未初始化。")

    private var isInitialized = false
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var currentLyricsList: List<utils.LrcLine> = emptyList()
    private var lastPostedLyricText: String? = null
    private var lastPostedLyricArtist: String? = null

    fun initialize(context: Context) {
        if (isInitialized) return
        this.appContext = context.applicationContext
        val appContext = this.appContext!!
        
        // 恢复均衡器持久化配置
        val prefs = appContext.getSharedPreferences("2fmusic_prefs", Context.MODE_PRIVATE)
        eqEnabled = prefs.getBoolean("eq_enabled", false)
        for (i in 0..15) {
            if (prefs.contains("eq_band_$i")) {
                bandLevels[i] = prefs.getInt("eq_band_$i", 0)
            }
        }
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
            
        val baseDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            
        Platform.config.getPasswordHash()?.let { hash ->
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
                this@AndroidPlayerController.initEqualizer(this.audioSessionId)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val newState = when (state) {
                            Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                            Player.STATE_READY -> if (this@apply.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
                            Player.STATE_ENDED -> PlaybackState.IDLE
                            Player.STATE_IDLE -> PlaybackState.IDLE
                            else -> PlaybackState.IDLE
                        }
                        this@AndroidPlayerController.playbackState.value = newState
                        
                        if (state == Player.STATE_READY) {
                            this@AndroidPlayerController.duration.value = this@apply.duration
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
                        this@AndroidPlayerController.playbackState.value = newState
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val songId = mediaItem?.mediaId
                        val song = _currentPlaylist.find { it.id == songId }
                        currentSong.value = song
                        val index = _currentPlaylist.indexOfFirst { it.id == songId }
                        currentIndex.value = index

                        lastPostedLyricText = null
                        lastPostedLyricArtist = null
                        currentLyricsList = emptyList()
                        song?.let { s ->
                            scope.launch(Dispatchers.IO) {
                                val localLrc = utils.FileStore.readLyrics(s.id)
                                if (localLrc != null) {
                                    currentLyricsList = utils.LrcParser.parse(localLrc, s.title)
                                }
                            }
                        }
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        this@AndroidPlayerController.initEqualizer(audioSessionId)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val song = currentSong.value
                        val errorMessage = if (song != null) {
                            if (song.localAudioPath == null) {
                                "歌曲 [${song.title}] 未下载，且无法连接服务器。"
                            } else {
                                "播放本地歌曲 [${song.title}] 失败，文件可能已损坏。"
                            }
                        } else {
                            "播放失败: ${error.message}"
                        }

                        Platform.logger.e("Player", "播放器错误: 代码=${error.errorCode}, 消息=${error.message}", error)
                        
                        // 发送 Toast 和通知栏提醒
                        Platform.toast.show(errorMessage)
                        Platform.notification.showMessage(
                            id = song?.id?.hashCode() ?: 999,
                            title = "播放异常",
                            content = errorMessage
                        )

                        this@AndroidPlayerController.playbackState.value = PlaybackState.ERROR
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

    private fun getAuthParam(): String {
        val password = Platform.config.getPassword()
        return if (password != null) "auth=${utils.Sha256.hash(password)}" else ""
    }

    private fun createMediaItem(song: Song, verbose: Boolean = false): MediaItem {
        val baseUrl = Platform.config.getBaseUrl()
        val auth = getAuthParam()
        
        var uri: Uri? = null
        song.localAudioPath?.let { path ->
            if (verbose) Platform.logger.i("Audio", "数据库中有本地路径: $path")
            utils.FileStore.getLocalPath(path)?.let { absPath ->
                uri = Uri.fromFile(java.io.File(absPath))
                if (verbose) Platform.logger.i("Audio", "文件存在于: $absPath。使用本地 URI。")
            } ?: run {
                if (verbose) Platform.logger.i("Audio", "未在预期位置找到文件，路径: $path")
            }
        }
        
        if (uri == null) {
            val url = "$baseUrl/api/music/play/${song.id}?$auth"
            uri = url.toUri()
            if (verbose) Platform.logger.i("Audio", "回退到远程 URL: $url")
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
            .setArtworkUri(albumArtUrl?.toUri())
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
        Platform.logger.i("Audio", "play(song): ${song.title} (ID: ${song.id})")
        val index = _currentPlaylist.indexOfFirst { it.id == song.id }
        if (index != -1) {
            Platform.logger.i("Player", "在现有播放列表中找到索引 $index")
            
            // 关键：检查当前播放队列里的 MediaItem 是否需要更新（例如从远程 URL 变为本地路径）
            val newMediaItem = createMediaItem(song, verbose = true)
            val currentMediaItem = try { player.getMediaItemAt(index) } catch (_: Exception) { null }
            
            if (currentMediaItem != null && newMediaItem.localConfiguration?.uri != currentMediaItem.localConfiguration?.uri) {
                Platform.logger.i("Audio", "正在将索引 $index 处的媒体项更新为本地化 URI。")
                player.replaceMediaItem(index, newMediaItem)
            }
            
            playAtIndex(index)
        } else {
            Platform.logger.i("Player", "不在播放列表中，设为单曲播放")
            val mediaItem = createMediaItem(song, verbose = true)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            // 更新当前队列为仅此一曲
            _currentPlaylist = listOf(song)
            playlist.value = _currentPlaylist
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
                Platform.logger.e("Player", "启动服务失败", e)
            }
        }
    }
    private fun ensurePrepared() {
        if (player.playbackState == Player.STATE_IDLE) {
            Platform.logger.i("Player", "播放器处于 IDLE 状态，执行 prepare()")
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
        Platform.logger.i("Player", "next() 被调用。hasNext=${player.hasNextMediaItem()}, 列表大小=${_currentPlaylist.size}")
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else if (_currentPlaylist.isNotEmpty()) {
            // 列表结尾, 循环回第一首
            Platform.logger.i("Player", "next() 循环回到索引 0")
            player.seekTo(0, 0)
        }
        ensurePrepared()
        player.play() // 显式调用播放
        updateService()
        saveState()
    }

    override fun previous() {
        Platform.logger.i("Player", "previous() 被调用。hasPrevious=${player.hasPreviousMediaItem()}, 列表大小=${_currentPlaylist.size}")
        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        } else if (_currentPlaylist.isNotEmpty()) {
            // 列表开头, 循环到最后一首
            Platform.logger.i("Player", "previous() 循环到索引 ${_currentPlaylist.size - 1}")
            player.seekTo(_currentPlaylist.size - 1, 0)
        }
        ensurePrepared()
        player.play() // 显式调用播放
        updateService()
        saveState()
    }

    override fun seekTo(position: Long) {
        player.seekTo(position)
        updateLyricsMetadata(position)
        saveState()
    }

    override fun setPlayMode(mode: PlayMode) {
        playMode.value = mode
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
                    Platform.logger.i("Audio", "索引 $index 处的歌曲 [${song.title}] 路径已更新，执行热替换。")
                    val newItem = createMediaItem(song)
                    player.replaceMediaItem(index, newItem)
                }
            }
            _currentPlaylist = songs
            playlist.value = songs
            return
        }

        Platform.logger.i("Audio", "setPlaylist: 包含 ${songs.size} 首歌曲")
        _currentPlaylist = songs
        playlist.value = songs
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
                        currentPosition.value = pos
                        progress.value = pos.toFloat() / dur.toFloat()
                        
                        updateLyricsMetadata(pos)

                        // 每 10 秒保存一次进度
                        if ((Platform.getTimeMillis() % 10000L) < 500L) {
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

    private fun saveState() {
        val currentSongId = currentSong.value?.id
        val pos = _player?.currentPosition ?: 0L
        val data = model.PlaybackStateData(
            currentSongId = currentSongId,
            position = pos,
            playlist = _currentPlaylist,
            playMode = playMode.value
        )
        Platform.config.savePlaybackState(data)
    }

    private fun restoreState() {
        val data = Platform.config.loadPlaybackState() ?: return
        
        // 1. 恢复播放模式
        setPlayMode(data.playMode)
        
        // 2. 恢复播放列表
        if (data.playlist.isNotEmpty()) {
            _currentPlaylist = data.playlist
            playlist.value = _currentPlaylist
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

    override fun updateLyricsMetadata() {
        val player = _player ?: return
        updateLyricsMetadata(player.currentPosition)
    }

    private fun updateLyricsMetadata(pos: Long) {
        val song = currentSong.value ?: return
        val player = _player ?: return
        
        val showLyrics = Platform.config.getShowLyricsInNotification()
        
        var lyricText: String? = null
        if (showLyrics && currentLyricsList.isNotEmpty()) {
            val idx = utils.LrcParser.getCurrentLineIndex(currentLyricsList, pos)
            if (idx in currentLyricsList.indices) {
                lyricText = currentLyricsList[idx].lines.firstOrNull()
            }
        }
        
        val hasLyric = !lyricText.isNullOrBlank()
        val targetTitle = if (hasLyric) lyricText else (song.title ?: song.filename)
        val targetArtist = if (hasLyric) {
            "${song.title ?: "未知歌名"} - ${song.artist ?: "未知艺术家"}"
        } else {
            song.artist ?: "未知艺术家"
        }
        
        if (lastPostedLyricText == targetTitle && lastPostedLyricArtist == targetArtist) return
        lastPostedLyricText = targetTitle
        lastPostedLyricArtist = targetArtist
        
        try {
            val currentMediaItem = player.currentMediaItem
            if (currentMediaItem != null) {
                val newMetadata = currentMediaItem.mediaMetadata.buildUpon()
                    .setTitle(targetTitle)
                    .setArtist(targetArtist)
                    .build()
                
                val newMediaItem = currentMediaItem.buildUpon()
                    .setMediaMetadata(newMetadata)
                    .build()
                
                val currentIndex = player.currentMediaItemIndex
                player.replaceMediaItem(currentIndex, newMediaItem)
            }
        } catch (e: Exception) {
            Platform.logger.e("Audio", "更新动态歌词媒体元数据失败: ${e.message}")
        }
    }

    // 均衡器成员
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var eqEnabled = false
    private val bandLevels = mutableMapOf<Int, Int>() // 缓存设置的频段增益（毫分贝级）

    fun initEqualizer(sessionId: Int) {
        try {
            if (equalizer != null) {
                equalizer?.release()
                equalizer = null
            }
            if (sessionId != android.media.audiofx.AudioEffect.ERROR_BAD_VALUE && sessionId != 0) {
                val eq = android.media.audiofx.Equalizer(0, sessionId)
                equalizer = eq
                eq.enabled = eqEnabled
                
                // 恢复缓存的频段增益
                bandLevels.forEach { (band, level) ->
                    try {
                        eq.setBandLevel(band.toShort(), level.toShort())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences("2fmusic_prefs", Context.MODE_PRIVATE)
    }

    override fun isEqualizerSupported(): Boolean {
        return equalizer != null
    }

    override fun isEqualizerEnabled(): Boolean {
        return eqEnabled
    }

    override fun setEqualizerEnabled(enabled: Boolean) {
        eqEnabled = enabled
        getPrefs()?.edit()?.putBoolean("eq_enabled", enabled)?.apply()
        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getEqualizerBands(): List<String> {
        val eq = equalizer ?: return listOf("60Hz", "230Hz", "910Hz", "4kHz", "14kHz")
        val numBands = eq.numberOfBands.toInt()
        val bands = mutableListOf<String>()
        for (i in 0 until numBands) {
            val freq = eq.getCenterFreq(i.toShort())
            bands.add(if (freq >= 1000000) "${freq / 1000000}kHz" else "${freq / 1000}Hz")
        }
        return bands
    }

    override fun getEqualizerBandLevels(): List<Int> {
        val eq = equalizer ?: return listOf(0, 0, 0, 0, 0)
        val numBands = eq.numberOfBands.toInt()
        val levels = mutableListOf<Int>()
        for (i in 0 until numBands) {
            val lvl = bandLevels[i] ?: eq.getBandLevel(i.toShort()).toInt()
            levels.add(lvl)
        }
        return levels
    }

    override fun setEqualizerBandLevel(band: Int, level: Int) {
        bandLevels[band] = level
        getPrefs()?.edit()?.putInt("eq_band_$band", level)?.apply()
        try {
            equalizer?.setBandLevel(band.toShort(), level.toShort())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getEstimatedShutdownTime(minutes: Int): String {
        if (minutes <= 0) return "请滑动轮盘设定定时时间"
        return try {
            val calendar = java.util.Calendar.getInstance()
            calendar.add(java.util.Calendar.MINUTE, minutes)
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = calendar.get(java.util.Calendar.MINUTE)
            val hourStr = hour.toString().padStart(2, '0')
            val minuteStr = minute.toString().padStart(2, '0')
            "预计将在 $hourStr:$minuteStr 关闭播放 (${minutes} 分钟后)"
        } catch (e: Exception) {
            "${minutes} 分钟后关闭播放"
        }
    }
}
