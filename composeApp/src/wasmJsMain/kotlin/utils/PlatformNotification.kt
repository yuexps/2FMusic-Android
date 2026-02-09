package utils

actual object NotificationHelper {
    actual fun init(ctx: Any) {
        // No-op for web
    }

    actual fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int) {
        if (progress % 10 == 0) { // Log sparingly to console
            utils.Logger.i("Download", "正在下载 $title: $progress/$max")
        }
    }

    actual fun showMessage(id: Int, title: String, content: String) {
        utils.Logger.i("Notification", "$title: $content")
    }

    actual fun cancel(id: Int) {
        // No-op for web
    }
}
