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

    /** 当前正在执行（或最近登记）的 JDBC 语句，供外部线程发起 Statement.cancel。 */
    @Volatile
    private var currentStmt: Statement? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jdbc-${profile.id.take(6)}").apply { isDaemon = true }
    }

    override val isOpen: Boolean get() = conn?.isClosed == false

    /** 建立连接（幂等：已连接则跳过）。阻塞，勿在 UI 线程调用。 */
    override fun open() {
        if (conn?.isClosed == false) return
        val fresh = dialect.openConnection(profile)
        conn = fresh
    }

    /** 关闭连接并丢弃。 */
    override fun close() {
        runCatching { conn?.close() }
        conn = null
    }

    /** 在连接上执行阻塞块。连接未建立时抛 IllegalStateException。 */
    fun <T> onConnection(block: (Connection) -> T): T {
        val current = conn ?: error("连接已断开")
        val future: Future<T> = executor.submit(Callable { block(current) })
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
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
        onConnection { conn ->
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
    ): Long = onConnection { conn ->
        QueryExecutor.applyContext(conn, sessionContextSql)
        StreamingQuery.stream(conn, sql, dialect.cursorStrategy, dialect.streamFetchSize, onMeta, onRow)
    }

    override fun applyWriteOps(ops: List<WriteOp>, sessionContextSql: String?): Int =
        onConnection { conn ->
            QueryExecutor.applyContext(conn, sessionContextSql)
            RowUpdater.executeWriteBatch(conn, ops, dialect) { st -> registerStatement(st) }
        }
}
