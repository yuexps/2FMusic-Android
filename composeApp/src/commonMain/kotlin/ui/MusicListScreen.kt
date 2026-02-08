package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import api.GlobalPlayerController
import api.MusicApi
import api.GlobalState
import model.Song
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import utils.BackHandler

import data.MusicRepository

@Composable
fun MusicListScreen(
    repository: MusicRepository,
    modifier: Modifier = Modifier
) {
    val songsEntities by repository.getLocalSongs().collectAsState(initial = emptyList())
    val songs = remember(songsEntities) {
        songsEntities.map { entity ->
            Song(
                id = entity.id,
                path = entity.path ?: "",
                filename = entity.filename,
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                mtime = entity.mtime ?: 0.0,
                size = entity.size ?: 0L,
                hasCover = if (entity.hasCover == 1L) 1 else 0,
                albumArt = entity.albumArt
            )
        }
    }
    
    var isSyncing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val currentSong by GlobalPlayerController.currentSong.collectAsState()
    val scope = rememberCoroutineScope()
    
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        searchQuery = ""
    }

    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter { 
            it.title?.contains(searchQuery, ignoreCase = true) == true ||
            it.artist?.contains(searchQuery, ignoreCase = true) == true ||
            it.filename?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    val syncSongs = suspend {
        try {
            isSyncing = true
            repository.sync()
            isSyncing = false
            errorMessage = null
        } catch (e: Throwable) {
            isSyncing = false
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

    LaunchedEffect(Unit) {
        syncSongs()
        GlobalState.refreshSignal.collect {
            syncSongs()
        }
    }

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "音乐库",
                scrollBehavior = scrollBehavior
            )
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // 搜索栏入口
            SearchBar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                inputField = {
                    InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { /* 确认搜索 */ },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        label = "搜索音乐...",
                        leadingIcon = {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = "搜索",
                                modifier = Modifier.padding(start = 12.dp, end = 8.dp)
                            )
                        }
                    )
                },
                expanded = isSearchExpanded,
                onExpandedChange = { isSearchExpanded = it },
                outsideEndAction = {
                    Text(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable(
                                interactionSource = null, 
                                indication = null
                            ) { 
                                isSearchExpanded = false
                                searchQuery = ""
                            },
                        text = "取消",
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            ) {
                // 搜索结果展示区：背景点击即收起
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = null, 
                            indication = null
                        ) { 
                            isSearchExpanded = false 
                        }
                ) {
                    if (searchQuery.isNotBlank()) {
                        if (filteredSongs.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("未找到相关歌曲", style = TextStyle(color = Color.Gray))
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredSongs) { song ->
                                    SongItem(
                                        song = song,
                                        currentSong = currentSong,
                                        onClick = {
                                            GlobalPlayerController.setPlaylist(filteredSongs)
                                            GlobalPlayerController.play(song)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 顶部同步状态提示（仅在有数据且有错误时显示）
            if (songs.isNotEmpty()) {
                if (errorMessage != null) {
                    Text(
                        "同步失败: $errorMessage",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                    )
                }
            }

            // 主内容区域
            when {
                songs.isEmpty() -> {
                    when {
                        isSyncing -> {
                            Box(
                                Modifier.fillMaxSize().weight(1f),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text("正在首次拉取音乐库...")
                            }
                        }
                        errorMessage != null -> {
                             Box(
                                Modifier.fillMaxSize().weight(1f).padding(horizontal = 12.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        errorMessage!!,
                                        style = TextStyle(
                                            color = MiuixTheme.colorScheme.error,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 32.dp)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = { 
                                            scope.launch { syncSongs() }
                                        }
                                    ) {
                                        Text("同步")
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(songs) { song ->
                            SongItem(
                                song = song,
                                currentSong = currentSong,
                                onClick = {
                                    GlobalPlayerController.setPlaylist(songs)
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
fun SongItem(
    song: Song,
    currentSong: Song?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            // 封面加载逻辑：优先本地缓存 -> 远程服务器
            val albumArtUrl = utils.CoverUtil.getCoverUrl(song)

            if (albumArtUrl != null) {
                com.seiko.imageloader.ui.AutoSizeImage(
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
                val isPlaying = currentSong?.id == song.id
                Text(
                    song.title ?: song.filename ?: "未知音乐",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isPlaying) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    song.artist ?: "未知艺术家",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = if (isPlaying) MiuixTheme.colorScheme.primary.copy(
                            alpha = 0.7f
                        ) else Color.Gray
                    )
                )
            }
        }
    }
}
