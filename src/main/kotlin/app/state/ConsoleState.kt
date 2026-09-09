package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import db.ConsoleRecord
import db.ConnectionsRepository
import db.SqlHistoryRow
import jdbc.QueryExecutor
import jdbc.QueryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tinylog.Logger
import tree.ConnUiStatus

/**
 * SQL 控制台运行状态（M4）：一个数据源 → 多个命名控制台；每个控制台绑定一个 .sql 文件。
 *
 * - 元数据行（ConsolesRepository，SQLite）+ 正文（ConsoleFiles，.sql 文件）双持久化；
 *   编辑器缓冲区是文件的暂存副本，防抖 700ms 自动写回，切走/退出/执行前强制落盘。
 * - 执行结果按控制台分开存放（切换控制台各自保留最近一次结果）。
 * - 执行目标 = 控制台绑定的数据源（与左侧树选中解耦，树选中只做导航/切换激活）。
 */
data class ConsoleRunUi(
    val executing: Boolean = false,
    val result: QueryResult? = null,
    val error: String? = null,
    val ranMs: Long = 0L,
)

class ConsoleState(
    private val repository: ConnectionsRepository,
    private val connectionsState: ConnectionsState,
    private val scope: CoroutineScope,
) {

    /** profileId -> 该数据源全部控制台（首次访问同步载入）。 */
    val consolesByConnection = mutableStateMapOf<String, List<ConsoleRecord>>()

    /** 当前激活控制台 id（UI 编辑区即显示它的缓冲区）。 */
    var activeConsoleId by mutableStateOf<String?>(null)
        private set

    /** 有未落盘改动的控制台 id 集合（chip 上显示 ●）。 */
    var dirtyConsoleIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** consoleId -> 最近一次执行快照。 */
    val runSlots = mutableStateMapOf<String, ConsoleRunUi>()

    /** 会话内记住每个数据源最后激活的控制台（重启后默认选 updated_at 最新）。 */
    private val lastActivePerProfile = mutableMapOf<String, String>()

    /** 编辑器缓冲区与文件加载标记。 */
    private val buffers = mutableMapOf<String, String>()
    private val loaded = mutableSetOf<String>()
    private val saveJobs = mutableMapOf<String, Job>()

    /** 执行期追踪：代次（丢弃迟到结果）、开始时刻、执行 SQL、取消前快照。 */
    private val runGens = mutableMapOf<String, Long>()
    private val runStartedAt = mutableMapOf<String, Long>()
    private val runTarget = mutableMapOf<String, String>()
    private val lastStableSlots = mutableMapOf<String, ConsoleRunUi>()

    /** profileId -> 最近执行历史（展示用；权威在 sql_history 表）。 */
    val historyByProfile = mutableStateMapOf<String, List<SqlHistoryRow>>()


    // ---------- 查询辅助 ----------

    fun activeConsole(): ConsoleRecord? {
        val id = activeConsoleId ?: return null
        return findConsole(id)
    }

    private fun findConsole(consoleId: String): ConsoleRecord? =
        consolesByConnection.values.asSequence()
            .flatMap { it.asSequence() }
            .firstOrNull { it.id == consoleId }

    fun profileConsoles(profileId: String): List<ConsoleRecord> =
        consolesByConnection.getOrPut(profileId) { repository.listConsoles(profileId) }

    fun textOf(consoleId: String): String = buffers[consoleId] ?: ""

    fun runStateOf(consoleId: String): ConsoleRunUi = runSlots[consoleId] ?: ConsoleRunUi()

    fun isDirty(consoleId: String): Boolean = consoleId in dirtyConsoleIds

    // ---------- 激活 / 切换 ----------

    /** 激活指定控制台（未加载则从 .sql 文件读取，dirty 草稿不丢失）。 */
    fun activate(console: ConsoleRecord) {
        val prev = activeConsole()
        if (prev != null && prev.id != console.id) flushNow(prev.id)
        activeConsoleId = console.id
        lastActivePerProfile[console.connectionId] = console.id
        if (console.id !in loaded) {
            buffers[console.id] = repository.readConsoleContent(console.id)
            loaded += console.id
        }
    }

    /** 激活某数据源的控制台集合：无控制台则自动建「控制台 1」；选最后用的或最新改动的。 */
    fun activateForProfile(profileId: String): ConsoleRecord? {
        val list = profileConsoles(profileId)
        val chosen = if (list.isEmpty()) {
            createConsole(profileId, "控制台 1")
        } else {
            val lastId = lastActivePerProfile[profileId]
            list.firstOrNull { it.id == lastId }
                ?: list.maxByOrNull { it.updatedAt }
                ?: list.first()
        }
        activate(chosen)
        return chosen
    }

    // ---------- 控制台管理 ----------

    fun createConsole(profileId: String, name: String): ConsoleRecord {
        val rec = repository.createConsole(profileId, name)
        consolesByConnection[profileId] = profileConsoles(profileId) + rec
        loaded += rec.id
        buffers[rec.id] = ""
        activate(rec)
        return rec
    }

    fun renameConsole(consoleId: String, newName: String) {
        repository.renameConsole(consoleId, newName)
        val entry = consolesByConnection.entries.firstOrNull { (_, list) -> list.any { it.id == consoleId } }
        if (entry != null) {
            consolesByConnection[entry.key] = entry.value.map {
                if (it.id == consoleId) it.copy(name = newName) else it
            }
        }
    }

    /** 删除控制台（行 + .sql 文件）；若是当前激活则切到同源另一控制台（无则新建）。 */
    fun deleteConsole(consoleId: String) {
        val rec = consolesByConnection.values.asSequence().flatMap { it.asSequence() }
            .firstOrNull { it.id == consoleId } ?: return
        saveJobs.remove(consoleId)?.cancel()
        repository.deleteConsole(consoleId)
        buffers.remove(consoleId)
        loaded.remove(consoleId)
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        runSlots.remove(consoleId)
        runGens.remove(consoleId)
        runStartedAt.remove(consoleId)
        runTarget.remove(consoleId)
        lastStableSlots.remove(consoleId)
        consolesByConnection[rec.connectionId] =
            consolesByConnection[rec.connectionId].orEmpty().filterNot { it.id == consoleId }
        if (activeConsoleId == consoleId) {
            activeConsoleId = null
            activateForProfile(rec.connectionId)
        }
    }

    /** 连接档案被删除：清理其下全部控制台状态；若激活的是它则回到无目标引导态。 */
    fun onConnectionDeleted(profileId: String) {
        consolesByConnection.remove(profileId)?.forEach { rec ->
            saveJobs.remove(rec.id)?.cancel()
            buffers.remove(rec.id)
            loaded.remove(rec.id)
            dirtyConsoleIds = dirtyConsoleIds - rec.id
            runSlots.remove(rec.id)
            runGens.remove(rec.id)
            runStartedAt.remove(rec.id)
            runTarget.remove(rec.id)
            lastStableSlots.remove(rec.id)
        }
        historyByProfile.remove(profileId)
        if (activeConsole()?.connectionId == profileId) {
            activeConsoleId = null
        }
    }

    // ---------- 文本编辑 / 自动保存 ----------

    /** 编辑器文本变更：更新内存缓冲，防抖自动写回绑定的 .sql 文件。 */
    fun setText(consoleId: String, text: String) {
        buffers[consoleId] = text
        dirtyConsoleIds = dirtyConsoleIds + consoleId
        saveJobs.remove(consoleId)?.cancel()
        saveJobs[consoleId] = scope.launch {
            delay(AUTOSAVE_MS)
            withContext(Dispatchers.IO) { flushNow(consoleId) }
        }
    }

    /** 立即可靠写盘（切走前 / 执行前 / 退出时）。 */
    fun flushNow(consoleId: String) {
        if (consoleId !in dirtyConsoleIds) return
        val text = buffers[consoleId] ?: return
        runCatching { repository.writeConsoleContent(consoleId, text) }
            .onFailure { Logger.error(it, "console autosave failed {}", consoleId) }
        dirtyConsoleIds = dirtyConsoleIds - consoleId
    }

    /** Ctrl+S 主动保存：取消未决防抖任务并立即异步写盘（调用方在 IO 之外的协程即可）。 */
    fun saveNow(consoleId: String) {
        saveJobs.remove(consoleId)?.cancel()
        if (consoleId !in dirtyConsoleIds) return
        scope.launch {
            withContext(Dispatchers.IO) { flushNow(consoleId) }
        }
    }

    fun flushAllSync() {
        dirtyConsoleIds.toList().forEach { flushNow(it) }
    }

    // ---------- 执行 ----------

    fun clearRun(consoleId: String) {
        runSlots[consoleId] = ConsoleRunUi()
    }

    /** 清空当前控制台编辑器 + 结果。 */
    fun clearEditor(consoleId: String) {
        setText(consoleId, "")
        flushNow(consoleId)
        clearRun(consoleId)
    }

    /**
     * 执行控制台 SQL（目标 = 控制台绑定的 profile；profile 由调用方从档案列表解析）。
     * @param sql 待执行语句；null 时取控制台缓冲全文（预览/程序化执行用）。
     *   交互层规则：编辑器无选中文本时禁止全量执行，因此 UI 一律传选中片段。
     * 连接未就绪先补连/重连；同源所有 JDBC 都经 LiveConnection 单线程执行器串行。
     *
     * 执行期登记当前 Statement 供取消；结束（成功/失败）都会写 sql_history。
     * 代次守卫：取消或新执行会 bump runGens，使本 run 的迟到结果被丢弃（不覆盖新状态）。
     */
    suspend fun run(console: ConsoleRecord, profile: db.ConnectionProfile, sql: String? = null) {
        // 同控制台不允许叠加执行（按钮已禁，Ctrl+Enter/预览触发时的兜底）
        if (runSlots[console.id]?.executing == true) return
        withContext(Dispatchers.IO) { flushNow(console.id) }
        val target = sql?.trim().orEmpty().ifEmpty { textOf(console.id).trim() }
        if (target.isEmpty()) {
            runSlots[console.id] = ConsoleRunUi(error = "请输入要执行的 SQL")
            return
        }
        val status = connectionsState.statusOf(profile.id)
        if (status != ConnUiStatus.CONNECTED) {
            connectionsState.ensureConnectionReady(profile)
        }
        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            runSlots[console.id] = ConsoleRunUi(
                error = connectionsState.statusMessageOf(profile.id) ?: "连接不可用",
            )
            return
        }
        val live = connectionsState.liveConnection(profile.id)
        if (live == null) {
            runSlots[console.id] = ConsoleRunUi(error = "连接已断开")
            return
        }
        runGens[console.id] = (runGens[console.id] ?: 0L) + 1
        val gen = runGens.getValue(console.id)
        lastStableSlots[console.id] = runSlots[console.id] ?: ConsoleRunUi()
        runStartedAt[console.id] = System.currentTimeMillis()
        runTarget[console.id] = target
        runSlots[console.id] = ConsoleRunUi(executing = true)

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                live.onConnection { conn ->
                    QueryExecutor.execute(conn, target) { st -> live.registerStatement(st) }
                }
            }
        }

        val startedAt = runStartedAt.remove(console.id)
        val sqlText = runTarget.remove(console.id) ?: target
        if (runGens[console.id] != gen) return // 已被取消/新执行覆盖：丢弃迟到结果，历史已由发起方记
        val durationMs = startedAt?.let { System.currentTimeMillis() - it } ?: 0L
        outcome.onSuccess { r ->
            recordHistory(
                profileId = profile.id, sql = sqlText, ok = true,
                durationMs = durationMs, rowCount = r.affectedRows ?: r.rowCount,
            )
            runSlots[console.id] = ConsoleRunUi(result = r, ranMs = r.durationMs)
        }.onFailure { t ->
            Logger.error(t, "query failed on {}", profile.name)
            val msg = friendlySqlError(t)
            recordHistory(
                profileId = profile.id, sql = sqlText, ok = false,
                durationMs = durationMs, rowCount = 0, error = msg,
            )
            runSlots[console.id] = ConsoleRunUi(error = msg)
        }
    }

    /**
     * 取消某控制台正在执行的查询：向 LiveConnection 的当前语句发 Statement.cancel。
     * 成功后：bump 代次丢弃迟到结果、恢复执行前快照（上次结果/错误），并记一条“已取消”历史。
     * 返回是否发出了取消（正在排队未开始 / 驱动不支持时 false，UI 保持执行中）。
     */
    fun cancelRun(consoleId: String): Boolean {
        val cur = runSlots[consoleId] ?: return false
        if (!cur.executing) return false
        val rec = findConsole(consoleId) ?: return false
        val hit = connectionsState.cancelCurrentQuery(rec.connectionId)
        if (hit) {
            runGens[consoleId] = (runGens[consoleId] ?: 0L) + 1
            val startedAt = runStartedAt.remove(consoleId)
            val sqlText = runTarget.remove(consoleId).orEmpty()
            runSlots[consoleId] = lastStableSlots.remove(consoleId) ?: ConsoleRunUi()
            val durationMs = startedAt?.let { System.currentTimeMillis() - it } ?: 0L
            recordHistory(
                profileId = rec.connectionId, sql = sqlText, ok = false,
                durationMs = durationMs, rowCount = 0, error = "已取消执行",
            )
        }
        return hit
    }

    // ---------- 执行历史（面板展示 / 回填） ----------

    fun historyOf(profileId: String): List<SqlHistoryRow> = historyByProfile[profileId].orEmpty()

    /** 从库刷新某档案历史（历史面板打开时调用）。 */
    fun refreshHistory(profileId: String) {
        historyByProfile[profileId] = repository.listHistoryByProfile(profileId, HISTORY_PANEL_LIMIT)
    }

    fun clearHistory(profileId: String) {
        repository.clearHistoryForProfile(profileId)
        historyByProfile.remove(profileId)
    }

    private fun recordHistory(
        profileId: String,
        sql: String,
        ok: Boolean,
        durationMs: Long,
        rowCount: Int,
        error: String? = null,
    ) {
        if (sql.isBlank()) return
        runCatching {
            val row = repository.insertHistory(
                profileId = profileId, sqlText = sql, ok = ok,
                executedAtMs = System.currentTimeMillis(),
                durationMs = durationMs, rowCount = rowCount, errorMessage = error,
            )
            historyByProfile[profileId] = (listOf(row) + historyOf(profileId)).take(HISTORY_PANEL_LIMIT)
        }.onFailure { Logger.error(it, "sql_history insert failed") }
    }

    companion object {
        // 自动保存防抖窗口：用户停顿超过该时长才写盘（打字期间不写，不影响编辑）；
        // 切换控制台/执行前/退出仍强制落盘，保证基本不丢。
        private const val AUTOSAVE_MS = 3000L
        private const val HISTORY_PANEL_LIMIT = 100
    }
}
