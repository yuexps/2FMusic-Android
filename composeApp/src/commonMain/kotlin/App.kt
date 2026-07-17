package top.msfxp.music.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import utils.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import utils.Platform
import api.GlobalState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import ui.BottomPlayerBar
import ui.MusicListScreen
import ui.PlayerScreen
import ui.PlaylistScreen
import ui.SystemScreen
import utils.PlatformDependencies

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(platform: PlatformDependencies) {
    val repository = platform.repository
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showPlayerScreen by remember { mutableStateOf(false) }
    var isMusicBatchMode by remember { mutableStateOf(false) }
    val isDarkTheme = isSystemInDarkTheme()

    val navigationItems = remember {
        listOf(
            NavigationItem("音乐库", MiuixIcons.Music),
            NavigationItem("收藏夹", MiuixIcons.Favorites),
            NavigationItem("系统", MiuixIcons.Settings)
        )
    }

    val playlist by Platform.playerController.playlist.collectAsState()
    val showPlaylistState by GlobalState.showPlaylistState.collectAsState()
    val currentIndex by Platform.playerController.currentIndex.collectAsState()
    val currentSong by Platform.playerController.currentSong.collectAsState()

    // 处理安卓系统返回键
    BackHandler(enabled = showPlaylistState) {
        GlobalState.togglePlaylist(false)
    }

    BackHandler(enabled = !showPlaylistState && showPlayerScreen) {
        showPlayerScreen = false
    }

    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    // 监听键盘收起，自动清除焦点以隐藏光标
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) {
            // 当键盘收起时，清除焦点，确保光标消失
            focusManager.clearFocus()
        }
    }

    // 专门针对标签切换的副作用，确保切页即清焦点
    LaunchedEffect(selectedIndex) {
        focusManager.clearFocus()
    }

    LaunchedEffect(Unit) {
        // 1. 启动时监听本地数据库的收藏列表 (实现离线可用)
        launch {
            repository.ensureDefaultPlaylistExists()
            repository.getFavorites().collect { ids ->
                GlobalState.updateFavorites(ids)
            }
        }

        // 2. 网络同步逻辑
        suspend fun refreshFavs() {
            try {
                // 全量同步所有歌单及其歌曲到本地数据库
                repository.syncPlaylists()
            } catch (e: Throwable) {
                e.printStackTrace()
                // 网络失败时，不做任何操作，因为上面的 localFavsJob 已经保证了显示本地旧数据
            }
        }

        refreshFavs()
        GlobalState.refreshSignal.collect {
            refreshFavs()
        }
    }

    AppTheme(isDarkTheme = isDarkTheme) {
        val supportBlur = remember { isRuntimeShaderSupported() }
        val appBackdrop = if (supportBlur) rememberLayerBackdrop() else null
        var scaffoldBottomPadding by remember { mutableStateOf(0.dp) }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    AnimatedVisibility(
                        visible = !showPlayerScreen && !isMusicBatchMode,
                        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
                    ) {
                        NavigationBar {
                            navigationItems.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = selectedIndex == index,
                                    onClick = { selectedIndex = index },
                                    icon = item.icon,
                                    label = item.label
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                LaunchedEffect(innerPadding) {
                    scaffoldBottomPadding = innerPadding.calculateBottomPadding()
                }
                val contentModifier = Modifier.fillMaxSize()
                val finalContentModifier = if (supportBlur && appBackdrop != null) {
                    contentModifier.layerBackdrop(appBackdrop)
                } else {
                    contentModifier
                }
                val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                Box(
                    modifier = finalContentModifier.padding(
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        top = innerPadding.calculateTopPadding(),
                        end = innerPadding.calculateEndPadding(layoutDirection),
                        bottom = 0.dp
                    )
                ) {
                    MusicListScreen(
                        repository = repository,
                        onBatchModeChange = { isMusicBatchMode = it },
                        onNavigateToSettings = { selectedIndex = 2 },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (selectedIndex == 0) 1f else 0f }
                            .zIndex(if (selectedIndex == 0) 1f else 0f)
                    )
                    PlaylistScreen(
                        repository = repository,
                        onBatchModeChange = { isMusicBatchMode = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (selectedIndex == 1) 1f else 0f }
                            .zIndex(if (selectedIndex == 1) 1f else 0f)
                    )
                    SystemScreen(
                        repository = repository,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (selectedIndex == 2) 1f else 0f }
                            .zIndex(if (selectedIndex == 2) 1f else 0f)
                    )
                }

                // 全局播放列表弹窗
                WindowBottomSheet(
                    show = showPlaylistState,
                    onDismissRequest = { GlobalState.togglePlaylist(false) }
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val screenHeight = maxHeight
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = screenHeight * 2 / 3)
                                .padding(bottom = 16.dp)
                        ) {
                            // 标题与一键清空列表
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "当前播放 (${playlist.size})",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface
                            )

                            if (playlist.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        Platform.playerController.clearPlaylist()
                                        GlobalState.togglePlaylist(false)
                                    }
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = "清空播放列表",
                                        tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(playlist) { index, song ->
                                val isPlaying = index == currentIndex
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            Platform.playerController.playAtIndex(index)
                                            GlobalState.togglePlaylist(false)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = song.title ?: song.filename ?: "未知音乐",
                                                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isPlaying)
                                                    top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary
                                                else
                                                    top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = song.artist ?: "未知艺术家",
                                                fontSize = 12.sp,
                                                color = androidx.compose.ui.graphics.Color.Gray,
                                                maxLines = 1
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (isPlaying) {
                                                Icon(
                                                    imageVector = MiuixIcons.VolumeUp,
                                                    contentDescription = "Playing",
                                                    tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    Platform.playerController.removeAtIndex(index)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = MiuixIcons.Close,
                                                    contentDescription = "从播放列表移出",
                                                    tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 悬浮迷你播放器 (独立于 Scaffold 之外，浮动在底栏正上方)
        if (currentSong != null && !showPlayerScreen && !isMusicBatchMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = scaffoldBottomPadding)
                    .zIndex(50f),
                contentAlignment = Alignment.BottomCenter
            ) {
                FloatingToolbar(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shadowElevation = 0.dp,
                    outSidePadding = PaddingValues(12.dp, 8.dp)
                ) {
                    BottomPlayerBar(backdrop = appBackdrop, onClick = { showPlayerScreen = true })
                }
            }
        }

        // 全屏播放器置顶层 (if 模式防死锁)
        if (showPlayerScreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(99f)
            ) {
                PlayerScreen(
                    onClose = { showPlayerScreen = false },
                    repository = repository
                )
            }
        }

        // 存储权限引导弹窗
        val showStoragePermissionDialog by GlobalState.showStoragePermissionDialog.collectAsState()
        if (showStoragePermissionDialog) {
            WindowDialog(
                title = "存储权限申请",
                show = showStoragePermissionDialog,
                onDismissRequest = { GlobalState.updateShowStoragePermissionDialog(false) }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "下载和缓存歌曲需要存储管理权限，以保存音频、歌词与封面文件。是否前往开启？",
                        fontSize = 16.sp
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            text = "暂不开启",
                            onClick = { GlobalState.updateShowStoragePermissionDialog(false) },
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                        TextButton(
                            text = "前往开启",
                            onClick = {
                                GlobalState.updateShowStoragePermissionDialog(false)
                                Platform.requestStoragePermission?.invoke()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
        }
        }
    }
