package db

import engine.model.ColumnMeta
import engine.model.SchemaMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tinylog.Logger
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** 列缓存对象键：schema 唯一键 + 小写表名（跨库同名表不串）。 */
fun columnObjectKey(schema: SchemaMeta?, table: String): String =
    "${schema?.key ?: ""}\u0000${table.lowercase()}"

/** 列缓存读写抽象（便于单测注入假实现；实现见 [ColumnCache]）。 */
interface ColumnCacheStore {
    /** 命中返回缓存（可能过期，由调用方决定是否刷新）；指纹失配/损坏返回 null。 */
    fun load(profile: ConnectionProfile, objectKey: String): CachedColumns?

    /** 整体 upsert 单表列。失败仅记日志（缓存是尽力而为）。 */
    fun save(profile: ConnectionProfile, objectKey: String, columns: List<ColumnMeta>)

    /** 数据源档案删除/编辑/元数据刷新：清该 profile 全部列缓存行。 */
    fun delete(profileId: String)
}

/** 单表列清单的磁盘缓存载荷。 */
@Serializable
data class CachedColumns(
    val fingerprint: String,
    val savedAtMs: Long,
    val columns: List<ColumnMeta> = emptyList(),
) {
    fun isStale(nowMs: Long, ttlMs: Long): Boolean = savedAtMs < nowMs - ttlMs
}

/**
 * 列清单磁盘缓存（app.db 的 `column_cache` 表，一行 = 一表）。
 *
 * 与 [MetaCache] 同源策略：连接档案 URL 身份 [ConnectionProfile.metaFingerprint] 变化按未命中；
 * 载荷损坏按未命中；写入失败仅记日志。相比把列塞进 meta_cache 的 JSON，独立成行可按表增量
 * 读写、删除档案时按 profile 清理，不会把对象清单缓存撑大。
 *
 * 新鲜度：超过 [ttlMs] 视为过期，调用方（ColumnCatalog）先用旧值立即展示，再后台刷新一次；
 * 离线/刷新失败时旧值继续可用。
 */
class ColumnCache(
    private val dbPath: Path = AppPaths.appDatabasePath(),
) : ColumnCacheStore {

    val ttlMs: Long = DEFAULT_TTL_MS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(profile: ConnectionProfile, objectKey: String): CachedColumns? {
        val payload = runCatching {
            withConn { conn ->
                conn.prepareStatement(
                    "SELECT fingerprint, payload FROM column_cache WHERE profile_id = ? AND object_key = ?",
                ).use { ps ->
                    ps.setString(1, profile.id)
                    ps.setString(2, objectKey)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
                }
            }
        }.onFailure { Logger.warn(it, "column cache read failed {}", profile.id) }
            .getOrNull() ?: return null

        val (fingerprint, body) = payload
        if (fingerprint != profile.metaFingerprint()) return null
        return runCatching { json.decodeFromString<CachedColumns>(body) }
            .onFailure { Logger.warn(it, "column cache corrupt for {}; treat as miss", objectKey) }
            .getOrNull()
            ?.takeIf { it.columns.isNotEmpty() }
    }

    override fun save(profile: ConnectionProfile, objectKey: String, columns: List<ColumnMeta>) {
        if (columns.isEmpty()) return
        val payload = CachedColumns(
            fingerprint = profile.metaFingerprint(),
            savedAtMs = System.currentTimeMillis(),
            columns = columns,
        )
        runCatching {
            withConn { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO column_cache(profile_id, object_key, fingerprint, saved_at_ms, payload)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(profile_id, object_key) DO UPDATE SET
                        fingerprint = excluded.fingerprint,
                        saved_at_ms = excluded.saved_at_ms,
                        payload = excluded.payload
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, profile.id)
                    ps.setString(2, objectKey)
                    ps.setString(3, payload.fingerprint)
                    ps.setLong(4, payload.savedAtMs)
                    ps.setString(5, json.encodeToString(CachedColumns.serializer(), payload))
                    ps.executeUpdate()
                }
            }
        }.onFailure { Logger.warn(it, "column cache save failed {}.{}", profile.name, objectKey) }
    }

    override fun delete(profileId: String) {
        runCatching {
            withConn { conn ->
                conn.prepareStatement("DELETE FROM column_cache WHERE profile_id = ?").use { ps ->
                    ps.setString(1, profileId)
                    ps.executeUpdate()
                }
            }
        }.onFailure { Logger.warn(it, "column cache delete failed {}", profileId) }
    }

    private inline fun <T> withConn(block: (Connection) -> T): T {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st -> st.execute("PRAGMA busy_timeout = 3000") }
            return block(conn)
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 24L * 3600 * 1000
    }
}
