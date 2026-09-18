package tree

import engine.model.DbObjectMeta
import engine.model.ObjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyHierarchyTest {

    private fun key(name: String, type: String = "string") = DbObjectMeta(name, ObjectKind.KEY, detail = type)

    @Test
    fun `empty separator flattens keys`() {
        val tree = buildKeyTree(listOf(key("a:b"), key("a")), "")
        assertEquals(listOf("a", "a:b"), tree.map { it.path })
        assertTrue(tree.all { it.children.isEmpty() })
    }

    @Test
    fun `keys without separator stay flat leaves`() {
        val tree = buildKeyTree(listOf(key("plain"), key("other")), ":")
        assertEquals(listOf("other", "plain"), tree.map { it.path })
        assertTrue(tree.all { it.children.isEmpty() && it.key != null })
    }

    @Test
    fun `colon keys nest into namespaces`() {
        val tree = buildKeyTree(listOf(key("a:b:c"), key("a:b:d"), key("a:x")), ":")
        assertEquals(listOf("a"), tree.map { it.segment })
        val a = tree.single()
        assertNull(a.key)
        assertEquals(listOf("b", "x"), a.children.map { it.segment })
        val b = a.children.first()
        assertEquals("a:b", b.path)
        assertEquals(listOf("c", "d"), b.children.map { it.segment })
        assertEquals("a:b:c", b.children[0].key?.name)
    }

    @Test
    fun `prefix that is also a key carries both key and children`() {
        val tree = buildKeyTree(listOf(key("a"), key("a:b")), ":")
        val a = tree.single()
        assertEquals("a", a.key?.name)
        assertEquals(listOf("b"), a.children.map { it.segment })
        assertEquals(2, a.descendantKeyCount())
    }

    @Test
    fun `multi-char separator works`() {
        val tree = buildKeyTree(listOf(key("user::1::name")), "::")
        val user = tree.single()
        assertEquals("user", user.path)
        assertEquals("1", user.children.single().segment)
        assertEquals("name", user.children.single().children.single().segment)
    }

    @Test
    fun `descendant count sums nested leaves`() {
        val tree = buildKeyTree(listOf(key("a:b"), key("a:c:d"), key("a:c:e"), key("z")), ":")
        val a = tree.first { it.segment == "a" }
        assertEquals(3, a.descendantKeyCount())
        val z = tree.first { it.segment == "z" }
        assertEquals(1, z.descendantKeyCount())
    }
}
