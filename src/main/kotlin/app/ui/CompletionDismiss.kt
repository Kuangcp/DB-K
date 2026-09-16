package app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「鼠标按在补全弹层外 → 收起弹层」的窗口级信号。
 *
 * 补全弹层是编辑器内的 overlay（不是独立 Popup/Window），自身收不到别处（左侧树、结果区、
 * 工具栏、其它控制台标签）的鼠标按下。所以由 [app.core.Main] 的根布局旁路观察所有按下：
 * 弹层在 **Initial pass** 标记「这一下命中弹层」，根布局在 **Final pass** 对没被标记的按下
 * 发一次 dismiss。不必做坐标换算（弹层位置随光标/滚动变化，标记法更稳），也不消费事件。
 *
 * 编辑器内部「点一下移动光标」另有 onValueChange 通道（typed=false 即收起），两条路互补。
 */
@Stable
class CompletionDismissSignal {
    /** 每次「弹层外按下」自增；编辑器用 LaunchedEffect 观察它收起弹层。 */
    var tick by mutableStateOf(0)
        private set

    private var pressInside = false

    /** 弹层按下时标记（Initial pass）。 */
    fun markPressInside() {
        pressInside = true
    }

    /** 根布局在按下事件的 Final pass 调用；本次按下不在弹层内则发 dismiss。 */
    fun onPress() {
        val inside = pressInside
        pressInside = false
        if (!inside) tick++
    }
}

val LocalCompletionDismiss = staticCompositionLocalOf { CompletionDismissSignal() }
