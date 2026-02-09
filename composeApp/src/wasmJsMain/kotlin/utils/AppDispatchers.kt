package utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val IODispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun getTimeMillis(): Long = jsDateNow().toLong()

actual fun formatTime(millis: Long): String = jsFormatDate(millis.toDouble())

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Date.now()")
external fun jsDateNow(): Double

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(millis) => {
        const d = new Date(millis);
        const year = d.getFullYear();
        const month = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        const hours = String(d.getHours()).padStart(2, '0');
        const minutes = String(d.getMinutes()).padStart(2, '0');
        const seconds = String(d.getSeconds()).padStart(2, '0');
        const ms = String(d.getMilliseconds()).padStart(3, '0');
        return year + '-' + month + '-' + day + ' ' + hours + ':' + minutes + ':' + seconds + '.' + ms;
    }"""
)
external fun jsFormatDate(millis: Double): String
