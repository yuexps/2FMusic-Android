package utils

import kotlinx.coroutines.CoroutineDispatcher

expect val IODispatcher: CoroutineDispatcher

expect fun getTimeMillis(): Long

expect fun formatTime(millis: Long): String
