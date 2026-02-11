package utils

import config.AppConfig
import data.MusicRepository

/**
 * 全局平台能力访问点
 * 由各平台入口在初始化时赋值
 */
object Platform {
    private var _dependencies: PlatformDependencies? = null
    
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
    val isWasm: Boolean get() = dependencies.isWasm
    
    /**
     * 获取当前系统时间戳（毫秒）
     */
    fun getTimeMillis(): Long = utils.getTimeMillis()
}
