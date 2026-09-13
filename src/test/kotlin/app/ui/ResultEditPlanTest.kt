package app.ui

import jdbc.CellValue
import engine.model.QueryColumn
import engine.model.QueryResult
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 可编辑结果集纯逻辑单测：编辑计划判定 / 转置坐标映射 / 应用修改 / 生成 UPDATE 计划。
 */
class ResultEditPlanTest {

    private fun col(
        name: String,
        table: String? = "users",
        base: String? = name,
        sqlType: Int = Types.VARCHAR,
        schema: String? = null,
        catalog: String? = null,
        readOnly: Boolean = false,
        autoIncrement: Boolean = false,
    ) = QueryColumn(
        name = name,
        catalog = catalog,
        schema = schema,
        table = table,
        baseColumn = base,
        sqlType = sqlType,
        readOnly = readOnly,
        autoIncrement = autoIncrement,
    )

    private fun result(vararg cols: QueryColumn, rows: List<List<String?>> = listOf(listOf("1", "Bob", null))) =
        QueryResult(sql = "SELECT * FROM users", columns = cols.toList(), rows = rows)

    // ---------- 编辑计划判定 ----------

    @Test
    fun buildsPlanForSingleBaseTableWithPrimaryKey() {
        val r = result(
            col("id", sqlType = Types.INTEGER),
            col("name"),
            col("note"),
        )
        val plan = buildEditPlan(r, primaryKeys = setOf("id"), knownColumns = setOf("id", "name", "note"))
        checkNotNull(plan)
        assertEquals("users", plan.table)
        assertEquals(listOf(0), plan.keyColumns)
        assertEquals(listOf(0, 1, 2), plan.columns.map { it.index })
        assertTrue(plan.columnAt(1)!!.baseColumn == "name")
    }

