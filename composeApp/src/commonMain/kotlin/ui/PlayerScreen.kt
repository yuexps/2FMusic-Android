package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.GlobalPlayerController
import api.MusicApi
import api.GlobalState
import model.PlayMode
import model.PlaybackState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.extra.SuperDialog
import utils.LrcLine
import utils.LrcParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing

import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import utils.Toast

import data.MusicRepository

@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    repository: MusicRepository
) {
    val currentSong by GlobalPlayerController.currentSong.collectAsState()
    val playbackState by GlobalPlayerController.playbackState.collectAsState()
    val playMode by GlobalPlayerController.playMode.collectAsState()
    val progress by GlobalPlayerController.progress.collectAsState()
    val duration by GlobalPlayerController.duration.collectAsState()
    val currentPosition by GlobalPlayerController.currentPosition.collectAsState()
    
    val scope = rememberCoroutineScope()
    var lyrics by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var isLoadingLyrics by remember { mutableStateOf(false) }
    
    val api = remember { MusicApi() }
    val playlist by GlobalPlayerController.playlist.collectAsState()
    val currentIndex by GlobalPlayerController.currentIndex.collectAsState()
    
    val favoriteIds by GlobalState.favoriteIds.collectAsState()
    val isFavorite = currentSong?.let { favoriteIds.contains(it.id) } ?: false

    // 下滑关闭相关状态
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 150.dp.toPx() }
    val showDeleteDialog = remember { mutableStateOf(false) }

    // 当歌曲改变时加载歌词与封面持久化
    LaunchedEffect(currentSong) {
        currentSong?.let { song ->
            utils.FileStore.log("[Player] 歌曲变更: ${song.title} (ID: ${song.id})")
            isLoadingLyrics = true
            lyrics = emptyList()
            try {
                // 1. 触发后台持久化任务 (如果缺失)
                launch { repository.ensureCoverDownloaded(song) }
                repository.ensureLyricsDownloaded(song)
                
                // 2. 读取及显示
                val localLrc = utils.FileStore.readLyrics(song.id)
                if (localLrc != null) {
                    lyrics = LrcParser.parse(localLrc)
                }
            } catch (e: Throwable) {
                println("[PlayerScreen] 媒体资源加载失败: ${e.message}")
            } finally {
                isLoadingLyrics = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .background(MiuixTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (offsetY.value > dismissThreshold) {
                            onClose()
                        } else {
                            scope.launch { offsetY.animateTo(0f) }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetY.animateTo(0f) }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        // 仅允许向下滑动
                        val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                        change.consume()
                        scope.launch { offsetY.snapTo(newOffset) }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // 顶部栏: 使用 Miuix TopAppBar
            // 顶部栏: 自定义 Row 以消除多余间距
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp), //微调上下边距，保持视觉舒适但紧凑
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 关闭按钮
                IconButton(onClick = onClose) {
                    Icon(imageVector = MiuixIcons.ExpandLess, contentDescription = "Close")
                }

                // 更多菜单
                val showMenu = remember { mutableStateOf(false) }
                
                Box {
                    IconButton(onClick = { showMenu.value = true }) {
                        Icon(imageVector = MiuixIcons.More, contentDescription = "更多")
                    }
                    
                    SuperListPopup(
                        show = showMenu,
                        alignment = PopupPositionProvider.Align.End, // 靠右对齐
                        onDismissRequest = { showMenu.value = false }
                    ) {
                        ListPopupColumn {
                                DropdownImpl(
                                    text = "下载到本地",
                                    optionSize = 2,
                                    isSelected = false,
                                    onSelectedIndexChange = {
                                        showMenu.value = false
                                        currentSong?.let { song ->
                                            utils.Toast.show("开始下载: ${song.title}")
                                            println("[PlayerScreen] Start downloading ${song.title}")
                                            val result = repository.downloadMusic(song)
                                            when (result) {
                                                MusicRepository.DownloadResult.STARTED -> { /* Toast already shown */ }
                                                MusicRepository.DownloadResult.EXISTS -> utils.Toast.show("已存在，无需下载")
                                                MusicRepository.DownloadResult.ERROR -> utils.Toast.show("下载启动失败")

                                            }
                                        }
                                    },
                                    index = 0
                                )
                                DropdownImpl(
                                    text = "删除此音乐",
                                    optionSize = 2,
                                    isSelected = false,
                                    onSelectedIndexChange = { 
                                        showMenu.value = false
                                        showDeleteDialog.value = true
                                    },
                                    index = 1
                                )
                        }
                    }
                }
            }



            Spacer(Modifier.height(8.dp))

            // Tab 切换
            val tabs = listOf("封面", "歌词")
            val pagerState = rememberPagerState { tabs.size }
            var selectedTabIndex by remember { mutableIntStateOf(0) }

            LaunchedEffect(pagerState.currentPage) {
                selectedTabIndex = pagerState.currentPage
            }
            LaunchedEffect(selectedTabIndex) {
                pagerState.animateScrollToPage(selectedTabIndex)
            }

            TabRow(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            // 封面与歌词显示区域
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> {
                        // 封面页面
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val albumArtUrl = utils.CoverUtil.getCoverUrl(currentSong)
                            
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 16.dp)
                                    .sizeIn(maxWidth = 400.dp, maxHeight = 400.dp)
                                    .fillMaxWidth(0.8f) // 移动端依然占 80%
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .shadow(elevation = 16.dp)
                                    .background(Color.LightGray)
                            ) {
                                if (albumArtUrl != null) {
                                    com.seiko.imageloader.ui.AutoSizeImage(
                                        url = albumArtUrl,
                                        contentDescription = "Album Art",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        // 歌词页面
                        if (lyrics.isNotEmpty()) {
                            LyricsView(
                                lyrics = lyrics,
                                currentPosition = currentPosition,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isLoadingLyrics) "加载中..." else "暂无歌词",
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // 歌曲信息 - 仅在封面页显示
            if (selectedTabIndex == 0) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.text.BasicText(
                        text = currentSong?.title ?: currentSong?.filename ?: "未知音乐",
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentSong?.artist ?: "未知艺术家",
                        fontSize = 18.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }

                Spacer(Modifier.height(24.dp))

                // 进度条 - 仅在封面页显示
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    var sliderValue by remember { mutableStateOf<Float?>(null) }
                    
                    Slider(
                        value = sliderValue ?: if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { newValue ->
                            sliderValue = newValue
                        },
                        onValueChangeFinished = {
                            sliderValue?.let {
                                val seekPos = (it * duration).toLong()
                                GlobalPlayerController.seekTo(seekPos)
                                // 延迟 500ms 后再清空 sliderValue, 防止寻道时的旧状态回弹
                                scope.launch {
                                    delay(500)
                                    sliderValue = null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 22.dp, // 微调高度: 12dp 太细, 尝试 22dp 达到视觉平衡
                        colors = SliderDefaults.sliderColors(
                            foregroundColor = MiuixTheme.colorScheme.primary,
                            backgroundColor = MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.2f)
                        ),
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step // 增加触感逻辑
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(sliderValue?.let { (it * duration).toLong() } ?: currentPosition), fontSize = 12.sp, color = Color.Gray)
                        Text(formatTime(duration), fontSize = 12.sp, color = Color.Gray)
                    }
                }

                Spacer(Modifier.height(32.dp))
            }

            // 控制器 - 仅在封面页显示
            if (selectedTabIndex == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 播放模式
                    IconButton(onClick = {
                        val nextMode = when (playMode) {
                            PlayMode.LIST_LOOP -> PlayMode.SINGLE_LOOP
                            PlayMode.SINGLE_LOOP -> PlayMode.RANDOM
                            PlayMode.RANDOM -> PlayMode.LIST_LOOP
                        }
                        GlobalPlayerController.setPlayMode(nextMode)

                    }) {
                        when (playMode) {
                            PlayMode.LIST_LOOP -> Icon(
                                imageVector = MiuixIcons.Heavy.Refresh,
                                contentDescription = "列表循环",
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            PlayMode.SINGLE_LOOP -> Box {
                                Icon(
                                    imageVector = MiuixIcons.Heavy.Refresh,
                                    contentDescription = "单曲循环",
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "1",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.Center).offset(y = 1.dp)
                                )
                            }
                            PlayMode.RANDOM -> Icon(
                                imageVector = MiuixIcons.Heavy.Replace,
                                contentDescription = "随机播放",
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }

                    // 上一曲
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .clickable {
                                println("[PlayerScreen] Previous button clicked")
                                GlobalPlayerController.previous()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Heavy.ChevronBackward,
                            contentDescription = "Previous", 
                            modifier = Modifier.size(32.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    // 播放/暂停/缓冲
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(34.dp))
                            .background(MiuixTheme.colorScheme.primary)
                            .clickable {
                                if (playbackState == PlaybackState.PLAYING) GlobalPlayerController.pause()
                                else if (playbackState == PlaybackState.PAUSED || playbackState == PlaybackState.IDLE) GlobalPlayerController.resume()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (playbackState == PlaybackState.BUFFERING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (playbackState == PlaybackState.PLAYING) MiuixIcons.Heavy.Pause else MiuixIcons.Heavy.Play,
                                contentDescription = "Play/Pause",
                                tint = MiuixTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    // 下一曲
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .clickable {
                                println("[PlayerScreen] Next button clicked")
                                GlobalPlayerController.next()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Heavy.ChevronForward,
                            contentDescription = "Next", 
                            modifier = Modifier.size(32.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    // 收藏按钮
                    IconButton(onClick = { 
                        currentSong?.let { song ->
                            scope.launch {
                                try {
                                    if (isFavorite) {
                                        api.removeFavorite(song.id)
                                        repository.removeFavorite(song.id)
                                    } else {
                                        api.addFavorite(song.id)
                                        repository.addFavorite(song.id)
                                    }
                                    
                                    // 触发刷新 (网络侧)
                                    // 注意：本地状态更新已由 repository.add/removeFavorite -> App.kt 监听 -> GlobalState 完成
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }) {
                        Icon(
                            imageVector = MiuixIcons.Heavy.Favorites,
                            contentDescription = "Favorite", 
                            modifier = Modifier.size(26.dp),
                            tint = if (isFavorite) Color.Red else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(Modifier.height(48.dp))
            }
        }

        SuperDialog(
            title = "确认删除",
            summary = "确定要删除 ${currentSong?.filename ?: ""} 吗？",
            show = showDeleteDialog,
            onDismissRequest = { showDeleteDialog.value = false }
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showDeleteDialog.value = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确认",
                    onClick = {
                        scope.launch {
                            currentSong?.id?.let { sid ->
                                val res = api.deleteFile(sid)
                                if (res.success) {
                                    repository.deleteLocalAudio(sid) // 同时删除本地文件
                                    GlobalState.triggerRefresh()
                                    val newList = playlist.filter { it.id != currentSong!!.id }
                                    GlobalPlayerController.setPlaylist(newList)
                                    if (newList.isNotEmpty()) {
                                        GlobalPlayerController.next()
                                    } else {
                                        GlobalPlayerController.stop()
                                        onClose()
                                    }
                                }
                                showDeleteDialog.value = false
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

@Composable
fun LyricsView(
    lyrics: List<LrcLine>,
    currentPosition: Long,
    modifier: Modifier = Modifier
) {
    val currentIndex = LrcParser.getCurrentLineIndex(lyrics, currentPosition)
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val viewHeightPx = with(density) { maxHeight.toPx() }
        val itemHeightPx = with(density) { 80.dp.toPx() } // 预估的单行(含间距)基础高度

        // 监听索引变化，平滑轮滚到正中心
        LaunchedEffect(currentIndex) {
            if (currentIndex >= 0) {
                // 已经在 LazyColumn 设置了 contentPadding = maxHeight / 2 (项顶部对齐中心)
                // 现在将项向上偏移半个项高度，使项的“中心”对齐视口“中心”
                val halfItemHeightPx = with(density) { (80.dp.toPx() / 2).toInt() }
                listState.animateScrollToItem(
                    index = currentIndex,
                    scrollOffset = halfItemHeightPx
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 增加上下间距，确保第一行和最后一行也能滚到中间
            contentPadding = PaddingValues(vertical = maxHeight / 2)
        ) {
            itemsIndexed(lyrics, key = { index, _ -> index }) { index, line ->
                val isActive = index == currentIndex
                
                // 动画属性
                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.15f else 1f,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                )
                val alpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0.5f,
                    animationSpec = tween(durationMillis = 400)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 使用固定（最小）高度来防止 fontSize/scale 变化引起的布局回弹
                        .heightIn(min = 80.dp) 
                        .padding(vertical = 12.dp, horizontal = 32.dp) // 增加水平 padding 容纳缩放
                        .graphicsLayer {
                            this.alpha = alpha
                            this.scaleX = scale
                            this.scaleY = scale
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    line.lines.forEachIndexed { lineIndex, text ->
                        // 颜色动画
                        val textColor by animateColorAsState(
                            targetValue = if (lineIndex == 0) {
                                if (isActive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions
                            } else {
                                if (isActive) MiuixTheme.colorScheme.onSurfaceVariantActions else MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.6f)
                            },
                            animationSpec = tween(durationMillis = 400)
                        )

                        Text(
                            text = text,
                            // 这里不再动 fontSize，而是靠 graphicsLayer 的 scale 缩放，
                            // 这样就不会触发 Re-layout，从而彻底解决滚动抖动。
                            fontSize = if (lineIndex == 0) 20.sp else 15.sp,
                            fontWeight = if (isActive && lineIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        if (lineIndex < line.lines.size - 1) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
