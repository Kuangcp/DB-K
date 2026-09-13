package redis

import db.ConnectionProfile
import db.DbType
import engine.model.ObjectKind
import engine.model.SchemaMeta
import org.tinylog.Logger
import redis.clients.jedis.exceptions.JedisConnectionException
import kotlin.system.exitProcess

/**
 * Redis 后端自检（无需 UI）：`gradle smokeRedis`。
 *
 * 需要本机/容器内有可连的 Redis；连不上时打印 SKIP 并非失败退出。
 * 可用 `-Ddbk.redisHost=… -Ddbk.redisPort=… -Ddbk.redisPassword=…` 覆盖（默认 127.0.0.1:6379）。
 *
 * 覆盖：建连 → 写入五类键 → 命名空间/DBSIZE → SCAN + TYPE → 命令结果渲染 → 取消后重连。
 * ⚠️ 会 `FLUSHDB`，仅用于一次性测试库（db0）。
 */
fun main() {
    val host = System.getProperty("dbk.redisHost") ?: System.getenv("DBK_REDIS_HOST") ?: "127.0.0.1"
    val port = (System.getProperty("dbk.redisPort") ?: System.getenv("DBK_REDIS_PORT") ?: "6379").toInt()
    val password = System.getProperty("dbk.redisPassword") ?: System.getenv("DBK_REDIS_PASSWORD")
    val user = System.getProperty("dbk.redisUser") ?: System.getenv("DBK_REDIS_USER")
    Logger.info("smokeRedis target: {}:{}", host, port)

    val profile = ConnectionProfile(
        id = "smoke-redis", name = "redis-smoke", dbType = DbType.REDIS,
        host = host, port = port, database = "0", user = user, password = password,
    )
    val session = RedisSession(profile)
    val checks = mutableListOf<String>()
    try {
        val db0 = SchemaMeta(catalog = null, schema = "db0")
        try {
            session.open()
        } catch (e: JedisConnectionException) {
            Logger.info("smokeRedis SKIP: Redis 不可达（{}:{}）—— {}", host, port, e.message)
            return
        }
        checks += "connect"

        session.runStatement("FLUSHDB", "SELECT 0")
        session.runStatement("SET smoke:str hello", null)
        session.runStatement("HSET smoke:hash f1 v1 f2 v2", null)
        session.runStatement("RPUSH smoke:list a b c", null)
        session.runStatement("SADD smoke:set x y", null)
        session.runStatement("ZADD smoke:zset 1 one 2 two", null)
        checks += "seed"

        val namespaces = session.loadNamespaces()
        require(namespaces.any { it.schema == "db0" }) { "命名空间缺少 db0：$namespaces" }
        checks += "namespaces(${namespaces.size})"

        val counts = session.loadObjectCounts(db0)
        require(counts[ObjectKind.KEY] == 5) { "DBSIZE != 5：$counts" }
        checks += "dbsize"

        val keys = session.loadObjectsForKind(db0, ObjectKind.KEY)
        require(keys.size == 5) { "SCAN 键数 != 5：${keys.map { it.name }}" }
        val types = keys.map { it.detail }.toSet()
        require(types == setOf("string", "hash", "list", "set", "zset")) { "TYPE 结果异常：$types" }
        checks += "scan+type"

        val get = session.runStatement("GET smoke:str", null)
        require(get.rows.single() == listOf("hello")) { "GET 渲染异常：${get.rows}" }
        val hgetall = session.runStatement("HGETALL smoke:hash", null)
        require(hgetall.rows.size == 2) { "HGETALL 渲染异常：${hgetall.rows}" }
        val lrange = session.runStatement("LRANGE smoke:list 0 -1", null)
        require(lrange.rows.map { it.single() } == listOf("a", "b", "c")) { "LRANGE 渲染异常：${lrange.rows}" }
        checks += "commands"

        val preview = session.previewQuery(db0, keys.first { it.name == "smoke:hash" })
        require(preview == "HGETALL smoke:hash") { "预览命令异常：$preview" }
        require(RedisProtocol.dangerousCommand("FLUSHALL") == "FLUSHALL")
        checks += "preview+danger"

        // 取消 → 惰性重连后仍可执行
        require(session.cancel()) { "cancel 未生效" }
        val ping = session.runStatement("PING", null)
        require(ping.rows.single() == listOf("PONG")) { "取消后重连执行异常：${ping.rows}" }
        checks += "cancel+reconnect"

        Logger.info("smokeRedis PASS: {}", checks.joinToString(" + "))
    } catch (t: Throwable) {
        Logger.error(t, "smokeRedis FAIL")
        exitProcess(1)
    } finally {
        session.close()
    }
}
