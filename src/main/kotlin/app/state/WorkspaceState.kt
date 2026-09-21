package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.settings.WorkspacePrefs
import db.ConnectionsRepository
import db.WorkspaceRecord
import i18n.I18n
import i18n.Str

/**
 * 工作区状态：控制台的虚拟分组。
 *
 * - 成员关系（`workspaceId → [consoleId]`）= 该控制台是否显示在该工作区标签条上；
 *   正文/结果/光标只有一份，跨工作区共享（见 spec）。
 * - MRU 为**会话内**内存（重启后按标签顺序重建）；「上次激活的工作区」与
 *   「每个工作区上次激活的控制台」跨重启持久化。
 * - `ensureActive()` 在零工作区时建一个 auto_named 的「默认」工作区。
 */
class WorkspaceState(
    private val repository: ConnectionsRepository,
    /** 读「当前激活工作区」存档；默认走 `<dataDir>/app.properties`，测试注入内存实现。 */
    private val loadActive: () -> String? = { WorkspacePrefs.load() },
    /** 写「当前激活工作区」存档；默认走 `<dataDir>/app.properties`。 */
    private val saveActive: (String?) -> Unit = { WorkspacePrefs.save(it) },
) {

    var workspaces by mutableStateOf<List<WorkspaceRecord>>(emptyList())
        private set

    var activeWorkspaceId by mutableStateOf<String?>(null)
        private set

    /** workspaceId → 有序列 consoleId（标签顺序）。 */
    private val members = mutableStateMapOf<String, List<String>>()

    /** workspaceId → 会话内 MRU（最近在前）。 */
    private val mruByWorkspace = mutableMapOf<String, MutableList<String>>()

    private var loaded = false

    /** 首次访问时从仓库/存档载入（幂等）；Main 启动时显式调用一次，测试也可直接调用。 */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        workspaces = repository.listWorkspaces()
        members.putAll(repository.listWorkspaceConsoleIds())
        val saved = loadActive()
        activeWorkspaceId = saved?.takeIf { id -> workspaces.any { it.id == id } }
            ?: workspaces.firstOrNull()?.id
    }

    fun activeWorkspace(): WorkspaceRecord? {
        ensureLoaded()
        val id = activeWorkspaceId ?: return null
        return workspaces.firstOrNull { it.id == id }
    }

    /** 确保存在一个激活工作区：优先当前/第一个；零工作区才新建「默认」。 */
    fun ensureActive(): WorkspaceRecord {
        activeWorkspace()?.let { return it }
        workspaces.firstOrNull()?.let { rec ->
            setActive(rec.id)
            return rec
        }
        val created = createWorkspace(null)
        setActive(created.id)
        return created
    }

    /** [name] = null → auto_named（渲染时取 `Str.WorkspaceDefaultName`）。 */
    fun createWorkspace(name: String?): WorkspaceRecord {
        ensureLoaded()
        val rec = repository.createWorkspace(
            name = name ?: I18n.t(Str.WorkspaceDefaultName),
            autoNamed = name == null,
        )
        workspaces = workspaces + rec
        members[rec.id] = emptyList()
        return rec
    }

    fun renameWorkspace(id: String, newName: String) {
        ensureLoaded()
        repository.renameWorkspace(id, newName)
        workspaces = workspaces.map {
            if (it.id == id) it.copy(name = newName, autoNamed = false) else it
        }
    }

    /** 删除工作区；返回它是否是删除前的激活工作区。控制台实体不受影响。 */
    fun deleteWorkspace(id: String): Boolean {
        ensureLoaded()
        val wasActive = activeWorkspaceId == id
        repository.deleteWorkspace(id)
        workspaces = workspaces.filterNot { it.id == id }
        members.remove(id)
        mruByWorkspace.remove(id)
        if (wasActive) {
            activeWorkspaceId = null
            saveActive(null)
        }
        return wasActive
    }

    fun setActive(id: String) {
        ensureLoaded()
        activeWorkspaceId = id
        saveActive(id)
    }

    fun memberIds(wsId: String): List<String> {
        ensureLoaded()
        return members[wsId].orEmpty()
    }

    fun contains(wsId: String, consoleId: String): Boolean = memberIds(wsId).contains(consoleId)

    /** 加入成员（幂等）；返回 true = 本次真正新增。 */
    fun addMember(wsId: String, consoleId: String): Boolean {
        ensureLoaded()
        val added = repository.addConsoleToWorkspace(wsId, consoleId)
        if (added) members[wsId] = members[wsId].orEmpty() + consoleId
        return added
    }

    fun removeMember(wsId: String, consoleId: String) {
        ensureLoaded()
        repository.removeConsoleFromWorkspace(wsId, consoleId)
        members[wsId] = members[wsId].orEmpty().filterNot { it == consoleId }
        mruByWorkspace[wsId]?.remove(consoleId)
    }

    /** 控制台被删除 / 连接被删除时：从所有工作区移除其成员关系。 */
    fun removeConsoleEverywhere(consoleId: String) {
        ensureLoaded()
        members.filterValues { it.contains(consoleId) }.keys.toList().forEach { wsId ->
            repository.removeConsoleFromWorkspace(wsId, consoleId)
            members[wsId] = members[wsId].orEmpty().filterNot { it == consoleId }
            mruByWorkspace[wsId]?.remove(consoleId)
        }
    }

    /** 该工作区成员按 MRU 排序（最近在前）；不在 MRU 里的按标签顺序补到末尾。 */
    fun orderedMembers(wsId: String): List<String> {
        val mem = memberIds(wsId)
        val mru = mruByWorkspace[wsId].orEmpty()
        return mru.filter { it in mem } + mem.filter { it !in mru }
    }

    fun touch(wsId: String, consoleId: String) {
        val list = mruByWorkspace.getOrPut(wsId) { mutableListOf() }
        list.remove(consoleId)
        list.add(0, consoleId)
    }

    fun lastActiveConsoleOf(wsId: String): String? {
        ensureLoaded()
        return workspaces.firstOrNull { it.id == wsId }?.lastActiveConsoleId
    }

    fun setLastActive(wsId: String, consoleId: String?) {
        ensureLoaded()
        repository.setWorkspaceLastActiveConsole(wsId, consoleId)
        workspaces = workspaces.map {
            if (it.id == wsId) it.copy(lastActiveConsoleId = consoleId) else it
        }
    }
}
