package utils

data class LrcLine(
    val time: Long, // 毫秒
    val lines: List<String> // 支持多行:原文、翻译等
)

object LrcParser {
    fun parse(lrcText: String, title: String? = null): List<LrcLine> {
        val displayTitle = if (title != null) " [$title]" else ""
        Platform.logger.i("LrcParser", "开始解析歌词$displayTitle, 长度: ${lrcText.length}")
        
        val lines = lrcText.split("\n")
        // 使用 Map 收集同一时间戳的所有歌词行
        val timeMap = mutableMapOf<Long, MutableList<String>>()
        // 支持 [mm:ss.xx] 和 [mm:ss] 两种格式
        val timeRegex = """\[(\d+):(\d+)(?:\.(\d+))?\]""".toRegex()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            
            // 跳过元数据标签
            if (trimmedLine.matches(Regex("""\[(id|ar|ti|by|hash|al|sign|qq|total|offset|length|re|ve):.*?\]""", RegexOption.IGNORE_CASE))) {
                continue
            }

            val matchResults = timeRegex.findAll(trimmedLine)
            val rawContent = trimmedLine.replace(timeRegex, "").trim()
            
            if (rawContent.isEmpty()) continue
            
            // 解码 HTML 实体 (如 &apos; -> ')
            val content = decodeHtmlEntities(rawContent)

            // 一行可能有多个时间戳
            for (match in matchResults) {
                val min = match.groupValues[1].toLongOrNull() ?: 0
                val sec = match.groupValues[2].toLongOrNull() ?: 0
                val ms = match.groupValues[3].takeIf { it.isNotEmpty() }?.let {
                    // 处理两位或三位毫秒
                    when (it.length) {
                        2 -> it.toLongOrNull()?.times(10) ?: 0  // xx -> xx0
                        3 -> it.toLongOrNull() ?: 0              // xxx -> xxx
                        else -> 0
                    }
                } ?: 0
                
                val time = min * 60 * 1000 + sec * 1000 + ms
                
                // 将歌词添加到对应时间戳的列表中
                timeMap.getOrPut(time) { mutableListOf() }.add(content)
            }
        }

        // 转换为 LrcLine 列表并排序
        val lrcLines = timeMap.map { (time, lines) ->
            LrcLine(time, lines)
        }.sortedBy { it.time }

        Platform.logger.i("LrcParser", "歌词解析完成$displayTitle,共 ${lrcLines.size} 个时间戳,${timeMap.values.sumOf { it.size }} 行歌词")
        return lrcLines
    }

    private fun decodeHtmlEntities(text: String): String {
        return text.replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }

    fun getCurrentLineIndex(lrcLines: List<LrcLine>, currentTime: Long): Int {
        for (i in lrcLines.indices.reversed()) {
            if (currentTime >= lrcLines[i].time) {
                return i
            }
        }
        return -1
    }
}
