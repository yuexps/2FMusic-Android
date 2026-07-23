package api

import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import model.*
import utils.FileStore
import utils.Platform
import utils.getTimeMillis

@Serializable
data class WsRequest(
    val seq: String? = null,
    val action: String,
    val data: JsonElement = JsonObject(emptyMap())
)

@Serializable
data class WsResponse(
    val seq: String? = null,
    val type: String? = null,
    val action: String? = null,
    val success: Boolean = false,
    val data: JsonElement? = null,
    val error: String? = null
)

@Suppress("unused")
class MusicApi {

    val libraryChangedFlow get() = Companion.libraryChangedFlow
    val scanStatusFlow get() = Companion.scanStatusFlow
    val downloadStatusFlow get() = Companion.downloadStatusFlow
    val neteaseLoginStatusFlow get() = Companion.neteaseLoginStatusFlow

    private val baseUrl: String
        get() = Platform.config.getBaseUrl()

    // --- 1. 音乐库管理模块 ---
    suspend fun getMusicList(): List<Song> {
        return sendRequest<List<Song>>("music/get_list") ?: emptyList()
    }

    suspend fun deleteFile(songId: String): ApiResponse<Unit> {
        val data = buildJsonObject {
            put("song_id", songId)
        }
        sendRequest<Unit>("music/delete", data)
        return ApiResponse(success = true)
    }

    suspend fun clearMetadata(songId: String? = null, path: String? = null): ApiResponse<Unit> {
        val data = buildJsonObject {
            if (songId != null) put("song_id", songId)
            if (path != null) put("path", path)
        }
        sendRequest<Unit>("music/clear_metadata", data)
        return ApiResponse(success = true)
    }

    suspend fun getLyrics(songId: String, title: String, artist: String?, album: String? = null, filename: String?, yrc: Boolean? = null): LyricsResponse {
        val data = buildJsonObject {
            put("song_id", songId)
            put("title", title)
            if (artist != null) put("artist", artist)
            if (album != null) put("album", album)
            if (filename != null) put("filename", filename)
            if (yrc != null) put("yrc", yrc)
        }
        return sendRequest<LyricsResponse>("music/lyrics", data) ?: LyricsResponse()
    }

    suspend fun getAlbumArt(songId: String, title: String, artist: String?, album: String? = null, filename: String?): AlbumArtResponse {
        val data = buildJsonObject {
            put("song_id", songId)
            put("title", title)
            if (artist != null) put("artist", artist)
            if (album != null) put("album", album)
            if (filename != null) put("filename", filename)
        }
        return sendRequest<AlbumArtResponse>("music/album-art", data) ?: AlbumArtResponse()
    }

    // --- 2. 网易云音乐助手模块 ---
    suspend fun searchNetease(keywords: String, limit: Int = 20): List<NeteaseSong> {
        val data = buildJsonObject {
            put("keywords", keywords)
            put("limit", limit)
        }
        return sendRequest<List<NeteaseSong>>("netease/search", data) ?: emptyList()
    }

    suspend fun getLoginQrCode(): NeteaseQrCode {
        return sendRequest<NeteaseQrCode>("netease/login_qrcode") ?: throw Exception("Failed to get netease QR Code")
    }

    suspend fun getLoginStatus(): NeteaseLoginStatus {
        return sendRequest<NeteaseLoginStatus>("netease/login_status") ?: throw Exception("Failed to get netease login status")
    }

    suspend fun recommendNetease(): List<NeteaseSong> {
        return sendRequest<List<NeteaseSong>>("netease/recommend") ?: emptyList()
    }

    suspend fun logoutNetease(): ApiResponse<Unit> {
        sendRequest<Unit>("netease/logout")
        return ApiResponse(success = true)
    }

