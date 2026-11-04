package app.sync

import app.state.SyncState
import db.ConnectionsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val plan = engine.readTree(key).getOrElse { e ->
            return@serialized fail(SyncFailure.WRONG_PASSPHRASE, e.message)
        }
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
        val oldKey = keyFor(engine, old)
        if (!engine.loadOrCreateCheck(oldKey)) return@serialized fail(SyncFailure.WRONG_PASSPHRASE)

        val newKey = keyFor(engine, new)
        engine.writeCheck(newKey)
        engine.writeTree(newKey) // 旧密钥解不开 → 全部 .enc 重写
        engine.commitLocal(hostLabel)
        val outcome = engine.push("origin", settings.branch)
        if (!outcome.ok) {
            state.lastResult = outcome
            return@serialized outcome
        }
        prefs.save(settings.copy(passphrase = new))
        refreshState()
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

    /** 冲突弹窗取消：放弃整次拉取（工作树回到拉取前）。 */
    suspend fun cancelConflicts() = withContext(Dispatchers.IO) {
        pendingEngine?.abortMerge()
        pendingEngine = null
        state.conflicts = emptyList()
    }

    fun pendingConflictItems(): List<ConflictItem> = state.conflicts

    // ---------- 内部 ----------

    private suspend fun <T> serialized(block: suspend () -> T): T {
        state.running = true
        return try {
            mutex.withLock { withContext(Dispatchers.IO) { block() } }
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
