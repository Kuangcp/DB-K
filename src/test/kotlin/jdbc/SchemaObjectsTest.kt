package jdbc

import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.SchemaObjects
import engine.model.isPreviewable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaObjectsTest {

    @Test
    fun `simple ignores empty groups`() {
        val obj = SchemaObjects.simple(emptyList(), emptyList())
        assertTrue(obj.isEmpty)
        assertEquals(0, obj.total)
        assertEquals(emptyList(), obj.tables)
        assertEquals(emptyList(), obj.views)
        assertEquals(emptyList(), obj.triggers)
    }

    @Test
    fun `simple builds tables views and triggers`() {
        val trigger = DbObjectMeta("trg", ObjectKind.TRIGGER, "users")
        val obj = SchemaObjects.simple(
            tables = listOf("users", "orders"),
            views = listOf("rich_orders"),
            triggers = listOf(trigger),
        )
        assertEquals(listOf("users", "orders"), obj.tables)
        assertEquals(listOf("rich_orders"), obj.views)
        assertEquals(listOf(trigger), obj.triggers)
        assertEquals(4, obj.total)
        assertEquals(
            setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.TRIGGER),
            obj.objects.keys,
        )
    }

    @Test
    fun `extra empty group is dropped`() {
        val obj = SchemaObjects.simple(
            tables = listOf("t"),
            views = emptyList(),
            extra = mapOf(ObjectKind.MATERIALIZED_VIEW to emptyList()),
        )
        assertEquals(setOf(ObjectKind.TABLE), obj.objects.keys)
    }

    @Test
    fun `forKind returns empty for missing kind`() {
        val obj = SchemaObjects.simple(listOf("t"), emptyList())
        assertEquals(emptyList(), obj.forKind(ObjectKind.VIEW))
        assertEquals(1, obj.forKind(ObjectKind.TABLE).size)
    }

    @Test
    fun `total sums all groups`() {
        val obj = SchemaObjects(
            mapOf(
                ObjectKind.TABLE to listOf(DbObjectMeta("a", ObjectKind.TABLE), DbObjectMeta("b", ObjectKind.TABLE)),
                ObjectKind.VIEW to listOf(DbObjectMeta("v", ObjectKind.VIEW)),
            ),
        )
        assertEquals(3, obj.total)
        assertFalse(obj.isEmpty)
    }

    @Test
    fun `partial load exposes counts without bodies`() {
        val obj = SchemaObjects(
            objects = mapOf(ObjectKind.TABLE to listOf(DbObjectMeta("a", ObjectKind.TABLE))),
            counts = mapOf(ObjectKind.TABLE to 5, ObjectKind.ROUTINE to 1200),
        )
        // 已加载组以实际条数为准（最可信），未加载组用计数
        assertEquals(1, obj.countOf(ObjectKind.TABLE))
        assertEquals(1200, obj.countOf(ObjectKind.ROUTINE))
        assertEquals(0, obj.countOf(ObjectKind.VIEW))
        assertEquals(1201, obj.total)
        assertTrue(obj.isLoaded(ObjectKind.TABLE))
        assertFalse(obj.isLoaded(ObjectKind.ROUTINE))
        assertFalse(obj.isEmpty)
    }

    @Test
    fun `zero counts are still empty`() {
        val obj = SchemaObjects(counts = mapOf(ObjectKind.ROUTINE to 0))
        assertTrue(obj.isEmpty)
        assertEquals(0, obj.total)
    }

    @Test
    fun `previewable kinds are table view materialized view`() {
        assertTrue(ObjectKind.TABLE.isPreviewable())
        assertTrue(ObjectKind.VIEW.isPreviewable())
        assertTrue(ObjectKind.MATERIALIZED_VIEW.isPreviewable())
        assertFalse(ObjectKind.TRIGGER.isPreviewable())
        assertFalse(ObjectKind.SEQUENCE.isPreviewable())
        assertFalse(ObjectKind.ROUTINE.isPreviewable())
    }
}
