package jdbc

import db.ConnectionProfile
import engine.BackendCapabilities
import engine.DataSourceSession
import engine.EditorLanguage
import engine.Protocol
import engine.model.ColumnMeta
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.QueryColumn
import engine.model.QueryResult
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import org.tinylog.Logger
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * JDBC 后端实现（[DataSourceSession] 的 JDBC 版）：一条运行中的连接 =
 * 懒建 `java.sql.Connection` + 单线程执行器，保证所有 JDBC 调用在一条专用线程上串行执行
 * （驱动线程安全性不必假设）。
 *
 * 本层不依赖 compose/coroutines：方法均为阻塞式，由调用方（app 层）放到 `Dispatchers.IO` 执行。
 * 通用契约在 `engine.DataSourceSession`；JDBC 专属的写回能力见 [EditableSession]。
 */
class LiveConnection(private val profile: ConnectionProfile) : DataSourceSession, EditableSession {

    private val dialect: DbDialect = DialectRegistry.forProfile(profile)

    override val profileId: String = profile.id
    override val protocol: Protocol = Protocol.JDBC

    override val capabilities: BackendCapabilities = BackendCapabilities(
        editableResult = true,
        sqlCompletion = true,
        objectDdl = true,
        objectPreview = true,
        sessionContext = dialect.supportsTargetSwitch,
        lazyObjectGroups = dialect.lazyObjectGroups,
        editorLanguage = EditorLanguage.SQL,
        fetchMore = true,
    )

    @Volatile
    private var conn: Connection? = null

    /** 最近一次使用连接结束的时刻（`System.nanoTime()`）；用于判断空闲是否超阈值。 */
    @Volatile
    private var lastActivityNanos = 0L

