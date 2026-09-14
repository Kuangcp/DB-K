package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ui.CellKey
import app.ui.EditPlan
import app.ui.buildEditPlan
import app.ui.buildUpdatePlans
import app.ui.sqlHasPaginationClause
import db.ConsoleRecord
import db.ConnectionsRepository
import db.SqlHistoryRow
import engine.Protocol
import engine.model.QueryColumn
import engine.model.QueryResult
import engine.model.SchemaMeta
import jdbc.CellValue
import jdbc.DialectRegistry
import jdbc.RowUpdater
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tinylog.Logger
import redis.RedisProtocol
import tree.ConnUiStatus

/**
 * SQL 控制台运行状态（M4）：一个数据源 → 多个命名控制台；每个控制台绑定一个 .sql 文件。
 *
 * - 元数据行（ConsolesRepository，SQLite）+ 正文（ConsoleFiles，.sql 文件）双持久化；
 *   编辑器缓冲区是文件的暂存副本，防抖 700ms 自动写回，切走/退出/执行前强制落盘。
 * - 执行结果按控制台分开存放（切换控制台各自保留最近一次结果）。
 * - 光标/选区按控制台记忆（consoles.caret_start/caret_end）：内存草稿即时生效 + 1.5s 防抖落库，
 *   切控制台/退出强制落盘；重启后回到上次焦点所在行。
 * - 执行目标 = 控制台绑定的数据源（与左侧树选中解耦，树选中只做导航/切换激活）。
 */
/** 一次多语句执行中单个 SQL 语句的结果。 */
data class StatementOutcome(
    val sql: String,
    /** 成功且为查询（SELECT 等）时非空。 */
    val result: QueryResult? = null,
    /** 该语句失败原因。 */
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
    val isQuery: Boolean get() = result?.isQuery == true
    val affectedRows: Int? get() = result?.affectedRows
}

/**
 * 控制台最近一次执行快照。多语句执行时 [outcomes] 按序存放每条语句的结果
 * （出错即停在出错处），[activeIndex] 指向结果区当前展示的语句。
 */
data class ConsoleRunUi(
    val executing: Boolean = false,
    val outcomes: List<StatementOutcome> = emptyList(),
    val activeIndex: Int = 0,
    val ranMs: Long = 0L,
) {
    /** 当前激活语句的结果（UI 便捷访问）。 */
    val active: StatementOutcome? get() = outcomes.getOrNull(activeIndex)
    val result: QueryResult? get() = active?.result
    val error: String? get() = active?.error
    val hasOutcomes: Boolean get() = outcomes.isNotEmpty()
}

