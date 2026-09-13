package redis

import db.ConnectionProfile
import engine.BackendCapabilities
import engine.DataSourceSession
import engine.EditorLanguage
import engine.Protocol
import engine.model.ColumnMeta
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.QueryResult
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.params.ScanParams
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Redis 后端（`engine.DataSourceSession` 的 Redis 版，阻塞式 Jedis）。
 *
 * - 命名空间 = DB（`db0…db15`，按 `CONFIG GET databases` 尽量探测）；对象组只有一个「键」。
 * - 键清单按 `SCAN` 游标分页抓取（每页上限 [KEY_PAGE]），并 pipeline `TYPE` 标注键类型，
 *   供双击预览选对命令（`GET` / `HGETALL` / `LRANGE` …）。
 * - 控制台执行任意原生命令（含写命令），回复统一转 [QueryResult]（渲染规则见 [RedisProtocol]）。
 * - 所有 Jedis 调用串行到单线程执行器（Jedis 非线程安全；[cancel] 例外，故意跨线程断连）。
 */
class RedisSession(private val profile: ConnectionProfile) : DataSourceSession {

    override val profileId: String = profile.id
    override val protocol: Protocol = Protocol.REDIS

    override val capabilities: BackendCapabilities = BackendCapabilities(
        editableResult = false,
        sqlCompletion = false,
        objectDdl = false,
        objectPreview = true,
        sessionContext = true,
        lazyObjectGroups = true,
        editorLanguage = EditorLanguage.REDIS_COMMAND,
    )

    @Volatile
    private var jedis: Jedis? = null

