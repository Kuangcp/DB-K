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
    val workTree: Path,
) {

    /** 仓库内本应用的命名空间目录（`<workTree>/<app>/`）。 */
    fun root(): Path = SyncPaths.appRoot(workTree, app)

    // ---------- 仓库生命周期 ----------

    /**
     * 确保工作树是一个 git 仓库并指向 [remoteUrl]；返回 null 表示就绪。
     * 首次使用（目录为空/不存在）时 `git init` 并设置本仓库的提交身份。
     */
    fun ensureRepo(remoteUrl: String, branch: String): SyncFailure? {
        if (remoteUrl.isBlank()) return SyncFailure.NOT_CONFIGURED
        if (branch.isBlank()) return SyncFailure.NOT_CONFIGURED
        if (!Files.isDirectory(workTree.resolve(".git"))) {
            val init = git.init()
            if (!init.ok) {
                Logger.warn("sync: git init failed: {}", init.out)
                return SyncFailure.GIT_FAILED
            }
            git.setLocalIdentity()
        }
        ensureGitAttributes()
        val remote = git.setRemote("origin", remoteUrl)
        if (!remote.ok) {
            Logger.warn("sync: git remote setup failed: {}", remote.out)
            return SyncFailure.GIT_FAILED
        }
        return null
    }

    /** 克隆远端到工作树（仅「克隆并导入」与测试用）；已有 .git 时返回 [SyncFailure.GIT_FAILED]。 */
    fun gitClone(remoteUrl: String, branch: String): SyncFailure? {
        if (Files.isDirectory(workTree.resolve(".git"))) return SyncFailure.GIT_FAILED
        val result = GitRepo(workTree).clone(remoteUrl, branch)
        if (!result.ok) {
            Logger.warn("sync: clone failed: {}", result.out)
            return SyncFailure.GIT_FAILED
        }
        return null
    }

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

    /** 强制写入口令校验串（更换口令时用）。 */
    fun writeCheck(key: SecretKey) {
        writeText(SyncPaths.checkFile(root()), SecretBox.encrypt(key, SecretBox.CHECK_PLAINTEXT.toByteArray()))
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

    // ---------- 提交 / 推送 ----------

    /** 暂存本应用命名空间并提交；返回是否产生了提交（无变更 = false，不算失败）。 */
    fun commitLocal(hostLabel: String): Boolean {
        val add = git.add(listOf(app))
        if (!add.ok) {
            Logger.warn("sync: git add failed: {}", add.out)
            return false
        }
        return git.commit("$app sync: $hostLabel ${java.time.Instant.now()}")
    }

    fun push(remote: String, branch: String): SyncOutcome {
        val result = git.push(remote, branch)
        if (result.ok) return SyncOutcome(ok = true)
        val text = result.out
        val rejected = listOf("rejected", "non-fast-forward", "fetch first", "Updates were rejected")
            .any { text.contains(it, ignoreCase = true) }
        return SyncOutcome(
            ok = false,
            failure = if (rejected) SyncFailure.PUSH_REJECTED else SyncFailure.GIT_FAILED,
            detail = text.take(500),
        )
    }

    /**
     * 空库守卫：本地同步态为空，但工作树里已有连接条目 —— 说明要么新装设备还没拉取，
     * 要么本地数据目录被清空。此时若照常导出，会把远端仓库清成空。
     */
    fun isRemoteEmptyGuardTripped(): Boolean {
        if (!adapter.exportSnapshot().isEmpty()) return false
        val dir = root().resolve("connections")
        if (!Files.isDirectory(dir)) return false
        return Files.list(dir).use { s -> s.anyMatch { Files.isRegularFile(it) } }
    }

    // ---------- 拉取 / 冲突 ----------

    private var pendingConflicts: List<ConflictItem> = emptyList()
    private var pendingRemote: String = "origin"
    private var pendingBranch: String = "main"
    private var pendingHost: String = "local"

    /**
     * 拉取并合并：本地快照先提交（首次同步/克隆场景除外）→ fetch → merge →
     * 冲突则返回 [PullOutcome.NeedResolution]（不导入任何东西）→ 无冲突则导入合并结果。
     *
     * @param firstSync 本设备从未成功同步过（`sync.properties.lastSyncAtMs == 0`）。此时工作树可能刚
     *   从远端克隆而来，**不能**用本地快照提交（会把远端独有实体当成“本地删除”提交），直接从合并结果导入。
     */
    fun pull(
        remote: String,
        branch: String,
        key: SecretKey,
        passphraseOk: Boolean,
        hostLabel: String,
        firstSync: Boolean = false,
    ): PullOutcome {
        pendingRemote = remote
        pendingBranch = branch
        pendingHost = hostLabel.ifBlank { "local" }
        if (!passphraseOk) return PullOutcome.Failed(SyncFailure.WRONG_PASSPHRASE)
        if (!Files.isDirectory(workTree.resolve(".git"))) {
            return PullOutcome.Failed(SyncFailure.GIT_FAILED, "repository-not-initialized")
        }
        if (git.mergeInProgress()) git.mergeAbort()

        val remoteRef = "origin/$branch"
        if (!git.hasCommits()) {
            val fetch = git.fetch(remote, branch)
            if (!fetch.ok) return PullOutcome.Failed(SyncFailure.GIT_FAILED, fetch.out.take(300))
            if (git.run("rev-parse", "--verify", remoteRef).ok) {
                val reset = git.resetHard(remoteRef)
                if (!reset.ok) return PullOutcome.Failed(SyncFailure.GIT_FAILED, reset.out.take(300))
            }
            return importMerged(key)
        }

        val skipLocalExport = firstSync || isRemoteEmptyGuardTripped()
        if (!skipLocalExport) {
            writeTree(key)
            commitLocal(pendingHost)
        }

        val fetch = git.fetch(remote, branch)
        if (!fetch.ok) return PullOutcome.Failed(SyncFailure.GIT_FAILED, fetch.out.take(300))

        if (git.run("rev-parse", "--verify", remoteRef).ok) {
            val allowUnrelated = !git.hasMergeBase(remoteRef)
            val merge = git.merge(remoteRef, allowUnrelated = allowUnrelated)
            if (!merge.ok) {
                val conflicts = buildConflicts(key)
                if (conflicts.isEmpty()) {
                    git.mergeAbort()
                    return PullOutcome.Failed(SyncFailure.GIT_FAILED, merge.out.take(300))
                }
                pendingConflicts = conflicts
                return PullOutcome.NeedResolution(conflicts)
            }
        }
        return importMerged(key)
    }

    /** 用用户在冲突弹窗里的选择解决冲突，然后导入合并结果（可选再推送）。 */
    fun resolveConflicts(
        choices: Map<String, ConflictChoice>,
        key: SecretKey,
        autoPush: Boolean,
    ): SyncOutcome {
        val conflicted = git.conflictedPaths()
        if (conflicted.isEmpty()) {
            return SyncOutcome(ok = false, failure = SyncFailure.GIT_FAILED, detail = "no-conflict")
        }
        val missing = conflicted.filter { it !in choices }
        if (missing.isNotEmpty()) {
            return SyncOutcome(ok = false, failure = SyncFailure.GIT_FAILED, detail = "unresolved: $missing")
        }
        for (path in conflicted) {
            val stage = if (choices[path] == ConflictChoice.LOCAL) 2 else 3
            if (git.stageExists(stage, path)) {
                git.writeFile(path, git.stageBlob(stage, path))
            } else {
                git.deleteFile(path) // 选择的那一侧已删除该实体
            }
            git.add(listOf(path))
        }
        git.commit("$app sync: resolve conflicts $pendingHost ${java.time.Instant.now()}")
        pendingConflicts = emptyList()

        return when (val imported = importMerged(key)) {
            is PullOutcome.Failed -> SyncOutcome(ok = false, failure = imported.failure, detail = imported.detail)
            is PullOutcome.NeedResolution -> SyncOutcome(ok = false, failure = SyncFailure.GIT_FAILED)
            is PullOutcome.Synced -> {
                if (!autoPush) return SyncOutcome(ok = true, skippedConsoles = imported.skippedConsoles)
                push(pendingRemote, pendingBranch)
            }
        }
    }

    /** 放弃当前冲突（回到拉取前的状态）。 */
    fun abortMerge() {
        if (git.mergeInProgress()) git.mergeAbort()
        pendingConflicts = emptyList()
    }

    /** 当前待用户取舍的冲突（取消后为空）。 */
    fun pendingConflicts(): List<ConflictItem> = pendingConflicts

    private fun importMerged(key: SecretKey): PullOutcome {
        val plan = readTree(key).getOrElse { e ->
            Logger.error(e, "sync: cannot read merged tree")
            return PullOutcome.Failed(SyncFailure.WRONG_PASSPHRASE, e.message)
        }
        return runCatching { apply(plan) }
            .fold(
                onSuccess = { skipped -> PullOutcome.Synced(skippedConsoles = skipped + plan.skippedConsoles) },
                onFailure = { e ->
                    Logger.error(e, "sync: applying merged tree failed")
                    PullOutcome.Failed(SyncFailure.IO_FAILED, e.message)
                },
            )
    }

    private fun buildConflicts(key: SecretKey): List<ConflictItem> {
        val root = root()
        return git.conflictedPaths().map { path ->
            val file = workTree.resolve(path)
            val ref = runCatching { SyncPaths.parse(root, file) }.getOrNull()
            val kind = kindOf(ref)
            val local = git.stageBlob(2, path)
            val remote = git.stageBlob(3, path)
            ConflictItem(
                path = path,
                kind = kind,
                id = ref?.id,
                local = preview(kind, local, key, ref),
                remote = preview(kind, remote, key, ref),
                localBytes = local,
                remoteBytes = remote,
            )
        }
    }

    private fun kindOf(ref: SyncRef?): SyncEntityKind = when (ref) {
        is SyncRef.Folder -> SyncEntityKind.FOLDER
        is SyncRef.Connection -> SyncEntityKind.CONNECTION
        is SyncRef.Workspace -> SyncEntityKind.WORKSPACE
        is SyncRef.ConsoleMeta -> SyncEntityKind.CONSOLE_META
        is SyncRef.ConsoleBody -> SyncEntityKind.CONSOLE_BODY
        is SyncRef.Setting -> SyncEntityKind.SETTINGS
        null -> SyncEntityKind.UNKNOWN
    }

    /** 冲突摘要：密码永远不出现（连接只显示 name/dbType/host/port/database/user）。 */
    private fun preview(kind: SyncEntityKind, bytes: ByteArray, key: SecretKey, ref: SyncRef?): ConflictPreview {
        if (bytes.isEmpty()) return ConflictPreview.Unknown(0)
        val text = String(bytes, Charsets.UTF_8)
        return when (kind) {
            SyncEntityKind.CONNECTION -> runCatching {
                val plain = if (SecretBox.isEncrypted(text)) SecretBox.decrypt(key, text) else bytes
                val c = syncJson.decodeFromString(ConnectionSync.serializer(), String(plain, Charsets.UTF_8))
                ConflictPreview.Connection(c.name, c.dbType, c.host, c.port, c.database, c.user)
            }.getOrElse { ConflictPreview.Unknown(bytes.size) }

            SyncEntityKind.CONSOLE_BODY -> {
                val lines = text.lines()
                ConflictPreview.Sql(
                    lineCount = lines.size,
                    head = lines.take(5).map { it.take(120) },
                )
            }

            SyncEntityKind.FOLDER -> named(text) { syncJson.decodeFromString(FolderSync.serializer(), it).name }
                ?: ConflictPreview.Named(ref?.id.orEmpty())

            SyncEntityKind.WORKSPACE -> named(text) { syncJson.decodeFromString(WorkspaceSync.serializer(), it).name }
                ?: ConflictPreview.Named(ref?.id.orEmpty())

            SyncEntityKind.CONSOLE_META -> named(text) { syncJson.decodeFromString(ConsoleMetaSync.serializer(), it).name }
                ?: ConflictPreview.Named(ref?.id.orEmpty())

            SyncEntityKind.SETTINGS -> ConflictPreview.Named(ref?.id ?: "settings")
            SyncEntityKind.UNKNOWN -> ConflictPreview.Unknown(bytes.size)
        }
    }

    private fun named(text: String, name: (String) -> String): ConflictPreview? =
        runCatching { ConflictPreview.Named(name(text)) }.getOrNull()

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

    /**
     * 仓库根 `.gitattributes`：`.enc` 标二进制（禁换行归一化，否则密文会被改坏）、
     * `.sql` 统一 LF（跨平台 diff/合并稳定）。已存在则只追加缺失行，不覆盖用户内容。
     */
    private fun ensureGitAttributes() {
        val file = workTree.resolve(".gitattributes")
        val required = listOf("*.enc -text", "*.sql text eol=lf")
        val existing = if (Files.isRegularFile(file)) {
            runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val present = existing.map { it.trim() }.toSet()
        val missing = required.filterNot { it in present }
        if (missing.isEmpty()) return
        val content = buildString {
            if (existing.isNotEmpty()) {
                existing.forEach { appendLine(it) }
            }
            missing.forEach { appendLine(it) }
        }
        writeBytes(file, content.toByteArray(Charsets.UTF_8))
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
