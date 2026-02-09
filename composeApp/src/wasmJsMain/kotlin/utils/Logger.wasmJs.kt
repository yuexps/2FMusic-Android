package utils

/**
 * Web (Wasm) 端的日志实现：输出到浏览器控制台
 */
actual object Logger {
    actual fun i(tag: String, msg: String) {
        println("[$tag] $msg")
    }

    actual fun d(tag: String, msg: String) {
        println("[$tag] D: $msg")
    }

    actual fun e(tag: String, msg: String, t: Throwable?) {
        println("[$tag] ERROR: $msg")
        t?.printStackTrace()
    }
}
