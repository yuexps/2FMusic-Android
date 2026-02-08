package utils

import top.msfxp.music.shared.App
import androidx.compose.runtime.Composable
import android.content.Context

actual object Toast {
    private var context: Context? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    fun init(ctx: Context) {
        context = ctx
    }

    actual fun show(message: String) {
        context?.let { ctx ->
            handler.post {
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        } ?: println("Toast context not initialized: $message")
    }
}
