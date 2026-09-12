package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.settings.TreeExpandPrefs
import db.ConnectionProfile
import db.ConnectionsRepository
import db.FolderRow
import db.ProfileImportSummary
import db.ProfileTransfer

/**
 * 左侧树状态：本地元数据（文件夹 + 连接档案）内存镜像 + 展开/选中 + 增删改动作。
 * 模式同 api-x TreeState：持 repository，UI 只经其动作方法变更。
 */
class TreeState(
    private val repository: ConnectionsRepository,
    expandLoaded: Set<String>,
) {
    var folders by mutableStateOf(repository.listFolders())
        private set
    var connections by mutableStateOf(repository.listConnections())
        private set
    var expandedFolderIds by mutableStateOf(expandLoaded)
    /** 连接展开态（运行时加载后才有意义，重启后不保留：连接默认不自动重连）。 */
    var expandedConnectionIds by mutableStateOf<Set<String>>(emptySet())
    /** schema 展开态（key 形如 s:<profileId>:<catalog>\u0000<schema>）。 */
    var expandedSchemaKeys by mutableStateOf<Set<String>>(emptySet())
    /** 对象组展开态（key 形如 s:<profileId>:…:g:<GROUP>；随 session，不持久化）。 */
    var expandedGroupKeys by mutableStateOf<Set<String>>(emptySet())
    var selectedRowKey by mutableStateOf<String?>(null)

    fun refresh() {
        folders = repository.listFolders()
        connections = repository.listConnections()
    }

    fun select(key: String?) {
        selectedRowKey = key
    }

    fun selectedProfile(): ConnectionProfile? {
        val key = selectedRowKey ?: return null
        // 行 key 形如 c:<id> / s:<id>:… / p:<id>:… ；对象行以 s:<id>:… 开头
        val prefix = key.substringBefore(':')
        if (prefix !in setOf("c", "s", "p")) return null
        val id = key.split(':').getOrNull(1) ?: return null
        return connections.firstOrNull { it.id == id }
    }

    // ---------- expand ----------

    fun toggleFolder(id: String) {
        expandedFolderIds = if (id in expandedFolderIds) expandedFolderIds - id else expandedFolderIds + id
        TreeExpandPrefs.save(expandedFolderIds)
    }

    fun expandConnection(id: String) {
        expandedConnectionIds = expandedConnectionIds + id
    }

    fun collapseConnection(id: String) {
        expandedConnectionIds = expandedConnectionIds - id
    }

    fun expandSchema(rowKey: String) {
        expandedSchemaKeys = expandedSchemaKeys + rowKey
    }

    fun collapseSchema(rowKey: String) {
        expandedSchemaKeys = expandedSchemaKeys - rowKey
        // 收起 schema 时连带收起其下展开的对象组（组 key 以该 schema 行 key 开头）
        expandedGroupKeys = expandedGroupKeys.filterNot { it.startsWith("$rowKey:g:") }.toSet()
    }

    fun expandGroup(groupKey: String) {
        expandedGroupKeys = expandedGroupKeys + groupKey
    }

    fun collapseGroup(groupKey: String) {
        expandedGroupKeys = expandedGroupKeys - groupKey
    }

    /** 连接档案增删后清掉对应展开痕迹。 */
    fun forgetConnectionExpands(id: String) {
        expandedConnectionIds = expandedConnectionIds - id
        expandedSchemaKeys = expandedSchemaKeys.filterNot { it.startsWith("s:$id:") }.toSet()
        expandedGroupKeys = expandedGroupKeys.filterNot { it.startsWith("s:$id:") }.toSet()
    }

    // ---------- folder actions ----------

    fun addFolder(name: String) {
        val id = repository.createFolder(name)
        refresh()
        expandedFolderIds = expandedFolderIds + id
        TreeExpandPrefs.save(expandedFolderIds)
        selectedRowKey = "f:$id"
    }

    fun renameFolder(id: String, name: String) {
        repository.renameFolder(id, name)
        refresh()
    }

    /** 删除文件夹（其下连接移至根）。调用前需用户确认。 */
    fun deleteFolder(id: String) {
        repository.deleteFolder(id)
        refresh()
        if (selectedRowKey == "f:$id") selectedRowKey = null
    }

    // ---------- connection actions ----------

    fun createConnection(p: ConnectionProfile) {
        val id = repository.createConnection(p)
        refresh()
        p.folderId?.let { expandedFolderIds = expandedFolderIds + it }
        TreeExpandPrefs.save(expandedFolderIds)
        selectedRowKey = "c:$id"
    }

    fun updateConnection(p: ConnectionProfile) {
        repository.updateConnection(p)
        refresh()
    }

    /** P5：导入连接档案包（id 冲突自动重映射，不覆盖现有），刷新树并返回统计。 */
    fun importProfiles(bundle: ProfileTransfer.ProfileBundle): ProfileImportSummary {
        val summary = repository.importProfiles(bundle.folders, bundle.connections)
        refresh()
        return summary
    }

    /** 删除连接档案。调用前需用户确认。 */
    fun deleteConnection(id: String) {
        repository.deleteConnection(id)
        refresh()
        forgetConnectionExpands(id)
        if (selectedRowKey == "c:$id") selectedRowKey = null
    }

    fun folderById(id: String): FolderRow? = folders.firstOrNull { it.id == id }

    /** 删除确认文案需要：该文件夹下的连接数（删除时这些连接会移到根）。 */
    fun countConnectionsInFolder(id: String): Int = repository.countConnectionsInFolder(id)
}