    suspend fun downloadNetease(
        id: Long,
        title: String,
        artist: String,
        album: String,
        cover: String? = null,
        level: String = "exhigh",
        targetDir: String? = null
    ): NeteaseDownloadTask {
        val data = buildJsonObject {
            put("id", id)
            put("title", title)
            put("artist", artist)
            put("album", album)
            if (cover != null) put("cover", cover)
            put("level", level)
            if (targetDir != null) put("target_dir", targetDir)
        }
        return sendRequest<NeteaseDownloadTask>("netease/download", data) ?: throw Exception("Failed to start netease download")
    }

    suspend fun getNeteaseTaskStatus(taskId: String): NeteaseTaskDetail {
        val data = buildJsonObject {
            put("task_id", taskId)
        }
        return sendRequest<NeteaseTaskDetail>("netease/task_status", data) ?: throw Exception("Failed to query netease task detail")
    }

    suspend fun getNeteaseConfig(): NeteaseConfig {
        return sendRequest<NeteaseConfig>("netease/get_config") ?: throw Exception("Failed to query netease config")
    }

    suspend fun saveNeteaseConfig(downloadDir: String? = null, apiBase: String? = null): NeteaseConfig {
        val data = buildJsonObject {
            if (downloadDir != null) put("download_dir", downloadDir)
            if (apiBase != null) put("api_base", apiBase)
        }
        return sendRequest<NeteaseConfig>("netease/save_config", data) ?: throw Exception("Failed to save netease config")
    }

    suspend fun resolveNeteaseUrl(input: String): NeteaseResolveResult {
        val data = buildJsonObject {
            put("input", input)
        }
        return sendRequest<NeteaseResolveResult>("netease/resolve", data) ?: throw Exception("Failed to resolve netease url/id")
    }

    suspend fun checkNeteaseContainer(): NeteaseContainerStatus {
        return sendRequest<NeteaseContainerStatus>("netease/check_container") ?: throw Exception("Failed to check docker container")
    }

    suspend fun installNeteaseService(): ApiResponse<String> {
        val res = sendRequest<String>("netease/install_service") ?: ""
        return ApiResponse(success = true, data = res)
    }

    suspend fun getNeteaseInstallStatus(): NeteaseInstallStatus {
        return sendRequest<NeteaseInstallStatus>("netease/install_status") ?: throw Exception("Failed to query install status")
    }

    // --- 3. 收藏夹与自定义歌单模块 ---
    suspend fun getFavoritePlaylists(): List<Playlist> {
        return sendRequest<List<Playlist>>("favorite/list_playlists") ?: emptyList()
    }

    suspend fun createPlaylist(name: String): Playlist {
        val data = buildJsonObject {
            put("name", name)
        }
        return sendRequest<Playlist>("favorite/create_playlist", data) ?: throw Exception("Failed to create playlist")
    }

    suspend fun deletePlaylist(playlistId: String): ApiResponse<Unit> {
        val data = buildJsonObject {
            put("playlist_id", playlistId)
        }
        sendRequest<Unit>("favorite/delete_playlist", data)
        return ApiResponse(success = true)
    }

    suspend fun getPlaylistSongs(playlistId: String): List<String> {
        val data = buildJsonObject {
            put("playlist_id", playlistId)
        }
        return sendRequest<List<String>>("favorite/playlist_songs", data) ?: emptyList()
    }

    suspend fun addFavorite(songId: String, playlistId: String = "default"): ApiResponse<Unit> {
        val data = buildJsonObject {
            putJsonArray("song_ids") { add(songId) }
            putJsonArray("playlist_ids") { add(playlistId) }
        }
        sendRequest<Unit>("favorite/add", data)
        return ApiResponse(success = true)
    }

    suspend fun removeFavorite(songId: String, playlistId: String = "default"): ApiResponse<Unit> {
        val data = buildJsonObject {
            putJsonArray("song_ids") { add(songId) }
            putJsonArray("playlist_ids") { add(playlistId) }
        }
        sendRequest<Unit>("favorite/delete", data)
        return ApiResponse(success = true)
    }

