package ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import utils.BackHandler
import utils.Platform

import data.MusicRepository

@Composable
fun MusicListScreen(
    repository: MusicRepository,
    onBatchModeChange: (Boolean) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
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

    LaunchedEffect(isBatchMode) {
        onBatchModeChange(isBatchMode)
    }

    DisposableEffect(Unit) {
        onDispose { onBatchModeChange(false) }
    }

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
            errorMessage = e.message ?: e.toString()
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
            AnimatedContent(
                targetState = isBatchMode,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                },
                label = "batchTopBar"
            ) { batchMode ->
                if (batchMode) {
                    SmallTopAppBar(
                        title = "已选择 ${selectedSongIds.size} 首",
                        navigationIcon = {
                            Text(
                                text = "取消",
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .clickable {
                                        isBatchMode = false
                                        selectedSongIds = emptySet()
                                    }
                            )
                        }
                    )
                } else {
                    TopAppBar(
                        title = "音乐库",
                        scrollBehavior = scrollBehavior
                    )
                }
            }
        },
        floatingToolbar = {
            AnimatedVisibility(
                visible = isBatchMode,
                enter = slideInVertically(
                    animationSpec = tween(280),
                    initialOffsetY = { it / 2 }
                ) + fadeIn(tween(180)),
                exit = slideOutVertically(
                    animationSpec = tween(220),
                    targetOffsetY = { it / 2 }
                ) + fadeOut(tween(160))
            ) {
                FloatingToolbar(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    isBatchMode = false
                                    selectedSongIds = emptySet()
                                }
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Close,
                                    contentDescription = "退出批量管理",
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(
                                    text = "批量管理",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(Modifier.width(48.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            BatchActionButton(
                                icon = MiuixIcons.Favorites,
                                label = "收藏",
                                tint = MiuixTheme.colorScheme.primary,
                                onClick = {
                                    if (selectedSongIds.isEmpty()) {
                                        Platform.toast.show("请先选择歌曲")
                                    } else {
                                        activeMenuSong = null
                                        showSelectPlaylistDialog = true
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BatchActionButton(
                                icon = MiuixIcons.Download,
                                label = "下载",
                                tint = MiuixTheme.colorScheme.primary,
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
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BatchActionButton(
                                icon = MiuixIcons.Delete,
                                label = "删除",
                                tint = MiuixTheme.colorScheme.error,
                                onClick = {
                                    if (selectedSongIds.isEmpty()) {
                                        Platform.toast.show("请先选择歌曲")
                                    } else {
                                        activeMenuSong = null
                                        showDeleteConfirmDialog = true
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
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
                                                Platform.playerController.insertNext(song)
                                                Platform.toast.show("已设为下一首播放")
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
                                            Spacer(Modifier.height(132.dp))
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
                                            text = "网络请求失败，请前往设置页检查后端配置",
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
                                                onNavigateToSettings()
                                            }
                                        ) {
                                            Text("前往设置")
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
                                        Platform.playerController.insertNext(song)
                                        Platform.toast.show("已设为下一首播放")
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
                                    Spacer(Modifier.height(132.dp))
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
                Text(
                    text = "暂无歌单",
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
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
private fun BatchActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SongItem(
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
            AnimatedVisibility(
                visible = isBatchMode,
                enter = expandHorizontally(
                    animationSpec = tween(220),
                    expandFrom = androidx.compose.ui.Alignment.Start
                ) + fadeIn(tween(160)),
                exit = shrinkHorizontally(
                    animationSpec = tween(180),
                    shrinkTowards = androidx.compose.ui.Alignment.Start
                ) + fadeOut(tween(120))
            ) {
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
                                text = "下一首播放",
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
