package utils

interface NotificationHelper {
    fun init(ctx: Any)
    fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int, ongoing: Boolean = true)
    fun showMessage(id: Int, title: String, content: String)
    fun cancel(id: Int)
}
