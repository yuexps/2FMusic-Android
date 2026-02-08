package utils

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Web 端暂无物理返回键逻辑，此处为空实现
}