    suspend fun batchMoveSongs(songIds: List<String>, fromPlaylistId: String, toPlaylistId: String): ApiResponse<Unit> {
        val data = buildJsonObject {
            putJsonArray("song_ids") { songIds.forEach { add(it) } }
            put("from_playlist_id", fromPlaylistId)
            put("to_playlist_id", toPlaylistId)
        }
        sendRequest<Unit>("favorite/batch_move", data)
        return ApiResponse(success = true)
    }

    // --- 4. 播放历史模块 ---
    suspend fun getHistory(): List<PlayHistory> {
        return sendRequest<List<PlayHistory>>("history/get") ?: emptyList()
    }

    suspend fun addHistory(songId: String): ApiResponse<Unit> {
        val data = buildJsonObject {
            put("song_id", songId)
        }
        sendRequest<Unit>("history/add", data)
        return ApiResponse(success = true)
    }

    suspend fun removeHistory(songId: String, playTime: Long): ApiResponse<Unit> {
        val data = buildJsonObject {
            put("song_id", songId)
            put("play_time", playTime)
        }
        sendRequest<Unit>("history/remove", data)
        return ApiResponse(success = true)
    }

    suspend fun clearHistory(): ApiResponse<Unit> {
        sendRequest<Unit>("history/clear")
        return ApiResponse(success = true)
    }

    // --- 5. 挂载路径模块 ---
    suspend fun listMountPoints(): List<String> {
        return sendRequest<List<String>>("mount/list") ?: emptyList()
    }

    suspend fun addMountPoint(path: String): ApiResponse<Unit> {
        val data = buildJsonObject {
            put("path", path)
        }
        sendRequest<Unit>("mount/add", data)
        return ApiResponse(success = true)
    }

    suspend fun deleteMountPoint(path: String): ApiResponse<Unit> {
        val data = buildJsonObject {
            put("path", path)
        }
        sendRequest<Unit>("mount/delete", data)
        return ApiResponse(success = true)
    }

    suspend fun scanMountPoint(path: String): ApiResponse<String> {
        val data = buildJsonObject {
            put("path", path)
        }
        val res = sendRequest<String>("mount/scan", data) ?: ""
        return ApiResponse(success = true, data = res)
    }

    suspend fun retryScrapeMountPoint(path: String): ApiResponse<String> {
        val data = buildJsonObject {
            put("path", path)
        }
        val res = sendRequest<String>("mount/retry_scrape", data) ?: ""
        return ApiResponse(success = true, data = res)
    }

    // --- 6. 系统状态与偏好设置模块 ---
    suspend fun getSystemStatus(): SystemStatus {
        return sendRequest<SystemStatus>("system/get_status") ?: throw Exception("Failed to get system status from WS")
    }

    suspend fun getLyricsPreference(): PreferenceValue {
        return sendRequest<PreferenceValue>("system/get_lyrics_preference") ?: throw Exception("Failed to get lyrics preference")
    }

    suspend fun saveLyricsPreference(value: String): ApiResponse<Unit> {
        val data = buildJsonObject {
            put("value", value)
        }
        sendRequest<Unit>("system/save_lyrics_preference", data)
        return ApiResponse(success = true)
    }

