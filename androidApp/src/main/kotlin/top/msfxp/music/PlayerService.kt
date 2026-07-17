package top.msfxp.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.net.wifi.WifiManager
import android.app.AlarmManager
import androidx.core.app.AlarmManagerCompat
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.common.Player
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.*
import utils.Platform
import api.GlobalState
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures

@UnstableApi
class PlayerService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentArtworkUri: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isServiceForeground = false

    companion object {
        const val CHANNEL_ID = "music_control_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "top.msfxp.music.ACTION_PLAY"
        const val ACTION_PAUSE = "top.msfxp.music.ACTION_PAUSE"
        const val ACTION_NEXT = "top.msfxp.music.ACTION_NEXT"
        const val ACTION_PREV = "top.msfxp.music.ACTION_PREV"
        const val ACTION_STOP = "top.msfxp.music.ACTION_STOP"
        const val ACTION_SET_ALARM = "top.msfxp.music.ACTION_SET_ALARM"
        const val ACTION_CANCEL_ALARM = "top.msfxp.music.ACTION_CANCEL_ALARM"
        const val EXTRA_ALARM_MINUTES = "extra_alarm_minutes"
        const val ACTION_TOGGLE_FAVORITE = "top.msfxp.music.ACTION_TOGGLE_FAVORITE"
        const val ACTION_TOGGLE_LYRICS = "top.msfxp.music.ACTION_TOGGLE_LYRICS"
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
            availableSessionCommands.add(androidx.media3.session.SessionCommand(ACTION_TOGGLE_FAVORITE, android.os.Bundle.EMPTY))
            availableSessionCommands.add(androidx.media3.session.SessionCommand(ACTION_TOGGLE_LYRICS, android.os.Bundle.EMPTY))

            val currentSong = api.AndroidPlayerController.currentSong.value
            val isFavorite = currentSong?.let { GlobalState.favoriteIds.value.contains(it.id) } ?: false
            val showLyrics = Platform.config.getShowLyricsInNotification()

            val favoriteButton = androidx.media3.session.CommandButton.Builder(
                if (isFavorite) androidx.media3.session.CommandButton.ICON_STAR_FILLED else androidx.media3.session.CommandButton.ICON_STAR_UNFILLED
            )
                .setSessionCommand(androidx.media3.session.SessionCommand(ACTION_TOGGLE_FAVORITE, android.os.Bundle.EMPTY))
                .setDisplayName(if (isFavorite) "已收藏" else "收藏")
                .build()

            val lyricsButton = androidx.media3.session.CommandButton.Builder(
                if (showLyrics) androidx.media3.session.CommandButton.ICON_BOOKMARK_FILLED else androidx.media3.session.CommandButton.ICON_BOOKMARK_UNFILLED
            )
                .setSessionCommand(androidx.media3.session.SessionCommand(ACTION_TOGGLE_LYRICS, android.os.Bundle.EMPTY))
                .setDisplayName(if (showLyrics) "隐藏歌词" else "显示歌词")
                .build()

            val customLayout = listOf(favoriteButton, lyricsButton)

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands.build())
                .setAvailablePlayerCommands(connectionResult.availablePlayerCommands)
                .setCustomLayout(customLayout)
                .build()
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: androidx.media3.common.Rating
        ): ListenableFuture<androidx.media3.session.SessionResult> {
            println("[PlayerService] onSetRating: $rating")
            if (rating is androidx.media3.common.HeartRating) {
                val song = api.AndroidPlayerController.currentSong.value
                if (song != null) {
                    val isFav = GlobalState.favoriteIds.value.contains(song.id)
                    if (rating.isHeart != isFav) {
                        toggleFavorite()
                    }
                }
            } else {
                toggleFavorite()
            }
            return Futures.immediateFuture(
                androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<androidx.media3.session.SessionResult> {
            println("[PlayerService] onCustomCommand: ${customCommand.customAction}")
            when (customCommand.customAction) {
                ACTION_TOGGLE_FAVORITE -> {
                    toggleFavorite()
                }
                ACTION_TOGGLE_LYRICS -> {
                    toggleLyrics()
                }
            }
            return Futures.immediateFuture(
                androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
            )
        }
    }

    private fun toggleFavorite() {
        val song = api.AndroidPlayerController.currentSong.value
        if (song != null) {
            val sid = song.id
            val isFav = GlobalState.favoriteIds.value.contains(sid)
            serviceScope.launch {
                try {
                    if (isFav) {
                        Platform.repository.removeFavorite(sid)
                        val newFavs = GlobalState.favoriteIds.value.toMutableSet().apply { remove(sid) }
                        GlobalState.updateFavorites(newFavs)
                    } else {
                        Platform.repository.addFavorite(sid)
                        val newFavs = GlobalState.favoriteIds.value.toMutableSet().apply { add(sid) }
                        GlobalState.updateFavorites(newFavs)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                updateNotification()
            }
        }
    }

    private fun toggleLyrics() {
        val showLyrics = !Platform.config.getShowLyricsInNotification()
        Platform.config.setShowLyricsInNotification(showLyrics)
        api.AndroidPlayerController.updateLyricsMetadata()
        updateNotification()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotification()
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            updateNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateNotification()
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            updateNotification()
        }
    }

    private fun createNotificationChannel() {
        val channelName = "音乐控制"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
            description = "2FMusic 播放控制面板"
            setShowBadge(false)
            setSound(null, null)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onCreate() {
        super.onCreate()
        println("[PlayerService] onCreate")

        // 1. Ensure channel exists
        createNotificationChannel()

        // 初始化 WifiLock，高性能网络锁定
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "2FMusic:WifiLock")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Clean up old notifications and channels
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()

        // 3. Get Player and build MediaSession
        runCatching {
            val player = api.AndroidPlayerController.player
            
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_IMMUTABLE
            )

            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(pendingIntent)
                .setCallback(sessionCallback)
                .build()
            
            // Register listener
            player.addListener(playerListener)
        }.onFailure {
            println("[PlayerService] Failed to create MediaSession: ${it.message}")
            it.printStackTrace()
        }
    }

    private fun updateNotification() {
        val player = mediaSession?.player ?: return
        val metadata = player.mediaMetadata
        
        // 处理封面加载
        val artworkUri = metadata.artworkUri?.toString()
        if (artworkUri != null && artworkUri != currentArtworkUri) {
            currentArtworkUri = artworkUri
            serviceScope.launch {
                val request = ImageRequest.Builder(this@PlayerService)
                    .data(artworkUri)
                    .build()
                val result = imageLoader.execute(request)
                if (result is coil.request.SuccessResult) {
                    currentArtworkBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    updateNotification() // 再次刷新通知
                } else if (result is coil.request.ErrorResult) {
                    println("[PlayerService] Failed to load artwork: ${result.throwable.message}")
                }
            }
        } else if (artworkUri == null) {
            currentArtworkUri = null
            currentArtworkBitmap = null
        }

        // Prepare Action PendingIntents
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE
        val playIntent = PendingIntent.getService(this, 1, Intent(this, PlayerService::class.java).setAction(ACTION_PLAY), pendingIntentFlags)
        val pauseIntent = PendingIntent.getService(this, 2, Intent(this, PlayerService::class.java).setAction(ACTION_PAUSE), pendingIntentFlags)
        val nextIntent = PendingIntent.getService(this, 3, Intent(this, PlayerService::class.java).setAction(ACTION_NEXT), pendingIntentFlags)
        val prevIntent = PendingIntent.getService(this, 4, Intent(this, PlayerService::class.java).setAction(ACTION_PREV), pendingIntentFlags)

        val isPlaying = player.isPlaying
        val playPauseAction = if (isPlaying) {
             NotificationCompat.Action.Builder(android.R.drawable.ic_media_pause, "暂停", pauseIntent).build()
        } else {
             NotificationCompat.Action.Builder(android.R.drawable.ic_media_play, "播放", playIntent).build()
        }
        val prevAction = NotificationCompat.Action.Builder(android.R.drawable.ic_media_previous, "上一首", prevIntent).build()
        val nextAction = NotificationCompat.Action.Builder(android.R.drawable.ic_media_next, "下一首", nextIntent).build()

        val favoriteIntent = PendingIntent.getService(this, 5, Intent(this, PlayerService::class.java).setAction(ACTION_TOGGLE_FAVORITE), pendingIntentFlags)
        val lyricsIntent = PendingIntent.getService(this, 6, Intent(this, PlayerService::class.java).setAction(ACTION_TOGGLE_LYRICS), pendingIntentFlags)

        val currentSong = api.AndroidPlayerController.currentSong.value
        val isFavorite = currentSong?.let { GlobalState.favoriteIds.value.contains(it.id) } ?: false
        val favoriteAction = NotificationCompat.Action.Builder(
            if (isFavorite) android.R.drawable.star_on else android.R.drawable.star_off,
            if (isFavorite) "已收藏" else "收藏",
            favoriteIntent
        ).build()

        val showLyrics = Platform.config.getShowLyricsInNotification()
        val lyricsAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_agenda,
            if (showLyrics) "隐藏歌词" else "显示歌词",
            lyricsIntent
        ).build()

        val favoriteButton = androidx.media3.session.CommandButton.Builder(
            if (isFavorite) androidx.media3.session.CommandButton.ICON_STAR_FILLED else androidx.media3.session.CommandButton.ICON_STAR_UNFILLED
        )
            .setSessionCommand(androidx.media3.session.SessionCommand(ACTION_TOGGLE_FAVORITE, android.os.Bundle.EMPTY))
            .setDisplayName(if (isFavorite) "已收藏" else "收藏")
            .build()

        val lyricsButton = androidx.media3.session.CommandButton.Builder(
            if (showLyrics) androidx.media3.session.CommandButton.ICON_BOOKMARK_FILLED else androidx.media3.session.CommandButton.ICON_BOOKMARK_UNFILLED
        )
            .setSessionCommand(androidx.media3.session.SessionCommand(ACTION_TOGGLE_LYRICS, android.os.Bundle.EMPTY))
            .setDisplayName(if (showLyrics) "隐藏歌词" else "显示歌词")
            .build()

        mediaSession?.setCustomLayout(listOf(favoriteButton, lyricsButton))

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(metadata.title ?: "2FMusic")
            .setContentText(metadata.artist ?: "")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(currentArtworkBitmap) // 设置封面图
            .setStyle(MediaStyleNotificationHelper.MediaStyle(mediaSession!!)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(favoriteAction)
            .addAction(lyricsAction)
            
        mediaSession?.sessionActivity?.let { builder.setContentIntent(it) }

        val notification = builder.build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val shouldBeForeground = isPlaying || player.playWhenReady || player.playbackState == Player.STATE_BUFFERING
        
        // 动态锁定 WifiLock 守护网络
        if (shouldBeForeground) {
            try {
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (shouldBeForeground) {
            try {
                if (!isServiceForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    isServiceForeground = true
                } else {
                    // 已在前景，仅刷新通知，避免在后台频繁调用 startForeground 引发 crash
                    manager.notify(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            if (isServiceForeground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                isServiceForeground = false
            }
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        println("[PlayerService] onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_PLAY -> mediaSession?.player?.play()
            ACTION_PAUSE -> mediaSession?.player?.pause()
            ACTION_NEXT -> mediaSession?.player?.seekToNext()
            ACTION_PREV -> mediaSession?.player?.seekToPrevious()
            ACTION_TOGGLE_FAVORITE -> {
                toggleFavorite()
            }
            ACTION_TOGGLE_LYRICS -> {
                toggleLyrics()
            }

            ACTION_SET_ALARM -> {
                val minutes = intent.getIntExtra(EXTRA_ALARM_MINUTES, 0)
                if (minutes > 0) {
                    println("[PlayerService] ACTION_SET_ALARM 触发，定时 $minutes 分钟。")
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val stopIntent = Intent(this, PlayerService::class.java).setAction(ACTION_STOP)
                    val pendingIntent = PendingIntent.getService(
                        this,
                        1002,
                        stopIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val triggerAtMillis = System.currentTimeMillis() + minutes * 60 * 1000
                    try {
                        AlarmManagerCompat.setExactAndAllowWhileIdle(
                            alarmManager,
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            ACTION_CANCEL_ALARM -> {
                println("[PlayerService] ACTION_CANCEL_ALARM 触发，注销闹钟。")
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val stopIntent = Intent(this, PlayerService::class.java).setAction(ACTION_STOP)
                val pendingIntent = PendingIntent.getService(
                    this,
                    1002,
                    stopIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
            }
            ACTION_STOP -> {
                println("[PlayerService] ACTION_STOP 触发，服务强制自毁。")
                mediaSession?.player?.pause()
                try {
                    if (wifiLock?.isHeld == true) {
                        wifiLock?.release()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if (isServiceForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isServiceForeground = false
                }
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
                
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val stopIntent = Intent(this, PlayerService::class.java).setAction(ACTION_STOP)
                val pendingIntent = PendingIntent.getService(
                    this,
                    1002,
                    stopIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
                
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        updateNotification()
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
             stopSelf()
        }
    }

    override fun onDestroy() {
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        mediaSession?.player?.removeListener(playerListener)
        mediaSession?.run {
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
