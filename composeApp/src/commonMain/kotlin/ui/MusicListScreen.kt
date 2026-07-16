package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import api.GlobalState
import api.MusicApi
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
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import utils.BackHandler
import utils.Platform

import data.MusicRepository
import data.DownloadResult

@Composable
fun MusicListScreen(
    repository: MusicRepository,
    modifier: Modifier = Modifier
) {
    val songs by repository.getLocalSongs().collectAsState(initial = emptyList())
    
    var isSyncing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val currentSong by Platform.playerController.currentSong.collectAsState()
    val scope = rememberCoroutineScope()
    
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }

    // 重构引入交互控制状态
    val api = remember { MusicApi() }
    val playlists by repository.getAllPlaylists().collectAsState(initial = emptyList())

    var activeMenuSong by remember { mutableStateOf<Song?>(null) }
    var showSelectPlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var isBatchMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(emptySet<String>()) }

    BackHandler(enabled = isSearchExpanded || isBatchMode) {
        if (isSearchExpanded) {
            isSearchExpanded = false
            searchQuery = ""
        } else if (isBatchMode) {
            isBatchMode = false
            selectedSongIds = emptySet()
        }
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
        floatingToolbar = {
            if (isBatchMode) {
                FloatingToolbar(
                    cornerRadius = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        // 1. 批量收藏图标按钮
                        IconButton(
                            onClick = {
                                if (selectedSongIds.isEmpty()) {
                                    Platform.toast.show("请先选择歌曲")
                                } else {
                                    activeMenuSong = null
                                    showSelectPlaylistDialog = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Favorites,
                                contentDescription = "Batch Favorite",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 2. 批量下载图标按钮
                        IconButton(
                            onClick = {
                                if (selectedSongIds.isEmpty()) {
                                    Platform.toast.show("请先选择歌曲")
                                } else {
                                    scope.launch {
                                        Platform.toast.show("已加入后台批量下载: ${selectedSongIds.size} 首歌曲")
                                        songs.filter { selectedSongIds.contains(it.id) }.forEach { song ->
                                            repository.downloadMusic(song)
                                        }
                                        isBatchMode = false
                                        selectedSongIds = emptySet()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Download,
                                contentDescription = "Batch Download",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 3. 批量删除图标按钮
                        IconButton(
                            onClick = {
                                if (selectedSongIds.isEmpty()) {
                                    Platform.toast.show("请先选择歌曲")
                                } else {
                                    activeMenuSong = null
                                    showDeleteConfirmDialog = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "Batch Delete",
                                tint = Color.Red.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 4. 退出批量管理图标按钮 (用 Close 图标)
                        IconButton(
                            onClick = {
                                isBatchMode = false
                                selectedSongIds = emptySet()
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Close,
                                contentDescription = "Exit Batch",
                                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        },
        floatingToolbarPosition = ToolbarPosition.BottomEnd,
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
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
                                    Text("未找到相关歌曲", style = TextStyle(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)))
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
                                            isBatchMode = isBatchMode,
                                            isSelected = selectedSongIds.contains(song.id),
                                            onSelectedChange = { checked ->
                                                selectedSongIds = if (checked) selectedSongIds + song.id else selectedSongIds - song.id
                                            },
                                            onClick = {
                                                if (Platform.playerController.playlist.value != filteredSongs) {
                                                    Platform.playerController.setPlaylist(filteredSongs)
                                                }
                                                Platform.playerController.play(song)
                                            },
                                            onPlayClick = {
                                                if (Platform.playerController.playlist.value != filteredSongs) {
                                                    Platform.playerController.setPlaylist(filteredSongs)
                                                }
                                                Platform.playerController.play(song)
                                            },
                                            onAddToQueueClick = {
                                                val currentPlaylist = Platform.playerController.playlist.value.toMutableList()
                                                if (!currentPlaylist.any { it.id == song.id }) {
                                                    currentPlaylist.add(song)
                                                    Platform.playerController.setPlaylist(currentPlaylist)
                                                    Platform.toast.show("已添加至队列")
                                                } else {
                                                    Platform.toast.show("歌曲已在队列中")
                                                }
                                            },
                                            onAddToPlaylistClick = {
                                                activeMenuSong = song
                                                showSelectPlaylistDialog = true
                                            },
                                            onBatchManageClick = {
                                                isBatchMode = true
                                                selectedSongIds = setOf(song.id)
                                            }
                                        )
                                    }
                                    if (isBatchMode) {
                                        item {
                                            Spacer(Modifier.height(88.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                when {
                    songs.isEmpty() -> {
                        when {
                            isSyncing -> {
                                Box(
                                    Modifier.fillMaxSize().weight(1f),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    Text("加载中...")
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
                                            Text("重试")
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
                                    isBatchMode = isBatchMode,
                                    isSelected = selectedSongIds.contains(song.id),
                                    onSelectedChange = { checked ->
                                        selectedSongIds = if (checked) selectedSongIds + song.id else selectedSongIds - song.id
                                    },
                                    onClick = {
                                        if (Platform.playerController.playlist.value != songs) {
                                            Platform.playerController.setPlaylist(songs)
                                        }
                                        Platform.playerController.play(song)
                                    },
                                    onPlayClick = {
                                        if (Platform.playerController.playlist.value != songs) {
                                            Platform.playerController.setPlaylist(songs)
                                        }
                                        Platform.playerController.play(song)
                                    },
                                    onAddToQueueClick = {
                                        val currentPlaylist = Platform.playerController.playlist.value.toMutableList()
                                        if (!currentPlaylist.any { it.id == song.id }) {
                                            currentPlaylist.add(song)
                                            Platform.playerController.setPlaylist(currentPlaylist)
                                            Platform.toast.show("已添加至队列")
                                        } else {
                                            Platform.toast.show("歌曲已在队列中")
                                        }
                                    },
                                    onAddToPlaylistClick = {
                                        activeMenuSong = song
                                        showSelectPlaylistDialog = true
                                    },
                                    onBatchManageClick = {
                                        isBatchMode = true
                                        selectedSongIds = setOf(song.id)
                                    }
                                )
                            }
                            if (isBatchMode) {
                                item {
                                    Spacer(Modifier.height(88.dp))
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    WindowDialog(
        title = "确认物理删除",
        summary = if (isBatchMode) "确定要从服务端磁盘彻底物理删除选中的 ${selectedSongIds.size} 首歌曲吗？这将抹除其所有的数据库索引及附属的本地缓存，且不可撤销！" else "确定要从服务端磁盘彻底物理删除歌曲「${activeMenuSong?.filename ?: ""}」吗？这将抹除其所有的数据库索引及附属的封面歌词缓存，且不可撤销！",
        show = showDeleteConfirmDialog,
        onDismissRequest = { showDeleteConfirmDialog = false }
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                text = "取消",
                onClick = { showDeleteConfirmDialog = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "确认",
                onClick = {
                    if (isBatchMode) {
                        scope.launch {
                            try {
                                var successCount = 0
                                songs.filter { selectedSongIds.contains(it.id) }.forEach { song ->
                                    val res = api.deleteFile(song.id)
                                    if (res.success) {
                                        repository.deleteLocalAudio(song.id)
                                        successCount++
                                    }
                                }
                                GlobalState.triggerRefresh()
                                Platform.toast.show("成功批量删除 ${successCount} 首歌曲")
                            } catch (e: Exception) {
                                Platform.toast.show("批量删除发生错误")
                            }
                            isBatchMode = false
                            selectedSongIds = emptySet()
                            showDeleteConfirmDialog = false
                        }
                    } else {
                        activeMenuSong?.let { song ->
                            scope.launch {
                                try {
                                    val res = api.deleteFile(song.id)
                                    if (res.success) {
                                        repository.deleteLocalAudio(song.id)
                                        GlobalState.triggerRefresh()
                                        Platform.toast.show("删除成功：${song.title}")
                                    } else {
                                        Platform.toast.show("删除失败")
                                    }
                                } catch (e: Exception) {
                                    Platform.toast.show("删除出错")
                                }
                                showDeleteConfirmDialog = false
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }

    WindowDialog(
        title = "添加音乐至",
        show = showSelectPlaylistDialog,
        onDismissRequest = { showSelectPlaylistDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (playlists.isEmpty()) {
                Text("暂无歌单", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 16.dp))
            } else {
                playlists.forEach { playlist ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (isBatchMode) {
                                    scope.launch {
                                        try {
                                            var successCount = 0
                                            selectedSongIds.forEach { songId ->
                                                repository.addSongToPlaylist(songId, playlist.id)
                                                successCount++
                                            }
                                            Platform.toast.show("已成功将 ${successCount} 首音乐添加至「${playlist.name}」")
                                        } catch (e: Exception) {
                                            Platform.toast.show("批量添加失败")
                                        }
                                        isBatchMode = false
                                        selectedSongIds = emptySet()
                                        showSelectPlaylistDialog = false
                                    }
                                } else {
                                    activeMenuSong?.let { song ->
                                        scope.launch {
                                            try {
                                                repository.addSongToPlaylist(song.id, playlist.id)
                                                Platform.toast.show("已成功添加至「${playlist.name}」")
                                            } catch (e: Exception) {
                                                Platform.toast.show("添加失败")
                                            }
                                            showSelectPlaylistDialog = false
                                        }
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Favorites,
                                contentDescription = null,
                                tint = getPlaylistIconColor(playlist.id, MiuixTheme.colorScheme.primary),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(playlist.name, fontSize = 16.sp)
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = "取消",
                onClick = { showSelectPlaylistDialog = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SongItem(
    song: Song,
    currentSong: Song?,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onBatchManageClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                if (isBatchMode) {
                    onSelectedChange(!isSelected)
                } else {
                    onClick()
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            if (isBatchMode) {
                Checkbox(
                    state = androidx.compose.ui.state.ToggleableState(isSelected),
                    onClick = { onSelectedChange(!isSelected) },
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

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
                        .background(MiuixTheme.colorScheme.secondaryContainer)
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
                    ) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
            }
            if (!isBatchMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = "操作",
                            tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }

                    WindowListPopup(
                        show = showMenu,
                        alignment = PopupPositionProvider.Align.End,
                        onDismissRequest = { showMenu = false }
                    ) {
                        ListPopupColumn {
                            DropdownImpl(
                                text = "播放",
                                optionSize = 4,
                                isSelected = false,
                                onSelectedIndexChange = {
                                    showMenu = false
                                    onPlayClick()
                                },
                                index = 0
                            )
                            DropdownImpl(
                                text = "添加至队列",
                                optionSize = 4,
                                isSelected = false,
                                onSelectedIndexChange = {
                                    showMenu = false
                                    onAddToQueueClick()
                                },
                                index = 1
                            )
                            DropdownImpl(
                                text = "添加到收藏夹",
                                optionSize = 4,
                                isSelected = false,
                                onSelectedIndexChange = {
                                    showMenu = false
                                    onAddToPlaylistClick()
                                },
                                index = 2
                            )
                            DropdownImpl(
                                text = "批量管理",
                                optionSize = 4,
                                isSelected = false,
                                onSelectedIndexChange = {
                                    showMenu = false
                                    onBatchManageClick()
                                },
                                index = 3
                            )
                        }
                    }
                }
            }
        }
    }
}

private val MorandiColors = listOf(
    Color(0xFFF48FB1), // 柔粉
    Color(0xFF90CAF9), // 淡蓝
    Color(0xFFA5D6A7), // 薄荷绿
    Color(0xFFCE93D8), // 薰衣草紫
    Color(0xFFFFE082), // 香草黄
    Color(0xFFFFAB91), // 珊瑚橙
    Color(0xFF80CBC4), // 青玉色
    Color(0xFFB0BEC5)  // 浅灰蓝
)

private fun getPlaylistIconColor(playlistId: String, primaryColor: Color): Color {
    if (playlistId == "default") return primaryColor
    val index = kotlin.math.abs(playlistId.hashCode()) % MorandiColors.size
    return MorandiColors[index]
}


