package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sync.SyncSettings
import sync.ConflictItem
import sync.SyncOutcome

/**
 * 云同步的可观察状态（UI 只读这里，不直接持引擎/服务内部对象）。
 *
 * - [running]：动作进行中（按钮禁用 + 进度文案）。
 * - [configured]：远端 URL 已填写（决定按钮可用性）。
 * - [passphraseSet]：本机已记住口令。
 * - [gitAvailable]：`git --version` 探测结果。
 * - [lastResult]：最近一次动作结果（成功摘要 / 失败分类）。
 * - [conflicts]：待用户取舍的冲突；非空时主窗口显示冲突弹窗。
 */
class SyncState {
    var running by mutableStateOf(false)
    var configured by mutableStateOf(false)
    var passphraseSet by mutableStateOf(false)
    var gitAvailable by mutableStateOf(true)
    var settings by mutableStateOf(SyncSettings())
    var lastResult by mutableStateOf<SyncOutcome?>(null)
    var conflicts by mutableStateOf<List<ConflictItem>>(emptyList())
}
