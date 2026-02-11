package utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat


actual object NotificationHelper {
    private var context: Context? = null
    private const val CHANNEL_ID = "download_channel"
    private const val CHANNEL_NAME = "Download Progress"

    actual fun init(ctx: Any) {
        if (ctx is Context) {
            context = ctx
            createNotificationChannel()
        }
    }

    private fun createNotificationChannel() {
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance)
        val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(channel)
    }

    actual fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int, ongoing: Boolean) {
        val context = context ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            
        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else if (ongoing) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(0, 0, false)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(id, builder.build())
    }

    actual fun showMessage(id: Int, title: String, content: String) {
        val context = context ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(id, builder.build())
    }

    actual fun cancel(id: Int) {
        val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(id)
    }
}
