package jdbc

import db.ConnectionProfile
import db.DbType
import jdbc.model.ObjectKind
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
        }
    }
}
