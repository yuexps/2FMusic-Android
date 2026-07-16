package top.msfxp.music

import android.app.Application
import api.AndroidPlayerController


class FMusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 1. 初始化文件存储环境 (用于数据库等)
        val storageDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        utils.FileStore.initialize(storageDir)
        
        // 2. 实例化平台依赖
        val mApi = api.MusicApi()
        val driverFactory = database.DatabaseDriverFactory(this)
        val repository = data.SqlMusicRepository(mApi, driverFactory)
        
        val config = config.AndroidAppConfig().apply { initialize(this@FMusicApplication) }
        val logger = utils.AndroidLogger()
        val toast = utils.AndroidToast().apply { init(this@FMusicApplication) }
        val notification = utils.AndroidNotificationHelper().apply { init(this@FMusicApplication) }

        // 3. 实例化并注入平台依赖，确保其他组件在初始化时可安全使用 Platform
        val platform = utils.PlatformDependencies(
            repository = repository,
            config = config,
            logger = logger,
            toast = toast,
            notification = notification,
            playerController = AndroidPlayerController,
            isWasm = false
        )
        utils.Platform.init(platform)

        // 4. 初始化播放器控制器
        AndroidPlayerController.initialize(this)
    }
}
