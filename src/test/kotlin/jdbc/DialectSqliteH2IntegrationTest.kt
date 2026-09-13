package jdbc

import db.ConnectionProfile
import db.DbType
import engine.model.ObjectKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用嵌入式 SQLite / H2 实测方言探测，不依赖任何外部服务。 */
class DialectSqliteH2IntegrationTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `sqlite loads schemas and objects`() {
        val db = dir.resolve("demo.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
                st.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY)")
                st.execute("CREATE VIEW rich AS SELECT id FROM orders")
                st.execute("CREATE TRIGGER trg AFTER INSERT ON users BEGIN UPDATE orders SET id = NEW.id; END")
            }
        }
        val profile = ConnectionProfile(id = "p", name = "p", dbType = DbType.SQLITE, database = db.toString())
        SQLiteDialect.openConnection(profile).use { conn ->
            val schemas = SQLiteDialect.loadSchemas(conn)
            assertEquals(listOf("main"), schemas.map { it.displayName })

            val objects = SQLiteDialect.loadObjects(conn, schemas.first())
            assertEquals(listOf("orders", "users"), objects.tables)
            assertEquals(listOf("rich"), objects.views)
            assertEquals("trg", objects.triggers.single().name)
            assertEquals(
                setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.TRIGGER),
                objects.objects.keys,
            )

            // 列探测：单表精确查询，含类型与顺序
            val columns = SQLiteDialect.loadColumns(conn, schemas.first(), "users")
            assertEquals(listOf("id", "name"), columns.map { it.name })
            assertTrue(columns.all { it.ordinal > 0 })
            assertTrue(columns.first { it.name == "id" }.primaryKey, "SQLite 主键应被标记")
            assertTrue(columns.none { it.name == "name" && it.primaryKey })

            // 对象定义（Ctrl+Q）：sqlite_master.sql 即原始建表/建视图语句
            assertEquals(
                "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)",
                SQLiteDialect.tableDdl(conn, schemas.first(), "users"),
            )
            assertTrue(SQLiteDialect.tableDdl(conn, schemas.first(), "rich")!!.contains("CREATE VIEW"))
        }
    }

    @Test
    fun `sqlite falls back to unique index when no primary key`() {
        val db = dir.resolve("uniq.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE nopk (email TEXT UNIQUE, name TEXT)")
                st.execute("CREATE TABLE multi (a TEXT, b TEXT, note TEXT, UNIQUE (a, b))")
            }
        }
        val profile = ConnectionProfile(id = "p", name = "p", dbType = DbType.SQLITE, database = db.toString())
        SQLiteDialect.openConnection(profile).use { conn ->
            val schema = SQLiteDialect.loadSchemas(conn).first()
            val nopk = SQLiteDialect.loadColumns(conn, schema, "nopk")
            assertTrue(nopk.first { it.name == "email" }.primaryKey, "无主键时应回落唯一索引列")
            assertTrue(nopk.none { it.name == "name" && it.primaryKey })

            // 复合唯一索引：两列都应作为键（保证能唯一定位）
            val multi = SQLiteDialect.loadColumns(conn, schema, "multi")
            assertTrue(multi.first { it.name == "a" }.primaryKey)
            assertTrue(multi.first { it.name == "b" }.primaryKey)
            assertTrue(multi.none { it.name == "note" && it.primaryKey })
        }
    }

    @Test
    fun `h2 loads schemas and objects`() {
        val profile = ConnectionProfile(
            id = "p", name = "p", dbType = DbType.H2, host = "",
            database = dir.resolve("demo-h2").toString(), user = "sa", password = "",
        )
        H2Dialect.openConnection(profile).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE account (id INT PRIMARY KEY)")
                st.execute("CREATE VIEW v_account AS SELECT id FROM account")
            }
            val schemas = H2Dialect.loadSchemas(conn)
            assertTrue(schemas.any { it.displayName == "PUBLIC" })

            val objects = H2Dialect.loadObjects(conn, schemas.first { it.displayName == "PUBLIC" })
            assertTrue(objects.tables.any { it.equals("account", ignoreCase = true) })
            assertTrue(objects.views.any { it.equals("v_account", ignoreCase = true) })

            // 列探测：经 PUBLIC schema；小写表名应能命中原大写表（大小写兜底）
            val columns = H2Dialect.loadColumns(conn, schemas.first { it.displayName == "PUBLIC" }, "account")
            assertEquals(listOf("id"), columns.map { it.name.lowercase() })
            assertTrue(columns.single().primaryKey, "H2 主键应被标记")

            // 对象定义：H2 无内置 SHOW CREATE，走通用重建（列名/类型/NOT NULL）
            val ddl = H2Dialect.tableDdl(conn, schemas.first { it.displayName == "PUBLIC" }, "account")
            assertTrue(ddl != null && ddl.contains("CREATE TABLE"))
            assertTrue(ddl.contains("ID", ignoreCase = true))
            assertTrue(ddl.contains("NOT NULL", ignoreCase = true))
        }
    }
}
