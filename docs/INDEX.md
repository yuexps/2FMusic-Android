# 2FMusic-Android 客户端架构与技术细节索引 (INDEX.md)

> **Agent 接入指南**：本文档旨在为后续接任的 AI 编码助手（Agent）提供直击本质的技术地图与资产索引。本工程采用 Kotlin Multiplatform (KMP) 跨平台解耦架构。开发底线规约与平台解耦隔离红线请参考根目录下的 [AGENTS.md](../AGENTS.md)。

---

## 1. 核心目录与模块地图

### [composeApp/src/commonMain](../composeApp/src/commonMain) (多平台共享核心)

*   [**`api/`**](../composeApp/src/commonMain/kotlin/api)：网络通信层
    *   [**`MusicApi.kt`**](../composeApp/src/commonMain/kotlin/api/MusicApi.kt)：底层网络交互总控。封装 Ktor WebSocket 双向数据帧交互（`WsRequest/WsResponse`），内置自增 `seq` 机制将异步 WS 转化为 `suspend` 伪同步函数。
    *   [**`GlobalState.kt`**](../composeApp/src/commonMain/kotlin/api/GlobalState.kt)：全局状态单例，广播接收器事件（如收藏、库更新）由此触发全局 UI 或播放器状态重置。
*   [**`model/`**](../composeApp/src/commonMain/kotlin/model)：共享数据实体
    *   [**`Music.kt`**](../composeApp/src/commonMain/kotlin/model/Music.kt)：定义 JSON 反序列化实体（如不含 path 字段的 `Song`、`Playlist` 等）。
    *   [**`PlayerState.kt`**](../composeApp/src/commonMain/kotlin/model/PlayerState.kt)：播放器核心状态类型 `PlaybackState`（BUFFERING, PLAYING, PAUSED, IDLE, ERROR）定义。
*   [**`ui/`**](../composeApp/src/commonMain/kotlin/ui)：Jetpack Compose 界面层
    *   高度定制且深度集成了 Miuix (HyperOS 风格) 移动组件库，核心播放页 `PlayerScreen.kt` 与底栏 `BottomPlayerBar.kt` 内置了基于 `top.yukonga.miuix.kmp.blur` 的 `textureBlur` 与 `layerBackdrop` 等高动态毛玻璃模糊特效。
*   [**`utils/`**](../composeApp/src/commonMain/kotlin/utils)：解析与底层工具
    *   [**`LrcParser.kt`**](../composeApp/src/commonMain/kotlin/utils/LrcParser.kt)：轻量化歌词解析器。使用正则表达式匹配 `[分钟:秒.毫秒]` 仅支持**逐行解析与高亮**，不支持字级时间片（Yrc/Qrc 等）及文字平滑流光渐变。

### [composeApp/src/androidMain](../composeApp/src/androidMain) (Android 特定实现)
*   [**`api/AndroidPlayerController.kt`**](../composeApp/src/androidMain/kotlin/api/AndroidPlayerController.kt)：采用 **Jetpack Media3 (ExoPlayer)** 实现的播放器引擎。
*   [**`data/SqlMusicRepository.kt`**](../composeApp/src/androidMain/kotlin/data/SqlMusicRepository.kt)：基于 **SQLDelight** 实现的离线数据持久化层。

---

## 2. 核心技术实现细节 (基于源码查证)

### 2.1 ExoPlayer 播放引擎与鉴权头注入
在 `AndroidPlayerController.kt` 中：
*   **网络鉴权**：通过在 `DefaultHttpDataSource.Factory` 中自动配置默认请求属性，将本地持久化配置中的密码哈希作为 `X-Password` 头部注入到每一个音频网络数据源请求中，使得 ExoPlayer 能够直接请求受保护的服务端音频流（如 `/api/music/play/{id}`）。
*   **多协议分发**：使用 `DefaultDataSource.Factory` 配合 Http 数据源工厂，实现对本地已下载缓存文件（`file://` 协议）和服务器在线流媒体（`http://` 或 `https://` 协议）的动态路由播放。

### 2.2 锁屏/蓝牙动态歌词与播控联动
*   **状态栏/车机动态歌词**：如果配置启用了 `getShowLyricsInNotification`，控制器会周期性通过当前播放位置调用 `LrcParser.getCurrentLineIndex` 提取当前歌词文本，并利用 `replaceMediaItem` 动态重置当前 ExoPlayer 播放项 MediaMetadata 中的 Title (设为歌词文本) 和 Artist (设为“歌名 - 歌手”)。这一机制巧妙地实现了让锁屏界面、系统通知栏和蓝牙车机屏幕滚动显示当前歌词的特性。
*   **播控喜欢状态双向同步**：通过协程收集全局 `GlobalState.favoriteIds` 变更流，比对当前媒体项 userRating 状态，调用 `HeartRating(isFav)`，实现与 Android 13+ / MIUI / HyperOS 系统播控中心的“喜欢”爱心按钮的双向联动状态同步。

