package utils

import android.util.Log

/**
 * Android 端的日志实现：同时输出到 Logcat 和本地 info.log 文件
 */
class AndroidLogger : Logger {
    override fun i(tag: String, msg: String) {
        val formattedMsg = "[$tag] $msg"
        Log.i("2FMusic", formattedMsg)
        FileStore.log(formattedMsg)
    }

    override fun d(tag: String, msg: String) {
        val formattedMsg = "[$tag] $msg"
        Log.d("2FMusic", formattedMsg)
        FileStore.log(formattedMsg)
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        val formattedMsg = "[$tag] $msg" + (t?.let { "\n原因: ${it.stackTraceToString()}" } ?: "")
        Log.e("2FMusic", formattedMsg, t)
        FileStore.log(formattedMsg)
    }
}
