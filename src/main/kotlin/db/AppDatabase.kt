package db

import java.sql.Connection
import java.sql.Statement

/**
 * 应用元数据 SQLite 迁移。版本化迁移机制照搬 api-x：
 * 每版在 schema_migrations 记录版本号，未应用的版本顺序执行。
 */
object AppDatabase {

    private const val CURRENT_VERSION = 6

    fun migrate(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY NOT NULL
                )
                """.trimIndent(),
            )
        }
        val applied = conn.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_migrations ORDER BY version").use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getInt(1))
                }
            }
        }
        if (!applied.contains(1)) {
            conn.createStatement().use { st -> migrateToV1(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (1)").use { it.executeUpdate() }
        }
        if (!applied.contains(2)) {
            conn.createStatement().use { st -> migrateToV2(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (2)").use { it.executeUpdate() }
        }
        if (!applied.contains(3)) {
            conn.createStatement().use { st -> migrateToV3(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (3)").use { it.executeUpdate() }
        }
        if (!applied.contains(4)) {
            conn.createStatement().use { st -> migrateToV4(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (4)").use { it.executeUpdate() }
        }
        if (!applied.contains(5)) {
            conn.createStatement().use { st -> migrateToV5(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (5)").use { it.executeUpdate() }
        }
        if (!applied.contains(6)) {
            conn.createStatement().use { st -> migrateToV6(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (6)").use { it.executeUpdate() }
        }
    }

    /**
     * v6：每个控制台记住编辑器光标/选区（重启后回到上次焦点所在行）。
     * 与 consoles 同行 → 删控制台/删连接时随行级联消失，无需额外清理。
     * 默认 0 = 从头开始；刻意不动 updated_at（由调用方保证），避免污染“最近改动的控制台”启发式。
     */
    private fun migrateToV6(st: Statement) {
        st.executeUpdate("ALTER TABLE consoles ADD COLUMN caret_start INTEGER NOT NULL DEFAULT 0")
        st.executeUpdate("ALTER TABLE consoles ADD COLUMN caret_end INTEGER NOT NULL DEFAULT 0")
    }

    /** v5：数据源目录元数据磁盘缓存（库列表 + 各库对象 JSON），支撑“连接默认读缓存、右键刷新”。 */
    private fun migrateToV5(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS meta_cache (
                profile_id TEXT PRIMARY KEY NOT NULL,
                fingerprint TEXT NOT NULL,
                saved_at_ms INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent(),
        )
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_meta_cache_fp ON meta_cache(fingerprint)")
    }

    /** v4：consoles 记录每控制台的执行目标库/schema（"" = 连接默认）。 */
    private fun migrateToV4(st: Statement) {
        st.executeUpdate("ALTER TABLE consoles ADD COLUMN target TEXT NOT NULL DEFAULT ''")
    }

    /** v3：sql_history 补记录成败/行数/错误，支撑执行历史面板。 */
    private fun migrateToV3(st: Statement) {
        st.executeUpdate("ALTER TABLE sql_history ADD COLUMN ok INTEGER NOT NULL DEFAULT 1")
        st.executeUpdate("ALTER TABLE sql_history ADD COLUMN row_count INTEGER NOT NULL DEFAULT 0")
        st.executeUpdate("ALTER TABLE sql_history ADD COLUMN error_message TEXT")
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sql_history_profile_at ON sql_history(profile_id, executed_at_ms)")
    }

    /** v2：SQL 控制台（一个数据源可有多个命名控制台，每个绑定一个 .sql 文件）。 */
    private fun migrateToV2(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS consoles (
                id TEXT PRIMARY KEY NOT NULL,
                connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                file_path TEXT NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_consoles_conn ON consoles(connection_id)")
    }

    private fun migrateToV1(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS folders (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                parent_id TEXT NULL REFERENCES folders(id) ON DELETE CASCADE,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS connections (
                id TEXT PRIMARY KEY NOT NULL,
                folder_id TEXT NULL REFERENCES folders(id) ON DELETE SET NULL,
                name TEXT NOT NULL,
                db_type TEXT NOT NULL,
                host TEXT NOT NULL DEFAULT '',
                port INTEGER NOT NULL DEFAULT 0,
                database_name TEXT NOT NULL DEFAULT '',
                user_name TEXT NULL,
                password TEXT NULL,
                extra_params TEXT NOT NULL DEFAULT '',
                color TEXT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS sql_history (
                id TEXT PRIMARY KEY NOT NULL,
                profile_id TEXT NULL REFERENCES connections(id) ON DELETE SET NULL,
                sql_text TEXT NOT NULL,
                executed_at_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sql_history_at ON sql_history(executed_at_ms)")
    }
}
