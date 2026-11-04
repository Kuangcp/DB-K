package app.sync

import app.settings.KeymapPrefs
import db.ConnectionProfile
import db.ConnectionsRepository
import db.DisplayZone
import sync.ConsoleMetaSync
import sync.ConsoleSync
import sync.ConnectionSync
import sync.FolderSync
import sync.KeymapSync
import sync.RemovedIds
import sync.SyncAdapter
import sync.SyncSnapshot
import sync.WorkspaceSync
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * db-k 的同步适配器：`app.db`（SQLite）+ `<dataDir>` 文件 ⇄ [SyncSnapshot]。
 *
 * 过滤规则（spec §10）：
 * - `external = true` 的控制台不参与同步（路径是设备本地的）；
 * - 光标 / 标签关闭状态 / 窗口几何 / 明暗模式 / 字号缩放 / Redis 视图偏好等**不导出**；
 * - 连接密码以**明文**交给同步引擎（由引擎整条加密），本地库仍存机器本地密文。
 */
class DbKSyncAdapter(
    private val repo: ConnectionsRepository,
    private val dataDir: Path,
) : SyncAdapter {

    override fun exportSnapshot(): SyncSnapshot {
        val updatedAt = repo.connectionUpdatedAt()
        val memberships = repo.listWorkspaceConsoleIds()

        return SyncSnapshot(
            folders = repo.listFolders().map { folder ->
                FolderSync(
                    id = folder.id,
                    name = folder.name,
                    parentId = folder.parentId,
                    sortOrder = folder.sortOrder,
                )
            },
            connections = repo.listConnections().map { it.toSync(updatedAt[it.id] ?: 0) },
            workspaces = repo.listWorkspaces().map { workspace ->
                WorkspaceSync(
                    id = workspace.id,
                    name = workspace.name,
                    autoNamed = workspace.autoNamed,
                    sortOrder = workspace.sortOrder,
                    createdAt = workspace.createdAt,
                    lastActiveConsoleId = workspace.lastActiveConsoleId,
                    consoleIds = memberships[workspace.id].orEmpty(),
                )
            },
            consoles = repo.listAllConsoles()
                .filterNot { it.external }
                .map { record ->
                    ConsoleSync(
                        meta = ConsoleMetaSync(
                            id = record.id,
                            connectionId = record.connectionId,
                            name = record.name,
                            target = record.target,
                            sortOrder = record.sortOrder,
                            updatedAt = record.updatedAt,
                        ),
                        body = repo.readConsoleContent(record.id),
                    )
                },
            liveTemplatesJson = readTextOrNull("live-templates.json"),
            keymap = readKeymap(),
            themesJson = readTextOrNull("themes.json"),
        )
    }

    override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int =
        throw NotImplementedError("applySnapshot is implemented in Task 11")

    // ---------- 设置文件 ----------

    private fun readTextOrNull(name: String): String? {
        val file = dataDir.resolve(name)
        if (!Files.isRegularFile(file)) return null
        return runCatching { Files.readString(file) }.getOrNull()
    }

    private fun readKeymap(): KeymapSync {
        val file = dataDir.resolve("keymap.properties")
        if (!Files.isRegularFile(file)) return KeymapSync()
        val props = runCatching { Properties().apply { Files.newInputStream(file).use { load(it) } } }
            .getOrElse { return KeymapSync() }
        val raw = buildMap {
            for ((key, value) in props) {
                val id = key as? String ?: continue
                put(id, value as? String ?: continue)
            }
        }
        // 解码再编码：丢弃未知命令/无法解析的键位，保证仓库里只有有效覆盖项。
        return KeymapSync(KeymapPrefs.encodeOverrides(KeymapPrefs.decodeOverrides(raw)))
    }

    // ---------- 领域模型 → 同步模型 ----------

    private fun ConnectionProfile.toSync(updatedAt: Long) = ConnectionSync(
        id = id,
        name = name,
        dbType = dbType.name,
        folderId = folderId,
        host = host,
        port = port,
        database = database,
        user = user,
        password = password,
        extraParams = extraParams,
        color = color,
        sortOrder = sortOrder,
        keySeparator = keySeparator,
        displayTz = DisplayZone.toDb(displayZone),
        queryTimeoutSeconds = queryTimeoutSeconds,
        updatedAt = updatedAt,
    )
}
