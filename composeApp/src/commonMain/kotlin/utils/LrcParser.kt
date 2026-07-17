package utils

data class YrcWord(
    val text: String,
    val startTime: Long, // 绝对开始时间戳（毫秒）
    val duration: Long // 持续时间（毫秒）
)

data class LrcLine(
    val time: Long, // 毫秒
    val lines: List<String>, // 支持多行:原文、翻译等
    val isYrc: Boolean = false,
    val duration: Long = 0L, // 毫秒
    val words: List<YrcWord> = emptyList()
)

object LrcParser {
    private val timeRegex = """\[(\d+):(\d+)(?:\.(\d+))?\]""".toRegex()
    private val tagRegex = Regex("""\[(id|ar|ti|by|hash|al|sign|qq|total|offset|length|re|ve):.*?\]""", RegexOption.IGNORE_CASE)
    private val yrcRowRegex = """^\[(\d+),(\d+)\]""".toRegex()
    private val yrcWordRegex = """\((\d+),(\d+)(?:,\d+)?\)([^\(]*)""".toRegex()

    fun parse(lrcText: String, title: String? = null): List<LrcLine> {
        val displayTitle = if (title != null) " [$title]" else ""
        Platform.logger.i("LrcParser", "开始解析歌词$displayTitle, 长度: ${lrcText.length}")
        
        val lines = lrcText.split("\n")
        val tempLines = mutableListOf<LrcLine>()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            // 过滤并直接丢弃 JSON 元数据行
            if (trimmedLine.startsWith("{") && trimmedLine.endsWith("}")) {
                continue
            }
            
            // 跳过元数据标签
            if (trimmedLine.matches(tagRegex)) {
                continue
            }

            // 1. 优先尝试解析 YRC 逐字歌词
            val yrcMatch = yrcRowRegex.find(trimmedLine)
            if (yrcMatch != null) {
                val time = yrcMatch.groupValues[1].toLongOrNull() ?: 0L
                val duration = yrcMatch.groupValues[2].toLongOrNull() ?: 0L
                val content = trimmedLine.replace(yrcRowRegex, "")
                
                val words = mutableListOf<YrcWord>()
                val textBuilder = StringBuilder()
                
                val wordMatches = yrcWordRegex.findAll(content)
                for (wordMatch in wordMatches) {
                    val wStart = wordMatch.groupValues[1].toLongOrNull() ?: 0L
                    val wDuration = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                    val wText = decodeHtmlEntities(wordMatch.groupValues[3])
                    // 智能判定相对偏移量与绝对时间戳，保持高精度绝对时间存储
                    val absoluteStart = if (wStart < time) time + wStart else wStart
                    words.add(YrcWord(wText, absoluteStart, wDuration))
                    textBuilder.append(wText)
                }
                
                val lineText = textBuilder.toString()
                if (lineText.isNotEmpty()) {
                    tempLines.add(LrcLine(time, listOf(lineText), isYrc = true, duration = duration, words = words))
                }
                continue
            }

            // 2. 传统 LRC 歌词解析
            val matchResults = timeRegex.findAll(trimmedLine)
            val rawContent = trimmedLine.replace(timeRegex, "").trim()
            
            if (rawContent.isEmpty()) continue
            
            // 解码 HTML 实体
            val content = decodeHtmlEntities(rawContent)

            // 一行可能有多个时间戳
            for (match in matchResults) {
                val min = match.groupValues[1].toLongOrNull() ?: 0
                val sec = match.groupValues[2].toLongOrNull() ?: 0
                val ms = match.groupValues[3].takeIf { it.isNotEmpty() }?.let {
                    // 智能对准任意位数的毫秒值，防止位数不标准导致清零
                    val pad = it.padEnd(3, '0')
                    pad.substring(0, 3).toLongOrNull() ?: 0L
                } ?: 0L
                
                val time = min * 60 * 1000 + sec * 1000 + ms
                tempLines.add(LrcLine(time, listOf(content)))
            }
        }

        // 合并相同时间戳的普通 LRC 行（支持翻译多行合并）并进行时间升序排序
        val grouped = tempLines.groupBy { it.time }
        val sortedKeys = grouped.keys.sorted()
        val result = mutableListOf<LrcLine>()
        for (time in sortedKeys) {
            val items = grouped[time] ?: continue
            val first = items.first()
            if (first.isYrc) {
                result.add(first)
            } else {
                val mergedTexts = items.flatMap { it.lines }
                result.add(LrcLine(time, mergedTexts))
            }
        }

        Platform.logger.i("LrcParser", "歌词解析完成$displayTitle,共 ${result.size} 行歌词")
        return result
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