### 2.3 SQLDelight 离线缓存与版本差分同步
在 `SqlMusicRepository.kt` 中：
*   **防刷同步控制**：比对本地键为 `"last_library_version"` 的元数据与服务端 `library_version`。版本相同时直接跳过同步。版本变更时，触发 `api.getMusicList()` 获取歌曲全量数据。
*   **事务插入与本地字段防护**：在数据库 `transaction` 中批量写入歌曲时，将本地原有的 `localCoverPath`、`localLyricsPath`、`localAudioPath` 状态字段继承，防止重写本地实体时覆盖掉已下载的本地资源缓存。
*   **差分物理清理**：将本地旧歌曲 ID 集合与远程最新 ID 集合计算差集，针对已移除歌曲物理执行 `cleanupLocalFiles` 擦除本地对应的 `.mp3`、`.jpg` 和 `.lrc` 文件，并在数据库中将其彻底删除。
*   **物理与数据库双向自愈扫描同步**：通过 `scanAndSyncLocalFiles()` 实现了对本地物理缓存资源与数据库索引的自愈同步。首先，校验并重置因外部手动删除而物理丢失的媒体文件索引记录；其次，读取 Documents、Pictures 和 Music 物理隔离文件夹下的常规文件列表，并根据规范命名的文件名（`audio_songId.mp3` 等）反向提取 ID 并自动在数据库中关联补齐本地路径状态，实现双向自愈。

### 2.4 Okio 文件物理系统
*   **路径隔离**：通过 `PlatformFileSystem.kt` (commonMain 声明 `expect`，androidMain 通过 `FileSystem.SYSTEM` 实例化 `actual`) 引入 Okio 库。
*   **物理挂载与权限设计**：由 `FileStore.kt` 进行 4 通道物理目录路由。包括 `internalDir` (应用内部私有沙盒存储)，以及统一收纳在外部公共 `Documents/2FMusic` 目录下的三个子目录 `lyricsDir` (存放歌词)、`coverDir` (存放封面图)、`audioDir` (存放音频文件)；而 **`music.db` 数据库则被重构存放于 App 内部专属的安全数据库空间**（即 `"music.db"` 相对路径）。在 Android 11+ (API 30+) 环境下，外部共享存储读写需动态获取 `MANAGE_EXTERNAL_STORAGE` 权限。
*   **防媒体扫描保护**：在外部公共 `Documents/2FMusic` 根目录下**自动写入一个空的 `.nomedia` 文件**。借由 Android 媒体扫描器的向下递归忽略规则，自动实现对根目录下所有子媒体目录（歌词、封面、音频）的统一屏蔽保护，防止系统媒体库脏扫描。当用户授予权限返回前台时，在 `MainActivity.onResume` 中会触发重新自愈重建该物理结构。
*   **日志空间容量控制**：运行日志 `info.log` 直接存放于 `Documents/2FMusic` 根目录下。引入了单文件最大 **5MB** 的体积限制。一旦体积超限，自动执行滚动备份：将原日志重命名为 `info.log.1` 并重建 `info.log` 重新写入，以防无限增长撑爆设备空间。
*   **下载节流上报**：通过 Ktor 的 `onDownload` 回调获取实时字节大小，以 `200ms` 的最小频率限制调用 `Platform.notification.showProgress`，安全、防抖地在系统通知栏渲染下载进度条。

---

## 3. Agent 快速扩展指南 (如何新增接口)

若要为安卓端扩展与后端的通信接口，请依次修改以下位置：

1.  **定义实体**：在 [`Music.kt`](../composeApp/src/commonMain/kotlin/model/Music.kt) 中定义 `@Serializable` 的返回数据结构。
2.  **编写 API 动作**：在 [`MusicApi.kt`](../composeApp/src/commonMain/kotlin/api/MusicApi.kt) 的对应区域添加挂起函数。
    *   *示例代码*：
        ```kotlin
        suspend fun actionExample(param: String): ApiResponse<MyData> {
            val data = buildJsonObject { put("param_key", param) }
            return sendRequest<ApiResponse<MyData>>("module/action_name", data) 
                ?: throw Exception("网络请求失败")
        }
        ```
3.  **如果需要监听广播**：在 [`MusicApi.kt:L508`](../composeApp/src/commonMain/kotlin/api/MusicApi.kt#L508) 处的 `handleBroadcast` 的 `when(type)` 分支中追加新广播事件解析并写入对应的 `SharedFlow` 进行事件发射。
