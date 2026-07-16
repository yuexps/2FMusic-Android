package ui

import androidx.compose.foundation.layout.*
import utils.Platform
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SystemScreen(modifier: Modifier = Modifier) {
    val api = remember { MusicApi() }
    var status by remember { mutableStateOf<SystemStatus?>(null) }
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()
    var isRefreshing by remember { mutableStateOf(false) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // 旋转动画 (使用 InfiniteTransition 确保循环)
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_infinite")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_rotation"
    )

    val loadStatus = suspend {
        try {
            val result = api.getSystemStatus()
            status = result
            errorMessage = null
        } catch (e: Throwable) {
            status = null
            errorMessage = when {
                e.message?.contains("401", ignoreCase = true) == true || 
                e.message?.contains("unauthorized", ignoreCase = true) == true -> "认证失败，请配置正确的密码"
                else -> "无法连接到服务器"
            }
            Platform.logger.e("SystemScreen", "无法获取系统状态", e)
        } finally {
            isInitialLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadStatus()
        GlobalState.refreshSignal.collect {
            isRefreshing = true
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
                title = "系统设置",
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            if (!isRefreshing) {
                                coroutineScope.launch {
                                    GlobalState.triggerRefresh()
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier.graphicsLayer { 
                                if (isRefreshing) rotationZ = rotation 
                            }
                        )
                    }
                }
            )
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp)
        ) {
            // 版本檢查卡片 (Updater 风格)

            SmallTitle(text = "服务状态", modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (status != null) {
                        status?.let { s ->
                            StatusRow("后端状态", "已连接")
                            StatusRow("音乐数量", s.musicCount.toString())
                            StatusRow("歌单数量", s.playlistCount.toString())
                        }
                    } else if (isInitialLoading) {
                        Text("正在连接服务器...", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        Text(errorMessage ?: "无法连接到服务器", color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            SmallTitle(text = "后端配置", modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp))
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var baseUrl by remember { mutableStateOf(Platform.config.getBaseUrl()) }
                    var password by remember { mutableStateOf(Platform.config.getPassword() ?: "") }
                    var showSuccess by remember { mutableStateOf(false) }
                    var passwordVisible by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()
                    
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
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            Platform.config.setBaseUrl(baseUrl)
                            Platform.config.setPassword(password.ifBlank { null })
                            showSuccess = true
                            // 主动触发全局刷新，使各页面重新拉取数据
                            GlobalState.triggerRefresh()
                            coroutineScope.launch {
                                delay(2000)
                                showSuccess = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showSuccess) "保存成功！" else "保存配置")
                    }
                }
            }

        }
    }
}

@Composable
fun StatusRow(label: String, value: String, valueColor: Color? = null) {
    val resolvedColor = valueColor ?: MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp)
        Text(value, color = resolvedColor, fontSize = 14.sp)
    }
}
