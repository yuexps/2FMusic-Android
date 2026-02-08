package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import api.GlobalPlayerController
import api.GlobalState
import model.PlaybackState
import model.Song
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BottomPlayerBar(
    onClick: () -> Unit = {}
) {
    val currentSong by GlobalPlayerController.currentSong.collectAsState()
    val playbackState by GlobalPlayerController.playbackState.collectAsState()
    val progress by GlobalPlayerController.progress.collectAsState()
    val playlist by GlobalPlayerController.playlist.collectAsState()
    val currentIndex by GlobalPlayerController.currentIndex.collectAsState()
    

    if (currentSong == null) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 专辑封面
            val albumArtUrl = utils.CoverUtil.getCoverUrl(currentSong)
            
            if (albumArtUrl != null) {
                com.seiko.imageloader.ui.AutoSizeImage(
                    url = albumArtUrl,
                    contentDescription = "Album Art",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.LightGray)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentSong?.title ?: currentSong?.filename ?: "未知音乐",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = currentSong?.artist ?: "未知艺术家",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }

            // 播放按钮
            IconButton(
                onClick = {
                    when (playbackState) {
                        PlaybackState.PLAYING -> {
                            GlobalPlayerController.pause()
                        }
                        PlaybackState.ERROR -> {
                            // 错误状态下点击重试
                            GlobalPlayerController.play(currentSong!!)
                        }
                        else -> {
                            GlobalPlayerController.resume()
                        }
                    }
                }
            ) {
                 if (playbackState == PlaybackState.ERROR) {
                     // 错误图标
                     Text("!", color = Color.Red, fontWeight = FontWeight.Bold)
                 } else {
                    Icon(
                        imageVector = if (playbackState == PlaybackState.PLAYING) MiuixIcons.Pause else MiuixIcons.Play,
                        contentDescription = "Play/Pause",
                        tint = MiuixTheme.colorScheme.primary
                    )
                 }
            }

            Spacer(Modifier.width(8.dp))

            // 播放列表按钮
            IconButton(
                onClick = { GlobalState.togglePlaylist(true) }
            ) {
                Icon(
                    imageVector = MiuixIcons.Playlist,
                    contentDescription = "Playlist",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // 错误提示
        if (playbackState == PlaybackState.ERROR) {
             Text(
                 "播放出错",
                 color = Color.Red,
                 fontSize = 10.sp,
                 modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 8.dp)
             )
        }
        
        // 进度条背景
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.LightGray.copy(alpha = 0.3f))
        )
        
        // 进度条
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress)
                .height(2.dp)
                .background(MiuixTheme.colorScheme.primary)
        )
    }
}

@Composable
fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