    /**
     * 智能网络探活探测：当地址未提供协议时，按 HTTPS -> HTTP 顺序进行实际网络连通测试
     */
    suspend fun probeProtocol(inputUrl: String): String {
        val trimmed = inputUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        val httpsUrl = "https://$trimmed"
        val httpUrl = "http://$trimmed"

        // 1. 优先尝试 HTTPS 握手探活
        try {
            val response = client.get("$httpsUrl/api/system/get_status") {
                expectSuccess = false
                timeout {
                    requestTimeoutMillis = 1500
                    connectTimeoutMillis = 1500
                    socketTimeoutMillis = 1500
                }
            }
            Platform.logger.i("MusicApi", "HTTPS 探活成功 (Http ${response.status.value}): $httpsUrl")
            return httpsUrl
        } catch (e: Throwable) {
            Platform.logger.i("MusicApi", "HTTPS 探活失败 (${e.message})，转为尝试 HTTP 探活...")
        }

        // 2. 探活失败退避尝试 HTTP
        try {
            val response = client.get("$httpUrl/api/system/get_status") {
                expectSuccess = false
                timeout {
                    requestTimeoutMillis = 1500
                    connectTimeoutMillis = 1500
                    socketTimeoutMillis = 1500
                }
            }
            Platform.logger.i("MusicApi", "HTTP 探活成功 (Http ${response.status.value}): $httpUrl")
            return httpUrl
        } catch (e: Throwable) {
            Platform.logger.i("MusicApi", "HTTP 探活失败 (${e.message})，默认回退至 http://")
        }

        return httpUrl
    }

    // --- 7. HTTP 专属方法 ---
    suspend fun downloadFile(url: String, onProgress: ((bytesSentTotal: Long, contentLength: Long) -> Unit)? = null): ByteArray {
        val fullUrl = if (url.startsWith("http")) url else "$baseUrl$url"
        val response = client.get(fullUrl) {
            timeout {
                requestTimeoutMillis = 300000 // 5 分钟下载时长限制
                connectTimeoutMillis = 15000  // 15 秒连接超时
                socketTimeoutMillis = 60000   // 1 分钟无数据传输限制
            }
            onDownload { bytesSentTotal, contentLength ->
                onProgress?.invoke(bytesSentTotal, contentLength ?: 0L)
            }
        }
        return response.body()
    }

