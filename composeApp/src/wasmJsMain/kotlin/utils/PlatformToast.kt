package utils

import kotlinx.browser.window

actual object Toast {
    actual fun show(message: String) {
        window.alert(message)
    }
}
