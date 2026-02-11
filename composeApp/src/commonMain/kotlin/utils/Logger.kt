package utils

/**
 * 全局统一日志工具类
 */
interface Logger {
    fun i(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun e(tag: String, msg: String, t: Throwable? = null)
}
