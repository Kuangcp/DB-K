package sync

/**
 * 应用侧适配器：把本地存储映射成与 git 无关的 [SyncSnapshot]，或把合并结果写回本地。
 *
 * 契约：
 * - [exportSnapshot] 只读、无副作用；必须已按同步语义过滤（不含设备本地状态）。
 * - [applySnapshot] 必须在单事务内完成 SQLite 侧的增删改（失败整体回滚），
 *   返回被跳过的「孤儿」实体数（当前实现恒为 0，保留给将来扩展）。
 */
interface SyncAdapter {

    fun exportSnapshot(): SyncSnapshot

    fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int
}
