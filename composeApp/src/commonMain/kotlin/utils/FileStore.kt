package utils

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

object FileStore {
    private val fs = FileSystem.SYSTEM
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
        fs.write(path) {
            write(data)
        }
    }

    /**
     * 保存歌词文本
     */
    fun saveLyrics(songId: String, lyrics: String) {
        val path = "$baseDir/lyrics_$songId.lrc".toPath()
        fs.write(path) {
            writeUtf8(lyrics)
        }
    }

    /**
     * 保存封面数据
     */
    fun saveCover(songId: String, data: ByteArray) {
        val path = "$baseDir/cover_$songId.jpg".toPath()
        fs.write(path) {
            write(data)
        }
    }

    /**
     * 读取歌词文本
     */
    fun readLyrics(songId: String): String? {
        val path = "$baseDir/lyrics_$songId.lrc".toPath()
        return if (fs.exists(path)) {
            fs.source(path).use { it.buffer().readUtf8() }
        } else null
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
        try {
            fs.appendingSink(path).buffer().use { 
                it.writeUtf8("[${getCurrentTime()}] $message\n")
            }
        } catch (e: Exception) {
            // 如果文件不存在，appendingSink 可能会失败或自动创建取决于实现，
            // 兜底逻辑：如果不存在则直接创建并写入
            try {
                fs.write(path) {
                    writeUtf8("[${getCurrentTime()}] $message\n")
                }
            } catch (ignore: Exception) {}
        }
    }

    private fun getCurrentTime(): String {
        // 由于在 commonMain 中，无法使用 java.text.SimpleDateFormat
        // 简单返回一个标识，或者如果需要精确时间，可以配合平台实现
        return "LOG" 
    }
}
