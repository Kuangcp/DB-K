package app.state

import androidx.compose.runtime.mutableStateMapOf
import db.ColumnCache
import db.ColumnCacheStore
import db.ConnectionProfile
import db.columnObjectKey
import jdbc.model.ColumnMeta
import jdbc.model.SchemaMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tinylog.Logger

/**
 * 列元数据会话缓存（编辑器列补全）。
 *
 * 三层：会话内存（快照状态，[peek] 在组合中读 → 异步回填后自动重组合）→ 磁盘（[store]，
 * 离线/重启可用）→ 目标库（[loader]，走独立元数据连接）。[Mutex] 串行拉取（重复请求去重），
 * 每 profile LRU 上限（防大库/多表把内存撑大）。
 *
 * 磁盘命中先用旧值立即展示；过期（[ttlMs]）或未命中才查库，成功回写磁盘。查库失败保留旧值，
 * 离线不打断编辑。断开只清内存（[evict]，磁盘留给下次/离线）；档案编辑、元数据刷新、档案删除
 * 才清磁盘（[invalidate]）。
 *
 * [loader]、[store] 由 app/state 注入，便于单测。
 */
class ColumnCatalog(
    private val loader: suspend (ConnectionProfile, SchemaMeta?, String) -> List<ColumnMeta>,
    private val store: ColumnCacheStore? = null,
    private val ttlMs: Long = ColumnCache.DEFAULT_TTL_MS,
    private val maxTables: Int = MAX_TABLES,
) {

    /** 一条待解析的列请求。 */
    data class ColumnRef(val schema: SchemaMeta?, val table: String)

    private val cache = mutableStateMapOf<String, List<ColumnMeta>>()
    private val order = ArrayDeque<String>()
    private val mutex = Mutex()

    /** 命中即返回（组合中同步调用）；未命中返回 null，由 [ensure] 异步拉取。 */
    fun peek(profileId: String, schema: SchemaMeta?, table: String): List<ColumnMeta>? =
        cache[key(profileId, schema, table)]

    suspend fun ensure(profile: ConnectionProfile, refs: List<ColumnRef>) {
        val missing = refs.mapNotNull { ref ->
            val k = key(profile.id, ref.schema, ref.table)
            if (cache.containsKey(k)) null else k to ref
        }
        if (missing.isEmpty()) return
        mutex.withLock {
            for ((k, ref) in missing) {
                if (cache.containsKey(k)) continue
                val objectKey = columnObjectKey(ref.schema, ref.table)
                val disk = withContext(Dispatchers.IO) { store?.load(profile, objectKey) }
                if (disk != null) {
                    put(k, disk.columns)
                    if (!disk.isStale(System.currentTimeMillis(), ttlMs)) continue
                }
                try {
                    val columns = loader(profile, ref.schema, ref.table)
                    put(k, columns)
                    withContext(Dispatchers.IO) { store?.save(profile, objectKey, columns) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (disk == null) {
                        // 元数据权限不足/表不存在/离线且无缓存：静默回落（退回表名+关键字）
                        Logger.warn(t, "load columns failed {}.{}", ref.schema?.displayName, ref.table)
                    } else {
                        Logger.info("column cache refresh failed {}.{}; keep cached", ref.schema?.displayName, ref.table)
                    }
                }
            }
        }
    }

    /** 单数据源断开：只清会话内存，磁盘缓存保留（重连/离线仍可用）。 */
    fun evict(profileId: String) {
        val prefix = "$profileId\u0000"
        cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        order.removeAll { it.startsWith(prefix) }
    }

    /** 档案编辑/元数据刷新/档案删除：清内存 + 磁盘（下次进入重新查库）。 */
    fun invalidate(profileId: String) {
        evict(profileId)
        store?.delete(profileId)
    }

    /** 应用退出清理（仅内存；磁盘跨会话保留）。 */
    fun clear() {
        cache.clear()
        order.clear()
    }

    private fun put(k: String, columns: List<ColumnMeta>) {
        cache[k] = columns
        order.remove(k)
        order.addLast(k)
        while (order.size > maxTables) {
            val evicted = order.removeFirst()
            cache.remove(evicted)
        }
    }

    private fun key(profileId: String, schema: SchemaMeta?, table: String): String =
        "$profileId\u0000${schema?.key ?: ""}\u0000${table.lowercase()}"

    companion object {
        const val MAX_TABLES = 200
    }
}
