package app.sync

import app.state.SyncState
import db.ConnectionsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.tinylog.Logger
import sync.ConflictChoice
import sync.ConflictItem
import sync.GitRepo
import sync.PullOutcome
import sync.SecretBox
import sync.SyncEngine
import sync.SyncFailure
import sync.SyncOutcome
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.crypto.SecretKey

/**
 * 云同步的应用编排：串行执行（一次只跑一个动作）、口令会话、状态回写。
 *
 * 与 UI 的契约：所有动作都是 `suspend`，调用方用 `rememberCoroutineScope().launch { … }`；
 * 结果同时写入 [state]（UI 只读 state，不碰引擎）。
 *
 * 两个回调把「编辑器缓冲落盘」与「导入后刷新界面」留给 Main 注入，避免 app/sync 反向依赖 UI 状态类。
 */
class SyncService(
    private val repo: ConnectionsRepository,
    private val dataDir: Path,
    private val hostLabel: String = defaultHostLabel(),
    /** 动作开始前：编辑器 buffer 落盘（`ConsoleState.flushAllSync()`）。 */
    private val onBeforeExport: () -> Unit = {},
    /** 拉取导入成功后：刷新树/控制台/设置内存态。 */
    private val onAfterImport: () -> Unit = {},
    /** 仅测试注入：替换引擎构造（默认走 [DbKSyncAdapter]）。 */
    internal val engineFactory: (SyncSettings) -> SyncEngine = { settings ->
        val tree = resolveLocalDir(dataDir, settings)
        SyncEngine(APP, DbKSyncAdapter(repo, dataDir), GitRepo(tree), tree)
    },
) {

    private val prefs = SyncPrefs(dataDir.resolve("sync.properties"))
    val state = SyncState()

    private val mutex = Mutex()
    private var pendingEngine: SyncEngine? = null

    init {
        refreshState()
    }

    // ---------- 状态 ----------

    fun refreshState() {
        val settings = prefs.load()
        state.settings = settings
        state.configured = settings.remoteUrl.isNotBlank()
        state.passphraseSet = settings.passphrase != null
    }

    /** 保存远端/本地目录/开关等配置；口令不随表单传（保留本机已记住的口令）。 */
    suspend fun configure(settings: SyncSettings) = withContext(Dispatchers.IO) {
        val current = prefs.load()
        prefs.save(
            settings.copy(
                passphrase = current.passphrase ?: settings.passphrase,
                lastSyncAtMs = current.lastSyncAtMs,
            ),
        )
        refreshState()
    }

    suspend fun probeGit(): Boolean = withContext(Dispatchers.IO) {
        GitRepo.isAvailable().also { state.gitAvailable = it }
    }

    // ---------- 口令 ----------

    /** 校验并记住口令（失败不保存）。要求工作树已 clone/初始化。 */
    suspend fun submitPassphrase(passphrase: String): SyncOutcome = serialized {
        val settings = prefs.load()
        if (settings.remoteUrl.isBlank()) return@serialized fail(SyncFailure.NOT_CONFIGURED)
        val engine = engineFactory(settings)
        if (!Files.isDirectory(resolveLocalDir(dataDir, settings).resolve(".git"))) {
            return@serialized fail(SyncFailure.GIT_FAILED, "repository-not-initialized")
        }
        val key = keyFor(engine, passphrase)
        if (!engine.loadOrCreateCheck(key)) {
            state.passphraseSet = false
            return@serialized fail(SyncFailure.WRONG_PASSPHRASE)
        }
        prefs.save(settings.copy(passphrase = passphrase))
        refreshState()
        SyncOutcome(ok = true)
    }

    suspend fun clearPassphrase() = withContext(Dispatchers.IO) {
        prefs.save(prefs.load().copy(passphrase = null))
        refreshState()
    }

    // ---------- 动作 ----------

    suspend fun pull(): PullOutcome = serialized {
        val settings = prefs.load()
        if (settings.remoteUrl.isBlank()) return@serialized PullOutcome.Failed(SyncFailure.NOT_CONFIGURED)
        val passphrase = settings.passphrase ?: return@serialized PullOutcome.Failed(SyncFailure.WRONG_PASSPHRASE)

        val engine = engineFactory(settings)
        val key = keyFor(engine, passphrase)
        if (!engine.loadOrCreateCheck(key)) {
            state.passphraseSet = false
            return@serialized PullOutcome.Failed(SyncFailure.WRONG_PASSPHRASE)
        }

        onBeforeExport()
        val outcome = engine.pull(
            remote = "origin",
            branch = settings.branch,
            key = key,
            passphraseOk = true,
            hostLabel = hostLabel,
            firstSync = settings.lastSyncAtMs == 0L,
        )

        when (outcome) {
            is PullOutcome.Synced -> {
                pendingEngine = null
                state.conflicts = emptyList()
                onAfterImport()
                if (settings.autoPushAfterPull) {
                    val pushed = engine.push("origin", settings.branch)
                    if (!pushed.ok) Logger.warn("sync: auto-push after pull failed: {}", pushed.failure)
                }
                markSynced()
                state.lastResult = SyncOutcome(ok = true, skippedConsoles = outcome.skippedConsoles)
            }

            is PullOutcome.NeedResolution -> {
                pendingEngine = engine
                state.conflicts = outcome.conflicts
                state.lastResult = SyncOutcome(ok = false, detail = "conflicts")
            }

            is PullOutcome.Failed -> {
                state.lastResult = SyncOutcome(ok = false, failure = outcome.failure, detail = outcome.detail)
            }
        }
        outcome
    }

    suspend fun push(): SyncOutcome = serialized {
        val settings = prefs.load()
        if (settings.remoteUrl.isBlank()) return@serialized fail(SyncFailure.NOT_CONFIGURED)
        val passphrase = settings.passphrase ?: return@serialized fail(SyncFailure.WRONG_PASSPHRASE)

        val engine = engineFactory(settings)
        engine.ensureRepo(settings.remoteUrl, settings.branch)?.let { return@serialized fail(it) }
        if (engine.isRemoteEmptyGuardTripped()) {
            return@serialized fail(SyncFailure.EMPTY_LOCAL_GUARD, "local-empty")
        }
        val key = keyFor(engine, passphrase)
        if (!engine.loadOrCreateCheck(key)) {
            state.passphraseSet = false
            return@serialized fail(SyncFailure.WRONG_PASSPHRASE)
        }

        onBeforeExport()
        engine.writeTree(key)
        engine.commitLocal(hostLabel)
        val outcome = engine.push("origin", settings.branch)
        if (outcome.ok) markSynced()
        state.lastResult = outcome
        outcome
    }

    /** 首次接入：克隆远端 → 校验口令 → 导入（本地目录已有 .git 时拒绝）。 */
    suspend fun cloneAndImport(passphrase: String): SyncOutcome = serialized {
        val settings = prefs.load()
        if (settings.remoteUrl.isBlank()) return@serialized fail(SyncFailure.NOT_CONFIGURED)
        val tree = resolveLocalDir(dataDir, settings)
        if (Files.isDirectory(tree.resolve(".git"))) {
            return@serialized fail(SyncFailure.GIT_FAILED, "already-initialized")
        }
        val engine = engineFactory(settings)
        engine.gitClone(settings.remoteUrl, settings.branch)?.let { return@serialized fail(it) }
        val key = keyFor(engine, passphrase)
        if (!engine.loadOrCreateCheck(key)) {
            state.passphraseSet = false
            return@serialized fail(SyncFailure.WRONG_PASSPHRASE)
        }
        prefs.save(settings.copy(passphrase = passphrase))
        refreshState()
        val plan0 = engine.readTree(key).getOrElse { e ->
            return@serialized fail(SyncFailure.WRONG_PASSPHRASE, e.message)
        }
        // 首次接入前本地可能已有数据（例如本地目录还没建，但用户已配了连接）：
        // 远端为准更新同名实体，但**不删**本地独有实体 —— 它们会在下一次推送里补进仓库。
        val localHasData = !engine.localSnapshotIsEmpty()
        val plan = if (localHasData) plan0.copy(removed = sync.RemovedIds()) else plan0
        val applied = runCatching { engine.apply(plan) }.getOrElse { e ->
            Logger.error(e, "sync: import after clone failed")
            return@serialized fail(SyncFailure.IO_FAILED, e.message)
        }
        onAfterImport()
        markSynced()
        val outcome = SyncOutcome(ok = true, skippedConsoles = applied + plan.skippedConsoles)
        state.lastResult = outcome
        outcome
    }

    /** 更换同步口令：校验旧口令 → 用新密钥重写 check 与全部 `.enc` → 提交并推送。 */
    suspend fun changePassphrase(old: String, new: String): SyncOutcome = serialized {
        val settings = prefs.load()
        if (settings.remoteUrl.isBlank()) return@serialized fail(SyncFailure.NOT_CONFIGURED)
        if (new.isBlank()) return@serialized fail(SyncFailure.WRONG_PASSPHRASE)
        val engine = engineFactory(settings)
        if (engine.isRemoteEmptyGuardTripped()) {
            return@serialized fail(SyncFailure.EMPTY_LOCAL_GUARD, "local-empty")
        }
        val oldKey = keyFor(engine, old)
        if (!engine.loadOrCreateCheck(oldKey)) return@serialized fail(SyncFailure.WRONG_PASSPHRASE)

        val newKey = keyFor(engine, new)
        engine.writeCheck(newKey)
        engine.writeTree(newKey) // 旧密钥解不开 → 全部 .enc 重写
        engine.commitLocal(hostLabel)
        // 先记住新口令：即使推送失败，本地文件也已用新口令加密，否则下次拉取会解不开。
        prefs.save(settings.copy(passphrase = new))
        refreshState()
        val outcome = engine.push("origin", settings.branch)
        if (!outcome.ok) {
            state.lastResult = outcome
            return@serialized outcome
        }
        markSynced()
        val ok = SyncOutcome(ok = true)
        state.lastResult = ok
        ok
    }

    /** 冲突弹窗确认后继续拉取（完成导入 + 可选推送）。 */
    suspend fun resolveConflicts(choices: Map<String, ConflictChoice>): SyncOutcome = serialized {
        val engine = pendingEngine ?: return@serialized fail(SyncFailure.GIT_FAILED, "no-pending-pull")
        val settings = prefs.load()
        val passphrase = settings.passphrase ?: return@serialized fail(SyncFailure.WRONG_PASSPHRASE)
        val key = keyFor(engine, passphrase)

        val outcome = engine.resolveConflicts(choices, key, autoPush = settings.autoPushAfterPull)
        if (outcome.ok) {
            pendingEngine = null
            state.conflicts = emptyList()
            onAfterImport()
            markSynced()
        }
        state.lastResult = outcome
        outcome
    }

    /**
     * 退出时自动推送（开关默认关）：失败只记日志，不阻塞退出、不弹窗。
     * 未配置 / 没记住口令 / 开关关闭 → 什么也不做。
     */
    suspend fun exitAutoPush() {
        val settings = prefs.load()
        if (!settings.autoPushOnExit || settings.remoteUrl.isBlank() || settings.passphrase == null) return
        runCatching { withTimeoutOrNull(EXIT_PUSH_TIMEOUT_MS) { push() } }
            .onFailure { Logger.warn(it, "sync: exit auto-push failed") }
    }

    /** 冲突弹窗取消：放弃整次拉取（工作树回到拉取前）。 */
    suspend fun cancelConflicts() = withContext(Dispatchers.IO) {
        pendingEngine?.abortMerge()
        pendingEngine = null
        state.conflicts = emptyList()
    }

    fun pendingConflictItems(): List<ConflictItem> = state.conflicts

    // ---------- 内部 ----------

    private suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock {
        state.running = true
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            state.running = false
        }
    }

    private fun markSynced() {
        prefs.save(prefs.load().copy(lastSyncAtMs = System.currentTimeMillis()))
        refreshState()
    }

    private fun fail(failure: SyncFailure, detail: String? = null): SyncOutcome {
        state.lastResult = SyncOutcome(ok = false, failure = failure, detail = detail)
        return state.lastResult!!
    }

    private fun keyFor(engine: SyncEngine, passphrase: String): SecretKey =
        SecretBox.deriveKey(passphrase, engine.loadOrCreateSalt())

    private companion object {
        const val APP = "db-k"
        /** 退出自动推送最多等待 30s，避免网络问题卡住关窗。 */
        const val EXIT_PUSH_TIMEOUT_MS = 30_000L

        fun defaultHostLabel(): String =
            runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("local")
    }
}

/** `localDir` 相对 dataDir 解析（绝对路径原样使用）。 */
internal fun resolveLocalDir(dataDir: Path, settings: SyncSettings): Path {
    val raw = settings.localDir.trim().ifEmpty { "sync" }
    val path = Paths.get(raw)
    return if (path.isAbsolute) path else dataDir.resolve(path)
}

/**
 * 「导入后刷新界面」的延迟绑定点。
 *
 * Compose 里同步服务创建在 AppRoot（退出自动推送需要），而刷新依赖 AppBody 中后声明的
 * 设置快照；用这个 holder 在声明顺序上解耦，服务回调时再读 [run]。
 */
class SyncRefreshHook {
    var run: () -> Unit = {}
}
