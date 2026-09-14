package app.ui

import engine.model.QueryColumn
import engine.model.QueryResult
import java.sql.Types
import jdbc.CellValue
import jdbc.WriteOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N3 行级写操作纯逻辑单测：本地 overlay 模型 + 由 overlay 生成 INSERT / DELETE / UPDATE 计划。
 */
class ResultWritePlanTest {

    private fun col(name: String, sqlType: Int = Types.VARCHAR) =
        QueryColumn(name = name, table = "users", baseColumn = name, sqlType = sqlType)

    private fun result(rows: List<List<String?>>) = QueryResult(
        sql = "SELECT id, name FROM users",
        columns = listOf(col("id", Types.INTEGER), col("name")),
        rows = rows,
    )

    private fun plan() = buildEditPlan(result(listOf(listOf("1", "a"))), primaryKeys = setOf("id"))!!

    // ---------- 模型 ----------

    @Test
    fun `result edits count and emptiness`() {
        assertTrue(ResultEdits.EMPTY.isEmpty)
        assertEquals(0, ResultEdits.EMPTY.count)
        val edits = ResultEdits(
            cells = mapOf(CellKey(0, 1) to CellValue("x")),
            inserts = listOf(PendingInsert(1)),
            deletes = setOf(0),
        )
        assertEquals(3, edits.count)
        assertFalse(edits.isEmpty)
    }

    @Test
    fun `insert has values only when an editable column is filled`() {
        val plan = plan()
        assertFalse(ResultEdits.EMPTY.insertHasValues(plan, PendingInsert(1)))
        assertTrue(ResultEdits.EMPTY.insertHasValues(plan, PendingInsert(1, mapOf(0 to CellValue("9")))))
        // 只填了不可编辑列（此处不存在）→ 仍算未填
        assertFalse(ResultEdits.EMPTY.insertHasValues(plan, PendingInsert(1, mapOf(99 to CellValue("x")))))
    }

    // ---------- INSERT ----------

    @Test
    fun `insert plan includes only filled columns`() {
        val plan = plan()
        val inserts = listOf(
            PendingInsert(1, mapOf(1 to CellValue("alice"))),
            PendingInsert(2, mapOf(0 to CellValue("7"), 1 to CellValue(null))),
            PendingInsert(3), // 未填 → 不生成
        )
        val plans = buildInsertPlans(plan, inserts)
        assertEquals(2, plans.size)
        assertEquals(listOf("name"), plans[0].columns.map { it.column })
        assertEquals(listOf("alice"), plans[0].columns.map { it.value.raw })
        // 显式 NULL 与空串都要保留在列清单里
        assertEquals(listOf("id", "name"), plans[1].columns.map { it.column })
        assertEquals(listOf("7", null), plans[1].columns.map { it.value.raw })
        assertEquals("users", plans[0].table)
    }

    // ---------- DELETE ----------

    @Test
    fun `delete plan locates rows by key`() {
        val r = result(listOf(listOf("1", "a"), listOf("2", "b"), listOf("3", "c")))
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))!!
        val plans = buildDeletePlans(r, plan, setOf(2, 0))
        assertEquals(2, plans.size)
        assertEquals(listOf("1"), plans[0].keys.map { it.value.raw })
        assertEquals(listOf("3"), plans[1].keys.map { it.value.raw })
        assertEquals("id", plans[0].keys.single().column)
    }

    @Test
    fun `delete plan ignores out of range rows`() {
        val r = result(listOf(listOf("1", "a")))
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))!!
        assertTrue(buildDeletePlans(r, plan, setOf(5)).isEmpty())
    }

    // ---------- 组合顺序 ----------

    @Test
    fun `write ops are ordered delete update insert`() {
        val r = result(listOf(listOf("1", "a"), listOf("2", "b")))
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))!!
        val edits = ResultEdits(
            cells = mapOf(CellKey(0, 1) to CellValue("A")),
            inserts = listOf(PendingInsert(1, mapOf(1 to CellValue("new")))),
            deletes = setOf(1),
        )
        val ops = buildWriteOps(r, plan, edits)
        assertEquals(3, ops.size)
        assertTrue(ops[0] is WriteOp.Delete)
        assertTrue(ops[1] is WriteOp.Update)
        assertTrue(ops[2] is WriteOp.Insert)
        // UPDATE 的 WHERE 用原始键
        assertEquals(listOf("1"), (ops[1] as WriteOp.Update).plan.keys.map { it.value.raw })
    }

    @Test
    fun `write ops empty for empty overlay`() {
        val r = result(listOf(listOf("1", "a")))
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))!!
        assertTrue(buildWriteOps(r, plan, ResultEdits.EMPTY).isEmpty())
    }
}