    suspend fun downloadFileAsStream(url: String, fileName: String, onProgress: ((bytesSentTotal: Long, contentLength: Long) -> Unit)? = null) {
        val fullUrl = if (url.startsWith("http")) url else "$baseUrl$url"
        client.prepareGet(fullUrl) {
            timeout {
                requestTimeoutMillis = 300000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 60000
            }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                throw Exception("下载失败: HTTP ${response.status.value}")
            }
            val channel: io.ktor.utils.io.ByteReadChannel = response.body()
            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
            utils.FileStore.saveFromChannel(fileName, channel, contentLength, onProgress)
        }
    }

    // 统一代理请求到共享连接管道
    private suspend inline fun <reified T> sendRequest(action: String, data: JsonElement = JsonObject(emptyMap())): T? {
        return sendRequestShared<T>(action, data)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private val client = httpClient {
            expectSuccess = true

            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 5000
                connectTimeoutMillis = 3000
                socketTimeoutMillis = 5000
            }

            install(WebSockets)

            defaultRequest {
                val hash = Platform.config.getPasswordHash()
                if (hash != null) {
                    header("X-Password", hash)
                }
            }
        }

        private var wsSession: DefaultClientWebSocketSession? = null
        private val wsMutex = Mutex()
        private var wsScope: CoroutineScope? = null
        private val pendingMutex = Mutex()
        private val pendingRequests = mutableMapOf<String, CompletableDeferred<WsResponse>>()
        private var seqCounter = 0L

        private val apiScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var reconnectJob: Job? = null

        private var wsBaseUrlUsed: String? = null
        private var wsHashUsed: String? = null

        // 强类型共享 Flow 广播事件
        private val _libraryChangedFlow = MutableSharedFlow<LibraryChangedEvent>(extraBufferCapacity = 64)
        val libraryChangedFlow = _libraryChangedFlow.asSharedFlow()

        private val _scanStatusFlow = MutableSharedFlow<ScanStatusEvent>(extraBufferCapacity = 64)
        val scanStatusFlow = _scanStatusFlow.asSharedFlow()

        private val _downloadStatusFlow = MutableSharedFlow<DownloadStatusEvent>(extraBufferCapacity = 64)
        val downloadStatusFlow = _downloadStatusFlow.asSharedFlow()

        private val _neteaseLoginStatusFlow = MutableSharedFlow<NeteaseLoginStatusEvent>(extraBufferCapacity = 64)
        val neteaseLoginStatusFlow = _neteaseLoginStatusFlow.asSharedFlow()

        private fun generateSeq(): String {
            return "android_${getTimeMillis()}_${seqCounter++}"
        }

        private suspend fun getOrConnectWs(): DefaultClientWebSocketSession {
            val currentBaseUrl = Platform.config.getBaseUrl()
            val currentHash = Platform.config.getPasswordHash()

            val session = wsSession
            if (session != null && session.isActive && wsBaseUrlUsed == currentBaseUrl && wsHashUsed == currentHash) {
                return session
            }

            return wsMutex.withLock {
                val activeSession = wsSession
                if (activeSession != null && activeSession.isActive && wsBaseUrlUsed == currentBaseUrl && wsHashUsed == currentHash) {
                    activeSession
                } else {
                    activeSession?.let {
                        try {
                            it.close()
                        } catch (_: Exception) {}
                    }

                    val newSession = connectWs(currentBaseUrl, currentHash)
                    wsSession = newSession
                    wsBaseUrlUsed = currentBaseUrl
                    wsHashUsed = currentHash
                    newSession
                }
            }
        }

        private suspend fun connectWs(baseUrl: String, hash: String?): DefaultClientWebSocketSession {
            var wsBaseUrl = baseUrl
            if (!wsBaseUrl.startsWith("http://") && !wsBaseUrl.startsWith("https://")) {
                wsBaseUrl = "http://$wsBaseUrl"
            }
            wsBaseUrl = wsBaseUrl.replace("http://", "ws://").replace("https://", "wss://")

            val wsUrl = if (hash != null) {
                val separator = if (wsBaseUrl.contains("?")) "&" else "?"
                "$wsBaseUrl/api/ws${separator}auth=${hash.encodeURLParameter()}"
            } else {
                "$wsBaseUrl/api/ws"
            }

            Platform.logger.i("MusicApi", "Connecting to WebSocket: $wsUrl")
            val session = client.webSocketSession(wsUrl)
            startWsListener(session)
            return session
        }

        private fun startWsListener(session: DefaultClientWebSocketSession) {
            wsScope?.cancel()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            wsScope = scope

            scope.launch {
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val response = json.decodeFromString<WsResponse>(text)
                                if (response.type != null && response.type != "response" && response.type != "pong") {
                                    handleBroadcast(response)
                                    continue
                                }

                                val seq = response.seq
                                if (seq != null) {
                                    val deferred = pendingMutex.withLock { pendingRequests.remove(seq) }
                                    deferred?.complete(response)
                                }
                            } catch (e: Exception) {
                                Platform.logger.e("MusicApi", "Error parsing WebSocket frame: $text", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is java.net.SocketException || e is io.ktor.client.plugins.websocket.WebSocketException) {
                        Platform.logger.i("MusicApi", "WebSocket 连接中断 (${e.message})，准备重连...")
                    } else {
                        Platform.logger.e("MusicApi", "WebSocket 传输循环异常", e)
                    }
                } finally {
                    pendingMutex.withLock {
                        val copy = pendingRequests.toMap()
                        pendingRequests.clear()
                        copy.forEach { (_, deferred) ->
                            deferred.completeExceptionally(Exception("WebSocket disconnected"))
                        }
                    }

                    if (wsSession == session) {
                        wsSession = null
                        triggerReconnect()
                    }
                }
            }

            // 心跳包
            scope.launch {
                try {
                    while (session.isActive) {
                        delay(25000)
                        val pingReq = WsRequest(action = "ping")
                        session.send(Frame.Text(json.encodeToString(pingReq)))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Platform.logger.e("MusicApi", "WebSocket heartbeat error", e)
                }
            }
        }

        private fun triggerReconnect() {
            if (reconnectJob?.isActive == true) return
            reconnectJob = apiScope.launch {
                var attempt = 0
                while (true) {
                    val currentBaseUrl = Platform.config.getBaseUrl()
                    if (currentBaseUrl.isBlank()) {
                        delay(5000)
                        continue
                    }
                    val currentHash = Platform.config.getPasswordHash()
                    val session = wsSession
                    if (session != null && session.isActive && wsBaseUrlUsed == currentBaseUrl && wsHashUsed == currentHash) {
                        break
                    }
                    attempt++
                    val delayMs = kotlin.math.min(30000L, 2000L * attempt)
                    Platform.logger.i("MusicApi", "将在 ${delayMs / 1000} 秒后尝试自动重连 WebSocket (第 $attempt 次)...")
                    delay(delayMs)
                    try {
                        getOrConnectWs()
                        Platform.logger.i("MusicApi", "自动重连 WebSocket 成功！")
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Platform.logger.e("MusicApi", "第 $attempt 次自动重连失败: ${e.message}")
                    }
                }
            }
        }

        private fun handleBroadcast(response: WsResponse) {
            val action = response.action ?: response.type ?: return
            val data = response.data ?: return
            Platform.logger.i("MusicApi", "收到服务端广播通知: action=$action")
            wsScope?.launch {
                try {
                    when (action) {
                        "library_changed" -> {
                            val event = json.decodeFromJsonElement<LibraryChangedEvent>(data)
                            _libraryChangedFlow.emit(event)

                            for (songId in event.songIds) {
                                if (event.fields.contains("cover")) {
                                    FileStore.deleteFile("covers/cover_${songId}.webp")
                                }
                                if (event.fields.contains("lyrics")) {
                                    FileStore.deleteFile("lyrics/lyrics_${songId}.lrc")
                                }
                                if (event.fields.contains("audio")) {
                                    FileStore.deleteFile("audio/audio_${songId}.mp3")
                                }
                            }

                            if (event.fields.contains("history")) {
                                GlobalState.triggerHistoryRefresh()
                            }
                            if (event.fields.contains("favorite")) {
                                GlobalState.triggerFavoriteRefresh()
                            }
                            if (event.fields.isEmpty() || event.fields.any { it in listOf("audio", "metadata", "cover", "lyrics") }) {
                                GlobalState.triggerMusicListRefresh()
                            }
                        }
                        "scan_status" -> {
                            val event = json.decodeFromJsonElement<ScanStatusEvent>(data)
                            _scanStatusFlow.emit(event)
                        }
                        "download_status" -> {
                            val event = json.decodeFromJsonElement<DownloadStatusEvent>(data)
                            _downloadStatusFlow.emit(event)
                        }
                        "netease_login_status" -> {
                            val event = json.decodeFromJsonElement<NeteaseLoginStatusEvent>(data)
                            _neteaseLoginStatusFlow.emit(event)
                        }
                    }
                } catch (e: Exception) {
                    Platform.logger.e("MusicApi", "解析并分发广播事件失败: action=$action", e)
                }
            }
        }

        private suspend inline fun <reified T> sendRequestShared(action: String, data: JsonElement = JsonObject(emptyMap())): T? {
            val seq = generateSeq()
            val req = WsRequest(seq = seq, action = action, data = data)
            val deferred = CompletableDeferred<WsResponse>()

            pendingMutex.withLock {
                pendingRequests[seq] = deferred
            }

            try {
                val session = getOrConnectWs()
                session.send(Frame.Text(json.encodeToString(req)))
            } catch (e: Exception) {
                pendingMutex.withLock { pendingRequests.remove(seq) }
                throw e
            }

            val resp = deferred.await()
            if (!resp.success) {
                throw Exception(resp.error ?: "WebSocket action $action failed")
            }

            val resData = resp.data ?: return null
            return json.decodeFromJsonElement<T>(resData)
        }
    }
}