class ConsoleState(
    private val repository: ConnectionsRepository,
    private val connectionsState: ConnectionsState,
    private val scope: CoroutineScope,
    /** 慢操作调度器；测试注入虚拟时间调度器以确定性推进防抖/执行。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 危险命令（Redis FLUSHALL 等）执行前的二次确认钩子；返回是否继续；null = 不拦截。 */
    private val confirmDangerous: (suspend (String) -> Boolean)? = null,
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

    /**
     * 结果单元格未提交修改（本地 overlay）：consoleId → (原始坐标 → 新值)。
     * 纯瞬态，不落盘；新执行/刷新/切 Tab 即清（有修改时 UI 先确认）。
     */
    val editBuffers = mutableStateMapOf<String, Map<CellKey, CellValue>>()

    /** 当前结果集的编辑计划（表/主键/可编辑列）；null = 不可编辑（视图/无主键/表达式）。 */
    val editPlans = mutableStateMapOf<String, EditPlan?>()

    /** 正在提交/刷新某控制台的结果（禁用按钮）。 */
    val resultBusy = mutableStateMapOf<String, Boolean>()

    /** 会话内记住每个数据源最后激活的控制台（重启后默认选 updated_at 最新）。 */
    private val lastActivePerProfile = mutableMapOf<String, String>()

    /** 编辑器缓冲区与文件加载标记。 */
    private val buffers = mutableMapOf<String, String>()
    private val loaded = mutableSetOf<String>()
    private val saveJobs = mutableMapOf<String, Job>()

    /** 光标草稿（内存权威）：consoleId → (start,end)，防抖落库；记录前切回也立即生效。 */
    private val caretDrafts = mutableMapOf<String, Pair<Int, Int>>()
    private val caretJobs = mutableMapOf<String, Job>()

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

    /** 该数据源「已打开」（未关闭）的控制台——标签条只显示这些。 */
    fun openConsoles(profileId: String): List<ConsoleRecord> =
        profileConsoles(profileId).filter { !it.closed }

    /** 工作台级全部控制台（含已关闭；树右键「打开控制台」级联用）。 */
    fun allConsoles(profileIds: List<String>): List<ConsoleRecord> {
        val out = ArrayList<ConsoleRecord>()
        profileIds.forEach { pid -> out += profileConsoles(pid) }
        return out
    }

    /** 工作台级「已打开」控制台（按数据源顺序展平，跨数据源）——标签条用。 */
    fun allOpenConsoles(profileIds: List<String>): List<ConsoleRecord> =
        profileIds.flatMap { openConsoles(it) }

    fun textOf(consoleId: String): String = buffers[consoleId] ?: ""

    fun runStateOf(consoleId: String): ConsoleRunUi = runSlots[consoleId] ?: ConsoleRunUi()

    // ---------- 结果单元格编辑（本地 overlay → 提交写回） ----------

    fun editsOf(consoleId: String): Map<CellKey, CellValue> = editBuffers[consoleId].orEmpty()

    fun editCount(consoleId: String): Int = editsOf(consoleId).size

    fun editPlanOf(consoleId: String): EditPlan? = editPlans[consoleId]

    fun resultBusyOf(consoleId: String): Boolean = resultBusy[consoleId] == true

    /** 暂存一格修改（仅内存；重复点同一格覆盖）。 */
    fun setCellEdit(consoleId: String, key: CellKey, value: CellValue) {
        editBuffers[consoleId] = editsOf(consoleId) + (key to value)
    }

    /** 撤销单格修改。 */
    fun clearCellEdit(consoleId: String, key: CellKey) {
        val cur = editBuffers[consoleId] ?: return
        if (key !in cur) return
        val next = cur - key
        if (next.isEmpty()) editBuffers.remove(consoleId) else editBuffers[consoleId] = next
    }

    /** 丢弃某控制台全部未提交修改。 */
    fun clearEdits(consoleId: String) {
        editBuffers.remove(consoleId)
    }

    /** 全部控制台的未提交修改总数（退出前守卫用）。 */
    fun totalEditCount(): Int = editBuffers.values.sumOf { it.size }

    /**
     * 为当前激活结果重算编辑计划：找基表 → 解析 schema → 异步拉列元数据（含主键标记）→ 构建。
     * 结果不可编辑（无基表/无主键/视图）时存 null。UI 在结果变化时调用（幂等，有缓存）。
     */
    suspend fun ensureEditPlan(console: ConsoleRecord, profile: db.ConnectionProfile) {
        val ui = runSlots[console.id]
        val result = ui?.result
        if (result == null || !result.isQuery || result.columns.isEmpty()) {
            editPlans.remove(console.id)
            return
        }
        val tableCol = result.columns.firstOrNull { !it.table.isNullOrBlank() && !it.baseColumn.isNullOrBlank() }
        val table = tableCol?.table
        if (tableCol == null || table.isNullOrBlank()) {
            editPlans[console.id] = null
            return
        }
        val schemaMeta = resolveResultSchema(console, profile, tableCol)
        if (schemaMeta == null) {
            editPlans[console.id] = null
            return
        }
        connectionsState.columns.ensure(profile, listOf(ColumnCatalog.ColumnRef(schemaMeta, table)))
        val cols = connectionsState.columns.peek(profile.id, schemaMeta, table)
        if (cols == null) {
            editPlans[console.id] = null
            return
        }
        val primaryKeys = cols.filter { it.primaryKey }.map { it.name.lowercase() }.toSet()
        val knownColumns = cols.map { it.name.lowercase() }.toSet()
        // 目录未加载时不阻断（乐观）；提交失败仍会报错，不造成数据损坏
        val isBaseTable = connectionsState.objectsOf(profile.id, schemaMeta.key)
            ?.let { objs -> objs.tables.any { it.equals(table, ignoreCase = true) } }
            ?: true
        editPlans[console.id] = buildEditPlan(result, primaryKeys, knownColumns, isBaseTable)
    }

    /** 结果元数据里的 schema/catalog 优先；缺失（如 SQLite）时用控制台目标 / 唯一 schema 兜底。 */
    private fun resolveResultSchema(
        console: ConsoleRecord,
        profile: db.ConnectionProfile,
        column: QueryColumn,
    ): SchemaMeta? {
        if (column.schema != null || column.catalog != null) return SchemaMeta(column.catalog, column.schema)
        val schemas = connectionsState.schemasOf(profile.id)
        if (console.target.isNotBlank()) {
            schemas?.firstOrNull { it.displayName.equals(console.target, ignoreCase = true) }?.let { return it }
        }
        return schemas?.singleOrNull()
    }

    /**
     * 提交某控制台的全部未提交修改：参数化 UPDATE + 单事务 + 影响行数=1 校验；
     * 成功后清空 overlay、写执行历史，并**固定刷新当前 Tab**（用服务端权威值覆盖）。
     * 失败保留 overlay，返回可读错误（由 UI Toast 展示）。
     */
    suspend fun commitEdits(console: ConsoleRecord, profile: db.ConnectionProfile): Result<Int> {
        val ui = runSlots[console.id] ?: return Result.failure(IllegalStateException("没有可提交的结果"))
        if (ui.executing || resultBusyOf(console.id)) return Result.failure(IllegalStateException("正在执行，稍后再提交"))
        val result = ui.result ?: return Result.failure(IllegalStateException("没有可提交的结果"))
        val plan = editPlanOf(console.id) ?: return Result.failure(IllegalStateException("当前结果不可编辑"))
        val edits = editsOf(console.id)
        if (edits.isEmpty()) return Result.success(0)
        val plans = buildUpdatePlans(result, plan, edits)
        if (plans.isEmpty()) return Result.failure(IllegalStateException("没有可提交的修改"))

        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            connectionsState.ensureConnectionReady(profile)
        }
        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            return Result.failure(
                IllegalStateException(connectionsState.statusMessageOf(profile.id) ?: "连接不可用"),
            )
        }
        val session = connectionsState.sessionOf(profile.id)
            ?: return Result.failure(IllegalStateException("连接已断开"))
        if (session !is jdbc.EditableSession || !session.capabilities.editableResult) {
            return Result.failure(IllegalStateException("当前数据源不支持写回"))
        }
        val dialect = DialectRegistry.forProfile(profile)
        val contextSql = sessionContextSqlFor(console, profile)

        resultBusy[console.id] = true
        val outcome = withContext(ioDispatcher) {
            runCatching { session.applyUpdatePlans(plans, contextSql) }
        }
        resultBusy[console.id] = false

        return outcome.fold(
            onSuccess = { count ->
                plans.forEach { p ->
                    recordHistory(profile.id, RowUpdater.renderUpdateSql(p, dialect), true, 0, 1)
                }
                clearEdits(console.id)
                // 提交后固定刷新当前 Tab（等值：服务端触发器等可能改写结果）
                refreshOutcome(console, profile, ui.activeIndex)
                Result.success(count)
            },
            onFailure = { t ->
                Logger.error(t, "commit cell edits failed on {}", profile.name)
                Result.failure(IllegalStateException(friendlySqlError(t)))
            },
        )
    }

    /**
     * P7：提交前预览——把暂存修改渲染为可读 UPDATE（不执行、不改状态）。
     * 返回空列表表示没有可预览内容（无修改 / 不可编辑）。
     */
    fun previewCommit(console: ConsoleRecord, profile: db.ConnectionProfile): List<String> {
        val ui = runSlots[console.id] ?: return emptyList()
        val result = ui.result ?: return emptyList()
        val plan = editPlanOf(console.id) ?: return emptyList()
        val edits = editsOf(console.id)
        if (edits.isEmpty()) return emptyList()
        val dialect = DialectRegistry.forProfile(profile)
        return buildUpdatePlans(result, plan, edits).map { RowUpdater.renderUpdateSql(it, dialect) }
    }

    /**
     * 只重新执行当前（或指定）结果 Tab 的语句，替换该 outcome，其余 Tab / 编辑器草稿不动。
     * 调用前若存在未提交修改，UI 应先确认丢弃（本方法不弹窗）。
     */
    suspend fun refreshOutcome(console: ConsoleRecord, profile: db.ConnectionProfile, index: Int = -1) {
        val ui = runSlots[console.id] ?: return
        if (ui.executing || resultBusyOf(console.id)) return
        val idx = if (index >= 0) index else ui.activeIndex
        val stmt = ui.outcomes.getOrNull(idx)?.sql?.takeIf { it.isNotBlank() } ?: return

        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            connectionsState.ensureConnectionReady(profile)
        }
        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) return
        val session = connectionsState.sessionOf(profile.id) ?: return
        val contextSql = sessionContextSqlFor(console, profile)

        runGens[console.id] = (runGens[console.id] ?: 0L) + 1
        val gen = runGens.getValue(console.id)
        lastStableSlots[console.id] = ui
        resultBusy[console.id] = true
        val res = withContext(ioDispatcher) {
            runCatching { session.runStatement(stmt, contextSql) }
        }
        resultBusy[console.id] = false
        if (runGens[console.id] != gen) return

        val outcomes = ui.outcomes.toMutableList()
        res.onSuccess { r ->
            outcomes[idx] = StatementOutcome(stmt, result = r)
            recordHistory(profile.id, stmt, true, r.durationMs, r.affectedRows ?: r.rowCount)
        }.onFailure { t ->
            Logger.error(t, "refresh outcome failed on {}", profile.name)
            val msg = friendlySqlError(t)
            outcomes[idx] = StatementOutcome(stmt, error = msg)
            recordHistory(profile.id, stmt, false, 0, 0, msg)
        }
        runSlots[console.id] = ConsoleRunUi(
            outcomes = outcomes,
            activeIndex = idx,
            ranMs = res.getOrNull()?.durationMs ?: ui.ranMs,
        )
        clearEdits(console.id)
        ensureEditPlan(console, profile)
    }

    fun isDirty(consoleId: String): Boolean = consoleId in dirtyConsoleIds

    // ---------- N1「取更多」：按方言注入分页，追加不替换 ----------

    /** 当前激活结果是否还能「取更多」（被截断且原查询不含分页子句）。 */
    fun canFetchMore(consoleId: String): Boolean {
        val ui = runSlots[consoleId] ?: return false
        val active = ui.active ?: return false
        val result = active.result ?: return false
        if (ui.executing || resultBusyOf(consoleId)) return false
        if (!result.isQuery || !result.truncated) return false
        return !sqlHasPaginationClause(active.sql)
    }

    /**
     * 取更多：给当前语句按方言注入分页（[DialectRegistry.paginate]），取 [FETCH_MORE_PAGE] 行
     * **追加**到当前结果（不替换），满页说明可能还有、不满页则到底。返回实际追加行数。
     * 原查询无 ORDER BY 时由 UI 提示「顺序不保证」。
     */
    suspend fun fetchMore(console: ConsoleRecord, profile: db.ConnectionProfile): Result<Int> {
        val ui = runSlots[console.id] ?: return Result.failure(IllegalStateException("没有可追加的结果"))
        if (ui.executing || resultBusyOf(console.id)) {
            return Result.failure(IllegalStateException("正在执行，稍后再试"))
        }
        val idx = ui.activeIndex
        val outcome = ui.outcomes.getOrNull(idx) ?: return Result.failure(IllegalStateException("没有可追加的结果"))
        val result = outcome.result ?: return Result.failure(IllegalStateException("没有可追加的结果"))
        if (!result.isQuery) return Result.failure(IllegalStateException("当前结果不是查询"))
        val baseSql = outcome.sql.trim().trimEnd(';').trim()
        if (baseSql.isEmpty()) return Result.failure(IllegalStateException("原语句为空，无法取更多"))
        if (sqlHasPaginationClause(baseSql)) {
            return Result.failure(IllegalStateException("原查询已含分页子句，无法取更多"))
        }

        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            connectionsState.ensureConnectionReady(profile)
        }
        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            return Result.failure(
                IllegalStateException(connectionsState.statusMessageOf(profile.id) ?: "连接不可用"),
            )
        }
        val session = connectionsState.sessionOf(profile.id)
            ?: return Result.failure(IllegalStateException("连接已断开"))
        val pageSql = DialectRegistry.forProfile(profile)
            .paginate(baseSql, result.rows.size.toLong(), FETCH_MORE_PAGE)
        val contextSql = sessionContextSqlFor(console, profile)

        resultBusy[console.id] = true
        val fetched = withContext(ioDispatcher) {
            runCatching { session.runStatement(pageSql, contextSql) }
        }
        resultBusy[console.id] = false

        return fetched.fold(
            onSuccess = { page ->
                if (!page.isQuery) {
                    Result.failure(IllegalStateException("取更多未返回结果集"))
                } else {
                    val merged = result.copy(
                        rows = result.rows + page.rows,
                        // 满页说明后面可能还有；不满页即已到底
                        truncated = page.rows.size >= FETCH_MORE_PAGE,
                    )
                    val outcomes = ui.outcomes.toMutableList()
                    outcomes[idx] = outcome.copy(result = merged)
                    runSlots[console.id] = ui.copy(outcomes = outcomes)
                    Result.success(page.rows.size)
                }
            },
            onFailure = { t ->
                Logger.error(t, "fetch more failed on {}", profile.name)
                Result.failure(IllegalStateException(friendlySqlError(t)))
            },
        )
    }

    // ---------- 激活 / 切换 ----------

    /** 激活指定控制台（未加载则从 .sql 文件读取，dirty 草稿不丢失）。 */
    fun activate(console: ConsoleRecord) {
        val prev = activeConsole()
        if (prev != null && prev.id != console.id) {
            flushNow(prev.id)
            flushCaretNow(prev.id)
        }
        activeConsoleId = console.id
        lastActivePerProfile[console.connectionId] = console.id
        if (console.id !in loaded) {
            buffers[console.id] = repository.readConsoleContent(console.id)
            loaded += console.id
        }
    }

    /** 激活某数据源的控制台集合：优先已打开的；全被关闭则重开最近改动的；一个都没有则自动建「控制台 1」。 */
    fun activateForProfile(profileId: String): ConsoleRecord? {
        val open = openConsoles(profileId)
        val chosen = if (open.isNotEmpty()) {
            val lastId = lastActivePerProfile[profileId]
            open.firstOrNull { it.id == lastId }
                ?: open.maxByOrNull { it.updatedAt }
                ?: open.first()
        } else {
            // 全部已关闭：重开最近改动的那个（关闭只是隐藏，不丢内容）；从未建过则新建
            val any = profileConsoles(profileId).maxByOrNull { it.updatedAt }
            if (any != null) {
                repository.setConsoleClosed(any.id, false)
                setClosedFlag(any.id, false)
                findConsole(any.id) ?: any
            } else {
                createConsole(profileId, "控制台 1")
            }
        }
        activate(chosen)
        return chosen
    }

    /** 启动回位：无激活控制台时回到最近改动的「已打开」控制台（跨数据源）；一个都没有则返回 null。 */
    fun activateMostRecent(profileIds: List<String>): ConsoleRecord? {
        if (activeConsoleId != null) return activeConsole()
        val best = profileIds.asSequence()
            .flatMap { openConsoles(it).asSequence() }
            .maxByOrNull { it.updatedAt } ?: return null
        activate(best)
        return best
    }

    // ---------- 控制台管理 ----------

    fun createConsole(profileId: String, name: String): ConsoleRecord {
        val rec = repository.createConsole(profileId, name)
        // 直接取缓存追加（不能用 profileConsoles：仓库已插入 rec，getOrPut 会重读导致重复）
        val existing = consolesByConnection[profileId].orEmpty()
        consolesByConnection[profileId] = existing + rec
        loaded += rec.id
        buffers[rec.id] = ""
        activate(rec)
        return rec
    }

    /**
     * 关闭控制台：仅从标签条隐藏（保留元数据行与 .sql 文件，可从数据源右键重新打开）。
     * 若关闭的是当前激活的控制台，则切到同源最后一个已打开控制台（无则停在引导态）。
     */
    fun closeConsole(consoleId: String) {
        val rec = findConsole(consoleId) ?: return
        if (rec.closed) return
        repository.setConsoleClosed(consoleId, true)
        setClosedFlag(consoleId, true)
        if (activeConsoleId == consoleId) {
            flushNow(consoleId)
            flushCaretNow(consoleId)
            activeConsoleId = null
            val list = openConsoles(rec.connectionId)
            val lastId = lastActivePerProfile[rec.connectionId]
            val next = list.firstOrNull { it.id == lastId }
                ?: list.maxByOrNull { it.updatedAt }
                ?: list.firstOrNull()
            if (next != null) activate(next)
        }
    }

    /** 重新打开已关闭的控制台并激活（关闭只隐藏，内容仍在）。已打开则直接激活。 */
    fun reopenConsole(consoleId: String): ConsoleRecord? {
        val rec = findConsole(consoleId) ?: return null
        if (rec.closed) {
            repository.setConsoleClosed(consoleId, false)
            setClosedFlag(consoleId, false)
        }
        val updated = findConsole(consoleId) ?: rec
        activate(updated)
        return updated
    }

    /** 只更新缓存里的 closed 标记（不动 updated_at，不重读库）。 */
    private fun setClosedFlag(consoleId: String, closed: Boolean) {
        val entry = consolesByConnection.entries.firstOrNull { (_, list) -> list.any { it.id == consoleId } } ?: return
        consolesByConnection[entry.key] = entry.value.map {
            if (it.id == consoleId) it.copy(closed = closed) else it
        }
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

    /** 设置控制台的执行目标库/schema（立即落库；"" = 连接默认不切换）。 */
    fun setTarget(consoleId: String, target: String) {
        repository.setConsoleTarget(consoleId, target)
        val entry = consolesByConnection.entries.firstOrNull { (_, list) -> list.any { it.id == consoleId } }
        if (entry != null) {
            consolesByConnection[entry.key] = entry.value.map {
                if (it.id == consoleId) it.copy(target = target) else it
            }
        }
    }

    /** 删除控制台（行 + .sql 文件）；若是当前激活则切到同源另一控制台（无则新建）。 */
    fun deleteConsole(consoleId: String) {
        val rec = consolesByConnection.values.asSequence().flatMap { it.asSequence() }
            .firstOrNull { it.id == consoleId } ?: return
        saveJobs.remove(consoleId)?.cancel()
        caretJobs.remove(consoleId)?.cancel()
        caretDrafts.remove(consoleId)
        repository.deleteConsole(consoleId)
        buffers.remove(consoleId)
        loaded.remove(consoleId)
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        runSlots.remove(consoleId)
        editBuffers.remove(consoleId)
        editPlans.remove(consoleId)
        resultBusy.remove(consoleId)
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
        // 先判断后清理：activeConsole() 依赖 consolesByConnection 解析 id，删了会查不到
        val activeWasInProfile = activeConsole()?.connectionId == profileId
        consolesByConnection.remove(profileId)?.forEach { rec ->
            saveJobs.remove(rec.id)?.cancel()
            caretJobs.remove(rec.id)?.cancel()
            caretDrafts.remove(rec.id)
            buffers.remove(rec.id)
            loaded.remove(rec.id)
            dirtyConsoleIds = dirtyConsoleIds - rec.id
            runSlots.remove(rec.id)
            editBuffers.remove(rec.id)
            editPlans.remove(rec.id)
            resultBusy.remove(rec.id)
            runGens.remove(rec.id)
            runStartedAt.remove(rec.id)
            runTarget.remove(rec.id)
            lastStableSlots.remove(rec.id)
        }
        historyByProfile.remove(profileId)
        if (activeWasInProfile) {
            activeConsoleId = null
        }
    }

    // ---------- 文本编辑 / 自动保存 ----------

    /** 编辑器文本变更：更新内存缓冲，防抖自动写回绑定的 .sql 文件。 */
    fun setText(consoleId: String, text: String) {
        // 点击/光标移动可能以同文案上报（见 SqlWorkspace onValueChange 转发层的守卫）；
        // 此处再兜底：内容没变不置脏、不起防抖任务（否则点一下编辑器就变“未保存”）。
        if (buffers[consoleId] == text) return
        buffers[consoleId] = text
        dirtyConsoleIds = dirtyConsoleIds + consoleId
        saveJobs.remove(consoleId)?.cancel()
        saveJobs[consoleId] = scope.launch {
            delay(AUTOSAVE_MS)
            withContext(ioDispatcher) { flushNow(consoleId) }
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
            withContext(ioDispatcher) { flushNow(consoleId) }
        }
    }

    fun flushAllSync() {
        dirtyConsoleIds.toList().forEach { flushNow(it) }
        caretDrafts.keys.toList().forEach { flushCaretNow(it) }
    }

    // ---------- 光标记忆 / 持久化：每个控制台记住上次焦点所在行 ----------

    /**
     * 该控制台上次离开时的光标/选区（内存草稿优先；无记录 = (0,0) 从头开始）。
     * 重启后由 consoles.caret_start/caret_end 恢复，UI 层据此居中展示该行。
     */
    fun caretOf(consoleId: String): Pair<Int, Int> =
        caretDrafts[consoleId] ?: findConsole(consoleId)?.let { it.caretStart to it.caretEnd } ?: (0 to 0)

    /**
     * 光标/选区变化：只更新内存 + 重置防抖落库任务（连续点击/输入不会条条写库）。
     * 强制落库点：切控制台（[activate]）、退出（[flushAllSync]）。
     */
    fun setCaret(consoleId: String, start: Int, end: Int) {
        val pair = start to end
        if (caretDrafts[consoleId] == pair) return
        caretDrafts[consoleId] = pair
        caretJobs.remove(consoleId)?.cancel()
        caretJobs[consoleId] = scope.launch {
            delay(CARET_SAVE_MS)
            withContext(ioDispatcher) { flushCaretNow(consoleId) }
        }
    }

    /** 光标立即落库，并同步内存元数据行（否则草稿清掉后 caretOf 会读回旧值）。 */
    private fun flushCaretNow(consoleId: String) {
        val c = caretDrafts.remove(consoleId) ?: return
        runCatching { repository.setConsoleCaret(consoleId, c.first, c.second) }
            .onFailure { Logger.error(it, "console caret save failed {}", consoleId) }
        val entry = consolesByConnection.entries.firstOrNull { (_, list) -> list.any { it.id == consoleId } }
        if (entry != null) {
            consolesByConnection[entry.key] = entry.value.map {
                if (it.id == consoleId) it.copy(caretStart = c.first, caretEnd = c.second) else it
            }
        }
    }

    // ---------- 执行 ----------

    /**
     * 解析控制台已设目标库/schema 的执行前导 SQL（USE / SET search_path…）。
     * 目标为空、方言不支持切换、或当前目录快照里找不到同名库/schema 时返回 null
     * （不切换，用连接默认）。目录需已加载（连接后）；加载中时宁可不动也不误切。
     */
    fun sessionContextSqlFor(console: ConsoleRecord, profile: db.ConnectionProfile): String? {
        // 「命名空间即过滤器」（Redis DB）：执行目标 = 连接级当前 DB，与 console.target 解耦
        if (connectionsState.flatNamespaceOf(profile.id)) {
            val ns = connectionsState.activeNamespaceOf(profile.id) ?: return null
            return connectionsState.sessionOf(profile.id)?.sessionContextSql(ns)
        }
        val t = console.target
        if (t.isBlank()) return null
        val schemas = connectionsState.schemasOf(profile.id)
        if (schemas == null) return null
        val hit = schemas.firstOrNull { it.displayName.equals(t, ignoreCase = true) }
        if (hit == null) {
            Logger.info(
                "console target '{}' not found in schemas of {}; run with connection default",
                t, profile.name,
            )
            return null
        }
        return connectionsState.sessionOf(profile.id)?.sessionContextSql(hit)
    }

    /**
     * 执行控制台 SQL（目标 = 控制台绑定的 profile；profile 由调用方从档案列表解析）。
     * @param sql 待执行语句；null 时取控制台缓冲全文（预览/程序化执行用）。
     *   交互层规则：编辑器无选中文本时禁止全量执行，因此 UI 一律传选中片段。
     * 选中片段按 `;` 拆成多条语句依次执行（忽略字符串/注释内的分号与仅含注释的片段），
     * 每条语句一个结果，多语句时结果区多 Tab 展示；某条出错即停（后续不执行）。
     * 连接未就绪先补连/重连；同源所有 JDBC 都经 LiveConnection 单线程执行器串行。
     *
     * 执行期登记当前 Statement 供取消；每条语句成功/失败都会写 sql_history。
     * 代次守卫：取消或新执行会 bump runGens，使本 run 的迟到结果被丢弃（不覆盖新状态）。
     */
    suspend fun run(console: ConsoleRecord, profile: db.ConnectionProfile, sql: String? = null) {
        // 同控制台不允许叠加执行（执行中兜底）
        if (runSlots[console.id]?.executing == true) return
        // 新执行 → 丢弃旧的未提交修改/编辑计划（UI 已在有修改时先确认）
        clearEdits(console.id)
        editPlans.remove(console.id)
        withContext(ioDispatcher) { flushNow(console.id) }
        val target = sql?.trim().orEmpty().ifEmpty { textOf(console.id).trim() }
        if (target.isEmpty()) {
            runSlots[console.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome(sql = "", error = "请输入要执行的 SQL")),
            )
            return
        }
        val statements = SessionFactory.splitStatements(profile, target)
        if (statements.isEmpty()) {
            runSlots[console.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome(sql = target, error = "没有可执行的 SQL（选中内容全是注释/空白）")),
            )
            return
        }
        val status = connectionsState.statusOf(profile.id)
        if (status != ConnUiStatus.CONNECTED) {
            connectionsState.ensureConnectionReady(profile)
        }
        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            runSlots[console.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome(
                    sql = target,
                    error = connectionsState.statusMessageOf(profile.id) ?: "连接不可用",
                )),
            )
            return
        }
        val session = connectionsState.sessionOf(profile.id)
        if (session == null) {
            runSlots[console.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome(sql = target, error = "连接已断开")),
            )
            return
        }
        // 控制台已设目标库/schema：每条语句执行前先切会话（USE / SET search_path…），保证复切/交错执行也生效
        val contextSql = sessionContextSqlFor(console, profile)
        runGens[console.id] = (runGens[console.id] ?: 0L) + 1
        val gen = runGens.getValue(console.id)
        lastStableSlots[console.id] = runSlots[console.id] ?: ConsoleRunUi()
        runStartedAt[console.id] = System.currentTimeMillis()
        runTarget[console.id] = target
        runSlots[console.id] = ConsoleRunUi(executing = true)

        // 逐条执行；出错即停（后续语句不执行），每条独立写历史
        val outcomes = mutableListOf<StatementOutcome>()
        for (stmt in statements) {
            // Redis 危险命令：执行前二次确认（由 UI 注入钩子）。
            val danger = if (session.protocol == Protocol.REDIS) RedisProtocol.dangerousCommand(stmt) else null
            if (danger != null && confirmDangerous?.invoke(danger) == false) {
                outcomes += StatementOutcome(stmt, error = "已取消执行危险命令「$danger」")
                break
            }
            val stmtStarted = System.currentTimeMillis()
            val result = withContext(ioDispatcher) {
                runCatching { session.runStatement(stmt, contextSql) }
            }
            if (runGens[console.id] != gen) return // 已被取消/新执行覆盖：丢弃迟到结果
            result.onSuccess { r ->
                outcomes += StatementOutcome(stmt, result = r)
                recordHistory(
                    profileId = profile.id, sql = stmt, ok = true,
                    durationMs = r.durationMs, rowCount = r.affectedRows ?: r.rowCount,
                )
            }.onFailure { t ->
                Logger.error(t, "query failed on {}", profile.name)
                val msg = friendlySqlError(t)
                outcomes += StatementOutcome(stmt, error = msg)
                recordHistory(
                    profileId = profile.id, sql = stmt, ok = false,
                    durationMs = System.currentTimeMillis() - stmtStarted, rowCount = 0, error = msg,
                )
                break
            }
        }

        if (runGens[console.id] != gen) return
        val startedAt = runStartedAt.remove(console.id)
        runTarget.remove(console.id)
        val durationMs = startedAt?.let { System.currentTimeMillis() - it } ?: 0L
        val errIdx = outcomes.indexOfFirst { !it.ok }
        runSlots[console.id] = ConsoleRunUi(
            outcomes = outcomes,
            activeIndex = if (errIdx >= 0) errIdx else 0,
            ranMs = durationMs,
        )
        // 结果落地后计算可编辑计划（无基表/无主键/视图 → 保持 null）
        ensureEditPlan(console, profile)
    }

    /** 切换结果区当前展示的语句（多语句时 Tab 选择）。 */
    fun selectRunOutcome(consoleId: String, index: Int) {
        val cur = runSlots[consoleId] ?: return
        if (index in cur.outcomes.indices && index != cur.activeIndex) {
            // 未提交修改只对应当前 Tab 的结果集，切 Tab 即丢弃（UI 已在有修改时先确认）
            clearEdits(consoleId)
            editPlans.remove(consoleId)
            runSlots[consoleId] = cur.copy(activeIndex = index)
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
        // 光标落库防抖窗口：光标/选区变化远频于文本改动，单独一个更短的窗口（内存即时生效）。
        private const val CARET_SAVE_MS = 1500L
        // N1「取更多」每页行数（< QueryExecutor.MAX_ROWS，保证单页不被截断）
        private const val FETCH_MORE_PAGE = 500
        private const val HISTORY_PANEL_LIMIT = 100
    }
}
