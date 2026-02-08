# 📖 后端 API 文档

> [!NOTE]
> *   **Base URL**: 默认端口 `23237`。
> *   **格式**: 所有接口均返回 JSON，除非另有说明（如流媒体/图片）。
> *   **鉴权**: 如开启密码，需在 Header 中携带 `Cookie: session=...`。
>
> **ID 定义**:
> *   **`song_id`**: 歌曲唯一标识，由 `音乐文件MD5值` 生成。
> *   **`playlist_id`**: 收藏夹 ID，默认收藏夹为 `default`。
> *   **`netease_id`**: 网易云音乐原始资源 ID。


## 1. 🎵 音乐库 (Music Library)

| 方法 | 路径 | 参数 (JSON/Query) | 描述 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/music` | - | 获取全量音乐列表 |
| `GET` | `/api/music/play/<song_id>` | - | **Stream** 播放音频文件 |
| `POST` | `/api/music/upload` | `file` (FormData, 必填), `target_dir` (Form, 可选) | 上传文件到指定目录 |
| `POST` | `/api/music/import_path` | `path` (JSON, 必填) | 导入服务器本地文件 |
| `DELETE` | `/api/music/delete/<song_id>` | - | **物理删除**文件及关联资源 |
| `POST` | `/api/music/clear_metadata` | `path` 或 `<song_id>` (URL) | 清除封面/歌词缓存 |

### 资源获取

| 方法 | 路径 | 参数 | 描述 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/music/lyrics` | `title` (Query, 必填), `artist`, `filename` | 获取歌词 (优先本地/内嵌，后聚合搜索) |
| `GET` | `/api/music/album-art` | `title` (Query, 必填), `filename` (Query, 必填), `artist` | 获取封面 URL |
| `GET` | `/api/music/covers/<name>` | - | **Image** 读取本地缓存封面 |

### 外部文件预览

| 方法 | 路径 | 参数 | 描述 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/music/external/meta` | `path` (Query, 必填) | 获取任意文件元数据 |
| `GET` | `/api/music/external/play` | `path` (Query, 必填) | **Stream** 播放任意文件 |

---

## 2. 📂 目录管理 (Mount Points)

| 方法 | 路径 | 参数 (JSON) | 描述 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/mount_points` | - | 列出挂载目录 |
| `POST` | `/api/mount_points` | `path` (JSON, 必填) | 添加目录并扫描 |
| `DELETE` | `/api/mount_points` | `path` (JSON, 必填) | 移除挂载目录 |
| `POST` | `/api/mount_points/update` | `path` (JSON, 必填) | 手动触发增量扫描 |
| `POST` | `/api/mount_points/retry_scrape` | `path` (JSON, 必填) | 重试元数据刮削 |

---

## 3. ❤️ 收藏夹 (Favorites)

### 列表与管理

| 方法 | 路径 | 参数 (JSON) | 描述 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/favorite_playlists` | - | 获取收藏夹列表 |
| `POST` | `/api/favorite_playlists` | `name` (JSON, 必填) | 创建新收藏夹 |
| `DELETE` | `/api/favorite_playlists/<playlist_id>`| - | 删除收藏夹 |
| `GET` | `/api/favorite_playlists/<playlist_id>/songs` | - | 获取收藏夹内歌曲 ID |

### 歌曲操作

| 方法 | 路径 | 参数 (JSON) | 描述 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/favorites` | `song_id` (必填), `playlist_id` (可选, 默认default) | 添加歌曲 |
| `DELETE` | `/api/favorites` | `song_id` (必填), `playlist_id` (可选, 默认default) | 移除歌曲 |
| `POST` | `/api/favorites/batch` | `song_ids`: [`song_id`], `playlist_ids`: [`playlist_id`] | 批量添加 |
| `DELETE` | `/api/favorites/batch` | `song_ids`: [`song_id`], `playlist_ids`: [`playlist_id`] | 批量移除 |
| `POST` | `/api/favorites/batch/move` | `song_ids`: [`song_id`], `from_playlist_id`, `to_playlist_id` | 批量移动 |

---

## 4. ☁️ 网易云音乐 (NetEase)

### 搜索与资源

| 方法 | 路径 | 参数 | 描述 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/netease/search` | `keywords` (必填), `limit` (可选, 默认20) | 搜索歌曲 |
| `GET` | `/api/netease/recommend` | - | 获取每日推荐 (需登录) |
| `GET` | `/api/netease/resolve` | `input` / `link` | 解析链接/ID |
| `GET` | `/api/netease/playlist` | `id` | 获取歌单详情 |
| `GET` | `/api/netease/song` | `id` | 获取单曲详情 |

### 下载管理

| 方法 | 路径 | 参数 (JSON) | 描述 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/netease/download` | `id` (必填), `target_dir` (可选), `level` (可选, 默认exhigh) | 异步下载歌曲 |
| `GET` | `/api/netease/task/<task_id>` | - | 查询下载任务状态 |
| `GET` | `/api/netease/config` | - | 获取下载配置 |
| `POST` | `/api/netease/config` | `download_dir`, `api_base` | 更新下载配置 |

### 账号与服务

| 方法 | 路径 | 描述 |
| :--- | :--- | :--- |
| `GET` | `/api/netease/login/qrcode` | 获取登录二维码 |
| `GET` | `/api/netease/login/check` | 检查扫码状态 (`key`) |
| `GET` | `/api/netease/login/status` | 检查登录状态 |
| `POST` | `/api/netease/logout` | 退出登录 |
| `POST` | `/api/netease/install_service` | 自动部署 API 服务 (Docker) |
| `GET` | `/api/netease/install/status` | 获取部署进度 |

---

## 5. ⚙️ 系统 (System)

| 方法 | 路径 | 参数 | 描述 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/system/status` | - | 获取扫描/刮削进度 |
| `GET` | `/api/version_check` | `force_refresh` (可选, 默认false) | 获取系统版本号 |
