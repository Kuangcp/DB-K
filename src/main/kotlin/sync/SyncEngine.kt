package sync

import org.tinylog.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey

/**
 * 同步编排：导出本地快照到 git 工作树 / 读取合并结果 / 提交 / 推送 / 拉取 / 冲突取舍。
 *
 * 职责边界：本类不认识 db-k 的 SQLite 或设置文件（只通过 [SyncAdapter] 拿快照），
 * 也不产出用户文案（错误用 [SyncFailure] 表达）。
 */
class SyncEngine(
    private val app: String,
    private val adapter: SyncAdapter,
    private val git: GitRepo,
    private val workTree: Path,
) {

    /** 仓库内本应用的命名空间目录（`<workTree>/<app>/`）。 */
    fun root(): Path = SyncPaths.appRoot(workTree, app)

    // ---------- 仓库级密钥材料 ----------

    /** 读取仓库盐；不存在（首台设备）则生成并落盘。 */
    fun loadOrCreateSalt(): ByteArray {
        val file = SyncPaths.saltFile(root())
        if (Files.isRegularFile(file)) return Files.readAllBytes(file)
        val salt = SecretBox.newSalt()
        writeBytes(file, salt)
        return salt
    }

    /**
     * 校验口令：仓库已有 `secret.check` 时解密比对，返回是否匹配；
     * 没有（首台设备）则写入。**只读判断，不改动任何实体文件。**
     */
    fun loadOrCreateCheck(key: SecretKey): Boolean {
        val file = SyncPaths.checkFile(root())
        if (!Files.isRegularFile(file)) {
            writeText(file, SecretBox.encrypt(key, SecretBox.CHECK_PLAINTEXT.toByteArray()))
            return true
        }
        val stored = runCatching { Files.readString(file) }.getOrNull() ?: return false
        return runCatching {
            String(SecretBox.decrypt(key, stored)) == SecretBox.CHECK_PLAINTEXT
        }.getOrDefault(false)
    }

    /** 仓库里是否已经有密钥材料（用于区分「首台设备的空仓库」）。 */
    fun hasSecrets(): Boolean = Files.isRegularFile(SyncPaths.checkFile(root()))

    // ---------- 导出 ----------

    /**
     * 把本地快照写到工作树。返回写入/删除/未变计数。
     *
     * `.enc` 去抖：GCM 随机 IV 让相同明文每次密文都不同，所以先解密仓库里已有条目比对明文，
     * 一致就不写 —— 否则「没改动」也会天天产生提交。
     */
    fun writeTree(key: SecretKey): SyncStats {
        val snapshot = adapter.exportSnapshot()
        val root = root()
        var written = 0
        var deleted = 0
        var unchanged = 0

        writeMetaIfAbsent(root)
        writeReadmeIfAbsent(root)

        val expected = HashSet<Path>()
        for (folder in snapshot.folders.sortedBy { it.id }) {
            val file = SyncPaths.folderFile(root, folder.id)
            expected.add(file.toAbsolutePath().normalize())
            if (putText(file, syncJson.encodeToString(FolderSync.serializer(), folder))) written++ else unchanged++
        }
        for (connection in snapshot.connections.sortedBy { it.id }) {
            val file = SyncPaths.connectionFile(root, connection.id)
            expected.add(file.toAbsolutePath().normalize())
            val plain = syncJson.encodeToString(ConnectionSync.serializer(), connection).toByteArray()
            if (putEncrypted(file, plain, key)) written++ else unchanged++
        }
        for (workspace in snapshot.workspaces.sortedBy { it.id }) {
            val file = SyncPaths.workspaceFile(root, workspace.id)
            expected.add(file.toAbsolutePath().normalize())
            if (putText(file, syncJson.encodeToString(WorkspaceSync.serializer(), workspace))) written++ else unchanged++
        }
        for (console in snapshot.consoles.sortedBy { it.meta.id }) {
            val metaFile = SyncPaths.consoleMetaFile(root, console.meta.id)
            val bodyFile = SyncPaths.consoleBodyFile(root, console.meta.id)
            expected.add(metaFile.toAbsolutePath().normalize())
            expected.add(bodyFile.toAbsolutePath().normalize())
            if (putText(metaFile, syncJson.encodeToString(ConsoleMetaSync.serializer(), console.meta))) written++ else unchanged++
            if (putText(bodyFile, console.body)) written++ else unchanged++
        }
        snapshot.liveTemplatesJson?.let { text ->
            val file = SyncPaths.settingFile(root, "live-templates.json")
            expected.add(file.toAbsolutePath().normalize())
            if (putText(file, text)) written++ else unchanged++
        }
        snapshot.keymap?.let { keymap ->
            val file = SyncPaths.settingFile(root, "keymap.json")
            expected.add(file.toAbsolutePath().normalize())
            if (putText(file, syncJson.encodeToString(KeymapSync.serializer(), keymap))) written++ else unchanged++
        }
        snapshot.themesJson?.let { text ->
            val file = SyncPaths.settingFile(root, "themes.json")
            expected.add(file.toAbsolutePath().normalize())
            if (putText(file, text)) written++ else unchanged++
        }

        deleted += sweepStale(root, expected)
        return SyncStats(written = written, deleted = deleted, unchanged = unchanged)
    }

    /** 删除「仓库里有、本地已不存在」的实体文件；未知文件（非实体命名）不动。 */
    private fun sweepStale(root: Path, expected: Set<Path>): Int {
        var removed = 0
        for (dir in ENTITY_DIRS) {
            val d = root.resolve(dir)
            if (!Files.isDirectory(d)) continue
            val files = runCatching { Files.list(d).use { s -> s.filter { Files.isRegularFile(it) }.toList() } }
                .getOrElse { emptyList() }
            for (file in files) {
                val normalized = file.toAbsolutePath().normalize()
                if (normalized in expected) continue
                if (SyncPaths.parse(root, normalized) == null) continue
                if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    // ---------- 读取合并结果 ----------

    /**
     * 读取工作树（合并后）为准的快照，并与本地快照求差得到删除集合。
     *
     * 任一连接条目解密/校验失败 → 整个读取失败（不返回半成品），调用方不得写本地。
     * JSON 损坏不阻断读取，只进入 [SyncPlan.corruptFiles]。
     */
    fun readTree(key: SecretKey): Result<SyncPlan> = runCatching {
        val root = root()
        val local = adapter.exportSnapshot()
        val corrupt = ArrayList<String>()

        fun files(dir: String): List<Path> {
            val d = root.resolve(dir)
            if (!Files.isDirectory(d)) return emptyList()
            return Files.list(d).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        }

        fun <T> parseOrReport(file: Path, parse: (String) -> T): T? {
            val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
            return runCatching { parse(text) }.getOrElse {
                corrupt.add(root.relativize(file).toString())
                null
            }
        }

        val folders = files("folders")
            .filter { SyncPaths.parse(root, it) is SyncRef.Folder }
            .mapNotNull { parseOrReport(it) { text -> syncJson.decodeFromString(FolderSync.serializer(), text) } }

        val connections = files("connections")
            .filter { SyncPaths.parse(root, it) is SyncRef.Connection }
            .mapNotNull { file ->
                val stored = Files.readString(file)
                val plain = SecretBox.decrypt(key, stored) // 口令错/损坏 → 抛出 → Result.failure
                parseOrReport(file) { _ ->
                    syncJson.decodeFromString(ConnectionSync.serializer(), String(plain, Charsets.UTF_8))
                }
            }

        val workspaces = files("workspaces")
            .filter { SyncPaths.parse(root, it) is SyncRef.Workspace }
            .mapNotNull { parseOrReport(it) { text -> syncJson.decodeFromString(WorkspaceSync.serializer(), text) } }

        val consoleMetas = files("consoles")
            .filter { SyncPaths.parse(root, it) is SyncRef.ConsoleMeta }
            .mapNotNull { parseOrReport(it) { text -> syncJson.decodeFromString(ConsoleMetaSync.serializer(), text) } }

        val connectionIds = connections.map { it.id }.toSet()
        var skippedConsoles = 0
        val consoles = consoleMetas.mapNotNull { meta ->
            if (meta.connectionId !in connectionIds) {
                skippedConsoles++
                return@mapNotNull null
            }
            val body = runCatching { Files.readString(SyncPaths.consoleBodyFile(root, meta.id)) }.getOrDefault("")
            ConsoleSync(meta, body)
        }

        val liveTemplates = readOptionalText(SyncPaths.settingFile(root, "live-templates.json"))
        val themes = readOptionalText(SyncPaths.settingFile(root, "themes.json"))
        val keymapFile = SyncPaths.settingFile(root, "keymap.json")
        val keymap = if (Files.isRegularFile(keymapFile)) {
            parseOrReport(keymapFile) { text -> syncJson.decodeFromString(KeymapSync.serializer(), text) }
                ?: KeymapSync()
        } else {
            null
        }

        val removed = RemovedIds(
            folders = local.folders.map { it.id }.toSet() - folders.map { it.id }.toSet(),
            connections = local.connections.map { it.id }.toSet() - connectionIds,
            workspaces = local.workspaces.map { it.id }.toSet() - workspaces.map { it.id }.toSet(),
            consoles = local.consoles.map { it.meta.id }.toSet() - consoles.map { it.meta.id }.toSet(),
        )

        SyncPlan(
            snapshot = SyncSnapshot(
                folders = folders,
                connections = connections,
                workspaces = workspaces,
                consoles = consoles,
                liveTemplatesJson = liveTemplates,
                keymap = keymap,
                themesJson = themes,
            ),
            removed = removed,
            skippedConsoles = skippedConsoles,
            corruptFiles = corrupt,
        )
    }

    /** 把导入计划交给应用层（单事务写入）；返回被跳过的孤儿控制台数。 */
    fun apply(plan: SyncPlan): Int = adapter.applySnapshot(plan.snapshot, plan.removed)

    private fun readOptionalText(file: Path): String? =
        if (Files.isRegularFile(file)) runCatching { Files.readString(file) }.getOrNull() else null

    // ---------- 写文件助手 ----------

    private fun writeMetaIfAbsent(root: Path) {
        val file = SyncPaths.metaFile(root)
        if (Files.isRegularFile(file)) return
        writeText(file, syncJson.encodeToString(SyncMeta.serializer(), SyncMeta(app = app)))
    }

    private fun writeReadmeIfAbsent(root: Path) {
        val file = root.resolve(SyncPaths.README_FILE)
        if (Files.isRegularFile(file)) return
        writeText(file, README_CONTENT)
    }

    /** 文本相同则不写；返回是否写入。 */
    private fun putText(file: Path, text: String): Boolean {
        if (Files.isRegularFile(file) && runCatching { Files.readString(file) }.getOrNull() == text) return false
        writeText(file, text)
        return true
    }

    /** 密文去抖：已有文件能解出相同明文则跳过。 */
    private fun putEncrypted(file: Path, plain: ByteArray, key: SecretKey): Boolean {
        if (Files.isRegularFile(file)) {
            val stored = runCatching { Files.readString(file) }.getOrNull()
            if (stored != null && SecretBox.isEncrypted(stored)) {
                val same = runCatching { SecretBox.decrypt(key, stored).contentEquals(plain) }.getOrDefault(false)
                if (same) return false
            }
        }
        writeText(file, SecretBox.encrypt(key, plain))
        return true
    }

    private fun writeText(file: Path, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    private fun writeBytes(file: Path, bytes: ByteArray) {
        Files.createDirectories(file.parent)
        val tmp = runCatching { Files.createTempFile(file.parent, ".${file.fileName}", ".tmp") }.getOrNull()
        if (tmp == null) {
            Files.write(file, bytes)
            return
        }
        runCatching {
            Files.write(tmp, bytes)
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { e ->
            Logger.warn(e, "sync: atomic write failed for {}; falling back", file)
            runCatching { Files.deleteIfExists(tmp) }
            Files.write(file, bytes)
        }
    }

    private companion object {
        val ENTITY_DIRS = listOf("folders", "connections", "workspaces", "consoles", "settings")

        val README_CONTENT = """
            # db-k 同步仓库

            本仓库由 db-k 自动维护，用来在多台设备之间同步「数据源配置 / 控制台 SQL / 设置」。

            - `db-k/connections/*.enc` 是**加密**的连接条目（用你的同步口令解密），手工编辑会损坏。
            - `db-k/consoles/*.sql` 是**明文**控制台正文：请不要把密码写进 SQL。
            - 其余 JSON 为可读文本，可以 code review，但请优先在应用内修改（应用负责加密与合并）。
            - 冲突请在 db-k 的「设置 → 云同步 → 拉取」里按实体取舍，不要手工解冲突标记。

            忘记同步口令 = 连接配置不可恢复（控制台 SQL 仍可读）。请把口令存进密码管理器。
        """.trimIndent() + "\n"
    }
}
