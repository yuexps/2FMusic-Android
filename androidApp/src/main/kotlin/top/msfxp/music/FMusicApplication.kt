package top.msfxp.music

import android.app.Application
import config.ConfigManager
import api.AndroidPlayerController
import utils.FileStore
import database.DatabaseDriverFactory

class FMusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigManager.initialize(this)
        AndroidPlayerController.initialize(this)
        FileStore.initialize(this.filesDir.absolutePath + "/cache")
    }
}
