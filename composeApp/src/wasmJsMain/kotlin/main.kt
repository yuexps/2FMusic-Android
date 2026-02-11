import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.fetch.Response
import top.msfxp.music.shared.App
import data.ApiMusicRepository
import api.MusicApi
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator
import utils.FileStore
import androidx.compose.ui.window.CanvasBasedWindow

private const val MiSansRegular = "./MiSans-Regular.woff2"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val logger = utils.WasmLogger()
    logger.i("WasmMain", "Wasm main started")
    
    FileStore.initialize("web_cache")
    
    val config = config.WasmAppConfig()
    val toast = utils.WasmToast()
    val notification = utils.WasmNotificationHelper()
    val repository = ApiMusicRepository(MusicApi())
    
    val platform = utils.PlatformDependencies(
        repository = repository,
        config = config,
        logger = logger,
        toast = toast,
        notification = notification,
        isWasm = true
    )

    utils.Platform.init(platform)

    ComposeViewport(viewportContainerId = "composeApplication") {
        val fontFamilyResolver = LocalFontFamilyResolver.current
        val fontsLoaded = remember { mutableStateOf(false) }
 
        LaunchedEffect(Unit) {
            logger.i("WasmMain", "LaunchedEffect started, loading font: $MiSansRegular")
            try {
                val miSansBytes = loadRes(MiSansRegular).toByteArray()
                logger.i("WasmMain", "Font bytes loaded: ${miSansBytes.size}")
                val fontFamily = FontFamily(Font("MiSans", miSansBytes))
                fontFamilyResolver.preload(fontFamily)
                logger.i("WasmMain", "Font preloaded successfully")
            } catch (e: Throwable) {
                logger.e("WasmMain", "Font loading failed", e)
            } finally {
                fontsLoaded.value = true
                logger.i("WasmMain", "fontsLoaded set to true")
            }
        }

        if (fontsLoaded.value) {
            SideEffect {
                logger.i("WasmMain", "hiding HTML loading")
            }
            hideLoading()
            App(platform)
        } else {
            // 在 Compose 内部显示一个临时的白色背景或简单文字，避免白屏
            Box(Modifier.fillMaxSize()) {
                // 暂时保持空白，但在 Compose 树中
            }
        }
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
suspend fun loadRes(url: String): ArrayBuffer {
    return window.fetch(url).await<Response>().arrayBuffer().await()
}

fun ArrayBuffer.toByteArray(): ByteArray {
    val source = Int8Array(this, 0, byteLength)
    return jsInt8ArrayToKotlinByteArray(source)
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
        function hideLoading() {
            const loading = document.getElementById('loading');
            if (loading) loading.style.display = 'none';
            const app = document.getElementById('composeApplication');
            if (app) app.style.display = 'block';
        }
    """
)
external fun hideLoading()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """ (src, size, dstAddr) => {
        const mem8 = new Int8Array(wasmExports.memory.buffer, dstAddr, size);
        mem8.set(src);
    }
"""
)
external fun jsExportInt8ArrayToWasm(src: Int8Array, size: Int, dstAddr: Int)

internal fun jsInt8ArrayToKotlinByteArray(x: Int8Array): ByteArray {
    val size = x.length
    @OptIn(UnsafeWasmMemoryApi::class)
    return withScopedMemoryAllocator { allocator ->
        val memBuffer = allocator.allocate(size)
        val dstAddress = memBuffer.address.toInt()
        jsExportInt8ArrayToWasm(x, size, dstAddress)
        ByteArray(size) { i -> (memBuffer + i).loadByte() }
    }
}
