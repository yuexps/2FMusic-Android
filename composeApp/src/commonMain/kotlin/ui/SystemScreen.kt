package ui

import androidx.compose.foundation.layout.*
import utils.Platform
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.GlobalState
import api.MusicApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.SystemStatus
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import data.MusicRepository
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.icon.extended.Back
import androidx.activity.compose.BackHandler

@Composable
fun SystemScreen(repository: MusicRepository, modifier: Modifier = Modifier) {
    val api = remember { MusicApi() }
    var status by remember { mutableStateOf<SystemStatus?>(null) }
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()
    var isRefreshing by remember { mutableStateOf(false) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var rawErrorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    var showConfigDialog by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(Platform.config.getBaseUrl()) }
    var password by remember { mutableStateOf(Platform.config.getPassword() ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }

    var isHistoryOpen by remember { mutableStateOf(false) }
    var historyRefreshTrigger by remember { mutableStateOf(0) }
    val historySongs by remember(historyRefreshTrigger) {
        repository.getPlayHistory()
    }.collectAsState(initial = emptyList())

    val currentPlayingSong by Platform.playerController.currentSong.collectAsState(initial = null)

    BackHandler(enabled = isHistoryOpen) {
        isHistoryOpen = false
    }

    val loadStatus = suspend {
        try {
            val result = api.getSystemStatus()
            status = result
            errorMessage = null
            rawErrorMessage = null
        } catch (e: Throwable) {
            status = null
            errorMessage = when {
                e.message?.contains("401", ignoreCase = true) == true ||
                e.message?.contains("unauthorized", ignoreCase = true) == true -> "认证失败，请配置正确的密码"

                e.message?.contains("fetch", ignoreCase = true) == true ||
                e.message?.contains("Network", ignoreCase = true) == true ||
                e.message?.contains("Connection", ignoreCase = true) == true ||
                e.message?.contains("failed", ignoreCase = true) == true -> "服务器连接失败，请检查网络连接或后端配置"

                else -> null
            }
            rawErrorMessage = e.message ?: e.toString()
            Platform.logger.e("SystemScreen", "无法获取系统状态", e)
        } finally {
            isInitialLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadStatus()
        GlobalState.refreshSignal.collect {
            isRefreshing = true
            historyRefreshTrigger++
            // 确保至少显示 1 秒动画，优化体验
            val refreshJob = launch { loadStatus() }
            val delayJob = launch { delay(1000) }
            refreshJob.join()
            delayJob.join()
            isRefreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (isHistoryOpen) "播放记录" else "系统设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (isHistoryOpen) {
                        IconButton(
                            onClick = { isHistoryOpen = false },
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
                    if (isHistoryOpen && historySongs.isNotEmpty()) {
                        var showClearConfirm by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "清空记录"
                            )
                        }

                            WindowDialog(
                                title = "清空记录",
                                show = showClearConfirm,
                                onDismissRequest = { showClearConfirm = false }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("确定要清空全部播放记录吗？", fontSize = 16.sp)
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
                                                        Platform.toast.show("已清空播放记录")
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
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        AnimatedContent(
            targetState = isHistoryOpen,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(300))) togetherWith
                            (slideOutHorizontally { width -> -width / 3 } + fadeOut(animationSpec = tween(300)))
                } else {
                    (slideInHorizontally { width -> -width / 3 } + fadeIn(animationSpec = tween(300))) togetherWith
                            (slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(300)))
                }
            },
            label = "history_page_transition",
            modifier = Modifier.padding(innerPadding)
        ) { historyOpen ->
            if (!historyOpen) {
                val pullToRefreshState = rememberPullToRefreshState()

                PullToRefresh(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        coroutineScope.launch {
                            GlobalState.triggerRefresh()
                        }
                    },
                    pullToRefreshState = pullToRefreshState,
                    topAppBarScrollBehavior = scrollBehavior,
                    refreshTexts = listOf(
                        "下拉刷新",
                        "松开刷新",
                        "正在刷新",
                        "刷新成功"
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(bottom = 24.dp)
                    ) {
                        SmallTitle(text = "服务状态", modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Column {
                                if (status != null) {
                                    status?.let { s ->
                                        BasicComponent(
                                            title = "后端状态",
                                            endActions = {
                                                Text("已连接", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 15.sp)
                                            }
                                        )
                                        BasicComponent(
                                            title = "音乐数量",
                                            endActions = {
                                                Text(s.musicCount.toString(), color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 15.sp)
                                            }
                                        )
                                    }
                                } else if (isInitialLoading) {
                                    BasicComponent(
                                        title = "后端状态",
                                        endActions = {
                                            Text("正在连接...", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 15.sp)
                                        }
                                    )
                                } else {
                                    BasicComponent(
                                        title = "后端状态",
                                        endActions = {
                                            Text("连接失败", color = MiuixTheme.colorScheme.error, fontSize = 15.sp)
                                        }
                                    )
                                }
                            }
                        }

                        if (status == null && !isInitialLoading) {
                            Spacer(Modifier.height(16.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = errorMessage ?: rawErrorMessage ?: "未知错误",
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }

                        SmallTitle(text = "配置", modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            BasicComponent(
                                title = "后端配置",
                                summary = "配置服务器地址和密码",
                                startAction = {
                                    Icon(
                                        modifier = Modifier.padding(end = 16.dp),
                                        imageVector = MiuixIcons.Settings,
                                        contentDescription = "设置",
                                        tint = MiuixTheme.colorScheme.onBackground
                                    )
                                },
                                onClick = { showConfigDialog = true }
                            )
                        }

                        SmallTitle(text = "其他", modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            BasicComponent(
                                title = "播放记录",
                                summary = "查看和管理您的最近播放足迹",
                                startAction = {
                                    Icon(
                                        modifier = Modifier.padding(end = 16.dp),
                                        imageVector = MiuixIcons.WorldClock,
                                        contentDescription = "播放记录",
                                        tint = MiuixTheme.colorScheme.onBackground
                                    )
                                },
                                onClick = { isHistoryOpen = true }
                            )
                        }

                            WindowDialog(
                                title = "后端配置",
                                show = showConfigDialog,
                                onDismissRequest = { showConfigDialog = false }
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    TextField(
                                        value = baseUrl,
                                        onValueChange = { baseUrl = it },
                                        label = "服务器地址",
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(12.dp))

                                    TextField(
                                        value = password,
                                        onValueChange = { password = it },
                                        label = "访问密码（可选）",
                                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { passwordVisible = !passwordVisible },
                                                modifier = Modifier.padding(end = 12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (passwordVisible) MiuixIcons.Show else MiuixIcons.Hide,
                                                    tint = if (passwordVisible) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSecondaryContainer,
                                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(20.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        TextButton(
                                            text = "取消",
                                            onClick = { showConfigDialog = false },
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        TextButton(
                                            text = "保存",
                                            onClick = {
                                                Platform.config.setBaseUrl(baseUrl)
                                                Platform.config.setPassword(password.ifBlank { null })
                                                GlobalState.triggerRefresh()
                                                showConfigDialog = false
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.textButtonColorsPrimary()
                                        )
                                    }
                                }
                            }
                    }
                }
            } else {
                if (historySongs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无播放记录", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(historySongs) { song ->
                            SongItem(
                                song = song,
                                isBatchMode = false,
                                isSelected = false,
                                onSelectedChange = {},
                                onClick = {
                                    if (Platform.playerController.playlist.value != historySongs) {
                                        Platform.playerController.setPlaylist(historySongs)
                                    }
                                    Platform.playerController.play(song)
                                },
                                showRemoveOption = true,
                                isHistory = true,
                                onRemoveClick = {
                                    coroutineScope.launch {
                                        try {
                                            repository.removeHistory(song.id, song.mtime?.toLong() ?: 0L)
                                            historyRefreshTrigger++
                                            Platform.toast.show("已删除播放记录")
                                        } catch (e: Exception) {
                                            Platform.toast.show("删除失败")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
