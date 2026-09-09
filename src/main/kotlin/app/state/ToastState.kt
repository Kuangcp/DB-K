package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 底部轻提示（Toast）：非阻断操作反馈（导出完成 / 控制台操作 / 自动保存失败）。
 * token 递增保证同文案重复触发也会重新计时（UI 侧以 token 作 LaunchedEffect key）。
 */
data class ToastMessage(val text: String, val token: Long)

class ToastState {
    var message by mutableStateOf<ToastMessage?>(null)
        private set

    private var seq = 0L

    fun show(text: String) {
        seq++
        message = ToastMessage(text, seq)
    }

    fun dismiss() {
        message = null
    }
}
