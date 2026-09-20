package tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbTreeDragTest {

    private fun rect(top: Float = 0f, bottom: Float = 20f, left: Float = 0f, right: Float = 200f) =
        Rect(left, top, right, bottom)

    @Test
    fun `connection onto connection top inserts before, bottom inserts after`() {
        val desc = RowDropDesc.ConnectionDesc("c2", folderId = "f", connectionIndex = 1)
        val zones = mapOf(desc.rowKey to (rect() to desc))

        val before = resolveDrop(TreeDragPayload.Connection("c1", null), zones, Offset(100f, 5f))!!
        assertEquals(DropIndicator.InsertBefore, before.indicator)
        assertEquals(TreeDropTarget.ConnectionSlot("f", 1), before.target)

        val after = resolveDrop(TreeDragPayload.Connection("c1", null), zones, Offset(100f, 15f))!!
        assertEquals(DropIndicator.InsertAfter, after.indicator)
        assertEquals(TreeDropTarget.ConnectionSlot("f", 2), after.target)
    }

    @Test
    fun `connection onto folder goes into folder`() {
        val desc = RowDropDesc.FolderDesc("f1", parentFolderId = null, folderIndex = 0)
        val zones = mapOf(desc.rowKey to (rect() to desc))

        val hit = resolveDrop(TreeDragPayload.Connection("c1", null), zones, Offset(100f, 5f))!!
        assertEquals(DropIndicator.Into, hit.indicator)
        assertEquals(TreeDropTarget.IntoFolder("f1"), hit.target)
    }

    @Test
    fun `connection onto root strip appends to root`() {
        val zones = mapOf(RowDropDesc.RootDesc.rowKey to (rect() to RowDropDesc.RootDesc))

        val hit = resolveDrop(TreeDragPayload.Connection("c1", "f1"), zones, Offset(100f, 10f))!!
        assertEquals(DropIndicator.Into, hit.indicator)
        assertEquals(TreeDropTarget.ConnectionSlot(null, Int.MAX_VALUE), hit.target)
    }

    @Test
    fun `folder onto folder top inserts before, bottom nests into`() {
        val desc = RowDropDesc.FolderDesc("f2", parentFolderId = "f1", folderIndex = 3)
        val zones = mapOf(desc.rowKey to (rect() to desc))

        val before = resolveDrop(TreeDragPayload.Folder("fX"), zones, Offset(100f, 5f))!!
        assertEquals(DropIndicator.InsertBefore, before.indicator)
        assertEquals(TreeDropTarget.FolderSlot("f1", 3), before.target)

        val into = resolveDrop(TreeDragPayload.Folder("fX"), zones, Offset(100f, 15f))!!
        assertEquals(DropIndicator.Into, into.indicator)
        assertEquals(TreeDropTarget.IntoFolder("f2"), into.target)
    }

    @Test
    fun `folder onto connection or root is null`() {
        val connDesc = RowDropDesc.ConnectionDesc("c2", folderId = null, connectionIndex = 0)
        val zones = mapOf(
            connDesc.rowKey to (rect() to connDesc),
            RowDropDesc.RootDesc.rowKey to (rect(top = 100f, bottom = 120f) to RowDropDesc.RootDesc),
        )
        assertNull(resolveDrop(TreeDragPayload.Folder("fX"), zones, Offset(100f, 10f)))
        assertNull(resolveDrop(TreeDragPayload.Folder("fX"), zones, Offset(100f, 110f)))
    }

    @Test
    fun `null payload yields null`() {
        val desc = RowDropDesc.ConnectionDesc("c2", null, 0)
        val zones = mapOf(desc.rowKey to (rect() to desc))
        assertNull(resolveDrop(null, zones, Offset(100f, 10f)))
    }

    @Test
    fun `hit picks smallest containing row`() {
        val outer = RowDropDesc.ConnectionDesc("c1", null, 0)
        val inner = RowDropDesc.ConnectionDesc("c2", null, 1)
        val zones = mapOf(
            outer.rowKey to (rect(top = 0f, bottom = 40f) to outer),
            inner.rowKey to (rect(top = 10f, bottom = 20f) to inner),
        )
        val hit = resolveDrop(TreeDragPayload.Connection("cX", null), zones, Offset(100f, 15f))!!
        assertEquals("c:c2", hit.rowKey)
    }
}
