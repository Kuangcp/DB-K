package db

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDatabaseMigrationTest {

    @TempDir
    lateinit var dir: Path

    private fun raw(db: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$db")

    @Test
    fun `fresh migrate applies all versions`() {
        val db = dir.resolve("fresh.db")
        raw(db).use { AppDatabase.migrate(it) }
        raw(db).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT version FROM schema_migrations ORDER BY version").use { rs ->
                    val versions = buildList { while (rs.next()) add(rs.getInt(1)) }
                    assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), versions)
                }
            }
        }
    }

    @Test
    fun `legacy v2 migrates preserving data and defaults`() {
        val db = dir.resolve("legacy.db")
        raw(db).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY NOT NULL)")
                st.execute(
                    "CREATE TABLE folders (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, " +
                        "parent_id TEXT NULL REFERENCES folders(id) ON DELETE CASCADE, " +
                        "sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
                )
                st.execute(
                    "CREATE TABLE connections (id TEXT PRIMARY KEY NOT NULL, " +
                        "folder_id TEXT NULL REFERENCES folders(id) ON DELETE SET NULL, name TEXT NOT NULL, " +
                        "db_type TEXT NOT NULL, host TEXT NOT NULL DEFAULT '', port INTEGER NOT NULL DEFAULT 0, " +
                        "database_name TEXT NOT NULL DEFAULT '', user_name TEXT NULL, password TEXT NULL, " +
                        "extra_params TEXT NOT NULL DEFAULT '', color TEXT NULL, sort_order INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                )
                st.execute(
                    "CREATE TABLE sql_history (id TEXT PRIMARY KEY NOT NULL, " +
                        "profile_id TEXT NULL REFERENCES connections(id) ON DELETE SET NULL, sql_text TEXT NOT NULL, " +
                        "executed_at_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL)",
                )
                st.execute(
                    "CREATE TABLE consoles (id TEXT PRIMARY KEY NOT NULL, " +
                        "connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE, name TEXT NOT NULL, " +
                        "file_path TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                )
                st.execute("CREATE INDEX idx_sql_history_at ON sql_history(executed_at_ms)")
                st.executeUpdate("INSERT INTO schema_migrations(version) VALUES (1),(2)")
                st.execute("INSERT INTO connections(id, name, db_type, sort_order, created_at, updated_at) VALUES ('c1','档案','SQLITE',0,1000,1000)")
                st.execute("INSERT INTO consoles(id, connection_id, name, file_path, sort_order, created_at, updated_at) VALUES ('cc1','c1','控制台','',0,1000,1000)")
                st.execute("INSERT INTO sql_history(id, profile_id, sql_text, executed_at_ms, duration_ms) VALUES ('h1','c1','SELECT 1',1000,5)")
            }
        }
        raw(db).use { AppDatabase.migrate(it) }
        raw(db).use { c ->
            c.createStatement().use { st ->
                // v3 旧历史行默认成功、0 行
                st.executeQuery("SELECT ok, row_count FROM sql_history WHERE id='h1'").use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt("ok"))
                    assertEquals(0, rs.getInt("row_count"))
                }
                // v4 旧控制台默认 target=''
                st.executeQuery("SELECT target FROM consoles WHERE id='cc1'").use { rs ->
                    rs.next()
                    assertEquals("", rs.getString("target"))
                }
                // v6 旧控制台默认 caret=0
                st.executeQuery("SELECT caret_start, caret_end FROM consoles WHERE id='cc1'").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt("caret_start"))
                    assertEquals(0, rs.getInt("caret_end"))
                }
                // v5 meta_cache 表存在且为空
                st.executeQuery("SELECT COUNT(*) FROM meta_cache").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
                // v7 column_cache 表存在且为空
                st.executeQuery("SELECT COUNT(*) FROM column_cache").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
                // v8 旧控制台默认 closed=0（未关闭）
                st.executeQuery("SELECT closed FROM consoles WHERE id='cc1'").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt("closed"))
                }
                // v9 旧连接默认 key_separator=':'
                st.executeQuery("SELECT key_separator FROM connections WHERE id='c1'").use { rs ->
                    rs.next()
                    assertEquals(":", rs.getString("key_separator"))
                }
            }
        }
    }

    @Test
    fun `v10 backfills only open consoles into one auto named workspace preserving order`() {
        val db = dir.resolve("v9.db")
        raw(db).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY NOT NULL)")
                st.execute(
                    "CREATE TABLE consoles (id TEXT PRIMARY KEY NOT NULL, connection_id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, file_path TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, target TEXT NOT NULL DEFAULT '', " +
                        "caret_start INTEGER NOT NULL DEFAULT 0, caret_end INTEGER NOT NULL DEFAULT 0, " +
                        "closed INTEGER NOT NULL DEFAULT 0)",
                )
                st.executeUpdate("INSERT INTO schema_migrations(version) VALUES (1),(2),(3),(4),(5),(6),(7),(8),(9)")
                st.execute("INSERT INTO consoles(id, connection_id, name, sort_order, created_at, updated_at, closed) VALUES ('b','c1','b',1,3,3,0)")
                st.execute("INSERT INTO consoles(id, connection_id, name, sort_order, created_at, updated_at, closed) VALUES ('a','c1','a',0,2,2,0)")
                st.execute("INSERT INTO consoles(id, connection_id, name, sort_order, created_at, updated_at, closed) VALUES ('x','c1','x',0,1,1,1)")
            }
        }
        raw(db).use { AppDatabase.migrate(it) }
        raw(db).use { c ->
            c.createStatement().use { st ->
                // 只建了一个工作区，auto_named=1
                st.executeQuery("SELECT id, auto_named FROM workspaces").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt("auto_named"))
                    assertFalse(rs.next())
                }
                // 未关闭的两个入区，按 sort_order 排序；已关闭的不入区
                st.executeQuery("SELECT console_id FROM workspace_consoles ORDER BY sort_order").use { rs ->
                    val ids = buildList { while (rs.next()) add(rs.getString(1)) }
                    assertEquals(listOf("a", "b"), ids)
                }
            }
        }
    }

    @Test
    fun `v10 creates no workspace when there is no open console`() {
        val db = dir.resolve("empty.db")
        raw(db).use { AppDatabase.migrate(it) }
        raw(db).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM workspaces").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }
}
