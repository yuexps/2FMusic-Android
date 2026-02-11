package utils

/**
 * Web (Wasm) 端的日志实现：输出到浏览器控制台
 */
class WasmLogger : Logger {
    override fun i(tag: String, msg: String) {
        println("[$tag] $msg")
    }

    override fun d(tag: String, msg: String) {
        println("[$tag] D: $msg")
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        println("[$tag] ERROR: $msg")
        t?.printStackTrace()
    }
}
