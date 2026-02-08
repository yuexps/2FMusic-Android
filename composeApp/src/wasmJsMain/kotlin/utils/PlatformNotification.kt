package utils

actual object NotificationHelper {
    actual fun init(ctx: Any) {
        // No-op for web
    }

    actual fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int) {
        if (progress % 10 == 0) { // Log sparingly to console
            println("Downloading $title: $progress/$max")
        }
    }

    actual fun cancel(id: Int) {
        // No-op for web
    }
}
