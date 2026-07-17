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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import api.MusicApi
import api.GlobalState
import model.PlayMode
import model.PlaybackState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.menu.WindowIconCascadingDropdownMenu
import utils.LrcLine
import utils.LrcParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import utils.Platform

import data.MusicRepository
import data.DownloadResult

@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    repository: MusicRepository
) {
    val supportBlur = remember { isRuntimeShaderSupported() }
    val isDark = isSystemInDarkTheme()
    val bgBackdrop = if (supportBlur) rememberLayerBackdrop() else null

    val currentSong by Platform.playerController.currentSong.collectAsState()
    val playbackState by Platform.playerController.playbackState.collectAsState()
    val playMode by Platform.playerController.playMode.collectAsState()
    val duration by Platform.playerController.duration.collectAsState()
    val currentPosition by Platform.playerController.currentPosition.collectAsState()

    val scope = rememberCoroutineScope()
    var lyrics by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var isLoadingLyrics by remember { mutableStateOf(false) }

    val api = remember { MusicApi() }
    val playlist by Platform.playerController.playlist.collectAsState()

    val favoriteIds by GlobalState.favoriteIds.collectAsState()
    val isFavorite = currentSong?.let { favoriteIds.contains(it.id) } ?: false

    // 下滑关闭与滑入滑出相关状态
    val density = LocalDensity.current
    val screenHeightPx = remember(density) { with(density) { 1000.dp.toPx() } }
    val offsetY = remember { Animatable(screenHeightPx) } // 初始在屏幕最底部
    val dismissThreshold = with(density) { 150.dp.toPx() }

    // 每次进入页面时启动从底部滑升进入的动画，并保障挂载位置正确
    LaunchedEffect(Unit) {
        offsetY.animateTo(
            targetValue = 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 300,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        )
    }

    // 统一下滑/退出关闭的动画处理
    val handleDismiss: () -> Unit = {
        scope.launch {
            offsetY.animateTo(
                targetValue = screenHeightPx,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 280,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
            onClose()
        }
    }
    val showDeleteDialog = remember { mutableStateOf(false) }
    val showDetailsDialog = remember { mutableStateOf(false) }
    var lyricFontSize by remember { mutableFloatStateOf(Platform.config.getLyricFontSize()) }
    var lyricTranslationMode by remember { mutableIntStateOf(Platform.config.getLyricTranslationMode()) }
    var showLyricsInNotification by remember { mutableStateOf(Platform.config.getShowLyricsInNotification()) }
    var isDynamicColorEnabled by remember { mutableStateOf(Platform.config.getDynamicColor()) }
    var showSongActions by remember { mutableStateOf(false) }
    var currentPalette by remember { mutableStateOf<MiuixPalette?>(null) }


    val isTimerActive by remember {
        utils.SleepTimerManager.remainingSeconds.map { it != null }.distinctUntilChanged()
    }.collectAsState(initial = false)
    var showSleepTimerSettings by remember { mutableStateOf(false) }
    var showEqualizerSettings by remember { mutableStateOf(false) }
    var isEqualizerEnabled by remember { mutableStateOf(Platform.playerController.isEqualizerEnabled()) }
    var selectedTimerHour by remember { mutableIntStateOf(0) }
    var selectedTimerMinute by remember { mutableIntStateOf(0) }

    val localSongs by repository.getLocalSongs().collectAsState(initial = emptyList())
    val downloadedSongIds = remember(localSongs) {
        localSongs.filter { it.localAudioPath != null }.map { it.id }.toSet()
    }
    val isDownloaded = currentSong?.let { downloadedSongIds.contains(it.id) } ?: false
    val playlists by repository.getAllPlaylists().collectAsState(initial = emptyList())
    val showSelectPlaylistDialog = remember { mutableStateOf(false) }

    // 当歌曲改变时加载歌词与封面持久化
    LaunchedEffect(currentSong) {
        currentSong?.let { song ->
            Platform.logger.i("Player", "歌曲变更: ${song.title} (ID: ${song.id})")
            isLoadingLyrics = true
            lyrics = emptyList()
            try {
                // 1. 触发后台持久化任务 (如果缺失)
                launch { repository.ensureCoverDownloaded(song) }
                repository.ensureLyricsDownloaded(song)

                // 2. 读取及显示
                val localLrc = utils.FileStore.readLyrics(song.id)
                if (localLrc != null) {
                    lyrics = LrcParser.parse(localLrc, song.title)
                }
            } catch (e: Throwable) {
                Platform.logger.e("PlayerScreen", "媒体资源加载失败", e)
            } finally {
                isLoadingLyrics = false
            }
        }
    }

    val albumArtUrl = utils.CoverUtil.getCoverUrl(currentSong)

    LaunchedEffect(albumArtUrl) {
        if (albumArtUrl != null) {
            utils.Platform.coverColorExtractor?.invoke(albumArtUrl) { extractedColor ->
                currentPalette = pickPaletteForColor(extractedColor)
            }
        } else {
            currentPalette = null
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
                            handleDismiss()
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
        // 1. 动态取色背景层 (支持与不支持模糊的底层 fallback 渐变，始终渲染防止硬质露底)
        val bgColors = if (isDynamicColorEnabled && currentPalette != null) {
            val palette = currentPalette!!
            if (isDark) {
                listOf(palette.darkBackground, Color.Black)
            } else {
                listOf(palette.lightBackground, Color.White)
            }
        } else {
            if (isDark) {
                listOf(Color(0xFF1F1F24), Color(0xFF0F0F12)) // 黑曜石暗色莫兰迪渐变
            } else {
                listOf(Color(0xFFF2F4F7), Color(0xFFE4E7EB)) // 优雅的浅灰莫兰迪渐变
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(bgColors))
        )

        // 2. 硬件级毛玻璃层 (高级高斯模糊)
        if (supportBlur && isDynamicColorEnabled && bgBackdrop != null && albumArtUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(bgBackdrop)
            ) {
                com.seiko.imageloader.ui.AutoSizeImage(
                    url = albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .textureBlur(
                        backdrop = bgBackdrop,
                        shape = RoundedCornerShape(0.dp),
                        colors = top.yukonga.miuix.kmp.blur.BlurDefaults.blurColors(
                            blendColors = listOf(
                                top.yukonga.miuix.kmp.blur.BlendColorEntry(
                                    color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.35f),
                                    mode = top.yukonga.miuix.kmp.blur.BlurBlendMode.SrcOver
                                )
                            ),
                            brightness = if (isDark) -0.05f else 0.02f,
                            contrast = 1.0f,
                            saturation = 1.3f
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // 顶部栏: 整合 Tab 与折叠、设置按钮
            val tabs = listOf("歌曲", "歌词")
            val pagerState = rememberPagerState { tabs.size }
            var selectedTabIndex by remember { mutableIntStateOf(0) }

            LaunchedEffect(pagerState.currentPage) {
                selectedTabIndex = pagerState.currentPage
            }
            LaunchedEffect(selectedTabIndex) {
                pagerState.animateScrollToPage(selectedTabIndex)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 收起按钮
                IconButton(onClick = handleDismiss) {
                    Icon(
                        imageVector = MiuixIcons.ExpandLess,
                        contentDescription = "Close",
                        modifier = Modifier.graphicsLayer { rotationZ = 180f }
                    )
                }

                // 中间 Tab 切换 (歌曲 | 歌词)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val isSelected = selectedTabIndex == index
                            Text(
                                text = title,
                                fontSize = 16.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        selectedTabIndex = index
                                    }
                            )
                        }
                    }
                }

                // 级联设置下拉菜单
                val settingsEntry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = "歌词大小",
                            children = listOf(
                                DropdownItem(
                                    text = "极小 (16 sp)",
                                    selected = lyricFontSize == 16f,
                                    onClick = {
                                        lyricFontSize = 16f
                                        Platform.config.setLyricFontSize(16f)
                                    }
                                ),
                                DropdownItem(
                                    text = "小 (18 sp)",
                                    selected = lyricFontSize == 18f,
                                    onClick = {
                                        lyricFontSize = 18f
                                        Platform.config.setLyricFontSize(18f)
                                    }
                                ),
                                DropdownItem(
                                    text = "默认 (20 sp)",
                                    selected = lyricFontSize == 20f,
                                    onClick = {
                                        lyricFontSize = 20f
                                        Platform.config.setLyricFontSize(20f)
                                    }
                                ),
                                DropdownItem(
                                    text = "中 (22 sp)",
                                    selected = lyricFontSize == 22f,
                                    onClick = {
                                        lyricFontSize = 22f
                                        Platform.config.setLyricFontSize(22f)
                                    }
                                ),
                                DropdownItem(
                                    text = "大 (26 sp)",
                                    selected = lyricFontSize == 26f,
                                    onClick = {
                                        lyricFontSize = 26f
                                        Platform.config.setLyricFontSize(26f)
                                    }
                                ),
                                DropdownItem(
                                    text = "特大 (30 sp)",
                                    selected = lyricFontSize == 30f,
                                    onClick = {
                                        lyricFontSize = 30f
                                        Platform.config.setLyricFontSize(30f)
                                    }
                                ),
                                DropdownItem(
                                    text = "超大 (34 sp)",
                                    selected = lyricFontSize == 34f,
                                    onClick = {
                                        lyricFontSize = 34f
                                        Platform.config.setLyricFontSize(34f)
                                    }
                                )
                            )
                        ),
                        DropdownItem(
                            text = "歌词翻译",
                            children = listOf(
                                DropdownItem(
                                    text = "隐藏翻译",
                                    selected = lyricTranslationMode == 0,
                                    onClick = {
                                        lyricTranslationMode = 0
                                        Platform.config.setLyricTranslationMode(0)
                                    }
                                ),
                                DropdownItem(
                                    text = "仅当前行",
                                    selected = lyricTranslationMode == 1,
                                    onClick = {
                                        lyricTranslationMode = 1
                                        Platform.config.setLyricTranslationMode(1)
                                    }
                                ),
                                DropdownItem(
                                    text = "全局双语",
                                    selected = lyricTranslationMode == 2,
                                    onClick = {
                                        lyricTranslationMode = 2
                                        Platform.config.setLyricTranslationMode(2)
                                    }
                                )
                            )
                        ),
                        DropdownItem(
                            text = "通知歌词",
                            children = listOf(
                                DropdownItem(
                                    text = "开启",
                                    selected = showLyricsInNotification,
                                    onClick = {
                                        showLyricsInNotification = true
                                        Platform.config.setShowLyricsInNotification(true)
                                        Platform.playerController.updateLyricsMetadata()
                                    }
                                ),
                                DropdownItem(
                                    text = "关闭",
                                    selected = !showLyricsInNotification,
                                    onClick = {
                                        showLyricsInNotification = false
                                        Platform.config.setShowLyricsInNotification(false)
                                        Platform.playerController.updateLyricsMetadata()
                                    }
                                )
                            )
                        ),
                        DropdownItem(
                            text = "动态取色",
                            children = listOf(
                                DropdownItem(
                                    text = "开启",
                                    selected = isDynamicColorEnabled,
                                    onClick = {
                                        isDynamicColorEnabled = true
                                        Platform.config.setDynamicColor(true)
                                    }
                                ),
                                DropdownItem(
                                    text = "关闭",
                                    selected = !isDynamicColorEnabled,
                                    onClick = {
                                        isDynamicColorEnabled = false
                                        Platform.config.setDynamicColor(false)
                                    }
                                )
                            )
                        )
                    )
                )

                WindowIconCascadingDropdownMenu(
                    entry = settingsEntry
                ) {
                    Icon(
                        imageVector = MiuixIcons.Settings,
                        contentDescription = "设置",
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

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
                        // 歌曲封面页面
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val albumArtUrl = utils.CoverUtil.getCoverUrl(currentSong)

                            Box(
                                modifier = Modifier
                                    .padding(vertical = 16.dp)
                                    .sizeIn(maxWidth = 360.dp, maxHeight = 360.dp)
                                    .fillMaxWidth(0.85f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(28.dp))
                                    .shadow(elevation = 12.dp)
                                    .background(MiuixTheme.colorScheme.secondaryContainer)
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
                                fontSizeSp = lyricFontSize,
                                translationMode = lyricTranslationMode,
                                currentSong = currentSong,
                                onLineClick = { time ->
                                    Platform.playerController.seekTo(time)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isLoadingLyrics) "加载中..." else "暂无歌词",
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 控制器区域 - 仅在“歌曲”主页显示
            if (selectedTabIndex == 0) {
                // 1. 歌曲标题/歌手 (左对齐) + 红心收藏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSong?.title ?: currentSong?.filename ?: "未知音乐",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = currentSong?.artist ?: "未知歌手",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    // 红心收藏
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
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }) {
                        Icon(
                            imageVector = MiuixIcons.Demibold.Favorites,
                            contentDescription = "Favorite",
                            modifier = Modifier.size(26.dp),
                            tint = if (isFavorite) Color(0xFFEF5350) else (if (isDark) Color.White.copy(alpha = 0.6f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 工具栏 (定时、音效、下载、详情、更多)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showSleepTimerSettings = true }) {
                        Icon(
                            imageVector = MiuixIcons.Alarm,
                            contentDescription = "Timer",
                            modifier = Modifier.size(22.dp),
                            tint = if (isTimerActive) MiuixTheme.colorScheme.primary else (if (isDark) Color.White.copy(alpha = 0.65f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        )
                    }

                    IconButton(onClick = {
                        showEqualizerSettings = true
                    }) {
                        Icon(
                            imageVector = MiuixIcons.Tune,
                            contentDescription = "Sound Effect",
                            modifier = Modifier.size(22.dp),
                            tint = if (isEqualizerEnabled) MiuixTheme.colorScheme.primary else (if (isDark) Color.White.copy(alpha = 0.65f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        )
                    }
                    IconButton(onClick = {
                        currentSong?.let { song ->
                            if (isDownloaded) {
                                Platform.toast.show("歌曲已下载至本地")
                                return@IconButton
                            }
                            Platform.toast.show("开始下载: ${song.title}")
                            val result = repository.downloadMusic(song)
                            when (result) {
                                DownloadResult.STARTED -> {}
                                DownloadResult.EXISTS -> Platform.toast.show("已存在，无需下载")
                                DownloadResult.ERROR -> Platform.toast.show("下载启动失败")
                            }
                        }
                    }) {
                        Icon(
                            imageVector = if (isDownloaded) MiuixIcons.Folder else MiuixIcons.Download,
                            contentDescription = "Download",
                            modifier = Modifier.size(22.dp),
                            tint = if (isDownloaded) MiuixTheme.colorScheme.primary else (if (isDark) Color.White.copy(alpha = 0.65f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        )
                    }
                    IconButton(onClick = { showSongActions = true }) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = "More",
                            modifier = Modifier.size(22.dp),
                            tint = if (isDark) Color.White.copy(alpha = 0.65f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 2. 进度条与时间
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    var sliderValue by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = sliderValue ?: if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            sliderValue?.let {
                                val seekPos = (it * duration).toLong()
                                Platform.playerController.seekTo(seekPos)
                                scope.launch {
                                    delay(500)
                                    sliderValue = null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.sliderColors(
                            foregroundColor = MiuixTheme.colorScheme.primary,
                            backgroundColor = MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.15f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(sliderValue?.let { (it * duration).toLong() } ?: currentPosition), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text(formatTime(duration), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 3. 播放控制按键组
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 播放模式 (三档：小巧点缀，细线条)
                    IconButton(
                        onClick = {
                            val nextMode = when (playMode) {
                                PlayMode.LIST_LOOP -> PlayMode.SINGLE_LOOP
                                PlayMode.SINGLE_LOOP -> PlayMode.RANDOM
                                PlayMode.RANDOM -> PlayMode.LIST_LOOP
                            }
                            Platform.playerController.setPlayMode(nextMode)
                        },
                        modifier = Modifier.size(42.dp)
                    ) {
                        val modeTint = if (isDark) Color.White.copy(alpha = 0.65f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        when (playMode) {
                            PlayMode.LIST_LOOP -> Icon(
                                imageVector = MiuixIcons.Refresh, // 循环箭头回路 (无数字)
                                contentDescription = "列表循环",
                                modifier = Modifier.size(24.dp),
                                tint = modeTint
                            )
                            PlayMode.SINGLE_LOOP -> Box {
                                Icon(
                                    imageVector = MiuixIcons.Refresh, // 循环箭头回路
                                    contentDescription = "单曲循环",
                                    modifier = Modifier.size(24.dp),
                                    tint = modeTint
                                )
                                Text(
                                    text = "1",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.Center).offset(y = 0.5.dp),
                                    color = modeTint
                                )
                            }
                            PlayMode.RANDOM -> Icon(
                                imageVector = MiuixIcons.Help, // 随机交叉回路
                                contentDescription = "随机播放",
                                modifier = Modifier.size(24.dp),
                                tint = modeTint
                            )
                        }
                    }

                    // 上一曲
                    IconButton(
                        onClick = { Platform.playerController.previous() },
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(46.dp),
                            tint = if (isDark) Color.White.copy(alpha = 0.9f) else MiuixTheme.colorScheme.onSurface
                        )
                    }

                    // 播放/暂停
                    IconButton(
                        onClick = {
                            if (playbackState == PlaybackState.PLAYING) Platform.playerController.pause()
                            else if (playbackState == PlaybackState.PAUSED || playbackState == PlaybackState.IDLE) Platform.playerController.resume()
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .background(
                                color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(34.dp)
                            )
                    ) {
                        if (playbackState == PlaybackState.BUFFERING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp)
                            )
                        } else {
                            val playIconOffset = if (playbackState == PlaybackState.PLAYING) 0.dp else 2.dp
                            Icon(
                                imageVector = if (playbackState == PlaybackState.PLAYING) MiuixIcons.Demibold.Pause else MiuixIcons.Demibold.Play,
                                contentDescription = "Play/Pause",
                                tint = if (isDark) Color.White.copy(alpha = 0.95f) else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(34.dp)
                                    .offset(x = playIconOffset)
                            )
                        }
                    }

                    // 下一曲
                    IconButton(
                        onClick = { Platform.playerController.next() },
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(46.dp),
                            tint = if (isDark) Color.White.copy(alpha = 0.9f) else MiuixTheme.colorScheme.onSurface
                        )
                    }

                    // 播放列表按钮
                    IconButton(
                        onClick = { GlobalState.togglePlaylist(true) },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Playlist, // 细线版本
                            contentDescription = "Playlist",
                            modifier = Modifier.size(24.dp),
                            tint = if (isDark) Color.White.copy(alpha = 0.65f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
            }
        }

        WindowDialog(
            title = "确认删除",
            summary = "确定要删除 ${currentSong?.filename ?: ""} 吗？",
            show = showDeleteDialog.value,
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
                            val songToDelete = currentSong ?: return@launch
                            val songTitle = songToDelete.title ?: songToDelete.filename ?: "未知音乐"
                            val sid = songToDelete.id

                            Platform.logger.i("PlayerScreen", "用户确认删除音乐: $songTitle (ID: $sid)")
                            try {
                                val res = api.deleteFile(sid)
                                if (res.success) {
                                    Platform.logger.i("PlayerScreen", "删除成功: $songTitle")
                                    repository.deleteLocalAudio(sid) // 同时删除本地文件
                                    GlobalState.triggerRefresh()
                                    val newList = playlist.filter { it.id != sid }
                                    Platform.playerController.setPlaylist(newList)

                                    // 删除后停止播放并返回列表
                                    Platform.playerController.stop()
                                    onClose()

                                    Platform.toast.show("已删除：$songTitle")
                                } else {
                                    Platform.logger.e("PlayerScreen", "删除失败: 服务端返回失败")
                                    Platform.toast.show("删除失败：$songTitle")
                                }
                            } catch (e: Exception) {
                                Platform.logger.e("PlayerScreen", "删除异常", e)
                                Platform.toast.show("删除发生错误：$songTitle")
                            }
                            showDeleteDialog.value = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }

        // 选择歌单对话框
        WindowDialog(
            title = "添加音乐至",
            show = showSelectPlaylistDialog.value,
            onDismissRequest = { showSelectPlaylistDialog.value = false }
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
                                    currentSong?.let { song ->
                                        scope.launch {
                                            try {
                                                repository.addSongToPlaylist(song.id, playlist.id)
                                                Platform.toast.show("已成功添加至「${playlist.name}」")
                                            } catch (e: Exception) {
                                                Platform.toast.show("添加失败")
                                            }
                                            showSelectPlaylistDialog.value = false
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
                                Text(
                                    text = playlist.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        WindowDialog(
            title = "歌曲详情",
            show = showDetailsDialog.value,
            onDismissRequest = { showDetailsDialog.value = false }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DetailItem(label = "ID", value = currentSong?.id ?: "未知")
                DetailItem(label = "歌名", value = currentSong?.title ?: "未知")
                DetailItem(label = "歌手", value = currentSong?.artist ?: "未知")
                DetailItem(label = "专辑", value = currentSong?.album ?: "未知")
                DetailItem(label = "文件名", value = currentSong?.filename ?: "未知")
                
                val localPath = currentSong?.localAudioPath?.let { path ->
                    utils.FileStore.getLocalPath(path)
                }
                DetailItem(label = "本地路径", value = localPath ?: "未下载（仅在线）")

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(
                    text = "关闭",
                    onClick = { showDetailsDialog.value = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }


        WindowBottomSheet(
            show = showSongActions,
            title = null,
            onDismissRequest = { showSongActions = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // 1. 扁平化曲目卡片头部 (左对齐，左圆角小封面 + 右文本)
                val albumArtUrl = utils.CoverUtil.getCoverUrl(currentSong)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
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

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSong?.title ?: currentSong?.filename ?: "未知音乐",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentSong?.artist ?: "未知歌手",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 项 0: 添加到收藏夹/歌单
                    BasicComponent(
                        title = "添加到收藏夹",
                        startAction = {
                            Icon(
                                imageVector = MiuixIcons.Favorites,
                                contentDescription = "Add to playlist",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp).size(20.dp)
                            )
                        },
                        onClick = {
                            showSongActions = false
                            showSelectPlaylistDialog.value = true
                        }
                    )

                    // 项 1: 下载到本地
                    BasicComponent(
                        title = if (isDownloaded) "已下载到本地" else "下载到本地",
                        startAction = {
                            Icon(
                                imageVector = if (isDownloaded) MiuixIcons.Folder else MiuixIcons.Download,
                                contentDescription = "Download",
                                tint = if (isDownloaded) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp).size(20.dp)
                            )
                        },
                        onClick = {
                            showSongActions = false
                            if (isDownloaded) {
                                Platform.toast.show("歌曲已存在于本地")
                                return@BasicComponent
                            }
                            currentSong?.let { song ->
                                Platform.toast.show("开始下载: ${song.title}")
                                Platform.logger.i("PlayerScreen", "开始下载: ${song.title}")
                                val result = repository.downloadMusic(song)
                                when (result) {
                                    DownloadResult.STARTED -> { /* Toast already shown */ }
                                    DownloadResult.EXISTS -> Platform.toast.show("已存在，无需下载")
                                    DownloadResult.ERROR -> Platform.toast.show("下载启动失败")
                                }
                            }
                        }
                    )

                    // 项 2: 重新刮削
                    BasicComponent(
                        title = "重新刮削",
                        startAction = {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "Refresh",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp).size(20.dp)
                            )
                        },
                        onClick = {
                            showSongActions = false
                            currentSong?.let { song ->
                                scope.launch {
                                    Platform.toast.show("重新刮削中...")
                                    Platform.logger.i("PlayerScreen", "请求重新刮削歌曲: ${song.title}")
                                    try {
                                        val res = api.clearMetadata(songId = song.id)
                                        if (res.success) {
                                            repository.clearMetadata(song)
                                            repository.ensureCoverDownloaded(song)
                                            repository.ensureLyricsDownloaded(song)

                                            val localLrc = utils.FileStore.readLyrics(song.id)
                                            lyrics = if (localLrc != null) {
                                                LrcParser.parse(localLrc, song.title)
                                            } else {
                                                emptyList()
                                            }
                                            Platform.playerController.reloadLyrics()
                                            Platform.toast.show("刮削已就绪")
                                        } else {
                                            Platform.toast.show("重新刮削触发失败")
                                        }
                                   } catch (e: Exception) {
                                        Platform.logger.e("PlayerScreen", "重新刮削异常", e)
                                        Platform.toast.show("重新刮削失败")
                                    }
                                }
                            }
                        }
                    )

                    // 项 3: 歌曲详情
                    BasicComponent(
                        title = "歌曲详情",
                        startAction = {
                            Icon(
                                imageVector = MiuixIcons.Info,
                                contentDescription = "Info",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp).size(20.dp)
                            )
                        },
                        onClick = {
                            showSongActions = false
                            showDetailsDialog.value = true
                        }
                    )

                    // 项 4: 删除此音乐
                    BasicComponent(
                        startAction = {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "Delete",
                                tint = Color.Red.copy(alpha = 0.8f),
                                modifier = Modifier.padding(end = 16.dp).size(20.dp)
                            )
                        },
                        onClick = {
                            showSongActions = false
                            showDeleteDialog.value = true
                        }
                    ) {
                        Text(
                            text = "删除此音乐",
                            color = Color.Red.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        WindowBottomSheet(
            show = showSleepTimerSettings,
            title = "定时关闭",
            onDismissRequest = { showSleepTimerSettings = false }
        ) {
            val estimatedMinutes by remember(selectedTimerHour, selectedTimerMinute) {
                derivedStateOf { selectedTimerHour * 60 + selectedTimerMinute }
            }

            val timeEstimateText by remember(estimatedMinutes) {
                derivedStateOf {
                    Platform.playerController.getEstimatedShutdownTime(estimatedMinutes)
                }
            }

            // 1. 打开弹窗时，将后台当前的倒计时时间同步到轮盘状态中
            LaunchedEffect(showSleepTimerSettings) {
                if (showSleepTimerSettings) {
                    val seconds = utils.SleepTimerManager.remainingSeconds.value
                    if (seconds != null && seconds > 0) {
                        val remMinutes = (seconds + 59) / 60
                        selectedTimerHour = remMinutes / 60
                        selectedTimerMinute = remMinutes % 60
                    } else {
                        selectedTimerHour = 0
                        selectedTimerMinute = 0
                    }
                }
            }

            // 2. 轮盘数值改变时的防抖自动调用已移除，现已改为点击底部按钮显式开启。

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                if (isTimerActive) {
                    Box {
                        val secondsState by utils.SleepTimerManager.remainingSeconds.collectAsState()
                        secondsState?.let { seconds ->
                            val min = seconds / 60
                            val sec = seconds % 60
                            Text(
                                text = "正在倒计时：${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                        }
                    }
                } else if (estimatedMinutes > 0) {
                    Text(
                        text = timeEstimateText,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                NumberPicker(
                                    value = selectedTimerHour,
                                    onValueChange = { selectedTimerHour = it },
                                    range = 0..12,
                                    label = { it.toString().padStart(2, '0') },
                                    wrapAround = true,
                                    visibleItemCount = 3,
                                    modifier = Modifier.width(64.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "时",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Spacer(modifier = Modifier.width(48.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                NumberPicker(
                                    value = selectedTimerMinute,
                                    onValueChange = { selectedTimerMinute = it },
                                    range = 0..59,
                                    label = { it.toString().padStart(2, '0') },
                                    wrapAround = true,
                                    visibleItemCount = 3,
                                    modifier = Modifier.width(64.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "分",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (isTimerActive) {
                    Button(
                        onClick = {
                            showSleepTimerSettings = false
                            utils.SleepTimerManager.stopTimer()
                            Platform.toast.show("已取消定时")
                            selectedTimerHour = 0
                            selectedTimerMinute = 0
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Red.copy(alpha = 0.1f),
                            contentColor = Color.Red
                        )
                    ) {
                        Text("取消当前定时", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                } else {
                    val isEnabled = estimatedMinutes > 0
                    Button(
                        onClick = {
                            utils.SleepTimerManager.startTimer(estimatedMinutes)
                            showSleepTimerSettings = false
                            Platform.toast.show("定时已启动")
                        },
                        enabled = isEnabled,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = if (isEnabled) {
                            ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary,
                                contentColor = MiuixTheme.colorScheme.onPrimary
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                contentColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                    ) {
                        Text(
                            text = if (isEnabled) "开启定时关闭" else "请选择关闭时间",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        WindowBottomSheet(
            show = showEqualizerSettings,
            title = "音效与均衡器",
            onDismissRequest = { showEqualizerSettings = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val bands = Platform.playerController.getEqualizerBands()
                var levels by remember { mutableStateOf(Platform.playerController.getEqualizerBandLevels()) }

                val presets = remember {
                    listOf(
                        "原声" to listOf(0, 0, 0, 0, 0),
                        "流行" to listOf(300, 200, -100, 200, 400),
                        "低音" to listOf(800, 400, 0, 0, 0)
                    )
                }

                val matchingPresetIndex by remember(levels, isEqualizerEnabled) {
                    derivedStateOf {
                        if (!isEqualizerEnabled) {
                            0
                        } else {
                            val idx = presets.indexOfFirst { preset ->
                                val presetVals = preset.second
                                if (levels.size >= presetVals.size) {
                                    val prefix = levels.take(presetVals.size)
                                    val suffix = levels.drop(presetVals.size)
                                    prefix == presetVals && suffix.all { it == 0 }
                                } else {
                                    false
                                }
                            }
                            idx
                        }
                    }
                }

                val tabs = remember(matchingPresetIndex) {
                    if (matchingPresetIndex == -1) {
                        presets.map { it.first } + "自定义"
                    } else {
                        presets.map { it.first }
                    }
                }

                val selectedTabIndex = if (matchingPresetIndex == -1) presets.size else matchingPresetIndex

                TabRowWithContour(
                    selectedTabIndex = selectedTabIndex,
                    tabs = tabs,
                    onTabSelected = { tabIndex ->
                        if (tabIndex < presets.size) {
                            val (_, bandVals) = presets[tabIndex]
                            val totalBands = Platform.playerController.getEqualizerBands().size
                            for (i in 0 until totalBands) {
                                val level = bandVals.getOrNull(i) ?: 0
                                Platform.playerController.setEqualizerBandLevel(i, level)
                            }

                            if (tabIndex == 0) {
                                Platform.playerController.setEqualizerEnabled(false)
                                isEqualizerEnabled = false
                            } else {
                                Platform.playerController.setEqualizerEnabled(true)
                                isEqualizerEnabled = true
                            }
                            levels = Platform.playerController.getEqualizerBandLevels()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "均衡器频率微调", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 12.dp))

                        (bands.zip(levels)).forEachIndexed { index, pair ->
                            val bandName = pair.first
                            val levelValue = pair.second
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                              ) {
                                Text(
                                    text = bandName,
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.width(60.dp)
                                )
                                Slider(
                                    value = levelValue.toFloat(),
                                    onValueChange = {
                                        val valInt = it.roundToInt()
                                        val newList = levels.toMutableList()
                                        if (index < newList.size) {
                                            newList[index] = valInt
                                            levels = newList
                                        }
                                        Platform.playerController.setEqualizerBandLevel(index, valInt)
                                        Platform.playerController.setEqualizerEnabled(true)
                                        isEqualizerEnabled = true
                                    },
                                    valueRange = -1500f..1500f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.sliderColors(
                                        foregroundColor = MiuixTheme.colorScheme.primary,
                                        backgroundColor = MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.15f)
                                    )
                                )
                                val dbText = if (levelValue >= 0) "+${levelValue / 100} dB" else "${levelValue / 100} dB"
                                Text(
                                    text = dbText,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(55.dp),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun LyricsView(
    lyrics: List<LrcLine>,
    currentPosition: Long,
    fontSizeSp: Float,
    translationMode: Int, // 0: 隐藏, 1: 仅当前行, 2: 全局双语
    currentSong: model.Song?,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentIndex = LrcParser.getCurrentLineIndex(lyrics, currentPosition)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val playbackState by Platform.playerController.playbackState.collectAsState()

    // 记录用户是否进行了手动滚动介入
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var userScrolledByManual by remember { mutableStateOf(false) }

    // 预分配 Canvas 羽化边缘渐变色数组，杜绝在每一帧绘制中动态 zip 和 toTypedArray 产生的 GC 内存开销
    val fadeColorStops = remember {
        arrayOf(
            0.0f to Color.Transparent,
            0.08f to Color.Black,
            0.92f to Color.Black,
            1.0f to Color.Transparent
        )
    }

    // 确保回滚时能拿到倒计时结束那一刻的最新的播放行索引
    val latestIndex by rememberUpdatedState(currentIndex)

    // 当切换歌曲时，立刻重置手动滚动状态
    LaunchedEffect(currentSong) {
        userScrolledByManual = false
    }

    LaunchedEffect(isDragged, userScrolledByManual) {
        if (isDragged) {
            userScrolledByManual = true
        } else {
            if (userScrolledByManual) {
                // 手指抬起，静止等待 3 秒后自动复位
                delay(3000)
                if (latestIndex >= 0) {
                    val currentItem = listState.layoutInfo.visibleItemsInfo.find { it.index == latestIndex }
                    val line = lyrics.getOrNull(latestIndex)
                    val hasTrans = line?.let { it.lines.size > 1 && translationMode != 0 } ?: false
                    val estimatedHeight = if (hasTrans) 80.dp else 50.dp
                    val itemHeightPx = currentItem?.size ?: with(density) { estimatedHeight.toPx().toInt() }

                    val viewportHeightPx = listState.layoutInfo.viewportEndOffset.let { if (it > 0) it else with(density) { 500.dp.toPx().toInt() } }
                    val centerScrollOffsetPx = (viewportHeightPx / 2) - (itemHeightPx / 2)

                    val currentVisibleIndex = listState.firstVisibleItemIndex
                    val distance = kotlin.math.abs(currentVisibleIndex - latestIndex)

                    if (distance > 15) {
                        // 距离太远，先快速跳转到距离目标 8 个 item 处，防御超长距离动画在 Compose 中引起的渲染闪烁与重置到顶部的 Bug
                        val jumpIndex = if (latestIndex > currentVisibleIndex) {
                            latestIndex - 8
                        } else {
                            latestIndex + 8
                        }
                        listState.scrollToItem(index = jumpIndex)
                    }

                    // 平滑滚动回当前行 (虚线指示器依然存在时滚动，视觉上非常连贯)
                    listState.animateScrollToItem(
                        index = latestIndex,
                        scrollOffset = centerScrollOffsetPx
                    )
                }
                // 动画完全结束后才关闭手动标志，虚线优雅消失，随后恢复自动跟随
                userScrolledByManual = false
            }
        }
    }

    // 只有在手动滚动交互中才进行视口中央歌词行的搜索计算，自动跟随滚动时短路跳过，大幅节减滑动时的 CPU 负荷
    val centerItemIndex by remember {
        derivedStateOf {
            if (!userScrolledByManual) {
                0
            } else {
                val items = listState.layoutInfo.visibleItemsInfo
                if (items.isEmpty()) 0 else {
                    val viewportCenter = listState.layoutInfo.viewportEndOffset / 2
                    items.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }?.index ?: 0
                }
            }
        }
    }

    // 自动跟随滚动
    LaunchedEffect(currentIndex) {
        if (!userScrolledByManual && currentIndex >= 0) {
            val currentItem = listState.layoutInfo.visibleItemsInfo.find { it.index == currentIndex }
            val line = lyrics.getOrNull(currentIndex)
            val hasTrans = line?.let { it.lines.size > 1 && translationMode != 0 } ?: false
            val estimatedHeight = if (hasTrans) 80.dp else 50.dp
            val itemHeightPx = currentItem?.size ?: with(density) { estimatedHeight.toPx().toInt() }

            val viewportHeightPx = listState.layoutInfo.viewportEndOffset.let { if (it > 0) it else with(density) { 500.dp.toPx().toInt() } }
            val centerScrollOffsetPx = (viewportHeightPx / 2) - (itemHeightPx / 2)

            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = centerScrollOffsetPx
            )
        }
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. 固定头部信息 (左对齐)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                Text(
                    text = currentSong?.title ?: currentSong?.filename ?: "未知音乐",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = currentSong?.artist ?: "未知歌手",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. 歌词列表
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = fadeColorStops,
                                    startY = 0f,
                                    endY = size.height
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    horizontalAlignment = Alignment.Start,
                    contentPadding = PaddingValues(vertical = maxHeight / 2)
                ) {
                    itemsIndexed(lyrics, key = { index, _ -> index }) { index, line ->
                        LrcLineItem(
                            line = line,
                            isActive = index == currentIndex,
                            translationMode = translationMode,
                            fontSizeSp = fontSizeSp,
                            onClick = {
                                userScrolledByManual = false
                                onLineClick(line.time)
                            }
                        )
                    }
                }
                if (userScrolledByManual) {
                    val lineColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                        ) {
                            val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            drawLine(
                                color = lineColor,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                pathEffect = pathEffect,
                                strokeWidth = 1f
                            )
                        }

                        val centerLineTime = lyrics.getOrNull(centerItemIndex)?.time ?: 0L
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.8f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = formatTime(centerLineTime),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 4. 最右下角播放/暂停控制小按键
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable {
                    if (playbackState == PlaybackState.PLAYING) {
                        Platform.playerController.pause()
                    } else {
                        Platform.playerController.resume()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val isPlaying = playbackState == PlaybackState.PLAYING
            Icon(
                imageVector = if (isPlaying) MiuixIcons.Demibold.Pause else MiuixIcons.Demibold.Play,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

@Suppress("DEPRECATION")
@Composable
fun DetailItem(label: String, value: String) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            style = androidx.compose.ui.text.TextStyle(
                lineBreak = androidx.compose.ui.text.style.LineBreak.Heading
            ),
            modifier = Modifier
                .weight(3.5f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(value))
                    utils.Platform.toast.show("已复制: $label")
                },
            textAlign = TextAlign.End
        )
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

data class MiuixPalette(
    val name: String,
    val hue: Int,
    val darkBackground: Color,
    val lightBackground: Color,
    val accent: Color,
    val secondary: Color
)

val BUILTIN_THEME_PALETTES = listOf(
    MiuixPalette("Sunset Bloom", 8, Color(0xFF170D0E), Color(0xFFFFF4EF), Color(0xFFFF7A59), Color(0xFFFFC4AE)),
    MiuixPalette("Golden Hour", 42, Color(0xFF171108), Color(0xFFFFF8E7), Color(0xFFEAB308), Color(0xFFFCD34D)),
    MiuixPalette("Forest Echo", 145, Color(0xFF09140F), Color(0xFFEEFBF3), Color(0xFF22C55E), Color(0xFF86EFAC)),
    MiuixPalette("Ocean Mist", 190, Color(0xFF08141A), Color(0xFFEBFBFF), Color(0xFF06B6D4), Color(0xFF67E8F9)),
    MiuixPalette("Twilight Signal", 228, Color(0xFF0C1020), Color(0xFFEEF2FF), Color(0xFF6366F1), Color(0xFFA5B4FC)),
    MiuixPalette("Rose Vinyl", 338, Color(0xFF180D13), Color(0xFFFFF1F5), Color(0xFFF43F5E), Color(0xFFFDA4AF))
)

fun getHueFromColor(color: Color): Int {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta == 0f) return 220

    var hue = 0f
    if (max == r) {
        hue = ((g - b) / delta) % 6f
    } else if (max == g) {
        hue = (b - r) / delta + 2f
    } else {
        hue = (r - g) / delta + 4f
    }

    return ((hue * 60f + 360f) % 360f).toInt()
}

fun getHueDistance(a: Int, b: Int): Int {
    val distance = kotlin.math.abs(a - b)
    return minOf(distance, 360 - distance)
}

fun pickPaletteForColor(color: Color): MiuixPalette {
    val hue = getHueFromColor(color)
    var bestPalette = BUILTIN_THEME_PALETTES[0]
    var minDistance = 360

    for (candidate in BUILTIN_THEME_PALETTES) {
        val distance = getHueDistance(hue, candidate.hue)
        if (distance < minDistance) {
            minDistance = distance
            bestPalette = candidate
        }
    }

    return bestPalette
}

@Composable
private fun LrcLineItem(
    line: LrcLine,
    isActive: Boolean,
    translationMode: Int,
    fontSizeSp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.1f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
    )
    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.45f,
        animationSpec = tween(durationMillis = 400)
    )

    val hasTrans = line.lines.size > 1 && translationMode != 0
    val minHeight = if (hasTrans) 80.dp else 50.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .heightIn(min = minHeight)
            .padding(vertical = 8.dp, horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    this.alpha = alpha
                    this.scaleX = scale
                    this.scaleY = scale
                    this.transformOrigin = TransformOrigin(0f, 0.5f)
                },
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            line.lines.forEachIndexed { lineIndex, text ->
                if (lineIndex > 0 && translationMode == 0) return@forEachIndexed

                val isMainLine = lineIndex == 0
                val textColor by animateColorAsState(
                    targetValue = if (isMainLine) {
                        if (isActive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        if (isActive) MiuixTheme.colorScheme.onSurfaceVariantActions else MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.4f)
                    },
                    animationSpec = tween(durationMillis = 400)
                )

                if (isMainLine) {
                    Text(
                        text = text,
                        fontSize = fontSizeSp.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = textColor,
                        textAlign = TextAlign.Start
                    )
                } else {
                    if (translationMode == 1) {
                        AnimatedVisibility(
                            visible = isActive,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = text,
                                    fontSize = (fontSizeSp * 0.75f).sp,
                                    fontWeight = FontWeight.Normal,
                                    color = textColor,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    } else if (translationMode == 2) {
                        Column {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = text,
                                  fontSize = (fontSizeSp * 0.75f).sp,
                                  fontWeight = FontWeight.Normal,
                                  color = textColor,
                                  textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            }
        }
    }
}


