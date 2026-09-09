package jdbc

import db.ConnectionProfile
import jdbc.model.SchemaObjects
import jdbc.model.SchemaMeta
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 一条运行中的 JDBC 连接：懒建 java.sql.Connection + 单线程执行器，
 * 保证所有 JDBC 调用在一条专用线程上串行执行（驱动线程安全性不必假设）。
 *
 * 本层不依赖 compose/coroutines：方法均为阻塞式，由调用方（app 层）放到
 * Dispatchers.IO 执行；取消语义在 M3 执行引擎上补（Statement.cancel）。
 */
class LiveConnection(private val profile: ConnectionProfile) {

    private val dialect: DbDialect = DialectRegistry.forProfile(profile)

    @Volatile
    private var conn: Connection? = null

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

    // ---------- 便捷元数据入口（内部一律串行到连接线程） ----------

    fun loadSchemas(): List<SchemaMeta> = onConnection { dialect.loadSchemas(it) }

    fun loadObjects(schema: SchemaMeta): SchemaObjects =
        onConnection { dialect.loadObjects(it, schema) }
}
