package app.ui

import engine.model.QueryColumn
import engine.model.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultViewTest {

    private fun result(vararg rows: List<String?>): QueryResult {
        val cols = List(rows.first().size) { QueryColumn("c$it") }
        return QueryResult(sql = "select", columns = cols, rows = rows.toList())
    }

    private val data = result(
        listOf("b", "2", "x"),
        listOf("a", "10", "y"),
        listOf(null, "1", "x"),
        listOf("a", null, "z"),
    )

    @Test
    fun `no spec is identity`() {
        val v = buildResultView(data, ResultViewSpec())
        assertEquals(listOf(0, 1, 2, 3), v.rowOrder)
        assertFalse(v.filtered)
        assertEquals(4, v.totalRows)
    }

    @Test
    fun `quick filter matches any column case-insensitively`() {
        val v = buildResultView(data, ResultViewSpec(quickFilter = "X"))
        assertEquals(listOf(0, 2), v.rowOrder)
        assertTrue(v.filtered)
    }

    @Test
    fun `per column contains and not equals`() {
        val v = buildResultView(data, ResultViewSpec(filters = mapOf(0 to "a")))
        assertEquals(listOf(1, 3), v.rowOrder)
        val v2 = buildResultView(data, ResultViewSpec(filters = mapOf(1 to "!=1")))
        assertEquals(3, v2.rowOrder.size)
        assertFalse(2 in v2.rowOrder)
    }

    @Test
    fun `numeric comparison filters use numeric order`() {
        // c1 = 2,10,null,"1" → >2 只命中 10（字符串序会把 "10" 排到 "2" 前）
        val v = buildResultView(data, ResultViewSpec(filters = mapOf(1 to ">2")))
        assertEquals(listOf(1), v.rowOrder)
        val eq = buildResultView(data, ResultViewSpec(filters = mapOf(1 to "=2")))
        assertEquals(listOf(0), eq.rowOrder)
        val re = buildResultView(data, ResultViewSpec(filters = mapOf(2 to "~^[xy]$")))
        assertEquals(listOf(0, 1, 2), re.rowOrder)
    }

    @Test
    fun `sort ascending keeps nulls last and is numeric aware`() {
        val v = buildResultView(data, ResultViewSpec(sorts = listOf(SortSpec(1))))
        assertEquals(listOf(2, 0, 1, 3), v.rowOrder)
    }

    @Test
    fun `sort descending keeps nulls last`() {
        val v = buildResultView(data, ResultViewSpec(sorts = listOf(SortSpec(1, ascending = false))))
        assertEquals(listOf(1, 0, 2, 3), v.rowOrder)
    }

    @Test
    fun `multi column sort then stable tiebreak`() {
        val v = buildResultView(
            data,
            ResultViewSpec(sorts = listOf(SortSpec(0), SortSpec(1, ascending = false))),
        )
        // c0: a(10), a(null), b(2), null(1) —— null 最后；a 组内按 c1 降序（10 > null）
        assertEquals(listOf(1, 3, 0, 2), v.rowOrder)
    }

    @Test
    fun `filter composes with sort`() {
        val v = buildResultView(
            data,
            ResultViewSpec(sorts = listOf(SortSpec(1)), filters = mapOf(2 to "x")),
        )
        assertEquals(listOf(2, 0), v.rowOrder) // x 组：c1=1 在前，c1=2 在后
    }

    @Test
    fun `display to original maps view and transpose coordinates`() {
        val order = listOf(2, 0, 3, 1)
        assertEquals(CellKey(2, 1), displayToOriginal(order, 3, transposed = false, viewRow = 0, viewCol = 1))
        assertEquals(CellKey(1, 0), displayToOriginal(order, 3, transposed = false, viewRow = 3, viewCol = 0))
        assertNull(displayToOriginal(order, 3, transposed = false, viewRow = 4, viewCol = 0))
        assertNull(displayToOriginal(order, 3, transposed = false, viewRow = 0, viewCol = 3))
        // 转置：viewRow=原列，viewCol-1=视图行，viewCol=0 是标签列
        assertEquals(CellKey(3, 2), displayToOriginal(order, 3, transposed = true, viewRow = 2, viewCol = 3))
        assertNull(displayToOriginal(order, 3, transposed = true, viewRow = 2, viewCol = 0))
        assertNull(displayToOriginal(order, 3, transposed = true, viewRow = 3, viewCol = 1))
    }

    @Test
    fun `toggle sort cycles and appends when additive`() {
        val s0 = ResultViewSpec()
        val s1 = s0.toggleSort(1)
        assertEquals(listOf(SortSpec(1)), s1.sorts)
        val s2 = s1.toggleSort(1)
        assertEquals(false, s2.sorts.single().ascending)
        val s3 = s2.toggleSort(1)
        assertTrue(s3.sorts.isEmpty())
        val multi = s1.toggleSort(2, additive = true)
        assertEquals(listOf(SortSpec(1), SortSpec(2)), multi.sorts)
        // 已是多列时再点非末列依旧单列替换
        assertEquals(listOf(SortSpec(2)), multi.toggleSort(2).sorts)
    }

    @Test
    fun `parse column filter operators`() {
        assertEquals(ColumnFilter(FilterOp.CONTAINS, "ab"), parseColumnFilter("ab"))
        assertEquals(ColumnFilter(FilterOp.EQUALS, "ab"), parseColumnFilter("=ab"))
        assertEquals(ColumnFilter(FilterOp.NOT_EQUALS, "ab"), parseColumnFilter("!=ab"))
        assertEquals(ColumnFilter(FilterOp.GREATER_EQ, "5"), parseColumnFilter(">=5"))
        assertEquals(ColumnFilter(FilterOp.LESS, "5"), parseColumnFilter("<5"))
        assertEquals(ColumnFilter(FilterOp.REGEX, "^a"), parseColumnFilter("~^a"))
        assertNull(parseColumnFilter(""))
    }
}
