package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.MusicApi
import api.GlobalState
import api.GlobalPlayerController
import com.seiko.imageloader.ui.AutoSizeImage
import config.ConfigManager
import kotlinx.coroutines.launch
import model.Playlist
import model.Song
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import utils.BackHandler

import data.MusicRepository

@Composable
fun PlaylistScreen(
    repository: MusicRepository,
    modifier: Modifier = Modifier
) {
    // 1. 获取所有歌单 (本地全量缓存, 实时更新)
    val playlists by repository.getAllPlaylists().collectAsState(initial = emptyList())

    // 详情页状态
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    
    // 2. 获取当前选定歌单的歌曲 (本地缓存)
    val playlistSongs by remember(selectedPlaylist) {
        if (selectedPlaylist == null) kotlinx.coroutines.flow.flowOf(emptyList()) 
        else repository.getSongsInPlaylist(selectedPlaylist!!.id)
    }.collectAsState(initial = emptyList())
    
    // 适配安卓返回键：若处于详情页，点返回键则退回列表
    BackHandler(enabled = selectedPlaylist != null) {
        selectedPlaylist = null
    }

    // 处理进入歌单详情
    val onPlaylistClick: (Playlist) -> Unit = { playlist ->
        selectedPlaylist = playlist
    }

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = selectedPlaylist?.name ?: "收藏夹",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (selectedPlaylist != null) {
                        IconButton(
                            onClick = { selectedPlaylist = null },
                            modifier = Modifier.padding(start = 16.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回"
                            )
                        }
                    }
                }
            )
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (selectedPlaylist == null) {
                // 显示收藏夹列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists) { playlist ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlaylistClick(playlist) }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 图标区分：默认用心，其他用锁
                                if (playlist.id == "default") {
                                    Icon(
                                        imageVector = MiuixIcons.Favorites,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = MiuixIcons.Lock,
                                        contentDescription = "Read Only",
                                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium))
                                    Text(
                                        "${playlist.songCount} 首歌曲" + if (playlist.isDefault == 1) " · 默认" else "",
                                        style = TextStyle(fontSize = 14.sp, color = Color.Gray)
                                    )
                                }
                                Icon(
                                    imageVector = MiuixIcons.ChevronForward,
                                    contentDescription = null,
                                    tint = Color.LightGray
                                )
                            }
                        }
                    }
                }
            } else {
                // 显示歌单内歌曲列表
                if (playlistSongs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中...")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(playlistSongs) { song ->
                            SongItem(
                                song = song,
                                onClick = {
                                    if (GlobalPlayerController.playlist.value != playlistSongs) {
                                        GlobalPlayerController.setPlaylist(playlistSongs)
                                    }
                                    GlobalPlayerController.play(song)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongItem(song: Song, onClick: () -> Unit) {
    val currentSong by GlobalPlayerController.currentSong.collectAsState()
    val isPlaying = currentSong?.id == song.id
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面加载逻辑：优先尝试本地缓存 -> 远程服务器
            val albumArtUrl = utils.CoverUtil.getCoverUrl(song)
            
            if (albumArtUrl != null) {
                AutoSizeImage(
                    url = albumArtUrl,
                    contentDescription = "Album Art",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.LightGray)
                )
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title ?: song.filename ?: "未知音乐",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isPlaying) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                    )
                )
                Text(
                    song.artist ?: "未知艺术家",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = if (isPlaying) MiuixTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.Gray
                    )
                )
            }
        }
    }
}
