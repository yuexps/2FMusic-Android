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
import utils.Platform
import api.GlobalState
import model.PlaybackState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext

@Composable
fun BottomPlayerBar(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    onClick: () -> Unit = {}
) {
    val currentSong by Platform.playerController.currentSong.collectAsState()
    val playbackState by Platform.playerController.playbackState.collectAsState()
    val progress by Platform.playerController.progress.collectAsState()


    if (currentSong == null) return

    val supportBlur = remember { isRuntimeShaderSupported() }
    val baseModifier = Modifier
        .fillMaxWidth()
        .height(76.dp)
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(20.dp))

    val bgModifier = if (supportBlur && backdrop != null) {
        baseModifier.textureBlur(
            backdrop = backdrop,
            shape = RoundedCornerShape(20.dp),
            colors = top.yukonga.miuix.kmp.blur.BlurDefaults.blurColors(
                blendColors = listOf(
                    top.yukonga.miuix.kmp.blur.BlendColorEntry(
                        color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        mode = top.yukonga.miuix.kmp.blur.BlurBlendMode.SrcOver
                    )
                ),
                brightness = 0f,
                contrast = 1f,
                saturation = 1f
            )
        )
    } else {
        baseModifier.background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
    }

    Box(
        modifier = bgModifier
            .clickable { onClick() }
            .padding(start = 10.dp, end = 2.dp),
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
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.secondaryContainer)
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
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }

            // 播放按钮
            IconButton(
                onClick = {
                    when (playbackState) {
                        PlaybackState.PLAYING -> {
                            Platform.playerController.pause()
                        }
                        PlaybackState.ERROR -> {
                            // 错误状态下点击重试
                            Platform.playerController.play(currentSong!!)
                        }
                        else -> {
                            Platform.playerController.resume()
                        }
                    }
                }
            ) {
                 if (playbackState == PlaybackState.ERROR) {
                      // 错误图标
                      Text("!", color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                  } else {
                    Icon(
                        imageVector = if (playbackState == PlaybackState.PLAYING) MiuixIcons.Pause else MiuixIcons.Play,
                        contentDescription = "Play/Pause",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                 }
            }

            Spacer(Modifier.width(4.dp))

            // 下一曲按钮
            IconButton(
                onClick = { Platform.playerController.next() }
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            // 播放列表按钮
            IconButton(
                onClick = { GlobalState.togglePlaylist(true) }
            ) {
                Icon(
                    imageVector = MiuixIcons.Playlist,
                    contentDescription = "Playlist",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        
        // 错误提示
         if (playbackState == PlaybackState.ERROR) {
              Text(
                  "播放出错",
                  color = MiuixTheme.colorScheme.error,
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
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f))
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
