package utils

interface PlatformNotification {
    fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int)
    fun cancel(id: Int)
}

expect object NotificationHelper {
    fun init(ctx: Any)
    fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int, ongoing: Boolean = true)
    fun showMessage(id: Int, title: String, content: String)
    fun cancel(id: Int)
}
