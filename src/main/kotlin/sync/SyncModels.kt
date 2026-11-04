package sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 同步仓库里的数据模型（与本地 SQLite/设置文件解耦）。
 *
 * 约定：
 * - 所有 id 用本机 24 位 UUID，跨设备稳定；文件名即 id。
 * - `encodeDefaults = false` + `prettyPrint = true` → diff 稳定、体积小。
 * - 连接条目**含明文密码**，由 [SyncEngine] 整条加密后才落盘，绝不直接写 JSON。
 */
internal val syncJson: Json = Json {
    prettyPrint = true
    encodeDefaults = false
    ignoreUnknownKeys = true
}

@Serializable
data class FolderSync(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class ConnectionSync(
    val formatVersion: Int = 1,
    val id: String,
    val name: String,
    val dbType: String,
    val folderId: String? = null,
    val host: String = "",
    val port: Int = 0,
    val database: String = "",
    val user: String? = null,
    /** 明文密码；整条条目由 [SecretBox] 加密后写入 `.enc`。 */
    val password: String? = null,
    val extraParams: String = "",
    val color: String? = null,
    val sortOrder: Int = 0,
    val keySeparator: String = ":",
    val displayTz: String = "",
    val queryTimeoutSeconds: Int = 20,
    val updatedAt: Long = 0,
)

@Serializable
data class WorkspaceSync(
    val id: String,
    val name: String,
    val autoNamed: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val lastActiveConsoleId: String? = null,
    val consoleIds: List<String> = emptyList(),
)

@Serializable
data class ConsoleMetaSync(
    val id: String,
    val connectionId: String,
    val name: String,
    val target: String = "",
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class SyncMeta(val formatVersion: Int = 1, val app: String)

/** `settings/keymap.json`：只存与默认不同的命令（空串 = 显式解绑）。 */
@Serializable
data class KeymapSync(val overrides: Map<String, String> = emptyMap())

/** 控制台 = 元数据 + 正文（正文直接写 `.sql` 文本，不进 JSON）。 */
data class ConsoleSync(val meta: ConsoleMetaSync, val body: String)

data class SyncSnapshot(
    val folders: List<FolderSync> = emptyList(),
    val connections: List<ConnectionSync> = emptyList(),
    val workspaces: List<WorkspaceSync> = emptyList(),
    val consoles: List<ConsoleSync> = emptyList(),
    /** 仓库 settings 目录下文件的原始文本；null = 本地没有该文件（不写入仓库）。 */
    val liveTemplatesJson: String? = null,
    val keymap: KeymapSync? = null,
    val themesJson: String? = null,
) {
    /** 空库守卫只看这两类（文件夹/工作区单独存在不算「有内容」）。 */
    fun isEmpty(): Boolean = connections.isEmpty() && consoles.isEmpty()
}

/** 合并结果里「本地有、仓库没有」的 id 集合（导入时据此删除本地实体）。 */
data class RemovedIds(
    val folders: Set<String> = emptySet(),
    val connections: Set<String> = emptySet(),
    val workspaces: Set<String> = emptySet(),
    val consoles: Set<String> = emptySet(),
)

data class SyncStats(val written: Int = 0, val deleted: Int = 0, val unchanged: Int = 0)

/** 拉取结果：成功 / 需要用户取舍冲突 / 失败。 */
sealed interface PullOutcome {
    data class Synced(val stats: SyncStats = SyncStats(), val skippedConsoles: Int = 0) : PullOutcome
    data class NeedResolution(val conflicts: List<ConflictItem>) : PullOutcome
    data class Failed(val failure: SyncFailure, val detail: String? = null) : PullOutcome
}

/** 读取合并结果后的导入计划：要写入的快照 + 要删除的本地 id + 被跳过的孤儿控制台数。 */
data class SyncPlan(
    val snapshot: SyncSnapshot,
    val removed: RemovedIds,
    val skippedConsoles: Int = 0,
    /** 无法解析的非密文文件（相对仓库路径）；不阻断整次导入，只报告。 */
    val corruptFiles: List<String> = emptyList(),
)

/** 冲突项摘要（UI 渲染用；**永不含密码**）。 */
sealed interface ConflictPreview {
    data class Connection(
        val name: String,
        val dbType: String,
        val host: String,
        val port: Int,
        val database: String,
        val user: String?,
    ) : ConflictPreview

    data class Sql(val lineCount: Int, val head: List<String>) : ConflictPreview
    data class Named(val label: String) : ConflictPreview
    data class Unknown(val sizeBytes: Int) : ConflictPreview
}

data class ConflictItem(
    /** 相对仓库工作树的路径（git 用）。 */
    val path: String,
    val kind: SyncEntityKind,
    val id: String?,
    val local: ConflictPreview,
    val remote: ConflictPreview,
    val localBytes: ByteArray,
    val remoteBytes: ByteArray,
)

enum class SyncEntityKind { FOLDER, CONNECTION, WORKSPACE, CONSOLE_META, CONSOLE_BODY, SETTINGS, UNKNOWN }

enum class ConflictChoice { LOCAL, REMOTE }

/** 同步失败的机器可读分类（文案在 UI 层映射，`sync/` 不依赖 i18n）。 */
enum class SyncFailure {
    NOT_CONFIGURED,
    GIT_MISSING,
    WRONG_PASSPHRASE,
    PUSH_REJECTED,
    GIT_FAILED,
    IO_FAILED,
    /** 本地没有任何同步内容、仓库里却有连接 → 拒绝推送（避免把远端清空）。 */
    EMPTY_LOCAL_GUARD,
}

data class SyncOutcome(
    val ok: Boolean,
    val failure: SyncFailure? = null,
    val detail: String? = null,
    val stats: SyncStats = SyncStats(),
    val conflicts: List<ConflictItem> = emptyList(),
    val skippedConsoles: Int = 0,
)
