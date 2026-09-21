package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.core.export.ExportFormat
import app.core.export.ExportOptions
import db.ConnectionProfile
import db.ConsoleRecord
import db.FolderRow
import db.WorkspaceRecord
import engine.model.SchemaMeta
import i18n.Str

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

/** 工作区新建/重命名弹窗请求。 */
sealed interface WorkspaceDialogRequest {
    data object Create : WorkspaceDialogRequest
    data class Rename(val workspace: WorkspaceRecord) : WorkspaceDialogRequest
}

/**
 * 结果提交前预览（P7）：展示将要执行的参数化 UPDATE（只读、不执行），确认后 [onConfirm]。
 * [statements] 由 `RowUpdater.renderWriteSql` 渲染（UPDATE / INSERT / DELETE），仅供人工核对定位条件与目标值。
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
    /** 对象名词 key（表/视图/物化视图/对象…），用于标题；由 UI 层翻译。 */
    val noun: Str,
)

/** 危险操作确认弹窗请求（删除类）。 */
sealed interface ConfirmRequest {
    data class DeleteFolder(val id: String, val name: String, val movingConnections: Int, val childFolders: Int = 0) : ConfirmRequest
    data class DeleteConnection(val id: String, val name: String) : ConfirmRequest
    data class DeleteConsole(val id: String, val name: String, val connectionName: String) : ConfirmRequest

    /** 删除工作区（仅解除成员关系，控制台与 .sql 保留）。 */
    data class DeleteWorkspace(val id: String, val name: String) : ConfirmRequest

    /** 有未提交的结果修改时，刷新/重跑等动作会丢弃它们 → 确认；[onDiscard] 为确认后执行的动作。 */
    class DiscardResultEdits(val count: Int, val action: Str, val onDiscard: () -> Unit) : ConfirmRequest

    /** 批量标记删除结果行（已有多行待删时）→ 二次确认；[onConfirm] 为确认后执行的标记动作。 */
    class DeleteRows(val count: Int, val onConfirm: () -> Unit) : ConfirmRequest

    /** 危险命令（Redis FLUSHALL 等）执行前的二次确认；[onDecision] 回传用户选择。 */
    class DangerConfirm(val command: String, val onDecision: (Boolean) -> Unit) : ConfirmRequest
}

/**
 * 导出加密 / 导入解密的口令输入弹窗。
 * [onSubmit] 返回 null = 成功（弹窗关闭），非空 = 错误提示（保持打开）。
 */
data class PassphraseRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    /** 是否要求二次输入确认（导出时防打错；导入时只需一次）。 */
    val requireConfirmation: Boolean = false,
    val onSubmit: (String) -> String?,
)

/**
 * 导入同名冲突解决弹窗：[choices] 为「导入连接 id → 是否导入为新档案」（false = 跳过）。
 */
data class ImportConflictRequest(
    val conflicts: List<ConnectionProfile>,
    val onSubmit: (Map<String, Boolean>) -> Unit,
)

/**
 * 结果导出弹窗请求（N8）：选格式与参数，确认后由 Main 打开文件对话框并写出。
 * [onSubmit] 参数：格式、参数、是否全量流式（重跑 SQL，仅结果被截断时可选）。
 */
data class ExportRequest(
    val rowCount: Int,
    val truncated: Boolean,
    val defaultTableName: String,
    /** 结果中存在因超限被截断的单元格：导出必须重跑 SQL 取完整值（否则会写出截断标记）。 */
    val cellsTruncated: Boolean = false,
    val onSubmit: (ExportFormat, ExportOptions, Boolean) -> Unit,
)

/** 批量打开外部文件时，为未关联数据源的文件选择数据源。 */
data class PickProfileRequest(
    val names: List<String>,
    val profiles: List<ConnectionProfile>,
    val defaultProfileId: String?,
    val onSubmit: (String) -> Unit,
)

/** 退出时外部文件「已丢失且脏」的处理选择。 */
data class ExternalMissingExitRequest(
    val consoles: List<ConsoleRecord>,
    val onRebuildAll: () -> Unit,
    val onSaveAsEach: () -> Unit,
    val onDiscard: () -> Unit,
    val onCancel: () -> Unit,
)

class DialogState {
    var connectionEditor by mutableStateOf<ConnectionEditorRequest?>(null)
    var folderDialog by mutableStateOf<FolderDialogRequest?>(null)
    var consoleRename by mutableStateOf<ConsoleRenameRequest?>(null)
    var workspaceDialog by mutableStateOf<WorkspaceDialogRequest?>(null)
    var tableDdl by mutableStateOf<TableDdlRequest?>(null)
    var commitPreview by mutableStateOf<CommitPreviewRequest?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    /** 导出加密 / 导入解密的口令弹窗。 */
    var passphrase by mutableStateOf<PassphraseRequest?>(null)
    /** 导入同名冲突解决弹窗。 */
    var importConflicts by mutableStateOf<ImportConflictRequest?>(null)
    /** 设置窗口显隐。 */
    var showSettings by mutableStateOf(false)
    /** 主题管理对话框显隐。 */
    var showThemeDialog by mutableStateOf(false)
    /** 结果导出弹窗（N8）。 */
    var export by mutableStateOf<ExportRequest?>(null)

    /** 外部文件数据源选择框。 */
    var pickProfile by mutableStateOf<PickProfileRequest?>(null)

    /** 退出时外部文件缺失处理框。 */
    var externalMissingExit by mutableStateOf<ExternalMissingExitRequest?>(null)
}
