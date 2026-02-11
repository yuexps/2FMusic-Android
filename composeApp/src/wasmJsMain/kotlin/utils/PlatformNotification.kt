package utils

class WasmNotificationHelper : NotificationHelper {
    override fun init(ctx: Any) {
        // No-op for web
    }

    override fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int, ongoing: Boolean) {
        if (progress % 10 == 0) { // Log sparingly to console
            // utils.Logger.i("Download", "正在下载 $title: $progress/$max (ongoing=$ongoing)")
            println("Download Progress: $title: $progress/$max")
        }
    }

    override fun showMessage(id: Int, title: String, content: String) {
        println("Notification: $title: $content")
    }

    override fun cancel(id: Int) {
        // No-op for web
    }
}
