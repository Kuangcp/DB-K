package jdbc

import db.ConnectionProfile
import db.DbType
import engine.model.ObjectKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** H2 本地文件类型（DbType.H2LOCAL）：嵌入式读写本地磁盘，不依赖外部服务。 */
class H2LocalIntegrationTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `h2local creates file and loads schemas objects and ddl`() {
        val dbPath = dir.resolve("demo-h2local").toString()
        val profile = ConnectionProfile(
            id = "p", name = "p", dbType = DbType.H2LOCAL, host = "",
            database = dbPath, user = "sa", password = "",
        )
        // 本地文件形态：预览 URL 恒为 jdbc:h2:<path>
        assertEquals("jdbc:h2:$dbPath", profile.urlPreview())

        H2LocalDialect.openConnection(profile).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE account (id INT PRIMARY KEY, balance DECIMAL NOT NULL)")
                st.execute("CREATE VIEW v_account AS SELECT id FROM account")
            }
            val schemas = H2LocalDialect.loadSchemas(conn)
            val public = schemas.first { it.displayName == "PUBLIC" }

            val objects = H2LocalDialect.loadObjects(conn, public)
            assertTrue(objects.tables.any { it.equals("account", ignoreCase = true) })
            assertTrue(objects.views.any { it.equals("v_account", ignoreCase = true) })
            assertTrue(objects.objects.keys.containsAll(setOf(ObjectKind.TABLE, ObjectKind.VIEW)))

            // 小写表名应能命中原大写表（大小写兜底），主键标记可用
            val columns = H2LocalDialect.loadColumns(conn, public, "account")
            assertEquals(listOf("id", "balance"), columns.map { it.name.lowercase() })
            assertTrue(columns.first { it.name.equals("id", ignoreCase = true) }.primaryKey)

            // 无内置 SHOW CREATE → 走通用重建
            val ddl = H2LocalDialect.tableDdl(conn, public, "account")
            assertTrue(ddl != null && ddl.contains("CREATE TABLE"))
            assertTrue(ddl.contains("NOT NULL", ignoreCase = true))
        }

        // 本地文件确实落盘（H2 MVStore 默认后缀 .mv.db）
        assertTrue(Files.exists(dir.resolve("demo-h2local.mv.db")), "H2 本地库文件应已创建")
    }
}
