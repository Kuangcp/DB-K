package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import db.ConnectionProfile
import db.FolderRow

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

/** 危险操作确认弹窗请求（删除类）。 */
sealed interface ConfirmRequest {
    data class DeleteFolder(val id: String, val name: String, val movingConnections: Int) : ConfirmRequest
    data class DeleteConnection(val id: String, val name: String) : ConfirmRequest
    data class DeleteConsole(val id: String, val name: String, val connectionName: String) : ConfirmRequest
}

class DialogState {
    var connectionEditor by mutableStateOf<ConnectionEditorRequest?>(null)
    var folderDialog by mutableStateOf<FolderDialogRequest?>(null)
    var consoleRename by mutableStateOf<ConsoleRenameRequest?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
}
