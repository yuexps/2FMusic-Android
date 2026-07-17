package top.msfxp.music.shared

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.Platform
import top.yukonga.miuix.kmp.utils.platform

import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTheme(
    isDarkTheme: Boolean,
    content: @Composable () -> Unit
) {
    if (platform() != Platform.MacOS) ComposeFoundationFlags.isNewContextMenuEnabled = true
    val mode = if (isDarkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(
        controller = controller
    ) {
        content()
    }
}
