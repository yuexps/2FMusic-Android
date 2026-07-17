package top.msfxp.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import top.msfxp.music.shared.App


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 请求通知权限 (仅 Android 13+ 需要)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        
        // 绑定存储权限检测与申请回调到全局桥接点，供 KMP common 模块在触发下载时进行检测
        utils.Platform.hasStoragePermission = { this.hasStoragePermission() }
        utils.Platform.requestStoragePermission = { this.checkAndRequestStoragePermission() }

        checkAndRequestStoragePermission()
        
        // 从全局访问点获取已在 Application 中初始化的依赖
        val platform = utils.Platform.dependencies
        
        startService(Intent(this, PlayerService::class.java))

        setContent {
            App(platform)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) {
            utils.FileStore.createRequiredDirectories()
        }
    }

    private fun checkAndRequestStoragePermission() {
        if (!hasStoragePermission()) {
            showStoragePermissionRationale()
        }
    }

    private fun showStoragePermissionRationale() {
        android.app.AlertDialog.Builder(this)
            .setTitle("存储权限申请")
            .setMessage("2FMusic 需要使用存储管理权限来在您的公共 Documents/2FMusic 目录下保存/读取离线音频、歌词、封面及运行日志。\n\n如果不授予，您将无法使用离线下载与缓存功能。是否前往开启？")
            .setPositiveButton("前往开启") { _, _ ->
                requestStoragePermission()
            }
            .setNegativeButton("暂不开启", null)
            .setCancelable(false)
            .show()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needsRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needsRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needsRequest.toTypedArray(), 100)
            }
        }
    }
}
