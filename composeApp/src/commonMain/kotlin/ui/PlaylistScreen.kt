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

@Composable
fun PlaylistScreen(modifier: Modifier = Modifier) {
    val api = remember { MusicApi() }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 详情页状态
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var playlistSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isSongsLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // 适配安卓返回键：若处于详情页，点返回键则退回列表
    BackHandler(enabled = selectedPlaylist != null) {
        selectedPlaylist = null
    }

    // 加载收藏夹列表
    val loadPlaylists = {
        coroutineScope.launch {
            try {
                isLoading = true
                playlists = api.getFavoritePlaylists()
                isLoading = false
                errorMessage = null
            } catch (e: Throwable) {
                isLoading = false
                errorMessage = when {
                    e.message?.contains("401", ignoreCase = true) == true || e.message?.contains("unauthorized", ignoreCase = true) == true ->
                        "认证失败，请在'系统'页面配置正确的密码"

                    e.message?.contains("fetch", ignoreCase = true) == true || 
                    e.message?.contains("Network", ignoreCase = true) == true || 
                    e.message?.contains("Connection", ignoreCase = true) == true ||
                    e.message?.contains("failed", ignoreCase = true) == true ->
                        "服务器连接失败，请检查网络连接或后端配置"

                    else -> "发生异常\n(${e.message ?: "未知错误"})"
                }
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(Unit) {
        loadPlaylists()
        GlobalState.refreshSignal.collect {
            loadPlaylists()
            selectedPlaylist?.let { playlist ->
                coroutineScope.launch {
                    try {
                        isSongsLoading = true
                        val songIds = api.getPlaylistSongs(playlist.id)
                        val allSongs = api.getMusicList()
                        playlistSongs = allSongs.filter { it.id in songIds }
                        isSongsLoading = false
                    } catch (e: Throwable) {
                        isSongsLoading = false
                        println("Failed to load playlist songs: ${e.message}")
                    }
                }
            }
        }
    }

    // 处理进入收藏夹详情
    val onPlaylistClick: (Playlist) -> Unit = { playlist ->
        selectedPlaylist = playlist
        coroutineScope.launch {
            try {
                isSongsLoading = true
                val songIds = api.getPlaylistSongs(playlist.id)
                val allSongs = api.getMusicList()
                playlistSongs = allSongs.filter { it.id in songIds }
                isSongsLoading = false
            } catch (e: Throwable) {
                isSongsLoading = false
                println("Failed to load playlist songs: ${e.message}")
            }
        }
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
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在加载...")
                    }
                }
                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Text(
                                errorMessage!!, 
                                style = TextStyle(
                                    color = MiuixTheme.colorScheme.error,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { loadPlaylists() }) {
                                Text("重试")
                            }
                        }
                    }
                }
                selectedPlaylist == null -> {
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
                                    Icon(
                                        imageVector = MiuixIcons.Favorites,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
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
                }
                else -> {
                    // 显示收藏夹内歌曲列表
                    if (isSongsLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("正在加载歌曲...")
                        }
                    } else if (playlistSongs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("收藏夹为空")
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
                                        GlobalPlayerController.setPlaylist(playlistSongs)
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
            // 封面（复用逻辑）
            val hash = ConfigManager.getPasswordHash()
            val authSuffix = if (hash != null) {
                val separator = if (song.albumArt?.contains("?") == true) "&" else "?"
                "${separator}auth=$hash"
            } else ""
            
            val albumArtUrl = song.albumArt?.let {
                if (it.startsWith("http")) it else "${ConfigManager.getBaseUrl()}$it$authSuffix"
            }
            
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
