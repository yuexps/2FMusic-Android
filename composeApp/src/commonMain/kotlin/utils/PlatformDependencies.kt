package utils

import config.AppConfig
import data.MusicRepository

/**
 * 聚合所有平台相关的依赖逻辑
 */
data class PlatformDependencies(
    val repository: MusicRepository,
    val config: AppConfig,
    val logger: Logger,
    val toast: Toast,
    val notification: NotificationHelper,
    val isWasm: Boolean = false
)