    /** 当前正在执行（或最近登记）的 JDBC 语句，供外部线程发起 Statement.cancel。 */
    @Volatile
    private var currentStmt: Statement? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jdbc-${profile.id.take(6)}").apply { isDaemon = true }
    }

    override val isOpen: Boolean get() = conn?.isClosed == false

    /**
     * 建立连接（幂等：已连接则跳过）。阻塞，勿在 UI 线程调用。
     * 经单线程执行器串行，与用前重建不会交替写 [conn]。
     */
    override fun open() {
        if (conn?.isClosed == false) return
        submitAndGet(forceReconnect = false) { it }
    }

    /** 关闭连接并丢弃。 */
    override fun close() {
        runCatching { conn?.close() }
        conn = null
    }

    /**
     * 在连接上执行阻塞块。所有 JDBC 调用都经此入口，因此连接健康校验是单点的：
     * 空闲超阈值时先探活，死连接直接重建，用户无感（见 `doc/CONNECTION_HEALTH.md`）。
     * 元数据探测均为幂等读，默认允许断连后自动重试一次。
     */
    fun <T> onConnection(block: (Connection) -> T): T = runOnConnection({ true }, block)

    /**
     * @param retryOnLoss 中途抛「连接已断」时是否重连后重试一次；**仅幂等操作**应返回 true
     *   （写操作在服务端可能已执行、仅回包丢失，重试会重复写入）。
     */
    private fun <T> runOnConnection(retryOnLoss: () -> Boolean, block: (Connection) -> T): T =
        try {
            submitAndGet(forceReconnect = false, block)
        } catch (e: Throwable) {
            if (!isConnectionLost(e)) throw e
            val canRetry = retryOnLoss()
            // 刚失败时 lastActivity 是新的，不能靠空闲阈值/探活兜底，必须强制重建
            if (canRetry) {
                Logger.warn(e, "connection lost mid-flight; reconnect and retry once ({})", profile.name)
                submitAndGet(forceReconnect = true, block)
            } else {
                // 写操作不重试（服务端可能已执行）；但把连接重建好，用户重试即可
                Logger.warn(e, "connection lost mid-flight; reconnect without retry ({})", profile.name)
                runCatching { submitAndGet(forceReconnect = true) { } }
                throw e
            }
        }

    private fun <T> submitAndGet(forceReconnect: Boolean, block: (Connection) -> T): T {
        val future: Future<T> = executor.submit(Callable {
            val c = if (forceReconnect) reopen() else ensureAlive()
            try {
                block(c)
            } finally {
                lastActivityNanos = System.nanoTime()
            }
        })
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * 用前校验：连接为空/已关 → 重建；空闲未超阈值 → 直接复用（省一次往返）；
     * 超阈值才探活，探不通则重建。[dialect] 无健康策略（嵌入式/本地库）时只处理关/空。
     * 必须在单线程执行器上调用。
     */
    private fun ensureAlive(): Connection {
        val existing = conn
        if (existing == null || existing.isClosed) return reopen()
        val h = dialect.healthFor(profile) ?: return existing
        val idleMs = (System.nanoTime() - lastActivityNanos) / 1_000_000
        if (lastActivityNanos != 0L && idleMs < h.idleBeforeCheckMs) return existing
        if (isAlive(existing, h)) return existing
        Logger.info("connection lost while idle ({} ms); reconnecting {}", idleMs, profile.name)
        return reopen()
    }

    /** 探活：[ConnectionHealth.validationQuery] 为空时用 JDBC4 `isValid(timeout)`。 */
    private fun isAlive(c: Connection, h: ConnectionHealth): Boolean = runCatching {
        val q = h.validationQuery
        if (q == null) {
            c.isValid(h.validationTimeoutSeconds)
        } else {
            c.createStatement().use { st ->
                st.queryTimeout = h.validationTimeoutSeconds
                st.execute(q)
                true
            }
        }
    }.getOrElse {
        Logger.debug(it, "connection validation failed ({})", profile.name)
        false
    }

    /** 重建连接（旧连接关闭失败也继续）。失败向上抛，按连接错误处理。 */
    private fun reopen(): Connection {
        runCatching { conn?.close() }
        val fresh = dialect.openConnection(profile)
        conn = fresh
        lastActivityNanos = System.nanoTime()
        return fresh
    }

    /** 登记/注销当前执行语句（由 [QueryExecutor] 在语句生命周期内回调）。 */
    fun registerStatement(st: Statement?) {
        currentStmt = st
    }

    /**
     * 取消正在执行的语句（Statement.cancel，驱动支持时）。
     * 返回是否发出了取消请求；正在排队尚未开始的执行不在内。
     * 驱动忽略 cancel 时，等待中的查询仍会按其自身超时（30s）结束。
     */
    override fun cancel(): Boolean {
        val st = currentStmt ?: return false
        return runCatching { st.cancel(); true }.getOrDefault(false)
    }

    // ---------- 元数据（内部一律串行到连接线程） ----------

    override fun loadNamespaces(): List<SchemaMeta> = onConnection { dialect.loadSchemas(it) }

    override fun loadObjects(ns: SchemaMeta): SchemaObjects =
        onConnection { dialect.loadObjects(it, ns) }

    override fun loadObjectCounts(ns: SchemaMeta): Map<ObjectKind, Int> =
        onConnection { dialect.loadObjectCounts(it, ns) }

    override fun loadCoreObjects(ns: SchemaMeta): SchemaObjects =
        onConnection { dialect.loadCoreObjects(it, ns) }

    override fun loadObjectsForKind(ns: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> =
        onConnection { dialect.loadObjectsForKind(it, ns, kind) }

    override fun loadColumns(ns: SchemaMeta?, table: String): List<ColumnMeta> =
        onConnection { dialect.loadColumns(it, ns, table) }

    override fun objectDdl(ns: SchemaMeta?, name: String): String? =
        onConnection { dialect.tableDdl(it, ns, name) }

    override fun previewQuery(ns: SchemaMeta?, obj: DbObjectMeta): String = dialect.previewSelect(ns, obj.name)

    override fun sessionContextSql(ns: SchemaMeta): String? = dialect.sessionContextSql(ns)

    /** 标识符引用（N8 SQL INSERT 导出按方言用反引号 / 双引号）。 */
    fun quoteIdent(name: String): String = dialect.quoteIdent(name)

    // ---------- 执行 ----------

    override fun runStatement(statement: String, sessionContextSql: String?): QueryResult =
        runOnConnection({ QueryExecutor.isQueryLike(statement) }) { conn ->
            QueryExecutor.applyContext(conn, sessionContextSql)
            QueryExecutor.execute(conn, statement) { st -> registerStatement(st) }
        }

    override fun paginate(statement: String, offset: Long, limit: Int): String? =
        dialect.paginate(statement, offset, limit)

    /**
     * N8 全量流式导出：重跑 [sql] 并按方言游标策略逐行回调（[onMeta] 一次 / [onRow] 每行）。
     * JDBC 专属（同 [EditableSession] 思路，不进 `engine` 通用契约）。返回数据行数。
     */
    fun streamQuery(
        sql: String,
        sessionContextSql: String?,
        onMeta: (List<QueryColumn>) -> Unit,
        onRow: (List<String?>) -> Unit,
    ): Long {
        // 已产出过行就绝不重试（会重复行）；0 行时断连可安全重跑。
        var emitted = 0L
        return runOnConnection({ emitted == 0L }) { conn ->
            QueryExecutor.applyContext(conn, sessionContextSql)
            StreamingQuery.stream(conn, sql, dialect.cursorStrategy, dialect.streamFetchSize, onMeta) { row ->
                emitted++
                onRow(row)
            }
        }
    }

    override fun applyWriteOps(ops: List<WriteOp>, sessionContextSql: String?): Int =
        runOnConnection({ false }) { conn ->
            QueryExecutor.applyContext(conn, sessionContextSql)
            RowUpdater.executeWriteBatch(conn, ops, dialect) { st -> registerStatement(st) }
        }
}
