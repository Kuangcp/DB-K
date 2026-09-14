package jdbc

import app.ui.CellKey
import app.ui.buildEditPlan
import app.ui.buildUpdatePlans
import db.ConnectionProfile
import db.DbType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 行写回（[RowUpdater]）与"元数据 → 编辑计划 → UPDATE"链路的嵌入式 SQLite 实测。
 * 覆盖：主键标记 / QueryColumn 来源元数据 / 单事务 / 影响行数校验回滚 / 类型转换 / NULL 与空串。
 */
class RowUpdaterTest {

    @TempDir
    lateinit var dir: Path

    private fun open(): Connection {
        val db = dir.resolve("edit-${System.nanoTime()}.db")
        val profile = ConnectionProfile(id = "p", name = "p", dbType = DbType.SQLITE, database = db.toString())
        return SQLiteDialect.openConnection(profile)
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.scalar(sql: String): String? =
        QueryExecutor.execute(this, sql).rows.singleOrNull()?.singleOrNull()

    @Test
    fun `sqlite metadata drives edit plan and update`() {
        open().use { c ->
            c.exec("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)")
            c.exec("INSERT INTO users VALUES (1, 'alice', 30), (2, 'bob', 40)")

            val schema = SQLiteDialect.loadSchemas(c).first()
            val cols = SQLiteDialect.loadColumns(c, schema, "users")
            assertTrue(cols.first { it.name == "id" }.primaryKey, "id 应被标记为主键")
            assertFalse(cols.first { it.name == "name" }.primaryKey)

            val result = QueryExecutor.execute(c, "SELECT id, name, age FROM users ORDER BY id")
            assertEquals("users", result.columns[1].table, "结果列应带来源表")
            assertEquals("name", result.columns[1].baseColumn, "结果列应带真实列名")

            val plan = buildEditPlan(
                result,
                primaryKeys = cols.filter { it.primaryKey }.map { it.name.lowercase() }.toSet(),
                knownColumns = cols.map { it.name.lowercase() }.toSet(),
            )
            checkNotNull(plan)
            assertEquals("users", plan.table)
            assertEquals(listOf(0), plan.keyColumns)
            assertEquals(listOf(0, 1, 2), plan.columns.map { it.index })

            val edits = mapOf(CellKey(1, 1) to CellValue("Bobby"), CellKey(1, 2) to CellValue("41"))
            val plans = buildUpdatePlans(result, plan, edits)
            assertEquals(1, RowUpdater.executeBatch(c, plans, SQLiteDialect))
            assertEquals("Bobby", c.scalar("SELECT name FROM users WHERE id = 2"))
            assertEquals("41", c.scalar("SELECT age FROM users WHERE id = 2"))
        }
    }

    @Test
    fun `renderUpdateSql escapes and renders null key as is null`() {
        val plan = UpdatePlan(
            schema = null,
            table = "users",
            sets = listOf(ColumnValue("name", CellValue("O'Reilly"), Types.VARCHAR)),
            keys = listOf(ColumnValue("id", CellValue("1"), Types.INTEGER)),
        )
        assertEquals(
            "UPDATE \"users\" SET \"name\" = 'O''Reilly' WHERE \"id\" = '1';",
            RowUpdater.renderUpdateSql(plan, SQLiteDialect),
        )
        val nullKey = plan.copy(keys = listOf(ColumnValue("ext", CellValue(null), Types.VARCHAR)))
        assertTrue(RowUpdater.renderUpdateSql(nullKey, SQLiteDialect).endsWith("WHERE \"ext\" IS NULL;"))
    }

    @Test
    fun `affected rows not one rolls back`() {
        open().use { c ->
            c.exec("CREATE TABLE t (a TEXT, b TEXT)")
            c.exec("INSERT INTO t VALUES ('x', 'old1'), ('x', 'old2')")
            val bad = UpdatePlan(
                schema = null,
                table = "t",
                sets = listOf(ColumnValue("b", CellValue("new"), Types.VARCHAR)),
                keys = listOf(ColumnValue("a", CellValue("x"), Types.VARCHAR)),
            )
            val ex = assertFailsWith<IllegalStateException> { RowUpdater.executeBatch(c, listOf(bad), SQLiteDialect) }
            assertTrue(ex.message!!.contains("影响 2 行"), "错误应说明影响行数：${ex.message}")
            assertEquals(listOf("old1", "old2"), QueryExecutor.execute(c, "SELECT b FROM t").rows.map { it.single() })
        }
    }

    @Test
    fun `batch is atomic on constraint violation`() {
        open().use { c ->
            c.exec("CREATE TABLE acc (id INTEGER PRIMARY KEY, bal INTEGER CHECK (bal >= 0))")
            c.exec("INSERT INTO acc VALUES (1, 100), (2, 50)")
            val plans = listOf(
                UpdatePlan(null, "acc", listOf(ColumnValue("bal", CellValue("200"), Types.INTEGER)), listOf(ColumnValue("id", CellValue("1"), Types.INTEGER))),
                UpdatePlan(null, "acc", listOf(ColumnValue("bal", CellValue("-1"), Types.INTEGER)), listOf(ColumnValue("id", CellValue("2"), Types.INTEGER))),
            )
            assertFailsWith<SQLException> { RowUpdater.executeBatch(c, plans, SQLiteDialect) }
            assertEquals("100", c.scalar("SELECT bal FROM acc WHERE id = 1"), "第一条已执行也必须回滚")
            assertEquals("50", c.scalar("SELECT bal FROM acc WHERE id = 2"))
        }
    }

    @Test
    fun `null and empty string are distinct`() {
        open().use { c ->
            c.exec("CREATE TABLE s (id INTEGER PRIMARY KEY, v TEXT)")
            c.exec("INSERT INTO s VALUES (1, 'x')")
            val toNull = UpdatePlan(null, "s", listOf(ColumnValue("v", CellValue(null), Types.VARCHAR)), listOf(ColumnValue("id", CellValue("1"), Types.INTEGER)))
            RowUpdater.executeBatch(c, listOf(toNull), SQLiteDialect)
            assertEquals(1, QueryExecutor.execute(c, "SELECT COUNT(*) FROM s WHERE v IS NULL").rows.single().single()!!.toInt())

            val toEmpty = UpdatePlan(null, "s", listOf(ColumnValue("v", CellValue(""), Types.VARCHAR)), listOf(ColumnValue("id", CellValue("1"), Types.INTEGER)))
            RowUpdater.executeBatch(c, listOf(toEmpty), SQLiteDialect)
            assertEquals("", c.scalar("SELECT v FROM s WHERE id = 1"))
            assertEquals(1, QueryExecutor.execute(c, "SELECT COUNT(*) FROM s WHERE v = ''").rows.single().single()!!.toInt())
        }
    }

    @Test
    fun `invalid value type fails before writing`() {
        open().use { c ->
            c.exec("CREATE TABLE n (id INTEGER PRIMARY KEY, qty INTEGER)")
            c.exec("INSERT INTO n VALUES (1, 5)")
            val bad = UpdatePlan(null, "n", listOf(ColumnValue("qty", CellValue("abc"), Types.INTEGER)), listOf(ColumnValue("id", CellValue("1"), Types.INTEGER)))
            val ex = assertFailsWith<CellValueException> { RowUpdater.executeBatch(c, listOf(bad), SQLiteDialect) }
            assertTrue(ex.message!!.contains("qty"))
            assertEquals("5", c.scalar("SELECT qty FROM n WHERE id = 1"))
        }
    }

    @Test
    fun `composite primary key is supported`() {
        open().use { c ->
            c.exec("CREATE TABLE cp (a INTEGER, b INTEGER, v TEXT, PRIMARY KEY (a, b))")
            c.exec("INSERT INTO cp VALUES (1, 2, 'old')")
            val plan = UpdatePlan(
                null, "cp",
                sets = listOf(ColumnValue("v", CellValue("new"), Types.VARCHAR)),
                keys = listOf(
                    ColumnValue("a", CellValue("1"), Types.INTEGER),
                    ColumnValue("b", CellValue("2"), Types.INTEGER),
                ),
            )
            assertEquals(1, RowUpdater.executeBatch(c, listOf(plan), SQLiteDialect))
            assertEquals("new", c.scalar("SELECT v FROM cp WHERE a = 1 AND b = 2"))
        }
    }

    @Test
    fun `binary types are not editable`() {
        assertTrue(isBinarySqlType(Types.BLOB))
        assertTrue(isBinarySqlType(Types.VARBINARY))
        assertFalse(isBinarySqlType(Types.VARCHAR))
        assertFalse(isBinarySqlType(Types.INTEGER))
    }

    // ---------- N3：INSERT / DELETE 与混合提交 ----------

    private fun Connection.rowCount(table: String): Int =
        QueryExecutor.execute(this, "SELECT COUNT(*) FROM $table").rows.single().single()!!.toInt()

    @Test
    fun `insert and delete in single transaction`() {
        open().use { c ->
            c.exec("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
            c.exec("INSERT INTO users VALUES (1, 'alice'), (2, 'bob'), (3, 'carol')")

            val insert = InsertPlan(
                schema = null,
                table = "users",
                columns = listOf(ColumnValue("id", CellValue("4"), Types.INTEGER), ColumnValue("name", CellValue("dave"), Types.VARCHAR)),
            )
            val delete = DeletePlan(
                schema = null,
                table = "users",
                keys = listOf(ColumnValue("id", CellValue("2"), Types.INTEGER)),
            )
            val updated = UpdatePlan(
                null, "users",
                sets = listOf(ColumnValue("name", CellValue("ALICE"), Types.VARCHAR)),
                keys = listOf(ColumnValue("id", CellValue("1"), Types.INTEGER)),
            )

            val total = RowUpdater.executeWriteBatch(
                c,
                listOf(WriteOp.Delete(delete), WriteOp.Update(updated), WriteOp.Insert(insert)),
                SQLiteDialect,
            )
            assertEquals(3, total)
            assertEquals(3, c.rowCount("users"))
            assertEquals("ALICE", c.scalar("SELECT name FROM users WHERE id = 1"))
            assertEquals(null, c.scalar("SELECT name FROM users WHERE id = 2"))
            assertEquals("dave", c.scalar("SELECT name FROM users WHERE id = 4"))
        }
    }

    @Test
    fun `render insert and delete sql`() {
        val insert = InsertPlan(
            schema = null,
            table = "users",
            columns = listOf(ColumnValue("name", CellValue("O'Reilly"), Types.VARCHAR), ColumnValue("note", CellValue(null), Types.VARCHAR)),
        )
        assertEquals(
            "INSERT INTO \"users\" (\"name\", \"note\") VALUES ('O''Reilly', NULL);",
            RowUpdater.renderInsertSql(insert, SQLiteDialect),
        )
        val del = DeletePlan(null, "users", listOf(ColumnValue("id", CellValue("1"), Types.INTEGER)))
        assertEquals("DELETE FROM \"users\" WHERE \"id\" = '1';", RowUpdater.renderDeleteSql(del, SQLiteDialect))
        assertEquals(
            RowUpdater.renderDeleteSql(del, SQLiteDialect),
            RowUpdater.renderWriteSql(WriteOp.Delete(del), SQLiteDialect),
        )
    }

    @Test
    fun `mixed write batch rolls back when one delete misses`() {
        open().use { c ->
            c.exec("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
            c.exec("INSERT INTO users VALUES (1, 'alice')")
            val insert = InsertPlan(
                null, "users",
                listOf(ColumnValue("id", CellValue("2"), Types.INTEGER), ColumnValue("name", CellValue("bob"), Types.VARCHAR)),
            )
            // 删除不存在的行 → affected 0 → 整体回滚（含前面的插入）
            val missing = DeletePlan(null, "users", listOf(ColumnValue("id", CellValue("99"), Types.INTEGER)))
            val ex = assertFailsWith<IllegalStateException> {
                RowUpdater.executeWriteBatch(c, listOf(WriteOp.Insert(insert), WriteOp.Delete(missing)), SQLiteDialect)
            }
            assertTrue(ex.message!!.contains("影响 0 行"), ex.message!!)
            assertEquals(1, c.rowCount("users"), "插入也应回滚")
        }
    }

    @Test
    fun `insert with bound types and defaults`() {
        open().use { c ->
            c.exec("CREATE TABLE t (id INTEGER PRIMARY KEY, qty INTEGER, tag TEXT DEFAULT 'dflt')")
            // 只填 id 与 qty，tag 走库默认值
            val insert = InsertPlan(
                null, "t",
                listOf(ColumnValue("id", CellValue("1"), Types.INTEGER), ColumnValue("qty", CellValue("5"), Types.INTEGER)),
            )
            assertEquals(1, RowUpdater.executeWriteBatch(c, listOf(WriteOp.Insert(insert)), SQLiteDialect))
            assertEquals("5", c.scalar("SELECT qty FROM t WHERE id = 1"))
            assertEquals("dflt", c.scalar("SELECT tag FROM t WHERE id = 1"))
        }
    }
}
