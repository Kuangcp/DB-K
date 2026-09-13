package app.state

import db.CachedColumns
import db.ColumnCacheStore
import db.ConnectionProfile
import db.DbType
import db.columnObjectKey
import engine.model.ColumnMeta
import engine.model.SchemaMeta
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColumnCatalogTest {

    private val profile = ConnectionProfile(id = "p", name = "p", dbType = DbType.SQLITE)
    private val schema = SchemaMeta(catalog = null, schema = "main")

    private fun ref(table: String) = ColumnCatalog.ColumnRef(schema, table)

    @Test
    fun `ensure fills cache and dedups repeated loaders`() = runTest {
        var calls = 0
        val catalog = ColumnCatalog(loader = { _, _, _ ->
            calls++
            listOf(ColumnMeta("id", ordinal = 1))
        })
        assertNull(catalog.peek(profile.id, schema, "users"))
        catalog.ensure(profile, listOf(ref("users")))
        assertEquals(1, calls)
        assertEquals(listOf("id"), catalog.peek(profile.id, schema, "users")!!.map { it.name })
        // 命中后不重复拉取
        catalog.ensure(profile, listOf(ref("users")))
        assertEquals(1, calls)
    }

    @Test
    fun `invalidate clears only one profile`() = runTest {
        val catalog = ColumnCatalog(loader = { _, _, t -> listOf(ColumnMeta(t)) })
        val other = profile.copy(id = "q")
        catalog.ensure(profile, listOf(ref("a")))
        catalog.ensure(other, listOf(ref("b")))
        catalog.invalidate(profile.id)
        assertNull(catalog.peek(profile.id, schema, "a"))
        assertEquals(listOf("b"), catalog.peek(other.id, schema, "b")!!.map { it.name })
    }

    @Test
    fun `lru evicts oldest beyond cap`() = runTest {
        val catalog = ColumnCatalog(loader = { _, _, t -> listOf(ColumnMeta(t)) }, maxTables = 2)
        catalog.ensure(profile, listOf(ref("a"), ref("b"), ref("c")))
        assertNull(catalog.peek(profile.id, schema, "a"))
        assertTrue(catalog.peek(profile.id, schema, "b") != null)
        assertTrue(catalog.peek(profile.id, schema, "c") != null)
    }

    @Test
    fun `loader failure is swallowed and retried next time`() = runTest {
        var calls = 0
        val catalog = ColumnCatalog(loader = { _, _, _ ->
            calls++
            if (calls == 1) error("boom") else listOf(ColumnMeta("ok"))
        })
        catalog.ensure(profile, listOf(ref("a")))
        assertNull(catalog.peek(profile.id, schema, "a"))
        catalog.ensure(profile, listOf(ref("a")))
        assertEquals(listOf("ok"), catalog.peek(profile.id, schema, "a")!!.map { it.name })
    }

    @Test
    fun `table key is case-insensitive`() = runTest {
        val catalog = ColumnCatalog(loader = { _, _, _ -> listOf(ColumnMeta("id")) })
        catalog.ensure(profile, listOf(ref("Users")))
        assertTrue(catalog.peek(profile.id, schema, "users") != null)
    }

    @Test
    fun `fresh disk hit avoids loader and survives evict`() = runTest {
        val store = FakeStore()
        val key = columnObjectKey(schema, "users")
        store.put(key, listOf(ColumnMeta("id", "INTEGER")))
        var calls = 0
        val catalog = ColumnCatalog(
            loader = { _, _, _ -> calls++; listOf(ColumnMeta("loaded")) },
            store = store,
        )
        catalog.ensure(profile, listOf(ref("users")))
        assertEquals(0, calls) // 新鲜磁盘命中，不查库
        assertEquals(listOf("id"), catalog.peek(profile.id, schema, "users")!!.map { it.name })
        // 断开只清内存：磁盘保留，重连（新 catalog）仍可命中
        catalog.evict(profile.id)
        assertNull(catalog.peek(profile.id, schema, "users"))
        assertTrue(store.saved.containsKey(key))
    }

    @Test
    fun `invalidate clears disk cache`() = runTest {
        val store = FakeStore()
        store.put(columnObjectKey(schema, "users"), listOf(ColumnMeta("id")))
        val catalog = ColumnCatalog(loader = { _, _, _ -> listOf(ColumnMeta("id")) }, store = store)
        catalog.ensure(profile, listOf(ref("users")))
        catalog.invalidate(profile.id)
        assertEquals(1, store.deletes)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `stale disk hit shows first then refreshes`() = runTest {
        val store = FakeStore(stale = true)
        val key = columnObjectKey(schema, "users")
        store.put(key, listOf(ColumnMeta("old")))
        val catalog = ColumnCatalog(
            loader = { _, _, _ -> listOf(ColumnMeta("fresh")) },
            store = store,
        )
        catalog.ensure(profile, listOf(ref("users")))
        // 刷新后为最新值，且已回写
        assertEquals(listOf("fresh"), catalog.peek(profile.id, schema, "users")!!.map { it.name })
        assertEquals(listOf("fresh"), store.saved[key]!!.map { it.name })
    }

    private inner class FakeStore(private val stale: Boolean = false) : ColumnCacheStore {
        val saved = mutableMapOf<String, List<ColumnMeta>>()
        var deletes = 0

        fun put(key: String, columns: List<ColumnMeta>) {
            saved[key] = columns
        }

        override fun load(profile: ConnectionProfile, objectKey: String): CachedColumns? =
            saved[objectKey]?.let {
                CachedColumns(
                    fingerprint = "fp",
                    savedAtMs = if (stale) 0L else System.currentTimeMillis(),
                    columns = it,
                )
            }

        override fun save(profile: ConnectionProfile, objectKey: String, columns: List<ColumnMeta>) {
            saved[objectKey] = columns
        }

        override fun delete(profileId: String) {
            deletes++
            saved.clear()
        }
    }
}
