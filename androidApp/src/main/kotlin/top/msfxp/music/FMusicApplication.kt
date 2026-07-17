package top.msfxp.music

import android.app.Application
import coil.imageLoader
import api.AndroidPlayerController


class FMusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. 实例化配置并初始化文件存储环境
        val config = config.AndroidAppConfig().apply { initialize(this@FMusicApplication) }
        utils.FileStore.initialize(
            internalPath = config.getInternalStorageDir(),
            lyricsPath = config.getLyricsStorageDir(),
            coverPath = config.getCoverStorageDir(),
            audioPath = config.getAudioStorageDir()
        )

        // 2. 实例化平台依赖
        val mApi = api.MusicApi()
        val driverFactory = database.DatabaseDriverFactory(this)
        val repository = data.SqlMusicRepository(mApi, driverFactory)
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

        // 绑定 Android 原生封面图色彩提取器，采样封面以获取高饱和度主导色
        utils.Platform.coverColorExtractor = { url, callback ->
            val imageLoader = this@FMusicApplication.imageLoader
            val request = coil.request.ImageRequest.Builder(this)
                .data(url)
                .allowHardware(false) // 关闭硬件加速，以便能够读取像素
                .target { result ->
                    if (result is android.graphics.drawable.BitmapDrawable) {
                        val bitmap = result.bitmap
                        val width = bitmap.width
                        val height = bitmap.height
                        val sampleSizeX = 10
                        val sampleSizeY = 10
                        val stepX = (width / sampleSizeX).coerceAtLeast(1)
                        val stepY = (height / sampleSizeY).coerceAtLeast(1)
                        var maxSat = -1f
                        var bestColor = 0xFF4F7CFF.toInt() // 默认蓝色
                        val hsv = FloatArray(3)

                        for (x in 0 until width step stepX) {
                            for (y in 0 until height step stepY) {
                                val pixel = bitmap.getPixel(x, y)
                                val alpha = (pixel shr 24) and 0xff
                                if (alpha < 200) continue
                                android.graphics.Color.colorToHSV(pixel, hsv)
                                val s = hsv[1]
                                val v = hsv[2]
                                if (v < 0.15f || s < 0.12f || (s < 0.2f && v > 0.85f)) continue
                                if (s > maxSat) {
                                    maxSat = s
                                    bestColor = pixel
                                }
                            }
                        }
                        callback(androidx.compose.ui.graphics.Color(bestColor))
                    }
                }
                .build()
            imageLoader.enqueue(request)
        }

        // 4. 初始化播放器控制器
        AndroidPlayerController.initialize(this)
    }
}
