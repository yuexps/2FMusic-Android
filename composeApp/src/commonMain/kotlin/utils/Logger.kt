package utils

/**
 * 全局统一日志工具类
 */
expect object Logger {
    /**
     * 信息日志
     */
    fun i(tag: String, msg: String)

    /**
     * 调试日志
     */
    fun d(tag: String, msg: String)

    /**
     * 错误日志
     */
    fun e(tag: String, msg: String, t: Throwable? = null)
}