    /** 连接获取/重建互斥（多个 IO 路径可能并发首次探测）。 */
    private val connLock = Any()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "redis-${profile.id.take(6)}").apply { isDaemon = true }
    }

    override val isOpen: Boolean get() = jedis != null

    override fun open() {
        currentJedis()
    }

    override fun close() {
        val j = synchronized(connLock) {
            val x = jedis
            jedis = null
            x
        } ?: return
        runCatching { submit { j.close() } }
    }

    /** 取当前连接；[cancel] 断连后为 null，下一次调用惰性重建。 */
    private fun currentJedis(): Jedis = synchronized(connLock) {
        jedis?.let { return it }
        val j = submit { buildConnection() }
        jedis = j
        j
    }

    private fun buildConnection(): Jedis {
        val host = profile.host.ifBlank { "127.0.0.1" }
        val port = if (profile.port > 0) profile.port else 6379
        val j = Jedis(host, port)
        profile.password?.takeIf { it.isNotBlank() }?.let { pass ->
            val user = profile.user?.takeIf { it.isNotBlank() }
            if (user != null) j.auth(user, pass) else j.auth(pass)
        }
        val db = dbIndex(profile.database)
        if (db != 0) j.select(db)
        j.ping() // 建连即验证可达 + 认证
        return j
    }

    /** 提交到单线程执行器（保证 Jedis 串行）。 */
    private fun <T> submit(block: () -> T): T {
        val future = executor.submit(Callable { block() })
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun <T> onRedis(block: (Jedis) -> T): T {
        val j = currentJedis()
        return submit { block(j) }
    }

    // ---------- 元数据 ----------

    override fun objectGroups(): List<ObjectKind> = listOf(ObjectKind.KEY)

    override fun loadNamespaces(): List<SchemaMeta> = onRedis { j ->
        val count = runCatching { j.configGet("databases")["databases"]?.toInt() }.getOrNull() ?: 16
        (0 until count.coerceIn(1, 1024)).map { SchemaMeta(catalog = null, schema = "db$it") }
    }

    override fun loadObjects(ns: SchemaMeta): SchemaObjects = onRedis { j ->
        j.select(dbIndexOf(ns))
        SchemaObjects(objects = mapOf(ObjectKind.KEY to scanKeys(j)))
    }

    override fun loadObjectCounts(ns: SchemaMeta): Map<ObjectKind, Int> = onRedis { j ->
        j.select(dbIndexOf(ns))
        mapOf(ObjectKind.KEY to j.dbSize().toInt())
    }

    /** Redis 键组完全懒加载：连库时只取 DBSIZE 计数，展开键组才 SCAN（避免大库连库时抓爆）。 */
    override fun loadCoreObjects(ns: SchemaMeta): SchemaObjects = SchemaObjects(objects = emptyMap())

    override fun loadObjectsForKind(ns: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> =
        if (kind == ObjectKind.KEY) onRedis { j ->
            j.select(dbIndexOf(ns))
            scanKeys(j)
        } else {
            emptyList()
        }

    override fun loadColumns(ns: SchemaMeta?, table: String): List<ColumnMeta> = emptyList()

    override fun objectDdl(ns: SchemaMeta?, name: String): String? = null

    override fun previewQuery(ns: SchemaMeta?, obj: DbObjectMeta): String {
        val key = obj.name
        return when (obj.detail?.lowercase()) {
            "string" -> "GET $key"
            "hash" -> "HGETALL $key"
            "list" -> "LRANGE $key 0 99"
            "set" -> "SMEMBERS $key"
            "zset" -> "ZRANGE $key 0 99 WITHSCORES"
            "stream" -> "XRANGE $key - +"
            else -> "TYPE $key"
        }
    }

    /** 控制台目标 = 某 DB：执行前 `SELECT n` 切换。 */
    override fun sessionContextSql(ns: SchemaMeta): String? = "SELECT ${dbIndexOf(ns)}"

    // ---------- 执行 ----------

    override fun runStatement(statement: String, sessionContextSql: String?): QueryResult = onRedis { j ->
        // 元数据探测会切换连接所在 DB；无显式目标时固定回到连接默认 DB，避免“跑到最后一个探测的 DB”。
        selectFromContext(j, sessionContextSql ?: "SELECT ${dbIndex(profile.database)}")
        val tokens = RedisProtocol.tokenize(statement)
        require(tokens.isNotEmpty()) { "空命令" }
        val started = System.currentTimeMillis()
        val reply = j.sendCommand(RawCommand(tokens[0]), *tokens.drop(1).toTypedArray())
        RedisProtocol.replyToResult(statement, reply, System.currentTimeMillis() - started)
    }

    /**
     * 取消：跨线程断开当前 socket（Jedis 会以异常终止阻塞中的命令），随后重连保持会话可用。
     * 属于「尽力而为」——命令若已返回则不触发。
     */
    override fun cancel(): Boolean {
        // 读 @Volatile 字段不加锁：避免与 currentJedis()（可能正阻塞在执行器上）争锁。
        val j = jedis ?: return false
        val ok = runCatching { j.disconnect(); true }.getOrDefault(false)
        if (ok) jedis = null // 下一次 onRedis 惰性重连；与重建竞争最坏只多连一次
        return ok
    }

    // ---------- 内部 ----------

    private fun selectFromContext(j: Jedis, contextSql: String?) {
        val n = contextSql?.trim()?.substringAfterLast(' ')?.toIntOrNull() ?: return
        j.select(n)
    }

    private fun dbIndexOf(ns: SchemaMeta): Int = dbIndex(ns.schema)

    private fun dbIndex(raw: String?): Int =
        (raw?.removePrefix("db")?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0)

    /** SCAN 抓取一页键并 pipeline 标注类型（上限 [KEY_PAGE]，避免大库一次拉爆）。 */
    private fun scanKeys(j: Jedis): List<DbObjectMeta> {
        val keys = mutableListOf<String>()
        var cursor = "0"
        do {
            val page = j.scan(cursor, ScanParams().count(SCAN_BATCH).match("*"))
            cursor = page.cursor
            keys += page.result
        } while (cursor != "0" && keys.size < KEY_PAGE)
        if (keys.isEmpty()) return emptyList()
        val pipeline = j.pipelined()
        val types = keys.map { pipeline.type(it) }
        pipeline.sync()
        return keys.take(KEY_PAGE).zip(types).map { (key, t) ->
            DbObjectMeta(key, ObjectKind.KEY, detail = runCatching { t.get() }.getOrNull())
        }
    }

    /** 任意命令：自定义 [ProtocolCommand]，绕过 Jedis 枚举白名单（支持模块/新命令）。 */
    private class RawCommand(private val name: String) : ProtocolCommand {
        override fun getRaw(): ByteArray = name.toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val KEY_PAGE = 1000
        const val SCAN_BATCH = 200
    }
}
