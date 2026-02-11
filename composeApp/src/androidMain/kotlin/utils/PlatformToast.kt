package utils

import top.msfxp.music.shared.App
import androidx.compose.runtime.Composable
import android.content.Context

class AndroidToast : Toast {
    private var context: Context? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    fun init(ctx: Context) {
        context = ctx
    }

    override fun show(message: String) {
        context?.let { ctx ->
            handler.post {
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
