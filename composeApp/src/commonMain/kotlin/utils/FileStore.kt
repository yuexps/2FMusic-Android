package utils

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

object FileStore {
    private val fs = platformFileSystem
    private var baseDir: String = ""

    fun initialize(dir: String) {
        baseDir = dir
        fs.createDirectories(dir.toPath())
    }

    /**
     * 保存数据到本地文件
     */
    fun saveFile(fileName: String, data: ByteArray) {
        val path = "$baseDir/$fileName".toPath()
        val sink = fs.sink(path).buffer()
        try {
            sink.write(data)
        } finally {
            sink.close()
        }
    }

    /**
     * 保存歌词文本
     */
    fun saveLyrics(songId: String, lyrics: String) {
        val path = "$baseDir/lyrics_$songId.lrc".toPath()
        val sink = fs.sink(path).buffer()
        try {
            sink.writeUtf8(lyrics)
        } finally {
            sink.close()
        }
    }

    /**
     * 保存封面数据
     */
    fun saveCover(songId: String, data: ByteArray) {
        val path = "$baseDir/cover_$songId.jpg".toPath()
        val sink = fs.sink(path).buffer()
        try {
            sink.write(data)
        } finally {
            sink.close()
        }
    }

    /**
     * 读取歌词文本
     */
    fun readLyrics(songId: String): String? {
        val path = "$baseDir/lyrics_$songId.lrc".toPath()
        if (!fs.exists(path)) return null
        val source = fs.source(path).buffer()
        return try {
            source.readUtf8()
        } finally {
            source.close()
        }
    }

    /**
     * 获取封面本地路径
     */
    fun getCoverPath(songId: String): String? {
        val path = "$baseDir/cover_$songId.jpg".toPath()
        return if (fs.exists(path)) path.toString() else null
    }

    /**
     * 获取本地文件路径（如果存在）
     */
    fun getLocalPath(fileName: String): String? {
        val path = "$baseDir/$fileName".toPath()
        return if (fs.exists(path)) path.toString() else null
    }

    /**
     * 删除本地文件
     */
    fun deleteFile(fileName: String) {
        val path = "$baseDir/$fileName".toPath()
        if (fs.exists(path)) {
            fs.delete(path)
        }
    }

    /**
     * 写入日志文件
     */
    fun log(message: String) {
        val path = "$baseDir/info.log".toPath()
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
