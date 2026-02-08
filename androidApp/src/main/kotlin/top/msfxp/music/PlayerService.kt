package top.msfxp.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.*

@UnstableApi
class PlayerService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentArtworkUri: String? = null
    private var currentArtworkBitmap: Bitmap? = null

    companion object {
        const val CHANNEL_ID = "music_control_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "top.msfxp.music.ACTION_PLAY"
        const val ACTION_PAUSE = "top.msfxp.music.ACTION_PAUSE"
        const val ACTION_NEXT = "top.msfxp.music.ACTION_NEXT"
        const val ACTION_PREV = "top.msfxp.music.ACTION_PREV"
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                session.player.availableCommands
            )
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Music Controls"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = "2FMusic Player Controls"
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        println("[PlayerService] onCreate")

        // 1. Ensure channel exists
        createNotificationChannel()

        // 2. Clean up old notifications and channels
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 删除旧的渠道，防止设置里出现多个
            manager.deleteNotificationChannel("default_channel_id")
            manager.deleteNotificationChannel("test_channel_id")
        }

        // 3. Get Player and build MediaSession
        runCatching {
            val player = api.AndroidPlayerController.player
            
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )

            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(pendingIntent)
                .setCallback(sessionCallback)
                .build()
            
            // Register listener
            player.addListener(playerListener)
            // Debug Toast removed
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
        val playIntent = PendingIntent.getService(this, 1, Intent(this, PlayerService::class.java).setAction(ACTION_PLAY), PendingIntent.FLAG_IMMUTABLE)
        val pauseIntent = PendingIntent.getService(this, 2, Intent(this, PlayerService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getService(this, 3, Intent(this, PlayerService::class.java).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE)
        val prevIntent = PendingIntent.getService(this, 4, Intent(this, PlayerService::class.java).setAction(ACTION_PREV), PendingIntent.FLAG_IMMUTABLE)

        val isPlaying = player.isPlaying
        val playPauseAction = if (isPlaying) {
             NotificationCompat.Action.Builder(android.R.drawable.ic_media_pause, "Pause", pauseIntent).build()
        } else {
             NotificationCompat.Action.Builder(android.R.drawable.ic_media_play, "Play", playIntent).build()
        }
        val prevAction = NotificationCompat.Action.Builder(android.R.drawable.ic_media_previous, "Previous", prevIntent).build()
        val nextAction = NotificationCompat.Action.Builder(android.R.drawable.ic_media_next, "Next", nextIntent).build()

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
            
        mediaSession?.sessionActivity?.let { builder.setContentIntent(it) }

        val notification = builder.build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Android 12+ 要求：如果通过 startForegroundService 启动，必须调用 startForeground。
        // 我们在缓冲、正在播放、或者用户点击了播放但由于某种原因（如缓冲/错误）未开始播放时，都保持前台。
        val shouldBeForeground = isPlaying || player.playWhenReady || player.playbackState == Player.STATE_BUFFERING
        
        if (shouldBeForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) { 
                e.printStackTrace()
                // 如果 startForeground 失败（例如权限问题），至少也尝试显示通知
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            // 已暂停或停止，可以退出前台但保留通知
            stopForeground(STOP_FOREGROUND_DETACH)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        println("[PlayerService] onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_PLAY -> mediaSession?.player?.play()
            ACTION_PAUSE -> mediaSession?.player?.pause()
            ACTION_NEXT -> mediaSession?.player?.seekToNext()
            ACTION_PREV -> mediaSession?.player?.seekToPrevious()
        }
        
        // Android 14 兼容：确保在 onStartCommand 逻辑中不仅处理 action，也刷新一次状态
        updateNotification()

        return super.onStartCommand(intent, flags, startId)
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
        mediaSession?.player?.removeListener(playerListener)
        mediaSession?.run {
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
