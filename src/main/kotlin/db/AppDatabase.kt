package db

import i18n.I18n
import i18n.Str
import java.sql.Connection
import java.sql.Statement
import java.util.UUID

/**
 * 应用元数据 SQLite 迁移。版本化迁移机制照搬 api-x：
 * 每版在 schema_migrations 记录版本号，未应用的版本顺序执行。
 */
object AppDatabase {

    private const val CURRENT_VERSION = 11

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
        if (!applied.contains(7)) {
            conn.createStatement().use { st -> migrateToV7(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (7)").use { it.executeUpdate() }
        }
        if (!applied.contains(8)) {
            conn.createStatement().use { st -> migrateToV8(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (8)").use { it.executeUpdate() }
        }
        if (!applied.contains(9)) {
            conn.createStatement().use { st -> migrateToV9(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (9)").use { it.executeUpdate() }
        }
        if (!applied.contains(10)) {
            conn.createStatement().use { st -> migrateToV10(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (10)").use { it.executeUpdate() }
        }
        if (!applied.contains(11)) {
            conn.createStatement().use { st -> migrateToV11(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (11)").use { it.executeUpdate() }
        }
    }

    /**
     * v7：单表列清单磁盘缓存（编辑器列补全；离线/重启可用）。一行 = 一表，
     * 指纹 = 连接档案 URL 身份，指纹失配按未命中。删除/编辑档案时按 profile_id 清理。
     */
    private fun migrateToV7(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS column_cache (
                profile_id TEXT NOT NULL,
                object_key TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                saved_at_ms INTEGER NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY (profile_id, object_key)
            )
            """.trimIndent(),
        )
    }

    /**
     * v9：Redis 键层级分隔符（数据源属性，删连接随行消失）。默认 `:`。
     */
    private fun migrateToV9(st: Statement) {
        st.executeUpdate("ALTER TABLE connections ADD COLUMN key_separator TEXT NOT NULL DEFAULT ':'")
    }

    /**
     * v10：工作区（控制台的虚拟分组）。
     * `workspace_consoles` 的成员关系即「是否显示在该工作区标签条」；主键去重；
     * 删工作区/删控制台/删连接（→ 删控制台）时由 FK 级联清理。
     * 回填：把存量「未关闭」控制台划入一个 auto_named 的默认工作区（标签顺序保持）；
     * 没有任何未关闭控制台（全新用户）则不建工作区（零工作区合法）。
     */
    private fun migrateToV10(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS workspaces (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                auto_named INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                last_active_console_id TEXT NULL
            )
            """.trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS workspace_consoles (
                workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
                console_id TEXT NOT NULL REFERENCES consoles(id) ON DELETE CASCADE,
                sort_order INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (workspace_id, console_id)
            )
            """.trimIndent(),
        )
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ws_consoles_console ON workspace_consoles(console_id)")

        val openIds = st.executeQuery(
            "SELECT id FROM consoles WHERE closed = 0 ORDER BY connection_id, sort_order, created_at",
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        if (openIds.isEmpty()) return

        val wsId = UUID.randomUUID().toString().replace("-", "").take(24)
        val now = System.currentTimeMillis()
        val defaultName = I18n.t(Str.WorkspaceDefaultName).replace("'", "''")
        st.executeUpdate(
            "INSERT INTO workspaces(id, name, auto_named, sort_order, created_at) " +
                "VALUES ('$wsId', '$defaultName', 1, 0, $now)",
        )
        openIds.forEachIndexed { i, cid ->
            st.executeUpdate(
                "INSERT INTO workspace_consoles(workspace_id, console_id, sort_order, added_at) " +
                    "VALUES ('$wsId', '$cid', $i, $now)",
            )
        }
    }

    /**
     * v11：工作区接管「关闭」语义后，consoles.closed 退役。
     */
    private fun migrateToV11(st: Statement) {
        st.executeUpdate("ALTER TABLE consoles DROP COLUMN closed")
    }

    /**
     * v8：控制台可「关闭」——从标签条隐藏，但保留元数据行与 .sql 文件；
     * 可从数据源右键「打开控制台」级联重新打开。同样不动 updated_at。
     */
    private fun migrateToV8(st: Statement) {
        st.executeUpdate("ALTER TABLE consoles ADD COLUMN closed INTEGER NOT NULL DEFAULT 0")
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
