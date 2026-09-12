package db

import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetaCacheTest {

    @TempDir
    lateinit var dir: Path

    private fun profile(id: String = "p1", host: String = "h") = ConnectionProfile(
        id = id, name = "c", dbType = DbType.SQLITE, host = host, database = "x.db",
    )

    private fun migratedDb(): Path = dir.resolve("app.db").also { db ->
        DriverManager.getConnection("jdbc:sqlite:$db").use { AppDatabase.migrate(it) }
    }

    private val schemas = listOf(SchemaMeta(null, "main"))
    private val objects = mapOf(
        SchemaMeta(null, "main").key to SchemaObjects.simple(listOf("t"), emptyList()),
    )

    @Test
    fun `save and load round trip`() {
        val mc = MetaCache(migratedDb())
        val p = profile()
        mc.save(p, schemas, objects)
        val hit = mc.load(p)
        assertNotNull(hit)
        assertEquals(schemas, hit.schemas)
        assertEquals(objects, hit.objects)
        assertFalse(hit.stale)
    }

    @Test
    fun `partial counts survive round trip`() {
        val mc = MetaCache(migratedDb())
        val p = profile()
        val partial = mapOf(
            SchemaMeta(null, "main").key to SchemaObjects(
                objects = mapOf(ObjectKind.TABLE to listOf(DbObjectMeta("t", ObjectKind.TABLE))),
                counts = mapOf(ObjectKind.TABLE to 1, ObjectKind.ROUTINE to 1200),
            ),
        )
        mc.save(p, schemas, partial)
        val hit = mc.load(p)
        assertNotNull(hit)
        // counts 随缓存持久化：重开应用后组行仍能直接显示计数，不必重查
        assertEquals(partial, hit.objects)
        assertEquals(1200, hit.objects.values.single().countOf(ObjectKind.ROUTINE))
        assertFalse(hit.objects.values.single().isLoaded(ObjectKind.ROUTINE))
    }

    @Test
    fun `fingerprint mismatch returns miss`() {
        val mc = MetaCache(migratedDb())
        val p = profile()
        mc.save(p, schemas, objects)
        assertNull(mc.load(p.copy(host = "other-host")))
    }

    @Test
    fun `delete removes cache row`() {
        val mc = MetaCache(migratedDb())
        val p = profile()
        mc.save(p, schemas, objects)
        mc.delete(p.id)
        assertNull(mc.load(p))
    }

    @Test
    fun `empty schemas treated as miss`() {
        val mc = MetaCache(migratedDb())
        val p = profile()
        mc.save(p, emptyList(), emptyMap())
        assertNull(mc.load(p))
    }
}
