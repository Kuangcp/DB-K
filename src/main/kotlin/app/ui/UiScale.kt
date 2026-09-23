package app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import app.state.UiScaleState

/** 当前子树已经叠加过的缩放倍率（默认 1 = 未缩放）。用于让 [ProvideUiScale] 幂等。 */
val LocalUiScale = staticCompositionLocalOf { 1f }

/**
 * 幂等密度换算：当前密度已叠加 [applied] 倍缩放，先还原系统密度再叠加 [target]。
 * [applied] 为 0 属异常输入，按未缩放处理，避免除零得到 Infinity。
 */
internal fun scaledDensity(currentDensity: Float, applied: Float, target: Float): Float =
    if (applied == 0f) currentDensity * target else currentDensity / applied * target

/**
 * 在当前 composition 根提供缩放后的 LocalDensity。每个独立窗口都要各包一层；
 * **幂等**：DialogWindow 可能已从父 composition 继承放大后的密度，重复包裹不会平方。
 */
@Composable
fun ProvideUiScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val applied = LocalUiScale.current
    val scale = UiScaleState.scale
    CompositionLocalProvider(
        LocalDensity provides Density(scaledDensity(current.density, applied, scale), current.fontScale),
        LocalUiScale provides scale,
        content = content,
    )
}

/** 固定尺寸窗口/对话框的 Dp 尺寸同比放大，避免缩放后内容溢出固定窗口。 */
@Composable
fun scaledSize(width: Dp, height: Dp): DpSize =
    DpSize(width * UiScaleState.scale, height * UiScaleState.scale)

@Composable
fun scaledSize(size: DpSize): DpSize = scaledSize(size.width, size.height)

/**
 * 与 [DialogWindow]（`onCloseRequest/state/title/resizable/onPreviewKeyEvent` 重载）同参，
 * 但在内容层套一层 [ProvideUiScale]。调用方把 `rememberDialogState` 的尺寸用 [scaledSize] 放大。
 */
@Composable
fun ScaledDialogWindow(
    onCloseRequest: () -> Unit,
    state: DialogState,
    title: String,
    resizable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DialogWindowScope.() -> Unit,
) {
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        title = title,
        resizable = resizable,
        onPreviewKeyEvent = onPreviewKeyEvent,
    ) {
        ProvideUiScale { content() }
    }
}
