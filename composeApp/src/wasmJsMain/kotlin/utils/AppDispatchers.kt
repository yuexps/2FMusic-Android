package utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val IODispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun getTimeMillis(): Long = kotlin.js.Date.now().toLong()

actual fun formatTime(millis: Long): String {
    val date = kotlin.js.Date(millis.toDouble())
    val year = date.getFullYear()
    val month = (date.getMonth() + 1).toString().padStart(2, '0')
    val day = date.getDate().toString().padStart(2, '0')
    val hours = date.getHours().toString().padStart(2, '0')
    val minutes = date.getMinutes().toString().padStart(2, '0')
    val seconds = date.getSeconds().toString().padStart(2, '0')
    val ms = date.getMilliseconds().toString().padStart(3, '0')
    return "$year-$month-$day $hours:$minutes:$seconds.$ms"
}
