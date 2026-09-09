package db

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * 应用元数据访问层：文件夹 / 连接档案 / SQL 历史，全部收敛于此。
 * 与 api-x 的 Repository 模式一致：单一 SQLite 连接 + PRAGMA foreign_keys + try-with-resources。
 */
class ConnectionsRepository(dbPath: Path) : AutoCloseable {

    private val keyFile: Path = PasswordVault.keyFileFor(dbPath)

    private val conn: Connection = DriverManager.getConnection(
        "jdbc:sqlite:${dbPath.toAbsolutePath()}",
        SQLiteConfig().apply { enforceForeignKeys(true) }.toProperties(),
    )

    init {
        AppDatabase.migrate(conn)
        // P3：存量明文密码原地转密；新写密码一律加密落盘（见 create/update）
        PasswordVault.migrateLegacyPasswords(conn, keyFile)
    }

    override fun close() {
        runCatching { conn.close() }
    }

    // ---------- folders ----------

    /** 全部根级文件夹（M1 仅一层）。 */
    fun listFolders(): List<FolderRow> {
        return conn.prepareStatement(
            "SELECT id, name, parent_id, sort_order FROM folders WHERE parent_id IS NULL ORDER BY sort_order, created_at",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(FolderRow(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            parentId = rs.getString("parent_id"),
                            sortOrder = rs.getInt("sort_order"),
                        ))
                    }
                }
            }
        }
    }

    fun createFolder(name: String): String {
        val id = newId()
        conn.prepareStatement("INSERT INTO folders(id, name, sort_order, created_at) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, name)
            ps.setInt(3, nextSortOrder("folders"))
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
        return id
    }

    fun renameFolder(id: String, name: String) {
        conn.prepareStatement("UPDATE folders SET name = ? WHERE id = ?").use { ps ->
            ps.setString(1, name)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** 删除文件夹，其下连接保留并移到根（folder_id 置 NULL）。返回受影响连接数。 */
    fun deleteFolder(id: String): Int {
        val moved = countConnectionsInFolder(id)
        conn.autoCommit = false
        try {
            conn.prepareStatement("UPDATE connections SET folder_id = NULL WHERE folder_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM folders WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
        return moved
    }

    fun countConnectionsInFolder(folderId: String): Int {
        return conn.prepareStatement("SELECT COUNT(*) FROM connections WHERE folder_id = ?").use { ps ->
            ps.setString(1, folderId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    // ---------- connections ----------

    fun listConnections(): List<ConnectionProfile> {
        return conn.prepareStatement(
            """
            SELECT id, folder_id, name, db_type, host, port, database_name, user_name, password,
                   extra_params, color, sort_order
            FROM connections ORDER BY sort_order, created_at
            """.trimIndent(),
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(mapRow(rs))
                    }
                }
            }
        }
    }

    fun getConnection(id: String): ConnectionProfile? {
        return conn.prepareStatement(
            "SELECT id, folder_id, name, db_type, host, port, database_name, user_name, password, extra_params, color, sort_order FROM connections WHERE id = ?",
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    /** 新建连接档案，返回新 id。 */
    fun createConnection(p: ConnectionProfile): String {
        val id = newId()
        conn.prepareStatement(
            """
            INSERT INTO connections(id, folder_id, name, db_type, host, port, database_name, user_name,
                password, extra_params, color, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            val now = System.currentTimeMillis()
            ps.setString(1, id)
            ps.setString(2, p.folderId)
            ps.setString(3, p.name)
            ps.setString(4, p.dbType.name)
            ps.setString(5, p.host)
            ps.setInt(6, p.port)
            ps.setString(7, p.database)
            ps.setString(8, p.user)
            ps.setString(9, PasswordVault.encrypt(keyFile, p.password))
            ps.setString(10, p.extraParams)
            ps.setString(11, p.color)
            ps.setInt(12, nextSortOrder("connections"))
            ps.setLong(13, now)
            ps.setLong(14, now)
            ps.executeUpdate()
        }
        return id
    }

    fun updateConnection(p: ConnectionProfile) {
        conn.prepareStatement(
            """
            UPDATE connections SET folder_id = ?, name = ?, db_type = ?, host = ?, port = ?,
                database_name = ?, user_name = ?, password = ?, extra_params = ?, color = ?,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, p.folderId)
            ps.setString(2, p.name)
            ps.setString(3, p.dbType.name)
            ps.setString(4, p.host)
            ps.setInt(5, p.port)
            ps.setString(6, p.database)
            ps.setString(7, p.user)
            ps.setString(8, PasswordVault.encrypt(keyFile, p.password))
            ps.setString(9, p.extraParams)
            ps.setString(10, p.color)
            ps.setLong(11, System.currentTimeMillis())
            ps.setString(12, p.id)
            ps.executeUpdate()
        }
    }

    fun deleteConnection(id: String) {
        conn.autoCommit = false
        try {
            // 删除该连接全部控制台及绑定的 .sql 文件（行级 ON DELETE CASCADE 兜底）
            listConsoles(id).forEach { rec ->
                if (rec.filePath.isNotBlank()) {
                    runCatching { ConsoleFiles.delete(java.nio.file.Paths.get(rec.filePath)) }
                }
            }
            conn.prepareStatement("DELETE FROM consoles WHERE connection_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM connections WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM meta_cache WHERE profile_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            // 连接删除后其历史 profile_id 被 FK 置 NULL；顺带清理历史孤儿行
            conn.createStatement().use { it.executeUpdate("DELETE FROM sql_history WHERE profile_id IS NULL") }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ---------- consoles（数据源 → 多个命名控制台，每个绑定一个 .sql 文件） ----------

    fun listConsoles(connectionId: String): List<ConsoleRecord> {
        return conn.prepareStatement(
            "SELECT id, connection_id, name, file_path, sort_order, updated_at, target FROM consoles WHERE connection_id = ? ORDER BY sort_order, created_at",
        ).use { ps ->
            ps.setString(1, connectionId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapConsole(rs))
                }
            }
        }
    }

    fun getConsole(id: String): ConsoleRecord? {
        return conn.prepareStatement(
            "SELECT id, connection_id, name, file_path, sort_order, updated_at, target FROM consoles WHERE id = ?",
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) mapConsole(rs) else null }
        }
    }

    /** 新建控制台：插元数据行 + 建空白 .sql 文件。返回完整记录。 */
    fun createConsole(connectionId: String, name: String): ConsoleRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val file = AppPaths.consoleFile(id)
        val rec = ConsoleRecord(
            id = id, connectionId = connectionId, name = name,
            filePath = file.toString(), sortOrder = nextSortOrder("consoles"), updatedAt = now,
        )
        conn.prepareStatement(
            """
            INSERT INTO consoles(id, connection_id, name, file_path, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, connectionId)
            ps.setString(3, name)
            ps.setString(4, rec.filePath)
            ps.setInt(5, rec.sortOrder)
            ps.setLong(6, now)
            ps.setLong(7, now)
            ps.executeUpdate()
        }
        ConsoleFiles.write(AppPaths.consoleFile(id), "")
        return rec
    }

    fun renameConsole(id: String, newName: String) {
        conn.prepareStatement("UPDATE consoles SET name = ?, updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, newName)
            ps.setLong(2, System.currentTimeMillis())
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    /** 设置控制台的执行目标库/schema（"" = 连接默认，不切换）。 */
    fun setConsoleTarget(id: String, target: String) {
        conn.prepareStatement("UPDATE consoles SET target = ?, updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, target)
            ps.setLong(2, System.currentTimeMillis())
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    /** 删除控制台：删行 + 删其 .sql 文件。 */
    fun deleteConsole(id: String) {
        val rec = getConsole(id) ?: return
        conn.prepareStatement("DELETE FROM consoles WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
        if (rec.filePath.isNotBlank()) {
            runCatching { ConsoleFiles.delete(java.nio.file.Paths.get(rec.filePath)) }
        }
    }

    /** 控制台正文读写（文件）。 */
    fun readConsoleContent(id: String): String {
        val rec = getConsole(id) ?: return ""
        return ConsoleFiles.read(java.nio.file.Paths.get(rec.filePath))
    }

    fun writeConsoleContent(id: String, text: String) {
        val rec = getConsole(id) ?: return
        ConsoleFiles.write(java.nio.file.Paths.get(rec.filePath), text)
        conn.prepareStatement("UPDATE consoles SET updated_at = ? WHERE id = ?").use { ps ->
            ps.setLong(1, System.currentTimeMillis())
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    private fun mapConsole(rs: java.sql.ResultSet): ConsoleRecord = ConsoleRecord(
        id = rs.getString("id"),
        connectionId = rs.getString("connection_id"),
        name = rs.getString("name"),
        filePath = rs.getString("file_path"),
        sortOrder = rs.getInt("sort_order"),
        updatedAt = rs.getLong("updated_at"),
        target = rs.getString("target") ?: "",
    )

    // ---------- sql_history（执行历史） ----------

    private val HISTORY_LIMIT = 200

    /** 写一条执行历史（成功后自动按档案裁剪到 HISTORY_LIMIT 条）。 */
    fun insertHistory(
        profileId: String,
        sqlText: String,
        ok: Boolean,
        executedAtMs: Long,
        durationMs: Long,
        rowCount: Int,
        errorMessage: String? = null,
    ): SqlHistoryRow {
        val id = newId()
        conn.prepareStatement(
            """
            INSERT INTO sql_history(id, profile_id, sql_text, ok, executed_at_ms, duration_ms, row_count, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, profileId)
            ps.setString(3, sqlText.take(20000))
            ps.setInt(4, if (ok) 1 else 0)
            ps.setLong(5, executedAtMs)
            ps.setLong(6, durationMs)
            ps.setInt(7, rowCount)
            ps.setString(8, errorMessage?.take(400))
            ps.executeUpdate()
        }
        pruneHistory(profileId)
        return SqlHistoryRow(
            id = id, profileId = profileId, sqlText = sqlText, ok = ok,
            executedAtMs = executedAtMs, durationMs = durationMs, rowCount = rowCount,
            errorMessage = errorMessage,
        )
    }

    /** 某档案最近 N 条历史，新→旧。 */
    fun listHistoryByProfile(profileId: String, limit: Int = HISTORY_LIMIT): List<SqlHistoryRow> {
        return conn.prepareStatement(
            """
            SELECT id, profile_id, sql_text, ok, executed_at_ms, duration_ms, row_count, error_message
            FROM sql_history WHERE profile_id = ?
            ORDER BY executed_at_ms DESC, id DESC LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, profileId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapHistory(rs))
                }
            }
        }
    }

    /** 清空某档案的历史。 */
    fun clearHistoryForProfile(profileId: String) {
        conn.prepareStatement("DELETE FROM sql_history WHERE profile_id = ?").use { ps ->
            ps.setString(1, profileId)
            ps.executeUpdate()
        }
    }

    private fun pruneHistory(profileId: String) {
        conn.prepareStatement(
            "DELETE FROM sql_history WHERE profile_id = ? AND id NOT IN (SELECT id FROM sql_history WHERE profile_id = ? ORDER BY executed_at_ms DESC LIMIT ?)",
        ).use { ps ->
            ps.setString(1, profileId)
            ps.setString(2, profileId)
            ps.setInt(3, HISTORY_LIMIT)
            ps.executeUpdate()
        }
    }

    private fun mapHistory(rs: java.sql.ResultSet): SqlHistoryRow = SqlHistoryRow(
        id = rs.getString("id"),
        profileId = rs.getString("profile_id"),
        sqlText = rs.getString("sql_text"),
        ok = rs.getInt("ok") != 0,
        executedAtMs = rs.getLong("executed_at_ms"),
        durationMs = rs.getLong("duration_ms"),
        rowCount = rs.getInt("row_count"),
        errorMessage = rs.getString("error_message"),
    )

    // ---------- helpers ----------

    private fun mapRow(rs: java.sql.ResultSet): ConnectionProfile = ConnectionProfile(
        id = rs.getString("id"),
        folderId = rs.getString("folder_id"),
        name = rs.getString("name"),
        dbType = DbType.valueOf(rs.getString("db_type")),
        host = rs.getString("host"),
        port = rs.getInt("port"),
        database = rs.getString("database_name"),
        user = rs.getString("user_name"),
        password = PasswordVault.decrypt(keyFile, rs.getString("password")),
        extraParams = rs.getString("extra_params"),
        color = rs.getString("color"),
        sortOrder = rs.getInt("sort_order"),
    )

    private fun nextSortOrder(table: String): Int {
        return conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM $table").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(24)
}
