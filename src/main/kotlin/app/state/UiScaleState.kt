package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.settings.UiScalePrefs

/**
 * 全局界面缩放（1.0 = 100%）的单一可观察来源。
 *
 * 主窗口与各 DialogWindow 是不同 composition，各自读取本对象 → 缩放变化时各自重组。
 * 读写经 [set]（内部按 [UiScalePrefs] 夹取）。
 */
object UiScaleState {
    var scale by mutableStateOf(UiScalePrefs.DEFAULT_SCALE)
        private set

    fun set(value: Float) {
        scale = UiScalePrefs.sanitized(value)
    }
}