    @Test
    fun expressionColumnIsNotEditableButOthersAre() {
        val r = result(
            col("id", sqlType = Types.INTEGER),
            col("name"),
            col("upper", table = null, base = null),
        )
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))
        checkNotNull(plan)
        assertEquals(listOf(0, 1), plan.columns.map { it.index })
        assertEquals(listOf(0), plan.keyColumns)
    }

    @Test
    fun noPrimaryKeyYieldsNull() {
        val r = result(col("id", sqlType = Types.INTEGER), col("name"))
        assertNull(buildEditPlan(r, primaryKeys = emptySet()))
    }

    @Test
    fun viewYieldsNull() {
        val r = result(col("id", sqlType = Types.INTEGER), col("name"))
        assertNull(buildEditPlan(r, primaryKeys = setOf("id"), isBaseTable = false))
    }

    @Test
    fun missingKeyColumnInResultYieldsNull() {
        val r = result(col("name"), col("note"))
        assertNull(buildEditPlan(r, primaryKeys = setOf("id")))
    }

    @Test
    fun columnsFromDifferentTablesYieldNull() {
        val r = result(col("id", table = "users", sqlType = Types.INTEGER), col("x", table = "orders"))
        assertNull(buildEditPlan(r, primaryKeys = setOf("id")))
    }

    @Test
    fun knownColumnsRejectsUnknownBaseColumn() {
        val r = result(col("id", table = "users", base = "bogus", sqlType = Types.INTEGER))
        assertNull(buildEditPlan(r, primaryKeys = setOf("id"), knownColumns = setOf("id", "name")))
    }

    @Test
    fun autoIncrementPrimaryKeyIsLocatorButNotEditable() {
        val r = result(
            col("id", sqlType = Types.INTEGER, readOnly = true, autoIncrement = true),
            col("name"),
        )
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))
        checkNotNull(plan)
        assertEquals(listOf(0), plan.keyColumns)
        assertEquals(listOf(1), plan.columns.map { it.index }, "自增主键不可编辑，但仍作为定位键")
        assertNull(plan.columnAt(0))
    }

    @Test
    fun readOnlyColumnExcluded() {
        val r = result(
            col("id", sqlType = Types.INTEGER),
            col("created", readOnly = true),
            col("name"),
        )
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))
        checkNotNull(plan)
        assertEquals(listOf(0, 2), plan.columns.map { it.index })
    }

    @Test
    fun binaryColumnNotEditableAndBinaryKeyYieldsNull() {
        val r = result(
            col("id", sqlType = Types.INTEGER),
            col("blob", sqlType = Types.BLOB),
        )
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))
        checkNotNull(plan)
        assertEquals(listOf(0), plan.columns.map { it.index })

        val binaryKey = result(col("data", sqlType = Types.BLOB), col("name"))
        assertNull(buildEditPlan(binaryKey, primaryKeys = setOf("data")))
    }

    @Test
    fun schemaAndCatalogCarryIntoPlan() {
        val r = result(col("id", table = "users", schema = "public", catalog = "db", sqlType = Types.INTEGER))
        val plan = buildEditPlan(r, primaryKeys = setOf("id"))
        checkNotNull(plan)
        assertEquals("public", plan.schemaMeta.schema)
        assertEquals("db", plan.schemaMeta.catalog)
    }

    // ---------- 转置坐标映射 ----------

    @Test
    fun viewToOriginalIdentityWhenNotTransposed() {
        val r = result(col("id"), col("name"), rows = listOf(listOf("1", "a"), listOf("2", "b")))
        assertEquals(CellKey(0, 1), viewToOriginal(r, transposed = false, viewRow = 0, viewCol = 1))
        assertNull(viewToOriginal(r, transposed = false, viewRow = 5, viewCol = 0))
    }

    @Test
    fun viewToOriginalMapsTransposedCoordinates() {
        // 原 2 列 × 2 行；转置后 2 行（原列）× 3 列（列名 + 2 行）
        val r = result(col("id"), col("name"), rows = listOf(listOf("1", "a"), listOf("2", "b")))
        // viewRow=0 对应原第 0 列；viewCol=2 对应原第 1 行
        assertEquals(CellKey(1, 0), viewToOriginal(r, transposed = true, viewRow = 0, viewCol = 2))
        // viewCol=0 是“列名”标签列，不可映射
        assertNull(viewToOriginal(r, transposed = true, viewRow = 0, viewCol = 0))
        assertNull(viewToOriginal(r, transposed = true, viewRow = 9, viewCol = 2))
    }

    // ---------- 应用修改 ----------

    @Test
    fun applyEditsOverlaysCellsAndKeepsOthers() {
        val r = result(col("id"), col("name"), rows = listOf(listOf("1", "a"), listOf("2", "b")))
        val edited = applyEdits(
            r,
            mapOf(CellKey(0, 1) to CellValue("A"), CellKey(1, 1) to CellValue(null)),
        )
        assertEquals(listOf("1", "A"), edited.rows[0])
        assertEquals(listOf("2", null), edited.rows[1], "null 与空串需区分")
        assertEquals(r.rows[0], listOf("1", "a"), "原结果不被改动")
    }

    // ---------- 生成 UPDATE 计划 ----------

    private val plan = buildEditPlan(
        result(col("id", sqlType = Types.INTEGER), col("name"), rows = listOf(listOf("7", "old"))),
        primaryKeys = setOf("id"),
    )!!

    @Test
    fun buildUpdatePlansUsesOriginalValuesForWhere() {
        val r = result(col("id", sqlType = Types.INTEGER), col("name"), rows = listOf(listOf("7", "old")))
        val plans = buildUpdatePlans(
            r,
            buildEditPlan(r, primaryKeys = setOf("id"))!!,
            mapOf(CellKey(0, 0) to CellValue("9"), CellKey(0, 1) to CellValue("new")),
        )
        val only = plans.single()
        assertEquals("users", only.table)
        assertEquals(listOf("id", "name"), only.sets.map { it.column })
        assertEquals(listOf("9", "new"), only.sets.map { it.value.raw })
        // WHERE 用原始 id=7，即便用户同时改了 id
        assertEquals(listOf("id"), only.keys.map { it.column })
        assertEquals(listOf("7"), only.keys.map { it.value.raw })
    }

    @Test
    fun buildUpdatePlansGroupsByRow() {
        val r = result(
            col("id", sqlType = Types.INTEGER),
            col("name"),
            col("note"),
            rows = listOf(listOf("1", "a", "x"), listOf("2", "b", "y")),
        )
        val p = buildEditPlan(r, primaryKeys = setOf("id"))!!
        val plans = buildUpdatePlans(r, p, mapOf(CellKey(0, 1) to CellValue("A"), CellKey(1, 2) to CellValue("Y")))
        assertEquals(2, plans.size)
        assertEquals(listOf("1"), plans[0].keys.map { it.value.raw })
        assertEquals(listOf("2"), plans[1].keys.map { it.value.raw })
        assertEquals(listOf("name"), plans[0].sets.map { it.column })
        assertEquals(listOf("note"), plans[1].sets.map { it.column })
    }

    @Test
    fun buildUpdatePlansEmptyForNoEdits() {
        assertTrue(buildUpdatePlans(result(col("id")), plan, emptyMap()).isEmpty())
    }
}
