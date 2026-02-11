package top.msfxp.music

import android.app.Application
import config.ConfigManager
import api.AndroidPlayerController
import utils.FileStore


class FMusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 1. 初始化配置管理
        ConfigManager.initialize(this)
        
        // 2. 初始化文件存储 (使用外部私有目录)
        val storageDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        FileStore.initialize(storageDir)
        
        // 3. 初始化播放器控制器
        AndroidPlayerController.initialize(this)
        
        // 4. 初始化 UI 插槽 (Toast, Notification)
        utils.Toast.init(this)
        utils.NotificationHelper.init(this)
    }
}
