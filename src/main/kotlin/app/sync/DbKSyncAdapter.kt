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

    override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int {
        val profiles = snapshot.connections.mapNotNull { it.toProfileOrNull() }
        val connectionIds = profiles.map { it.id }.toSet()

        val records = snapshot.consoles
            .filter { it.meta.connectionId in connectionIds }
            .map { console ->
                db.ConsoleRecord(
                    id = console.meta.id,
                    connectionId = console.meta.connectionId,
                    name = console.meta.name,
                    filePath = dataDir.resolve("consoles/${console.meta.id}.sql").toString(),
                    sortOrder = console.meta.sortOrder,
                    updatedAt = console.meta.updatedAt,
                    target = console.meta.target,
                )
            }
        val recordIds = records.map { it.id }.toSet()

        repo.importSyncedState(
            db.SyncImportRequest(
                folders = snapshot.folders.map { db.FolderRow(it.id, it.name, it.parentId, it.sortOrder) },
                connections = profiles,
                connectionUpdatedAt = snapshot.connections.associate { it.id to it.updatedAt },
                workspaces = snapshot.workspaces.map {
                    db.WorkspaceRecord(
                        id = it.id,
                        name = it.name,
                        autoNamed = it.autoNamed,
                        sortOrder = it.sortOrder,
                        createdAt = it.createdAt,
                        lastActiveConsoleId = it.lastActiveConsoleId,
                    )
                },
                workspaceConsoles = snapshot.workspaces.associate { it.id to it.consoleIds },
                consoles = records,
                removedFolderIds = removed.folders,
                removedConnectionIds = removed.connections,
                removedWorkspaceIds = removed.workspaces,
                removedConsoleIds = removed.consoles,
            ),
        )

        // 事务提交后写正文与设置文件（内容相同则不写，避免无意义的 mtime 变化）
        for (console in snapshot.consoles) {
            if (console.meta.id !in recordIds) continue
            val path = dirFile("consoles/${console.meta.id}.sql")
            if (runCatching { Files.readString(path) }.getOrNull() == console.body) continue
            db.ConsoleFiles.write(path, console.body)
        }
        snapshot.liveTemplatesJson?.let { writeText("live-templates.json", it) }
            ?: deleteFile("live-templates.json")
        snapshot.themesJson?.let { writeText("themes.json", it) }
            ?: deleteFile("themes.json")
        snapshot.keymap?.let { writeKeymap(it) } ?: deleteFile("keymap.properties")

        return 0
    }

    // ---------- 设置文件 ----------

    private fun writeKeymap(keymap: KeymapSync) {
        val props = Properties()
        val overrides = KeymapPrefs.encodeOverrides(KeymapPrefs.decodeOverrides(keymap.overrides))
        overrides.forEach { (id, value) -> props.setProperty(id, value) }
        if (props.isEmpty) {
            deleteFile("keymap.properties")
            return
        }
        val target = dirFile("keymap.properties")
        val tmp = runCatching { Files.createTempFile(target.parent, ".keymap", ".tmp") }.getOrNull() ?: target
        runCatching {
            Files.newOutputStream(tmp).use { props.store(it, "db-k keymap overrides (empty value = unbound)") }
            if (tmp != target) Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { e ->
            org.tinylog.Logger.warn(e, "sync: failed to write keymap.properties")
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun writeText(name: String, text: String) {
        val target = dirFile(name)
        Files.createDirectories(target.parent)
        val tmp = runCatching { Files.createTempFile(target.parent, ".$name", ".tmp") }.getOrNull()
        if (tmp == null) {
            Files.writeString(target, text)
            return
        }
        runCatching {
            Files.writeString(tmp, text)
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { e ->
            org.tinylog.Logger.warn(e, "sync: atomic write failed for {}", name)
            runCatching { Files.deleteIfExists(tmp) }
            Files.writeString(target, text)
        }
    }

    private fun deleteFile(name: String) {
        runCatching { Files.deleteIfExists(dirFile(name)) }
    }

    private fun dirFile(name: String): Path = dataDir.resolve(name)

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

    // ---------- 同步模型 → 领域模型 ----------

    /** 未知数据库类型（仓库来自更新版本）→ 跳过该连接并告警，不影响其余实体。 */
    private fun ConnectionSync.toProfileOrNull(): ConnectionProfile? {
        val type = runCatching { db.DbType.valueOf(dbType) }.getOrElse {
            org.tinylog.Logger.warn("sync: skipping connection {} with unknown dbType {}", id, dbType)
            return null
        }
        return ConnectionProfile(
            id = id,
            name = name,
            folderId = folderId,
            dbType = type,
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            extraParams = extraParams,
            color = color,
            sortOrder = sortOrder,
            keySeparator = keySeparator,
            displayZone = DisplayZone.fromDb(displayTz),
            queryTimeoutSeconds = queryTimeoutSeconds,
        )
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
