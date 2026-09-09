package db

import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tinylog.Logger
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * 数据源目录元数据磁盘缓存（库列表 + 各库对象清单）。
 *
 * 为什么有它：连接数据源时若每次都要向目标库发全量元数据查询（列库 → 逐库列对象），
 * 大库/频繁重连时对数据库与网络都是负担。因此连接动作只做 JDBC 建连；
 * 目录元数据默认从本缓存读取——命中零查询；只有缓存缺失/失配才查库并整体回写。
 *
 * 新鲜度策略（不对数据库造成周期性压力）：
 * - [ttlMs] 内命中即用，绝不自动查库；
 * - 过期后连接：先用缓存立即可用，后台静默重取一次（频率受 TTL 约束，每次连接至多 1 次）；
 * - 右键「刷新元数据缓存」随时手动重取回写；
 * - 连接档案 URL 身份（[ConnectionProfile.metaFingerprint]）变化自动视为失配重取。
 *
 * 存储：app.db 的 meta_cache 表，一行 = 一个 profile，payload 为单条 JSON。
 * 缓存损坏/版本不兼容时按未命中处理（回退查库并覆盖）。
 */
@Serializable
data class MetaCachePayload(
    val fingerprint: String,
    val savedAtMs: Long,
    val schemas: List<SchemaMeta>,
    val objects: Map<String, SchemaObjects>,
)

/** 连接档案的缓存身份：指向同一个库的档案才共享缓存（不含密码/名称/排序）。 */
fun ConnectionProfile.metaFingerprint(): String = listOf(
    dbType.name, host, port.toString(), database, user ?: "", extraParams.trim(),
).joinToString("|")

/** 元数据缓存读写。每次操作短连 SQLite（不自持连接，避免与其它仓库调用抢线程）。 */
class MetaCache(
    private val dbPath: Path = AppPaths.appDatabasePath(),
) {

    /** 缓存有效期。期内直接命中零目标库查询；默认 24h。 */
    val ttlMs: Long = DEFAULT_TTL_MS

    data class Hit(
        val schemas: List<SchemaMeta>,
        val objects: Map<String, SchemaObjects>,
        /** 超过 TTL：仍可用，但调用方应在后台静默重取一次。 */
        val stale: Boolean,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读命中（指纹匹配 + 可解码 + 非空 schema）；未命中返回 null（调用方查库）。 */
    fun load(profile: ConnectionProfile): Hit? {
        val row = queryRow(profile.id) ?: return null
        if (row.fingerprint != profile.metaFingerprint()) {
            Logger.info("meta cache fingerprint mismatch for {}; will refetch", profile.name)
            return null
        }
        val payload = runCatching { json.decodeFromString<MetaCachePayload>(row.payload) }
            .onFailure { Logger.warn(it, "meta cache corrupt for {}; treat as miss", profile.name) }
            .getOrNull() ?: return null
        if (payload.schemas.isEmpty()) return null
        val stale = payload.savedAtMs < System.currentTimeMillis() - ttlMs
        return Hit(payload.schemas, payload.objects, stale)
    }

    /** 整体回写（upsert）。失败仅记日志——缓存是尽力而为，绝不因写失败打断连接流程。 */
    fun save(profile: ConnectionProfile, schemas: List<SchemaMeta>, objects: Map<String, SchemaObjects>) {
        val payload = MetaCachePayload(
            fingerprint = profile.metaFingerprint(),
            savedAtMs = System.currentTimeMillis(),
            schemas = schemas,
            objects = objects,
        )
        runCatching {
            withConn { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO meta_cache(profile_id, fingerprint, saved_at_ms, payload)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(profile_id) DO UPDATE SET
                        fingerprint = excluded.fingerprint,
                        saved_at_ms = excluded.saved_at_ms,
                        payload = excluded.payload
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, profile.id)
                    ps.setString(2, payload.fingerprint)
                    ps.setLong(3, payload.savedAtMs)
                    ps.setString(4, json.encodeToString(MetaCachePayload.serializer(), payload))
                    ps.executeUpdate()
                }
            }
        }.onFailure { Logger.warn(it, "meta cache save failed for {}", profile.name) }
    }

    /** 连接档案删除时清掉对应缓存行。 */
    fun delete(profileId: String) {
        runCatching {
            withConn { conn ->
                conn.prepareStatement("DELETE FROM meta_cache WHERE profile_id = ?").use { ps ->
                    ps.setString(1, profileId)
                    ps.executeUpdate()
                }
            }
        }.onFailure { Logger.warn(it, "meta cache delete failed for {}", profileId) }
    }

    private data class Row(val fingerprint: String, val payload: String)

    private fun queryRow(profileId: String): Row? {
        return runCatching {
            withConn { conn ->
                conn.prepareStatement(
                    "SELECT fingerprint, payload FROM meta_cache WHERE profile_id = ?",
                ).use { ps ->
                    ps.setString(1, profileId)
                    ps.executeQuery().use { rs -> if (rs.next()) Row(rs.getString(1), rs.getString(2)) else null }
                }
            }
        }.onFailure { Logger.warn(it, "meta cache read failed for {}", profileId) }
            .getOrNull()
    }

    private inline fun <T> withConn(block: (Connection) -> T): T {
        DriverManager.getConnection(
            "jdbc:sqlite:${dbPath.toAbsolutePath()}",
        ).use { conn ->
            // 慢写让位：缓存读写绝不阻塞主库操作
            conn.createStatement().use { st -> st.execute("PRAGMA busy_timeout = 3000") }
            return block(conn)
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 24L * 3600 * 1000
    }
}
