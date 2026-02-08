package top.msfxp.music.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import api.GlobalPlayerController
import api.GlobalState
import api.MusicApi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import ui.BottomPlayerBar
import ui.MusicListScreen
import ui.PlayerScreen
import ui.PlaylistScreen
import ui.SystemScreen
import utils.BackHandler

import database.DatabaseDriverFactory
import data.MusicRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(driverFactory: DatabaseDriverFactory) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showPlayerScreen by remember { mutableStateOf(false) }
    val isDarkTheme = isSystemInDarkTheme()

    val navigationItems = remember {
        listOf(
            NavigationItem("音乐库", MiuixIcons.Music),
            NavigationItem("收藏夹", MiuixIcons.Favorites),
            NavigationItem("系统", MiuixIcons.Settings)
        )
    }

    val api = remember { MusicApi() }
    val repository = remember { MusicRepository(api, driverFactory) }

    val playlist by GlobalPlayerController.playlist.collectAsState()
    val showPlaylistState by GlobalState.showPlaylistState.collectAsState()
    val currentIndex by GlobalPlayerController.currentIndex.collectAsState()

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
        val localFavsJob = launch {
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
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!showPlayerScreen) {
                        Column {
                            BottomPlayerBar(onClick = { showPlayerScreen = true })
                            NavigationBar(
                                items = navigationItems,
                                selected = selectedIndex,
                                onClick = { selectedIndex = it }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    MusicListScreen(
                        repository = repository,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (selectedIndex == 0) 1f else 0f }
                            .zIndex(if (selectedIndex == 0) 1f else 0f)
                    )
                    PlaylistScreen(
                        repository = repository,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (selectedIndex == 1) 1f else 0f }
                            .zIndex(if (selectedIndex == 1) 1f else 0f)
                    )
                    SystemScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (selectedIndex == 2) 1f else 0f }
                            .zIndex(if (selectedIndex == 2) 1f else 0f)
                    )
                }

                // 全屏播放器
                AnimatedVisibility(
                    visible = showPlayerScreen,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    PlayerScreen(
                        onClose = { showPlayerScreen = false },
                        repository = repository
                    )
                }

                // 全局播放列表弹窗
                val showPlaylistStateMutable = remember { mutableStateOf(showPlaylistState) }
                LaunchedEffect(showPlaylistState) {
                    showPlaylistStateMutable.value = showPlaylistState
                }

                SuperBottomSheet(
                    show = showPlaylistStateMutable,
                    title = "当前播放 (${playlist.size})",
                    onDismissRequest = { GlobalState.togglePlaylist(false) }
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        itemsIndexed(playlist) { index, song ->
                            val isPlaying = index == currentIndex
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clickable {
                                        GlobalPlayerController.playAtIndex(index)
                                        GlobalState.togglePlaylist(false)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title ?: song.filename ?: "未知音乐",
                                            fontWeight = if (isPlaying) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
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
                                    if (isPlaying) {
                                        Icon(
                                            imageVector = MiuixIcons.VolumeUp,
                                            contentDescription = "Playing",
                                            tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
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
