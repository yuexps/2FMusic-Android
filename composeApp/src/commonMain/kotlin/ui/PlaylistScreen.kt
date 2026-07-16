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

import data.MusicRepository

@Composable
fun PlaylistScreen(
    repository: MusicRepository,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    // 1. 获取所有歌单 (实时更新)
    val playlists by repository.getAllPlaylists().collectAsState(initial = emptyList())

    // 详情页状态
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    
    // 2. 获取当前选定歌单的歌曲
    val playlistSongs by remember(selectedPlaylist) {
        if (selectedPlaylist == null) kotlinx.coroutines.flow.flowOf(emptyList()) 
        else repository.getSongsInPlaylist(selectedPlaylist!!.id)
    }.collectAsState(initial = emptyList())

    // UI 控制状态
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var activeMenuPlaylistId by remember { mutableStateOf<String?>(null) }

    var activeMenuSong by remember { mutableStateOf<Song?>(null) }
    var showSelectPlaylistDialog by remember { mutableStateOf(false) }
    var selectPlaylistMode by remember { mutableStateOf("") } // "add" | "move"
    
    // 适配安卓返回键
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
                },
                actions = {
                    if (selectedPlaylist == null) {
                        IconButton(
                            onClick = {
                                newPlaylistName = ""
                                showCreateDialog = true
                            },
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = "新建歌单"
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
                                // 图标区分：默认用心，其他用歌单标识
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
                                        "${playlist.songCount} 首歌曲" + if (playlist.isDefault == 1) " · 默认" else "",
                                        style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    )
                                }

                                if (playlist.id == "default") {
                                    Icon(
                                        imageVector = MiuixIcons.ChevronForward,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                } else {
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
                }
            } else {
                // 显示歌单内歌曲列表
                if (playlistSongs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无歌曲，快去添加吧")
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
                                    if (Platform.playerController.playlist.value != playlistSongs) {
                                        Platform.playerController.setPlaylist(playlistSongs)
                                    }
                                    Platform.playerController.play(song)
                                },
                                showRemoveOption = true,
                                onRemoveClick = {
                                    selectedPlaylist?.let { playlist ->
                                        coroutineScope.launch {
                                            try {
                                                repository.removeSongFromPlaylist(song.id, playlist.id)
                                                Platform.toast.show("已从当前歌单移出")
                                            } catch (e: Exception) {
                                                Platform.toast.show("移出失败")
                                            }
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
                                }
                            )
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
                Text("暂无可选择的其它歌单", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 16.dp))
            } else {
                targetPlaylists.forEach { playlist ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                activeMenuSong?.let { song ->
                                    coroutineScope.launch {
                                        try {
                                            if (selectPlaylistMode == "move" && selectedPlaylist != null) {
                                                repository.batchMoveSongs(listOf(song.id), selectedPlaylist!!.id, playlist.id)
                                                Platform.toast.show("转移成功")
                                            } else {
                                                repository.addSongToPlaylist(song.id, playlist.id)
                                                Platform.toast.show("添加成功")
                                            }
                                        } catch (e: Exception) {
                                            Platform.toast.show("操作失败")
                                        }
                                        showSelectPlaylistDialog = false
                                    }
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
    onClick: () -> Unit,
    showRemoveOption: Boolean = false,
    onRemoveClick: () -> Unit = {},
    onAddToOtherClick: () -> Unit = {},
    onMoveToOtherClick: () -> Unit = {}
) {
    val currentSong by Platform.playerController.currentSong.collectAsState()
    val isPlaying = currentSong?.id == song.id
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                        if (showRemoveOption) {
                            options.add("从当前歌单移出")
                        }
                        options.add("添加到其它歌单")
                        if (showRemoveOption) {
                            options.add("移动至其它歌单")
                        }
                        
                        options.forEachIndexed { index, text ->
                            DropdownImpl(
                                text = text,
                                optionSize = options.size,
                                isSelected = false,
                                onSelectedIndexChange = {
                                    showMenu = false
                                    when (text) {
                                        "从当前歌单移出" -> onRemoveClick()
                                        "添加到其它歌单" -> onAddToOtherClick()
                                        "移动至其它歌单" -> onMoveToOtherClick()
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

