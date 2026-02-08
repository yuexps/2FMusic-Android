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
     * 读取歌词文本
     */
    fun readLyrics(songId: String): String? {
        val path = "$baseDir/lyrics_$songId.lrc".toPath()
        return if (fs.exists(path)) {
            fs.source(path).use { it.buffer().readUtf8() }
        } else null
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
}
