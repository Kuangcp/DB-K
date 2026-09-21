package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ui.CellKey
import app.ui.EditPlan
import app.ui.PendingInsert
import app.ui.ResultEdits
import app.ui.buildEditPlan
import app.ui.buildWriteOps
import app.ui.sqlHasPaginationClause
import db.ConsoleFiles
import db.ConsoleRecord
import db.ConnectionsRepository
import db.SqlHistoryRow
import engine.Protocol
import engine.model.QueryColumn
import engine.model.QueryResult
import i18n.I18n
import i18n.Str
import java.nio.file.Files
import java.nio.file.Path
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

/** 外部文件当前的问题态：冲突（磁盘变了且本地脏）/ 缺失（磁盘文件不存在）。 */
enum class ExternalFileIssue { CONFLICT, MISSING }

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

/** Redis 结果视图的键元数据（双击预览时记录；瞬态，随新执行清除）。 */
data class RedisKeyMeta(
    val key: String,
    val type: String?,
    val ttlSeconds: Long?,
)

class ConsoleState(
    private val repository: ConnectionsRepository,
    private val connectionsState: ConnectionsState,
    /** 工作区状态（控制台的虚拟分组）：关闭=移出当前工作区，成员决定标签条。 */
    val workspaces: WorkspaceState,
    private val scope: CoroutineScope,
    /** 慢操作调度器；测试注入虚拟时间调度器以确定性推进防抖/执行。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 监听器（默认真实实现；测试注入 fake）。 */
    fileWatcher: FileWatcher? = null,
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

    /** consoleId -> 最近一次双击预览的 Redis 键元数据（结果视图 header 用）。 */
    val redisKeyMetas = mutableStateMapOf<String, RedisKeyMeta>()

    /** 外部文件当前问题态（冲突/缺失）；无键 = 正常。 */
    val externalIssues = mutableStateMapOf<String, ExternalFileIssue>()

    /** 外部文件内容重载信号：EditorArea 据此把权威缓冲刷进 TextFieldState。 */
    private val textRevisions = mutableStateMapOf<String, Int>()

    /** 外部文件上次载入/写入的内容，用于忽略自身写入。 */
    private val lastDiskContent = mutableMapOf<String, String>()

    /** 规范化路径 → consoleId（监听反向表）。 */
    private val pathToConsole = mutableMapOf<String, String>()

    private val watcher: FileWatcher = fileWatcher ?: ExternalFileWatcher(onChange = { path ->
        scope.launch { handleExternalChange(path) }
    })

    /**
     * 结果网格未提交写操作（本地 overlay）：consoleId → 单元格改值 / 待插入行 / 待删除行。
     * 纯瞬态，不落盘；新执行/刷新/切 Tab 即清（有修改时 UI 先确认）。
     */
    val editBuffers = mutableStateMapOf<String, ResultEdits>()

    /** 待插入行的临时 id 源（会话内自增，不落盘）。 */
    private var insertSeq = 0L

    /** 当前结果集的编辑计划（表/主键/可编辑列）；null = 不可编辑（视图/无主键/表达式）。 */
    val editPlans = mutableStateMapOf<String, EditPlan?>()

    /** 正在提交/刷新某控制台的结果（禁用按钮）。 */
    val resultBusy = mutableStateMapOf<String, Boolean>()

    /** Ctrl+Tab 循环会话：首次按下取「已打开控制台」快照，松开 Ctrl 清空。 */
    private var mruCycleIds: List<String>? = null
    private var mruCycleIndex: Int = 0

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

    private fun findConsole(consoleId: String): ConsoleRecord? {
        consolesByConnection.values.forEach { list ->
            list.firstOrNull { it.id == consoleId }?.let { return it }
        }
        // 未缓存（如冷启动由工作区成员反查）：读档案后整表载入该数据源缓存，避免部分缓存。
        val rec = repository.getConsole(consoleId) ?: return null
        return profileConsoles(rec.connectionId).firstOrNull { it.id == consoleId }
    }

    fun profileConsoles(profileId: String): List<ConsoleRecord> =
        consolesByConnection.getOrPut(profileId) { repository.listConsoles(profileId) }

    /** 当前工作区中该数据源的控制台（标签顺序）。 */
    fun openConsoles(profileId: String): List<ConsoleRecord> =
        allOpenConsoles(listOf(profileId))

    /** 工作台级全部控制台（含未入任何工作区的；树右键「打开控制台」级联用）。 */
    fun allConsoles(profileIds: List<String>): List<ConsoleRecord> {
        val out = ArrayList<ConsoleRecord>()
        profileIds.forEach { pid -> out += profileConsoles(pid) }
        return out
    }

    /** 当前工作区成员（按成员顺序，跨数据源）——标签条用。 */
    fun allOpenConsoles(profileIds: List<String>): List<ConsoleRecord> {
        val ws = workspaces.activeWorkspace() ?: return emptyList()
        val allowed = profileIds.toHashSet()
        return workspaces.memberIds(ws.id).asSequence()
            .mapNotNull { findConsole(it) }
            .filter { it.connectionId in allowed }
            .toList()
    }

    fun textOf(consoleId: String): String = buffers[consoleId] ?: ""

    fun runStateOf(consoleId: String): ConsoleRunUi = runSlots[consoleId] ?: ConsoleRunUi()

    fun redisKeyMetaOf(consoleId: String): RedisKeyMeta? = redisKeyMetas[consoleId]

    fun setRedisKeyMeta(consoleId: String, meta: RedisKeyMeta) {
        redisKeyMetas[consoleId] = meta
    }

    // ---------- 外部文件控制台：路径规范化 / 查询 / 问题态 ----------

    /** 仓库唯一性用的规范化路径：存在时消解符号链接（toRealPath），否则绝对规范化。 */
    private fun normalizePath(path: Path): String =
        runCatching { path.toRealPath().toString() }
            .getOrElse { path.toAbsolutePath().normalize().toString() }

    /** 监听反向表用的键：绝对规范化（与 ExternalFileWatcher 回调路径同源）。 */
    private fun absKey(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun resolveConsoleId(path: Path): String? =
        pathToConsole[absKey(path)]
            ?: repository.getConsoleByPath(absKey(path))?.id
            ?: repository.getConsoleByPath(normalizePath(path))?.id

    fun consoleByPath(path: Path): ConsoleRecord? = repository.getConsoleByPath(normalizePath(path))

    fun externalIssueOf(consoleId: String): ExternalFileIssue? = externalIssues[consoleId]

    fun textRevisionOf(consoleId: String): Int = textRevisions[consoleId] ?: 0

    // ---------- 结果单元格编辑（本地 overlay → 提交写回） ----------

    fun editsOf(consoleId: String): ResultEdits = editBuffers[consoleId] ?: ResultEdits.EMPTY

    fun editCount(consoleId: String): Int = editsOf(consoleId).count

    fun editPlanOf(consoleId: String): EditPlan? = editPlans[consoleId]

    fun resultBusyOf(consoleId: String): Boolean = resultBusy[consoleId] == true

    /** 暂存一格修改（仅内存；重复点同一格覆盖）。 */
    fun setCellEdit(consoleId: String, key: CellKey, value: CellValue) {
        val cur = editsOf(consoleId)
        editBuffers[consoleId] = cur.copy(cells = cur.cells + (key to value))
    }

    /** 撤销单格修改。 */
    fun clearCellEdit(consoleId: String, key: CellKey) {
        val cur = editBuffers[consoleId] ?: return
        if (key !in cur.cells) return
        putOrClear(consoleId, cur.copy(cells = cur.cells - key))
    }

    /** 追加一个待插入行，返回其临时 id；该行默认所有列未填（提交时走库默认值）。 */
    fun addPendingInsert(consoleId: String): Long {
        insertSeq += 1
        val id = insertSeq
        val cur = editsOf(consoleId)
        editBuffers[consoleId] = cur.copy(inserts = cur.inserts + PendingInsert(id))
        return id
    }

    /** 移除一个待插入行。 */
    fun removePendingInsert(consoleId: String, id: Long) {
        val cur = editBuffers[consoleId] ?: return
        if (cur.inserts.none { it.id == id }) return
        putOrClear(consoleId, cur.copy(inserts = cur.inserts.filterNot { it.id == id }))
    }

    /** 填写某待插入行的一列（结果列下标）。 */
    fun setPendingInsertCell(consoleId: String, id: Long, col: Int, value: CellValue) {
        val cur = editsOf(consoleId)
        val next = cur.inserts.map { if (it.id == id) it.copy(values = it.values + (col to value)) else it }
        editBuffers[consoleId] = cur.copy(inserts = next)
    }

    /** 清除某待插入行一列的填写（恢复为未填，走库默认值）。 */
    fun clearPendingInsertCell(consoleId: String, id: Long, col: Int) {
        val cur = editsOf(consoleId)
        val next = cur.inserts.map { if (it.id == id) it.copy(values = it.values - col) else it }
        putOrClear(consoleId, cur.copy(inserts = next))
    }

    /**
     * 标记一行待删除（原始行下标）；同时丢弃该行的单元格修改（避免既改又删）。
     */
    fun markRowDeleted(consoleId: String, row: Int) {
        val cur = editsOf(consoleId)
        val cells = cur.cells.filterKeys { it.row != row }
        editBuffers[consoleId] = cur.copy(cells = cells, deletes = cur.deletes + row)
    }

    /** 撤销某行的待删除标记。 */
    fun restoreRow(consoleId: String, row: Int) {
        val cur = editBuffers[consoleId] ?: return
        if (row !in cur.deletes) return
        putOrClear(consoleId, cur.copy(deletes = cur.deletes - row))
    }

    /** 丢弃某控制台全部未提交修改。 */
    fun clearEdits(consoleId: String) {
        editBuffers.remove(consoleId)
    }

    /** 全部控制台的未提交修改总数（退出前守卫用）。 */
    fun totalEditCount(): Int = editBuffers.values.sumOf { it.count }

    /** overlay 空则移除键，避免无意义状态残留。 */
    private fun putOrClear(consoleId: String, next: ResultEdits) {
        if (next.isEmpty) editBuffers.remove(consoleId) else editBuffers[consoleId] = next
    }

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
        val ui = runSlots[console.id] ?: return Result.failure(IllegalStateException(I18n.t(Str.ConsoleNoResult)))
        if (ui.executing || resultBusyOf(console.id)) return Result.failure(IllegalStateException(I18n.t(Str.ConsoleRunning)))
        val result = ui.result ?: return Result.failure(IllegalStateException(I18n.t(Str.ConsoleNoResult)))
        val plan = editPlanOf(console.id) ?: return Result.failure(IllegalStateException(I18n.t(Str.ConsoleNotEditable)))
        val edits = editsOf(console.id)
        if (edits.isEmpty) return Result.success(0)
        // 待插入行必须至少填一个可编辑列，否则无法生成 INSERT（不能静默丢弃）
        val blankInserts = edits.inserts.count { !edits.insertHasValues(plan, it) }
        if (blankInserts > 0) {
            return Result.failure(IllegalStateException(I18n.t(Str.ConsoleBlankInserts, blankInserts)))
        }
        val ops = buildWriteOps(result, plan, edits)
        if (ops.isEmpty()) return Result.failure(IllegalStateException(I18n.t(Str.ConsoleNoChanges)))

        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            connectionsState.ensureConnectionReady(profile)
        }
        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            return Result.failure(
                IllegalStateException(connectionsState.statusMessageOf(profile.id) ?: I18n.t(Str.ConsoleConnectionUnavailable)),
            )
        }
        val session = connectionsState.sessionOf(profile.id)
            ?: return Result.failure(IllegalStateException(I18n.t(Str.ConsoleConnectionClosed)))
        if (session !is jdbc.EditableSession || !session.capabilities.editableResult) {
            return Result.failure(IllegalStateException(I18n.t(Str.ConsoleWriteNotSupported)))
        }
        val dialect = DialectRegistry.forProfile(profile)
        val contextSql = sessionContextSqlFor(console, profile)

        resultBusy[console.id] = true
        val outcome = withContext(ioDispatcher) {
            runCatching { session.applyWriteOps(ops, contextSql) }
        }
        resultBusy[console.id] = false

        return outcome.fold(
            onSuccess = { count ->
                ops.forEach { op ->
                    recordHistory(profile.id, RowUpdater.renderWriteSql(op, dialect), true, 0, 1)
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
     * P7：提交前预览——把暂存写操作（UPDATE / INSERT / DELETE）渲染为可读语句（不执行、不改状态）。
     * 返回空列表表示没有可预览内容（无修改 / 不可编辑 / 待插入行尚未填写）。
     */
    fun previewCommit(console: ConsoleRecord, profile: db.ConnectionProfile): List<String> {
        val ui = runSlots[console.id] ?: return emptyList()
        val result = ui.result ?: return emptyList()
        val plan = editPlanOf(console.id) ?: return emptyList()
        val edits = editsOf(console.id)
        if (edits.isEmpty) return emptyList()
        val dialect = DialectRegistry.forProfile(profile)
        return buildWriteOps(result, plan, edits).map { RowUpdater.renderWriteSql(it, dialect) }
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

    /** 当前激活结果是否还能「取更多」（被截断，且后端支持改写语句）。 */
    fun canFetchMore(consoleId: String): Boolean {
        val ui = runSlots[consoleId] ?: return false
        val active = ui.active ?: return false
        val result = active.result ?: return false
        if (ui.executing || resultBusyOf(consoleId)) return false
        if (!result.isQuery || !result.truncated) return false
        val profileId = findConsole(consoleId)?.connectionId ?: return false
        val session = connectionsState.sessionOf(profileId) ?: return false
        if (!session.capabilities.fetchMore) return false
        // JDBC：原查询已含分页子句时不再二次注入（避免嵌套 LIMIT/TOP 歧义）
        if (session.protocol == Protocol.JDBC && sqlHasPaginationClause(active.sql)) return false
        return true
    }

    /**
     * 取更多：由后端把当前语句改写为取下一页（JDBC 注入 `LIMIT/OFFSET`；ES 设 DSL `from/size`），
     * 取 [FETCH_MORE_PAGE] 行**追加**到当前结果（不替换），满页说明可能还有、不满页则到底。返回实际追加行数。
     * 原查询无 ORDER BY 时由 UI 提示「顺序不保证」。
     */
    suspend fun fetchMore(console: ConsoleRecord, profile: db.ConnectionProfile): Result<Int> {
        val ui = runSlots[console.id] ?: return Result.failure(IllegalStateException(I18n.t(Str.AppendNoResult)))
        if (ui.executing || resultBusyOf(console.id)) {
            return Result.failure(IllegalStateException(I18n.t(Str.AppendRunning)))
        }
        val idx = ui.activeIndex
        val outcome = ui.outcomes.getOrNull(idx) ?: return Result.failure(IllegalStateException(I18n.t(Str.AppendNoResult)))
        val result = outcome.result ?: return Result.failure(IllegalStateException(I18n.t(Str.AppendNoResult)))
        if (!result.isQuery) return Result.failure(IllegalStateException(I18n.t(Str.AppendNotQuery)))
        val baseSql = outcome.sql.trim().trimEnd(';').trim()
        if (baseSql.isEmpty()) return Result.failure(IllegalStateException(I18n.t(Str.AppendEmptySql)))
        if (sqlHasPaginationClause(baseSql) && profile.dbType.protocol == Protocol.JDBC) {
            return Result.failure(IllegalStateException(I18n.t(Str.AppendHasPaging)))
        }

        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            connectionsState.ensureConnectionReady(profile)
        }
        if (connectionsState.statusOf(profile.id) != ConnUiStatus.CONNECTED) {
            return Result.failure(
                IllegalStateException(connectionsState.statusMessageOf(profile.id) ?: I18n.t(Str.ConsoleConnectionUnavailable)),
            )
        }
        val session = connectionsState.sessionOf(profile.id)
            ?: return Result.failure(IllegalStateException(I18n.t(Str.ConsoleConnectionClosed)))
        val pageSql = session.paginate(baseSql, result.rows.size.toLong(), FETCH_MORE_PAGE)
            ?: return Result.failure(IllegalStateException(I18n.t(Str.AppendNotSupported)))
        val contextSql = sessionContextSqlFor(console, profile)

        resultBusy[console.id] = true
        val fetched = withContext(ioDispatcher) {
            runCatching { session.runStatement(pageSql, contextSql) }
        }
        resultBusy[console.id] = false

        return fetched.fold(
            onSuccess = { page ->
                if (!page.isQuery) {
                    Result.failure(IllegalStateException(I18n.t(Str.AppendNoResultSet)))
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
        workspaces.activeWorkspace()?.let { ws ->
            workspaces.touch(ws.id, console.id)
            workspaces.setLastActive(ws.id, console.id)
        }
        if (console.id !in loaded) {
            val text = repository.readConsoleContent(console.id)
            buffers[console.id] = text
            loaded += console.id
            if (console.external) {
                val p = Path.of(console.filePath)
                lastDiskContent[console.id] = text
                pathToConsole[absKey(p)] = console.id
                watcher.watch(p)
                if (Files.isRegularFile(p)) externalIssues.remove(console.id)
                else externalIssues[console.id] = ExternalFileIssue.MISSING
            }
        }
    }

    /**
     * 打开某数据源的控制台（双击数据源 / 标题栏切换 / 右键「打开控制台」共用）：
     * 1. 当前工作区内已有该源控制台 → 激活 MRU 最前的；
     * 2. 否则取该源 `updated_at` 最大的控制台加入当前工作区并激活；
     * 3. 该源一个都没有 → 新建（新建即入区）。
     */
    fun activateForProfile(profileId: String): ConsoleRecord? {
        val ws = workspaces.ensureActive()
        val orderedInWs = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
            .filter { it.connectionId == profileId }
        if (orderedInWs.isNotEmpty()) {
            val chosen = orderedInWs.first()
            activate(chosen)
            return chosen
        }
        // updated_at 是毫秒级：“最近改动”取最大；同毫秒并列时用 sort_order（后建的更大）做确定性 tie-break
        val any = profileConsoles(profileId).maxWithOrNull(compareBy({ it.updatedAt }, { it.sortOrder }))
        if (any != null) {
            workspaces.addMember(ws.id, any.id)
            activate(any)
            return any
        }
        return createConsole(profileId, I18n.t(Str.ConsoleDefaultName, 1))
    }

    /** 启动回位：恢复当前工作区上次激活的控制台（否则该区 updated_at 最大者）。 */
    fun activateMostRecent(profileIds: List<String>): ConsoleRecord? {
        if (activeConsoleId != null) return activeConsole()
        val ws = workspaces.activeWorkspace() ?: return null
        val allowed = profileIds.toHashSet()
        val members = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
            .filter { it.connectionId in allowed }
        val last = workspaces.lastActiveConsoleOf(ws.id)?.let { id -> members.firstOrNull { it.id == id } }
        val chosen = last ?: members.maxWithOrNull(compareBy({ it.updatedAt }, { it.sortOrder })) ?: return null
        activate(chosen)
        return chosen
    }

    /**
     * Ctrl+Tab 循环切换：在当前工作区「已打开控制台按最近使用排序」的快照上前进/后退（[delta] = ±1），回绕。
     * 首次按下取快照，之后连续按都在同一快照上走；[endMruCycle] 在 Ctrl 松开时清快照。
     */
    fun switchConsoleByMru(delta: Int): ConsoleRecord? {
        val open = openConsolesByMru()
        if (open.size <= 1) return null
        val openIds = open.map { it.id }
        var ids = mruCycleIds
        if (ids == null || ids.any { it !in openIds }) {
            ids = openIds
            mruCycleIds = ids
            mruCycleIndex = ids.indexOf(activeConsoleId).let { if (it < 0) 0 else it }
        }
        mruCycleIndex = ((mruCycleIndex + delta) % ids.size + ids.size) % ids.size
        val target = open.firstOrNull { it.id == ids[mruCycleIndex] } ?: return null
        activate(target)
        return target
    }

    /** Ctrl 松开：结束 MRU 循环会话（下次按下重新取快照）。 */
    fun endMruCycle() {
        mruCycleIds = null
    }

    /** 切换工作区后：flush 旧控制台，激活新工作区的上次激活控制台（否则 updated_at 最大者）。 */
    fun onWorkspaceSwitched() {
        val prev = activeConsole()
        if (prev != null) {
            flushNow(prev.id)
            flushCaretNow(prev.id)
        }
        activeConsoleId = null
        mruCycleIds = null
        val ws = workspaces.activeWorkspace() ?: return
        val members = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
        val last = workspaces.lastActiveConsoleOf(ws.id)?.let { id -> members.firstOrNull { it.id == id } }
        (last ?: members.maxWithOrNull(compareBy({ it.updatedAt }, { it.sortOrder })))?.let { activate(it) }
    }

    /** 最后一个工作区被删除：进入零工作区引导态。 */
    fun onAllWorkspacesGone() {
        val prev = activeConsole()
        if (prev != null) {
            flushNow(prev.id)
            flushCaretNow(prev.id)
        }
        activeConsoleId = null
        mruCycleIds = null
    }

    /** 当前工作区里激活下一个控制台（优先同数据源）；没有则保持 null。 */
    private fun activateNextInWorkspace(preferProfileId: String? = null): ConsoleRecord? {
        val ws = workspaces.activeWorkspace() ?: return null
        val ordered = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
        val next = preferProfileId?.let { pid -> ordered.firstOrNull { it.connectionId == pid } }
            ?: ordered.firstOrNull()
        if (next != null) activate(next)
        return next
    }

    /** 当前工作区成员按最近使用排序（最新在前）；从未激活过的按标签顺序补到末尾。 */
    private fun openConsolesByMru(): List<ConsoleRecord> {
        val ws = workspaces.activeWorkspace() ?: return emptyList()
        return workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
    }

    // ---------- 控制台管理 ----------

    fun createConsole(profileId: String, name: String): ConsoleRecord {
        val rec = repository.createConsole(profileId, name)
        // 直接取缓存追加（不能用 profileConsoles：仓库已插入 rec，getOrPut 会重读导致重复）
        val existing = consolesByConnection[profileId].orEmpty()
        consolesByConnection[profileId] = existing + rec
        loaded += rec.id
        buffers[rec.id] = ""
        val ws = workspaces.ensureActive()
        workspaces.addMember(ws.id, rec.id)
        activate(rec)
        return rec
    }

    /**
     * 打开一个外部 .sql 文件为控制台（选择框 / 拖拽入口）：按规范化路径复用已有记录，
     * 未命中才新建（不覆盖文件内容）。加入当前工作区并激活、开始监听。
     */
    fun openExternalFile(profileId: String, path: Path): ConsoleRecord {
        val normalized = normalizePath(path)
        val existing = repository.getConsoleByPath(normalized)
        val rec = if (existing != null) {
            val list = profileConsoles(existing.connectionId)
            if (list.none { it.id == existing.id }) {
                consolesByConnection[existing.connectionId] = list + existing
            }
            existing
        } else {
            val created = repository.createExternalConsole(profileId, path.fileName.toString(), normalized)
            consolesByConnection[profileId] = consolesByConnection[profileId].orEmpty() + created
            created
        }
        val ws = workspaces.ensureActive()
        if (!workspaces.contains(ws.id, rec.id)) workspaces.addMember(ws.id, rec.id)
        activate(rec)
        return rec
    }

    /** 监听回调（已在 UI 线程）：缺文件标记缺失；否则与上次磁盘内容比对，干净重载、脏则冲突。 */
    fun handleExternalChange(path: Path) {
        val consoleId = resolveConsoleId(path) ?: return
        val p = Path.of(findConsole(consoleId)?.filePath ?: path.toString())
        if (!Files.isRegularFile(p)) {
            externalIssues[consoleId] = ExternalFileIssue.MISSING
            return
        }
        val disk = ConsoleFiles.read(p)
        if (disk == lastDiskContent[consoleId]) return
        if (consoleId !in dirtyConsoleIds) {
            buffers[consoleId] = disk
            lastDiskContent[consoleId] = disk
            externalIssues.remove(consoleId)
            textRevisions[consoleId] = (textRevisions[consoleId] ?: 0) + 1
        } else {
            externalIssues[consoleId] = ExternalFileIssue.CONFLICT
        }
    }

    /** 冲突：载入磁盘版本（丢弃本地、清脏、取消防抖任务）。 */
    fun reloadFromDisk(consoleId: String) {
        val rec = findConsole(consoleId) ?: return
        saveJobs.remove(consoleId)?.cancel()
        val disk = ConsoleFiles.read(Path.of(rec.filePath))
        buffers[consoleId] = disk
        lastDiskContent[consoleId] = disk
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        externalIssues.remove(consoleId)
        textRevisions[consoleId] = (textRevisions[consoleId] ?: 0) + 1
    }

    /** 冲突：保留我的（保持脏，下次落盘覆盖磁盘）。 */
    fun keepLocal(consoleId: String) {
        externalIssues.remove(consoleId)
    }

    /** 缺失：按原路径写回缓冲；父目录不存在返回 false。 */
    fun recreateExternalFile(consoleId: String): Boolean {
        val rec = findConsole(consoleId) ?: return false
        val p = Path.of(rec.filePath)
        if (p.parent == null || !Files.isDirectory(p.parent)) return false
        val text = buffers[consoleId] ?: ""
        return runCatching {
            ConsoleFiles.write(p, text)
            lastDiskContent[consoleId] = text
            dirtyConsoleIds = dirtyConsoleIds - consoleId
            externalIssues.remove(consoleId)
        }.isSuccess
    }

    /** 「另存为…」：写缓冲到新路径并重绑控制台；新路径被其他控制台占用返回 false。 */
    fun rebindExternalFile(consoleId: String, newPath: Path): Boolean {
        val rec = findConsole(consoleId) ?: return false
        val normalized = normalizePath(newPath)
        if (repository.getConsoleByPath(normalized)?.id?.let { it != consoleId } == true) return false
        val text = buffers[consoleId] ?: ""
        val ok = runCatching { ConsoleFiles.write(Path.of(normalized), text) }.isSuccess
        if (!ok) return false
        repository.rebindConsoleFile(consoleId, normalized)
        val oldPath = Path.of(rec.filePath)
        watcher.unwatch(oldPath)
        pathToConsole.remove(absKey(oldPath))
        pathToConsole[absKey(Path.of(normalized))] = consoleId
        watcher.watch(Path.of(normalized))
        lastDiskContent[consoleId] = text
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        externalIssues.remove(consoleId)
        val entry = consolesByConnection.entries.firstOrNull { (_, l) -> l.any { it.id == consoleId } }
        if (entry != null) {
            consolesByConnection[entry.key] = entry.value.map {
                if (it.id == consoleId) it.copy(filePath = normalized) else it
            }
        }
        return true
    }

    /** 启动：文件已丢失的外部控制台从所有工作区移除成员（等价关闭），保留行与关联。 */
    fun pruneMissingExternal(profileIds: List<String>) {
        allConsoles(profileIds).filter { it.external && !Files.isRegularFile(Path.of(it.filePath)) }
            .forEach { workspaces.removeConsoleEverywhere(it.id) }
    }

    /** 退出保护：external + missing + 脏的控制台。 */
    fun missingDirtyExternal(profileIds: List<String>): List<ConsoleRecord> =
        allConsoles(profileIds).filter {
            it.external && it.id in dirtyConsoleIds && externalIssues[it.id] == ExternalFileIssue.MISSING
        }

    /** 放弃某控制台的未保存改动（退出时选择「放弃」用）。 */
    fun discardDirty(consoleId: String) {
        saveJobs.remove(consoleId)?.cancel()
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        externalIssues.remove(consoleId)
    }

    fun closeExternalWatcher() {
        watcher.close()
    }

    /**
     * 关闭控制台 = 从当前工作区移出成员（正文/.sql 保留，其它工作区不受影响）。
     * 若关的是当前激活 → 切到本工作区下一个；没有则引导态。
     */
    fun closeConsole(consoleId: String) {
        val ws = workspaces.activeWorkspace() ?: return
        if (!workspaces.contains(ws.id, consoleId)) return
        workspaces.removeMember(ws.id, consoleId)
        if (activeConsoleId == consoleId) {
            flushNow(consoleId)
            flushCaretNow(consoleId)
            activeConsoleId = null
            activateNextInWorkspace()
        }
    }

    /** 把已存在的控制台加入当前工作区并激活（已在其中则直接激活）。 */
    fun reopenConsole(consoleId: String): ConsoleRecord? {
        val rec = findConsole(consoleId) ?: return null
        val ws = workspaces.ensureActive()
        if (!workspaces.contains(ws.id, consoleId)) workspaces.addMember(ws.id, consoleId)
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
        redisKeyMetas.remove(consoleId)
        runGens.remove(consoleId)
        runStartedAt.remove(consoleId)
        runTarget.remove(consoleId)
        lastStableSlots.remove(consoleId)
        if (rec.external) {
            watcher.unwatch(Path.of(rec.filePath))
            pathToConsole.remove(absKey(Path.of(rec.filePath)))
            externalIssues.remove(consoleId)
            lastDiskContent.remove(consoleId)
            textRevisions.remove(consoleId)
        }
        workspaces.removeConsoleEverywhere(consoleId)
        consolesByConnection[rec.connectionId] =
            consolesByConnection[rec.connectionId].orEmpty().filterNot { it.id == consoleId }
        if (activeConsoleId == consoleId) {
            activeConsoleId = null
            activateNextInWorkspace(preferProfileId = rec.connectionId)
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
            redisKeyMetas.remove(rec.id)
            runGens.remove(rec.id)
            runStartedAt.remove(rec.id)
            runTarget.remove(rec.id)
            lastStableSlots.remove(rec.id)
            if (rec.external) {
                watcher.unwatch(Path.of(rec.filePath))
                pathToConsole.remove(absKey(Path.of(rec.filePath)))
                externalIssues.remove(rec.id)
                lastDiskContent.remove(rec.id)
                textRevisions.remove(rec.id)
            }
            workspaces.removeConsoleEverywhere(rec.id)
        }
        historyByProfile.remove(profileId)
        if (activeWasInProfile) {
            activeConsoleId = null
        }
    }

    // ---------- 文本编辑 / 自动保存 ----------

    /** 编辑器文本变更：更新内存缓冲，防抖自动写回绑定的 .sql 文件。 */
    fun setText(consoleId: String, text: String) {
        // 点击/光标移动可能以同文案上报（见 EditorArea onValueChange 转发层的守卫）；
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
        val rec = findConsole(consoleId)
        // 外部文件缺失：绝不擅自重建，保持脏（退出保护会在退出时处理）
        if (rec?.external == true && externalIssues[consoleId] == ExternalFileIssue.MISSING) return
        runCatching { repository.writeConsoleContent(consoleId, text) }
            .onSuccess { if (rec?.external == true) lastDiskContent[consoleId] = text }
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
        // 任何新执行都使上次「双击预览」的 Redis 键元数据失效（预览路径会在执行后重新写入）
        redisKeyMetas.remove(console.id)
        withContext(ioDispatcher) { flushNow(console.id) }
        val target = sql?.trim().orEmpty().ifEmpty { textOf(console.id).trim() }
        if (target.isEmpty()) {
            runSlots[console.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome(sql = "", error = I18n.t(Str.RunEnterSql))),
            )
            return
        }
        val statements = SessionFactory.splitStatements(profile, target)
        if (statements.isEmpty()) {
            runSlots[console.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome(sql = target, error = I18n.t(Str.RunOnlyComments))),
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
                    error = connectionsState.statusMessageOf(profile.id) ?: I18n.t(Str.ConsoleConnectionUnavailable),
                )),
            )
            return
        }
        val session = connectionsState.sessionOf(profile.id)
        if (session == null) {
            runSlots[console.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome(sql = target, error = I18n.t(Str.ConsoleConnectionClosed))),
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
                outcomes += StatementOutcome(stmt, error = I18n.t(Str.RunDangerCancelled, danger))
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
                durationMs = durationMs, rowCount = 0, error = I18n.t(Str.ErrorCancelled),
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
