package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import db.ConnectionProfile
import db.FolderRow
import engine.model.SchemaMeta

/** 连接档案编辑弹窗请求。 */
sealed interface ConnectionEditorRequest {
    data class Create(val folderId: String?) : ConnectionEditorRequest
    data class Edit(val profile: ConnectionProfile) : ConnectionEditorRequest
}

/** 文件夹新建/重命名弹窗请求。 */
sealed interface FolderDialogRequest {
    data class Create(val parentId: String?) : FolderDialogRequest
    data class Rename(val folder: FolderRow) : FolderDialogRequest
}

/** 控制台重命名弹窗请求。 */
data class ConsoleRenameRequest(val consoleId: String, val currentName: String)

/**
 * 结果提交前预览（P7）：展示将要执行的参数化 UPDATE（只读、不执行），确认后 [onConfirm]。
 * [statements] 由 `RowUpdater.renderUpdateSql` 渲染，仅供人工核对定位条件与目标值。
 */
data class CommitPreviewRequest(
    val statements: List<String>,
    val onConfirm: () -> Unit,
)

/** 对象定义（DDL）浮窗请求（Ctrl+Q / 树右键）。 */
data class TableDdlRequest(
    val profile: ConnectionProfile,
    val schema: SchemaMeta?,
    val objectName: String,
    /** 中文名词（表/视图/物化视图），用于标题。 */
    val noun: String,
)

/** 危险操作确认弹窗请求（删除类）。 */
sealed interface ConfirmRequest {
    data class DeleteFolder(val id: String, val name: String, val movingConnections: Int) : ConfirmRequest
    data class DeleteConnection(val id: String, val name: String) : ConfirmRequest
    data class DeleteConsole(val id: String, val name: String, val connectionName: String) : ConfirmRequest

    /** 有未提交的结果修改时，刷新/重跑等动作会丢弃它们 → 确认；[onDiscard] 为确认后执行的动作。 */
    class DiscardResultEdits(val count: Int, val actionLabel: String, val onDiscard: () -> Unit) : ConfirmRequest

    /** 导出含明文密码前的风险确认；[onConfirm] 为确认后的导出动作。 */
    class ExportWithPasswords(val onConfirm: () -> Unit) : ConfirmRequest

    /** 危险命令（Redis FLUSHALL 等）执行前的二次确认；[onDecision] 回传用户选择。 */
    class DangerConfirm(val command: String, val onDecision: (Boolean) -> Unit) : ConfirmRequest
}

class DialogState {
    var connectionEditor by mutableStateOf<ConnectionEditorRequest?>(null)
    var folderDialog by mutableStateOf<FolderDialogRequest?>(null)
    var consoleRename by mutableStateOf<ConsoleRenameRequest?>(null)
    var tableDdl by mutableStateOf<TableDdlRequest?>(null)
    var commitPreview by mutableStateOf<CommitPreviewRequest?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    /** 设置窗口显隐。 */
    var showSettings by mutableStateOf(false)
}
