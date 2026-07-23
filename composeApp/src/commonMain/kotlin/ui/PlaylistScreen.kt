package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seiko.imageloader.ui.AutoSizeImage
import model.Playlist
import model.Song
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import utils.BackHandler
import utils.Platform
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

import data.MusicRepository

@Composable
fun PlaylistScreen(
    repository: MusicRepository,
    onBatchModeChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    // 1. 获取所有歌单 (实时更新)
    val playlists by repository.getAllPlaylists().collectAsState(initial = emptyList())

    // 详情页状态
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var historyRefreshTrigger by remember { mutableStateOf(0) }

    // 2. 获取当前选定歌单的歌曲
    val playlistSongs by remember(selectedPlaylist, historyRefreshTrigger) {
        if (selectedPlaylist == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else if (selectedPlaylist!!.id == "history") repository.getPlayHistory()
        else repository.getSongsInPlaylist(selectedPlaylist!!.id)
    }.collectAsState(initial = null)

    // UI 控制状态
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var activeMenuPlaylistId by remember { mutableStateOf<String?>(null) }

    var activeMenuSong by remember { mutableStateOf<Song?>(null) }
    var showSelectPlaylistDialog by remember { mutableStateOf(false) }
    var selectPlaylistMode by remember { mutableStateOf("") } // "add" | "move" | "batch_move"
    var isBatchMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(emptySet<String>()) }

    // 适配安卓返回键
    BackHandler(enabled = selectedPlaylist != null || isBatchMode) {
        if (isBatchMode) {
            isBatchMode = false
            selectedSongIds = emptySet()
        } else {
            selectedPlaylist = null
        }
    }

    LaunchedEffect(isBatchMode) {
        onBatchModeChange(isBatchMode)
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            api.GlobalState.historyRefreshSignal.collect {
                historyRefreshTrigger++
            }
        }
        coroutineScope.launch {
            api.GlobalState.favoriteRefreshSignal.collect {
                try {
                    repository.syncPlaylists()
                } catch (_: Exception) {}
            }
        }
    }

    // 处理进入歌单详情
    val onPlaylistClick: (Playlist) -> Unit = { playlist ->
        selectedPlaylist = playlist
    }

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selectedPlaylist != null && isBatchMode,
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
                        },
                        actions = {
                            if (selectedPlaylist?.id == "history") {
                                var showClearConfirm by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showClearConfirm = true },
                                    modifier = Modifier.padding(end = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = "清空历史"
                                    )
                                }

                                    WindowDialog(
                                        title = "清空历史",
                                        show = showClearConfirm,
                                        onDismissRequest = { showClearConfirm = false }
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("确定要清空全部播放历史记录吗？", fontSize = 16.sp)
                                            Spacer(Modifier.height(20.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                TextButton(
                                                    text = "取消",
                                                    onClick = { showClearConfirm = false },
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                TextButton(
                                                    text = "清空",
                                                    onClick = {
                                                        showClearConfirm = false
                                                        coroutineScope.launch {
                                                            try {
                                                                repository.clearHistory()
                                                                historyRefreshTrigger++
                                                                Platform.toast.show("已清空播放历史")
                                                            } catch (e: Exception) {
                                                                Platform.toast.show("清空失败")
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                                )
                                            }
                                        }
                                    }
                            }
                        }
                    )
                }
            }
        },
        floatingToolbar = {
            AnimatedVisibility(
                visible = selectedPlaylist != null && isBatchMode,
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
                            verticalAlignment = Alignment.CenterVertically
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
                                contentAlignment = Alignment.Center
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isHistory = selectedPlaylist?.id == "history"
                            BatchActionButton(
                                icon = MiuixIcons.Favorites,
                                label = if (isHistory) "收藏" else "移动",
                                tint = MiuixTheme.colorScheme.primary,
                                onClick = {
                                    if (selectedSongIds.isEmpty()) {
                                        Platform.toast.show("请先选择歌曲")
                                    } else {
                                        selectPlaylistMode = if (isHistory) "batch_add" else "batch_move"
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
                                        coroutineScope.launch {
                                            Platform.toast.show("已加入后台批量下载: ${selectedSongIds.size} 首歌曲")
                                            playlistSongs?.filter { selectedSongIds.contains(it.id) }?.forEach { song ->
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
                                label = if (selectedPlaylist?.id == "history") "移出历史" else "移出歌单",
                                tint = MiuixTheme.colorScheme.error,
                                onClick = {
                                    if (selectedSongIds.isEmpty()) {
                                        Platform.toast.show("请先选择歌曲")
                                    } else {
                                        selectedPlaylist?.let { playlist ->
                                            coroutineScope.launch {
                                                try {
                                                    var count = 0
                                                    selectedSongIds.forEach { songId ->
                                                        if (playlist.id == "history") {
                                                            val song = playlistSongs?.find { it.id == songId }
                                                            val playTime = song?.mtime?.toLong() ?: 0L
                                                            repository.removeHistory(songId, playTime)
                                                        } else {
                                                            repository.removeSongFromPlaylist(songId, playlist.id)
                                                        }
                                                        count++
                                                    }
                                                    if (playlist.id == "history") {
                                                        historyRefreshTrigger++
                                                    }
                                                    Platform.toast.show("已成功移出 ${count} 首歌曲")
                                                } catch (e: Exception) {
                                                    Platform.toast.show("批量移出失败")
                                                }
                                                isBatchMode = false
                                                selectedSongIds = emptySet()
                                            }
                                        }
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
            AnimatedContent(
                targetState = selectedPlaylist,
                transitionSpec = {
                    if (targetState != null && initialState == null) {
                        (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(300))) togetherWith
                                (slideOutHorizontally { width -> -width / 3 } + fadeOut(animationSpec = tween(300)))
                    } else {
                        (slideInHorizontally { width -> -width / 3 } + fadeIn(animationSpec = tween(300))) togetherWith
                                (slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(300)))
                    }
                },
                label = "playlist_transition"
            ) { currentPlaylist ->
                if (currentPlaylist == null) {
                    // 显示收藏夹列表
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val defaultPlaylist = playlists.find { it.isDefault == 1 || it.id == "default" }
                            ?: Playlist(id = "default", name = "我的收藏", songCount = 0, cover = null, isDefault = 1, createdAt = 0.0)

                        // 1. 默认收藏夹卡片 (我的收藏)
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onPlaylistClick(defaultPlaylist) }
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
                                        Text(defaultPlaylist.name, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium))
                                        Text(
                                            "${defaultPlaylist.songCount} 首歌曲 · 默认",
                                            style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                        )
                                    }

                                    Icon(
                                        imageVector = MiuixIcons.ChevronForward,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }

                        // 3. 其它自建歌单卡片
                        val otherPlaylists = playlists.filter { it.isDefault != 1 && it.id != "default" }
                        items(otherPlaylists) { playlist ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onPlaylistClick(playlist) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val iconColor = getPlaylistIconColor(playlist.id, MiuixTheme.colorScheme.primary)
                                    Icon(
                                        imageVector = MiuixIcons.Favorites,
                                        contentDescription = null,
                                        tint = iconColor,
                                        modifier = Modifier.size(32.dp)
                                    )

                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(playlist.name, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium))
                                        Text(
                                            "${playlist.songCount} 首歌曲",
                                            style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                        )
                                    }

                                    Box {
                                        IconButton(onClick = { activeMenuPlaylistId = playlist.id }) {
                                            Icon(
                                                imageVector = MiuixIcons.More,
                                                contentDescription = "操作",
                                                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            )
                                        }

                                        WindowListPopup(
                                            show = activeMenuPlaylistId == playlist.id,
                                            alignment = PopupPositionProvider.Align.End,
                                            onDismissRequest = { activeMenuPlaylistId = null }
                                        ) {
                                            ListPopupColumn {
                                                DropdownImpl(
                                                    text = "删除歌单",
                                                    optionSize = 1,
                                                    isSelected = false,
                                                    onSelectedIndexChange = {
                                                        activeMenuPlaylistId = null
                                                        playlistToDelete = playlist
                                                    },
                                                    index = 0
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 显示歌单内歌曲列表
                    val songs = playlistSongs
                    if (songs == null) {
                        var showLoadingIndicator by remember(currentPlaylist.id) { mutableStateOf(false) }
                        LaunchedEffect(currentPlaylist.id) {
                            delay(300) // 延迟 300 毫秒，常规速度不渲染任何指示器防止闪烁
                            showLoadingIndicator = true
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (showLoadingIndicator) {
                                InfiniteProgressIndicator(
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    } else if (songs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无歌曲，快去添加吧")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(songs) { song ->
                                SongItem(
                                    song = song,
                                    isBatchMode = isBatchMode,
                                    isSelected = selectedSongIds.contains(song.id),
                                    isHistory = currentPlaylist.id == "history",
                                    onSelectedChange = { checked ->
                                        selectedSongIds = if (checked) selectedSongIds + song.id else selectedSongIds - song.id
                                    },
                                    onClick = {
                                        if (Platform.playerController.playlist.value != songs) {
                                            Platform.playerController.setPlaylist(songs)
                                        }
                                        Platform.playerController.play(song)
                                    },
                                    showRemoveOption = true,
                                    onRemoveClick = {
                                        coroutineScope.launch {
                                            try {
                                                if (currentPlaylist.id == "history") {
                                                    repository.removeHistory(song.id, song.mtime?.toLong() ?: 0L)
                                                    historyRefreshTrigger++
                                                    Platform.toast.show("已从历史记录移出")
                                                } else {
                                                    repository.removeSongFromPlaylist(song.id, currentPlaylist.id)
                                                    Platform.toast.show("已从当前歌单移出")
                                                }
                                            } catch (e: Exception) {
                                                Platform.toast.show("移出失败")
                                            }
                                        }
                                    },
                                    onAddToOtherClick = {
                                        activeMenuSong = song
                                        selectPlaylistMode = "add"
                                        showSelectPlaylistDialog = true
                                    },
                                    onMoveToOtherClick = {
                                        activeMenuSong = song
                                        selectPlaylistMode = "move"
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

    // --- 3. 新建歌单对话框 ---
    WindowDialog(
        title = "新建歌单",
        show = showCreateDialog,
        onDismissRequest = { showCreateDialog = false }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = "歌单名称",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showCreateDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确认",
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            coroutineScope.launch {
                                try {
                                    repository.createPlaylist(newPlaylistName.trim())
                                    Platform.toast.show("歌单创建成功")
                                } catch (e: Exception) {
                                    Platform.toast.show("创建失败")
                                }
                                showCreateDialog = false
                            }
                        } else {
                            Platform.toast.show("歌单名不能为空")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }

    // --- 4. 删除歌单确认对话框 ---
    WindowDialog(
        title = "确认删除",
        summary = "确定要删除歌单「${playlistToDelete?.name ?: ""}」吗？此操作不可撤销，歌单内的音乐不会被物理删除。",
        show = playlistToDelete != null,
        onDismissRequest = { playlistToDelete = null }
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                text = "取消",
                onClick = { playlistToDelete = null },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "确认",
                onClick = {
                    playlistToDelete?.let { playlist ->
                        coroutineScope.launch {
                            try {
                                repository.deletePlaylist(playlist.id)
                                Platform.toast.show("歌单已删除")
                            } catch (e: Exception) {
                                Platform.toast.show("删除失败")
                            }
                            playlistToDelete = null
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }

    // --- 6. 选择目标歌单对话框 ---
    WindowDialog(
        title = if (selectPlaylistMode == "move") "移动音乐至" else "添加音乐至",
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
            val targetPlaylists = playlists.filter { selectedPlaylist == null || it.id != selectedPlaylist?.id }

            if (targetPlaylists.isEmpty()) {
                Text(
                    text = "暂无可选择的其它歌单",
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                targetPlaylists.forEach { playlist ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                coroutineScope.launch {
                                    try {
                                        if (selectPlaylistMode == "batch_move" && selectedPlaylist != null) {
                                            val ids = selectedSongIds.toList()
                                            repository.batchMoveSongs(ids, selectedPlaylist!!.id, playlist.id)
                                            Platform.toast.show("成功批量转移 ${ids.size} 首歌曲")
                                            isBatchMode = false
                                            selectedSongIds = emptySet()
                                        } else if (selectPlaylistMode == "batch_add") {
                                            val ids = selectedSongIds.toList()
                                            ids.forEach { id ->
                                                repository.addSongToPlaylist(id, playlist.id)
                                            }
                                            Platform.toast.show("成功批量收藏 ${ids.size} 首歌曲")
                                            isBatchMode = false
                                            selectedSongIds = emptySet()
                                        } else if (selectPlaylistMode == "move" && selectedPlaylist != null) {
                                            activeMenuSong?.let { song ->
                                                repository.batchMoveSongs(listOf(song.id), selectedPlaylist!!.id, playlist.id)
                                                Platform.toast.show("转移成功")
                                            }
                                        } else {
                                            activeMenuSong?.let { song ->
                                                repository.addSongToPlaylist(song.id, playlist.id)
                                                Platform.toast.show("添加成功")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Platform.toast.show("操作失败")
                                    }
                                    showSelectPlaylistDialog = false
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
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
                text = "创建新收藏夹",
                onClick = {
                    newPlaylistName = ""
                    showCreateDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SongItem(
    song: Song,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    showRemoveOption: Boolean = false,
    isHistory: Boolean = false,
    onRemoveClick: () -> Unit = {},
    onAddToOtherClick: () -> Unit = {},
    onMoveToOtherClick: () -> Unit = {},
    onBatchManageClick: () -> Unit = {}
) {
    val currentSong by Platform.playerController.currentSong.collectAsState()
    val isPlaying = currentSong?.id == song.id
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = isBatchMode,
                enter = expandHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(220),
                    expandFrom = Alignment.Start
                ) + fadeIn(androidx.compose.animation.core.tween(160)),
                exit = shrinkHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(180),
                    shrinkTowards = Alignment.Start
                ) + fadeOut(androidx.compose.animation.core.tween(120))
            ) {
                Checkbox(
                    state = androidx.compose.ui.state.ToggleableState(isSelected),
                    onClick = { onSelectedChange(!isSelected) },
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

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
                        .background(MiuixTheme.colorScheme.secondaryContainer)
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
                        color = if (isPlaying) MiuixTheme.colorScheme.primary.copy(alpha = 0.7f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                            val options = mutableListOf<String>()
                            if (isHistory) {
                                if (showRemoveOption) {
                                    options.add("删除记录")
                                }
                            } else {
                                options.add("下一首播放")
                                if (showRemoveOption) {
                                    options.add("从当前歌单移出")
                                }
                                options.add("添加到其它歌单")
                                if (showRemoveOption) {
                                    options.add("移动至其它歌单")
                                }
                                options.add("批量管理")
                            }

                            options.forEachIndexed { index, text ->
                                DropdownImpl(
                                    text = text,
                                    optionSize = options.size,
                                    isSelected = false,
                                    onSelectedIndexChange = {
                                        showMenu = false
                                        when (text) {
                                            "播放" -> onClick()
                                            "下一首播放" -> {
                                                Platform.playerController.insertNext(song)
                                                Platform.toast.show("已设为下一首播放")
                                            }
                                            "删除", "删除记录", "从当前歌单移出" -> onRemoveClick()
                                            "添加到其它歌单" -> onAddToOtherClick()
                                            "移动至其它歌单" -> onMoveToOtherClick()
                                            "批量管理" -> onBatchManageClick()
                                        }
                                    },
                                    index = index
                                )
                            }
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

@Composable
private fun BatchActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        verticalAlignment = Alignment.CenterVertically
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

