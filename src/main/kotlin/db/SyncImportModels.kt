package db

/**
 * 云同步导入请求：全部用本地领域模型表达，`db` 层**不依赖** `sync` 包。
 * 由 `app.sync.DbKSyncAdapter` 把同步快照翻译成本类型。
 *
 * 语义：`*` 列表按 id upsert（保留设备本地状态列：caret/closed/external/file_path）；
 * `removed*Ids` 为要删除的本地实体（含 `.sql` 文件清理）。
 */
data class SyncImportRequest(
    val folders: List<FolderRow> = emptyList(),
    val connections: List<ConnectionProfile> = emptyList(),
    /** 连接的内容变更时间（`connections.updated_at`）；缺省用当前时间。 */
    val connectionUpdatedAt: Map<String, Long> = emptyMap(),
    val workspaces: List<WorkspaceRecord> = emptyList(),
    /** 工作区 → 有序列 consoleId（每次导入整体重写该工作区成员）。 */
    val workspaceConsoles: Map<String, List<String>> = emptyMap(),
    val consoles: List<ConsoleRecord> = emptyList(),
    val removedFolderIds: Set<String> = emptySet(),
    val removedConnectionIds: Set<String> = emptySet(),
    val removedWorkspaceIds: Set<String> = emptySet(),
    val removedConsoleIds: Set<String> = emptySet(),
)
