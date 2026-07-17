package utils

import okio.Path.Companion.toPath
import okio.buffer
import io.ktor.utils.io.readAvailable

object FileStore {
    private val fs = platformFileSystem
    private var internalDir: String = ""
    private var lyricsDir: String = ""
    private var coverDir: String = ""
    private var audioDir: String = ""

    /**
     * 兼容单根目录初始化 (供非 Android 平台或旧调用兼容使用)
     */
    fun initialize(dir: String) {
        val root = dir.replace("\\", "/")
        initialize(
            internalPath = root,
            lyricsPath = "$root/lyrics",
            coverPath = "$root/cover",
            audioPath = "$root/audio"
        )
    }

    /**
     * 多路径分离式存储初始化，并自动在对应媒体目录生成 .nomedia 文件
     */
    fun initialize(
        internalPath: String,
        lyricsPath: String,
        coverPath: String,
        audioPath: String
    ) {
        internalDir = internalPath.replace("\\", "/")
        lyricsDir = lyricsPath.replace("\\", "/")
        coverDir = coverPath.replace("\\", "/")
        audioDir = audioPath.replace("\\", "/")

        createRequiredDirectories()
    }

    /**
     * 物理隔离目录与 .nomedia 文件的安全创建/自愈
     */
    fun createRequiredDirectories() {
        if (internalDir.isEmpty() || lyricsDir.isEmpty() || coverDir.isEmpty() || audioDir.isEmpty()) {
            return
        }
        try {
            fs.createDirectories(internalDir.toPath())
            fs.createDirectories(lyricsDir.toPath())
            fs.createDirectories(coverDir.toPath())
            fs.createDirectories(audioDir.toPath())

            // 对外部公共 Documents/2FMusic 根目录写入一个 .nomedia 即可，系统媒体库会自动递归屏蔽该根目录下所有的子文件夹
            val baseDocDir = lyricsDir.substringBeforeLast("/")
            createNoMediaFile(baseDocDir)
        } catch (e: Exception) {
            Platform.logger.e("FileStore", "创建物理目录失败: ${e.message}", e)
        }
    }

