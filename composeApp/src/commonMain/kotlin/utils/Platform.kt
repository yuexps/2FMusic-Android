package utils

import config.AppConfig
import data.MusicRepository
import api.MusicApi

/**
 * 全局平台能力访问点
 * 由各平台入口在初始化时赋值
 */
object Platform {
    private var _dependencies: PlatformDependencies? = null
    lateinit var api: MusicApi

    val dependencies: PlatformDependencies
        get() = _dependencies ?: throw IllegalStateException("Platform dependencies not initialized!")

    fun init(deps: PlatformDependencies) {
        _dependencies = deps
    }

    val config: AppConfig get() = dependencies.config
    val logger: Logger get() = dependencies.logger
    val toast: Toast get() = dependencies.toast
    val notification: NotificationHelper get() = dependencies.notification
    val repository: MusicRepository get() = dependencies.repository
    val playerController: api.PlayerController get() = dependencies.playerController
    val isWasm: Boolean get() = dependencies.isWasm

    // 封面图主色调提取器，由 Android 平台启动时进行赋值实现
    var coverColorExtractor: ((String, (androidx.compose.ui.graphics.Color) -> Unit) -> Unit)? = null

    // 存储权限相关的桥接回调，由平台端（如 Android MainActivity）实现
    var hasStoragePermission: (() -> Boolean)? = null
    var requestStoragePermission: (() -> Unit)? = null

    /**
     * 获取当前系统时间戳（毫秒）
     */
    fun getTimeMillis(): Long = utils.getTimeMillis()
}
