package jdbc

import db.ConnectionProfile
import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaObjects
import jdbc.model.SchemaMeta
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 一条运行中的 JDBC 连接：懒建 java.sql.Connection + 单线程执行器，
 * 保证所有 JDBC 调用在一条专用线程上串行执行（驱动线程安全性不必假设）。
 *
 * 本层不依赖 compose/coroutines：方法均为阻塞式，由调用方（app 层）放到
 * Dispatchers.IO 执行。
 */
class LiveConnection(private val profile: ConnectionProfile) {

    private val dialect: DbDialect = DialectRegistry.forProfile(profile)

    @Volatile
    private var conn: Connection? = null

    /** 当前正在执行（或最近登记）的 JDBC 语句，供外部线程发起 Statement.cancel。 */
    @Volatile
    private var currentStmt: Statement? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jdbc-${profile.id.take(6)}").apply { isDaemon = true }
    }

    val isOpen: Boolean get() = conn?.isClosed == false

    /** 建立连接（幂等：已连接则跳过）。阻塞，勿在 UI 线程调用。 */
    fun open() {
        if (conn?.isClosed == false) return
        val fresh = dialect.openConnection(profile)
        conn = fresh
    }

    /** 关闭连接并丢弃。 */
    fun close() {
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

    /** 登记/注销当前执行语句（由 QueryExecutor 在语句生命周期内回调）。 */
    fun registerStatement(st: Statement?) {
        currentStmt = st
    }

    /**
     * 取消正在执行的语句（Statement.cancel，驱动支持时）。
     * 返回是否发出了取消请求；正在排队尚未开始的执行不在内。
     * 驱动忽略 cancel 时，等待中的查询仍会按其自身超时（30s）结束。
     */
    fun cancelCurrentQuery(): Boolean {
        val st = currentStmt ?: return false
        return runCatching { st.cancel(); true }.getOrDefault(false)
    }

    // ---------- 便捷元数据入口（内部一律串行到连接线程） ----------

    fun loadSchemas(): List<SchemaMeta> = onConnection { dialect.loadSchemas(it) }

    fun loadObjects(schema: SchemaMeta): SchemaObjects =
        onConnection { dialect.loadObjects(it, schema) }

    /** 懒加载：组计数（不拉正文）。 */
    fun loadObjectCounts(schema: SchemaMeta): Map<ObjectKind, Int> =
        onConnection { dialect.loadObjectCounts(it, schema) }

    /** 懒加载：核心组正文（表/视图/物化视图/序列）。 */
    fun loadCoreObjects(schema: SchemaMeta): SchemaObjects =
        onConnection { dialect.loadCoreObjects(it, schema) }

    /** 懒加载：单一类型组正文。 */
    fun loadObjectsForKind(schema: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> =
        onConnection { dialect.loadObjectsForKind(it, schema, kind) }
}
