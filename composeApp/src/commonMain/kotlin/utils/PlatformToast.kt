package utils

interface PlatformToast {
    fun show(message: String)
}

expect object Toast {
    fun show(message: String)
}