    private fun createNoMediaFile(dir: String) {
        try {
            val path = "$dir/.nomedia".toPath()
            if (!fs.exists(path)) {
                fs.sink(path).buffer().use { sink ->
                    sink.writeUtf8("")
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 智能路由解析路径：根据相对路径前缀自动路由到不同的物理挂载点
     */
    private fun resolvePath(fileName: String): okio.Path {
        val normalized = fileName.replace("\\", "/")
        return when {
            normalized.startsWith("/") || (normalized.length > 1 && normalized[1] == ':') -> {
                normalized.toPath()
            }
            normalized.startsWith("lyrics/") -> {
                val realName = normalized.substringAfter("lyrics/")
                "$lyricsDir/$realName".toPath()
            }
            normalized.startsWith("cover/") -> {
                val realName = normalized.substringAfter("cover/")
                "$coverDir/$realName".toPath()
            }
            normalized.startsWith("audio/") -> {
                val realName = normalized.substringAfter("audio/")
                "$audioDir/$realName".toPath()
            }
            else -> {
                "$internalDir/$normalized".toPath()
            }
        }
    }

    /**
     * 保存字节数据至目标路径
     */
    fun saveFile(fileName: String, data: ByteArray) {
        val path = resolvePath(fileName)
        fs.sink(path).buffer().use { sink ->
            sink.write(data)
        }
    }

    /**
     * 从 ByteReadChannel 流式保存数据至目标路径，杜绝堆内存 OOM 崩溃
     */
    suspend fun saveFromChannel(
        fileName: String,
        channel: io.ktor.utils.io.ByteReadChannel,
        contentLength: Long,
        onProgress: ((bytesSentTotal: Long, contentLength: Long) -> Unit)? = null
    ) {
        val path = resolvePath(fileName)
        fs.sink(path).buffer().use { sink ->
            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                totalBytesRead += read
                onProgress?.invoke(totalBytesRead, contentLength)
            }
            sink.flush()
        }
    }

    /**
     * 保存封面文件
     */
    fun saveCover(songId: String, data: ByteArray) {
        saveFile("cover/cover_$songId.jpg", data)
    }

    /**
     * 保存歌词文本文件 (智能 YRC/LRC 后缀识别)
     */
    fun saveLyrics(songId: String, text: String) {
        val isYrc = text.contains(Regex("""\[\d+,\d+\]"""))
        val ext = if (isYrc) "yrc" else "lrc"
        try {
            val oppositeExt = if (isYrc) "lrc" else "yrc"
            val oppositePath = resolvePath("lyrics/lyrics_${songId}.$oppositeExt")
            if (fs.exists(oppositePath)) fs.delete(oppositePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        saveFile("lyrics/lyrics_${songId}.$ext", text.encodeToByteArray())
    }

    /**
     * 读取文本文件 (优先读取 .yrc，次之 .lrc)
     */
    fun readLyrics(songId: String): String? {
        var path = resolvePath("lyrics/lyrics_${songId}.yrc")
        if (!fs.exists(path)) {
            path = resolvePath("lyrics/lyrics_${songId}.lrc")
        }
        if (!fs.exists(path)) return null
        return fs.source(path).buffer().use { source ->
            source.readUtf8()
        }
    }

    /**
     * 获取物理存在的歌词文件路径 (优先 .yrc)
     */
    fun getLyricsPath(songId: String): String? {
        val yrcPath = resolvePath("lyrics/lyrics_${songId}.yrc")
        if (fs.exists(yrcPath)) return "lyrics/lyrics_${songId}.yrc"
        val lrcPath = resolvePath("lyrics/lyrics_${songId}.lrc")
        if (fs.exists(lrcPath)) return "lyrics/lyrics_${songId}.lrc"
        return null
    }

    /**
     * 获取本地封面路径
     */
    fun getCoverPath(songId: String): String? {
        val path = resolvePath("cover/cover_$songId.jpg")
        return if (fs.exists(path)) path.toString() else null
    }

    /**
     * 获取文件绝对路径（如果存在）
     */
    fun getLocalPath(fileName: String): String? {
        val path = resolvePath(fileName)
        return if (fs.exists(path)) path.toString() else null
    }

    /**
     * 删除本地缓存文件
     */
    fun deleteFile(fileName: String) {
        val path = resolvePath(fileName)
        if (fs.exists(path)) {
            fs.delete(path)
        }
    }

    /**
     * 获取指定分区（audio, cover, lyrics）下的物理常规文件名列表 (仅返回文件名本身)
     */
    fun listPhysicalFiles(type: String): List<String> {
        val dir = when (type) {
            "audio" -> audioDir.toPath()
            "cover" -> coverDir.toPath()
            "lyrics" -> lyricsDir.toPath()
            else -> return emptyList()
        }
        if (!fs.exists(dir)) return emptyList()
        return try {
            fs.list(dir)
                .filter { fs.metadataOrNull(it)?.isRegularFile == true }
                .map { it.name }
        } catch (e: Exception) {
            Platform.logger.e("FileStore", "列出 $type 物理文件失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 追加写入本地系统日志
     */
    fun log(message: String) {
        val baseDocDir = lyricsDir.substringBeforeLast("/")
        val path = "$baseDocDir/info.log".toPath()
        val backupPath = "$baseDocDir/info.log.1".toPath()

        try {
            val currentSize = fs.metadataOrNull(path)?.size ?: 0L
            if (currentSize >= 5 * 1024 * 1024) { // 5MB
                try {
                    if (fs.exists(backupPath)) {
                        fs.delete(backupPath)
                    }
                    fs.atomicMove(path, backupPath)
                } catch (_: Exception) {
                    try { fs.delete(path) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        var sink: okio.BufferedSink? = null
        try {
            sink = try {
                fs.appendingSink(path).buffer()
            } catch (e: Exception) {
                fs.sink(path).buffer()
            }
            sink.writeUtf8("[${getCurrentTime()}] $message\n")
        } catch (e: Exception) {
            // 忽略写入错误
        } finally {
            try { sink?.close() } catch (ignore: Exception) {}
        }
    }

    private fun getCurrentTime(): String {
        return formatTime(getTimeMillis())
    }
}
