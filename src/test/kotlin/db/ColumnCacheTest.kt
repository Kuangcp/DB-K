package db

import engine.model.ColumnMeta
import engine.model.SchemaMeta
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColumnCacheTest {

    @TempDir
    lateinit var dir: Path

    private fun profile(id: String = "p1", host: String = "h") = ConnectionProfile(
        id = id, name = "c", dbType = DbType.SQLITE, host = host, database = "x.db",
    )

    private fun migratedDb(): Path = dir.resolve("app.db").also { db ->
        DriverManager.getConnection("jdbc:sqlite:$db").use { AppDatabase.migrate(it) }
    }

    private val schema = SchemaMeta(null, "main")
    private val columns = listOf(
        ColumnMeta("id", "INTEGER", nullable = false, ordinal = 1),
        ColumnMeta("name", "TEXT", nullable = true, ordinal = 2),
    )

    @Test
    fun `save and load round trip with case-insensitive key`() {
        val cache = ColumnCache(migratedDb())
        val p = profile()
        val key = columnObjectKey(schema, "Users")
        assertEquals(columnObjectKey(schema, "users"), key)
        cache.save(p, key, columns)
        assertEquals(columns, cache.load(p, key)!!.columns)
    }

    @Test
    fun `fingerprint mismatch returns miss`() {
        val cache = ColumnCache(migratedDb())
        val p = profile()
        val key = columnObjectKey(schema, "users")
        cache.save(p, key, columns)
        assertNull(cache.load(p.copy(host = "other-host"), key))
    }

    @Test
    fun `empty columns are not stored`() {
        val cache = ColumnCache(migratedDb())
        val p = profile()
        val key = columnObjectKey(schema, "users")
        cache.save(p, key, emptyList())
        assertNull(cache.load(p, key))
    }

    @Test
    fun `delete removes only one profile`() {
        val cache = ColumnCache(migratedDb())
        val p = profile()
        val other = profile(id = "p2")
        cache.save(p, columnObjectKey(schema, "a"), columns)
        cache.save(other, columnObjectKey(schema, "b"), columns)
        cache.delete(p.id)
        assertNull(cache.load(p, columnObjectKey(schema, "a")))
        assertTrue(cache.load(other, columnObjectKey(schema, "b")) != null)
    }

    @Test
    fun `staleness uses savedAt and ttl`() {
        val cache = ColumnCache(migratedDb())
        val p = profile()
        val key = columnObjectKey(schema, "users")
        cache.save(p, key, columns)
        val hit = cache.load(p, key)!!
        assertTrue(hit.isStale(hit.savedAtMs + 1, ttlMs = 0))
        assertTrue(!hit.isStale(hit.savedAtMs + 100, ttlMs = 1000))
    }
}
