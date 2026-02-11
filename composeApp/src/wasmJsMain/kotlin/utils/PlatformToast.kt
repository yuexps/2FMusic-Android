package utils

import kotlinx.browser.window

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""
(message) => {
    const toast = document.createElement('div');
    toast.textContent = message;
    toast.style.position = 'fixed';
    toast.style.bottom = '100px';
    toast.style.left = '50%';
    toast.style.transform = 'translateX(-50%)';
    toast.style.backgroundColor = 'rgba(0, 0, 0, 0.7)';
    toast.style.color = 'white';
    toast.style.padding = '12px 24px';
    toast.style.borderRadius = '30px';
    toast.style.fontSize = '14px';
    toast.style.zIndex = '9999';
    toast.style.transition = 'opacity 0.3s';
    toast.style.pointerEvents = 'none';
    
    document.body.appendChild(toast);
    
    setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => document.body.removeChild(toast), 300);
    }, 2500);
}
""")
private external fun jsToast(message: String)

class WasmToast : Toast {
    override fun show(message: String) {
        jsToast(message)
    }
}
