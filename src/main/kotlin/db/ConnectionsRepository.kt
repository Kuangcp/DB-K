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
class ConnectionsRepository(
    dbPath: Path,
    /** 控制台 .sql 文件目录；默认跟随应用数据目录，测试可注入临时目录隔离。 */
    private val consolesDir: Path = AppPaths.consolesDir(),
) : AutoCloseable {

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

    /** 全部文件夹（含嵌套层级；parentId 为父文件夹，null = 根级）。 */
    fun listFolders(): List<FolderRow> {
        return conn.prepareStatement(
            "SELECT id, name, parent_id, sort_order FROM folders ORDER BY sort_order, created_at",
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

    fun createFolder(name: String, parentId: String? = null): String {
        val id = newId()
        conn.prepareStatement("INSERT INTO folders(id, name, parent_id, sort_order, created_at) VALUES (?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, name)
            if (parentId == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, parentId)
            ps.setInt(4, nextSortOrder("folders"))
            ps.setLong(5, System.currentTimeMillis())
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

    /** 删除文件夹：其下连接保留并移到根（folder_id 置 NULL）；子文件夹上移到被删文件夹的父级。返回受影响（移根）连接数。 */
    fun deleteFolder(id: String): Int {
        val moved = countConnectionsInFolder(id)
        conn.autoCommit = false
        try {
            val parentId = getFolderParentId(id)
            // 子文件夹先上移，否则 FK ON DELETE CASCADE 会连坐删除整棵子树
            conn.prepareStatement("UPDATE folders SET parent_id = ? WHERE parent_id = ?").use { ps ->
                if (parentId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, parentId)
                ps.setString(2, id)
                ps.executeUpdate()
            }
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

    fun getFolderParentId(folderId: String): String? {
        return conn.prepareStatement("SELECT parent_id FROM folders WHERE id = ?").use { ps ->
            ps.setString(1, folderId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("parent_id") else null
            }
        }
    }

    fun countChildFolders(folderId: String): Int {
        return conn.prepareStatement("SELECT COUNT(*) FROM folders WHERE parent_id = ?").use { ps ->
            ps.setString(1, folderId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
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

    // ---------- 拖拽排序 / 移动 ----------

    /** 某父文件夹（null=根级）下连接 id 的有序列表。 */
    fun loadOrderedConnectionIds(folderId: String?): List<String> {
        val sql = if (folderId == null) {
            "SELECT id FROM connections WHERE folder_id IS NULL ORDER BY sort_order, created_at"
        } else {
            "SELECT id FROM connections WHERE folder_id = ? ORDER BY sort_order, created_at"
        }
        val out = mutableListOf<String>()
        conn.prepareStatement(sql).use { ps ->
            if (folderId != null) ps.setString(1, folderId)
            ps.executeQuery().use { rs -> while (rs.next()) out += rs.getString("id") }
        }
        return out
    }

    /** 某父文件夹（null=根级）下子文件夹 id 的有序列表。 */
    fun loadOrderedFolderIds(parentId: String?): List<String> {
        val sql = if (parentId == null) {
            "SELECT id FROM folders WHERE parent_id IS NULL ORDER BY sort_order, created_at"
        } else {
            "SELECT id FROM folders WHERE parent_id = ? ORDER BY sort_order, created_at"
        }
        val out = mutableListOf<String>()
        conn.prepareStatement(sql).use { ps ->
            if (parentId != null) ps.setString(1, parentId)
            ps.executeQuery().use { rs -> while (rs.next()) out += rs.getString("id") }
        }
        return out
    }

    private fun reorderConnections(folderId: String?, orderedIds: List<String>) {
        val now = System.currentTimeMillis()
        for ((i, id) in orderedIds.withIndex()) {
            conn.prepareStatement("UPDATE connections SET folder_id = ?, sort_order = ?, updated_at = ? WHERE id = ?").use { ps ->
                if (folderId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, folderId)
                ps.setInt(2, i)
                ps.setLong(3, now)
                ps.setString(4, id)
                ps.executeUpdate()
            }
        }
    }

    private fun reorderFolders(parentId: String?, orderedIds: List<String>) {
        for ((i, id) in orderedIds.withIndex()) {
            conn.prepareStatement("UPDATE folders SET parent_id = ?, sort_order = ? WHERE id = ?").use { ps ->
                if (parentId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, parentId)
                ps.setInt(2, i)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    private fun getConnectionFolderId(id: String): String? {
        return conn.prepareStatement("SELECT folder_id FROM connections WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("folder_id") else null }
        }
    }

    private fun connectionExists(id: String): Boolean {
        return conn.prepareStatement("SELECT 1 FROM connections WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun folderExists(id: String): Boolean {
        return conn.prepareStatement("SELECT 1 FROM folders WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    /** 把连接移到 newFolderId（null=根级）下第 insertIndex 位（越界夹取到末尾）。同组重排 / 跨组移动。 */
    fun moveConnection(id: String, newFolderId: String?, insertIndex: Int): Boolean {
        if (!connectionExists(id)) return false
        val oldFolderId = getConnectionFolderId(id)
        conn.autoCommit = false
        return try {
            if (oldFolderId == newFolderId) {
                val ids = loadOrderedConnectionIds(oldFolderId).toMutableList()
                if (!ids.remove(id)) {
                    conn.rollback()
                    return false
                }
                ids.add(insertIndex.coerceIn(0, ids.size), id)
                reorderConnections(oldFolderId, ids)
            } else {
                val target = loadOrderedConnectionIds(newFolderId).toMutableList()
                target.remove(id)
                target.add(insertIndex.coerceIn(0, target.size), id)
                reorderConnections(newFolderId, target)
                val source = loadOrderedConnectionIds(oldFolderId).toMutableList()
                source.remove(id)
                reorderConnections(oldFolderId, source)
            }
            conn.commit()
            true
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** descendantCandidateId 是否在 ancestorFolderId 的子树内（含自身）。 */
    fun isFolderStrictDescendantOf(descendantCandidateId: String, ancestorFolderId: String): Boolean {
        var cur: String? = descendantCandidateId
        while (cur != null) {
            if (cur == ancestorFolderId) return true
            cur = getFolderParentId(cur)
        }
        return false
    }

    /** 把文件夹移到 newParentId（null=根级）下第 insertIndex 位（禁止移入自身或其子树）。 */
    fun moveFolder(id: String, newParentId: String?, insertIndex: Int): Boolean {
        if (newParentId == id) return false
        if (newParentId != null && isFolderStrictDescendantOf(newParentId, id)) return false
        if (!folderExists(id)) return false
        val oldParentId = getFolderParentId(id)
        conn.autoCommit = false
        return try {
            if (oldParentId == newParentId) {
                val ids = loadOrderedFolderIds(oldParentId).toMutableList()
                if (!ids.remove(id)) {
                    conn.rollback()
                    return false
                }
                ids.add(insertIndex.coerceIn(0, ids.size), id)
                reorderFolders(oldParentId, ids)
            } else {
                val target = loadOrderedFolderIds(newParentId).toMutableList()
                target.remove(id)
                target.add(insertIndex.coerceIn(0, target.size), id)
                reorderFolders(newParentId, target)
                val source = loadOrderedFolderIds(oldParentId).toMutableList()
                source.remove(id)
                reorderFolders(oldParentId, source)
            }
            conn.commit()
            true
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ---------- connections ----------

    fun listConnections(): List<ConnectionProfile> {
        return conn.prepareStatement(
            """
            SELECT id, folder_id, name, db_type, host, port, database_name, user_name, password,
                   extra_params, color, sort_order, key_separator
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
            "SELECT id, folder_id, name, db_type, host, port, database_name, user_name, password, extra_params, color, sort_order, key_separator FROM connections WHERE id = ?",
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
                password, extra_params, color, sort_order, key_separator, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.setString(13, p.keySeparator)
            ps.setLong(14, now)
            ps.setLong(15, now)
            ps.executeUpdate()
        }
        return id
    }

    fun updateConnection(p: ConnectionProfile) {
        conn.prepareStatement(
            """
            UPDATE connections SET folder_id = ?, name = ?, db_type = ?, host = ?, port = ?,
                database_name = ?, user_name = ?, password = ?, extra_params = ?, color = ?,
                key_separator = ?, updated_at = ?
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
            ps.setString(11, p.keySeparator)
            ps.setLong(12, System.currentTimeMillis())
            ps.setString(13, p.id)
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

    // ---------- 连接档案导出/导入（批量） ----------

    /**
     * 批量导入连接档案 + 文件夹（P5）：
     * - **id 冲突时自动生成新 id，绝不覆盖现有档案**；
     * - 文件夹被重映射时，引用它的连接 `folder_id` 同步重映射（保持层级）；
     * - 整个导入在单事务内，任一失败全部回滚。
     * 导入内容来自 [ProfileTransfer]（应已在仓外解析校验）。
     */
    fun importProfiles(
        folders: List<FolderRow>,
        connections: List<ConnectionProfile>,
        /** 同名冲突中选择「跳过」的导入连接 id 集合（由 `ProfileTransfer.findNameConflicts` 得出）。 */
        skipConnectionIds: Set<String> = emptySet(),
    ): ProfileImportSummary {
        conn.autoCommit = false
        try {
            val folderIds = existingIds("folders")
            val folderRemap = mutableMapOf<String, String>()
            var foldersAdded = 0
            // 第一遍：先插文件夹本体（parent_id 暂空），避免子文件夹先于父文件夹违反 FK
            folders.forEach { f ->
                val id = if (f.id in folderIds) newId() else f.id
                if (id != f.id) folderRemap[f.id] = id
                conn.prepareStatement(
                    "INSERT INTO folders(id, name, parent_id, sort_order, created_at) VALUES (?, ?, NULL, ?, ?)",
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, f.name)
                    ps.setInt(3, nextSortOrder("folders"))
                    ps.setLong(4, System.currentTimeMillis())
                    ps.executeUpdate()
                }
                folderIds += id
                foldersAdded++
            }
            // 第二遍：回填父文件夹引用（按 remap 重映射，保持导入前的嵌套层级）
            folders.forEach { f ->
                val id = folderRemap[f.id] ?: f.id
                val parentId = f.parentId?.let { folderRemap[it] ?: it }
                if (parentId != null) {
                    conn.prepareStatement("UPDATE folders SET parent_id = ? WHERE id = ?").use { ps ->
                        ps.setString(1, parentId)
                        ps.setString(2, id)
                        ps.executeUpdate()
                    }
                }
            }
            val connectionIds = existingIds("connections")
            var connectionsAdded = 0
            var connectionsSkipped = 0
            connections.forEach { c ->
                if (c.id in skipConnectionIds) {
                    connectionsSkipped++
                    return@forEach
                }
                val id = if (c.id in connectionIds) newId() else c.id
                val folderId = c.folderId?.let { folderRemap[it] ?: it }
                conn.prepareStatement(
                    """
                    INSERT INTO connections(id, folder_id, name, db_type, host, port, database_name, user_name,
                        password, extra_params, color, sort_order, key_separator, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    val now = System.currentTimeMillis()
                    ps.setString(1, id)
                    ps.setString(2, folderId)
                    ps.setString(3, c.name)
                    ps.setString(4, c.dbType.name)
                    ps.setString(5, c.host)
                    ps.setInt(6, c.port)
                    ps.setString(7, c.database)
                    ps.setString(8, c.user)
                    ps.setString(9, PasswordVault.encrypt(keyFile, c.password))
                    ps.setString(10, c.extraParams)
                    ps.setString(11, c.color)
                    ps.setInt(12, nextSortOrder("connections"))
                    ps.setString(13, c.keySeparator)
                    ps.setLong(14, now)
                    ps.setLong(15, now)
                    ps.executeUpdate()
                }
                connectionIds += id
                connectionsAdded++
            }
            conn.commit()
            return ProfileImportSummary(foldersAdded, connectionsAdded, connectionsSkipped)
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun existingIds(table: String): MutableSet<String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM $table").use { rs ->
                buildSet { while (rs.next()) rs.getString(1)?.let { add(it) } }.toMutableSet()
            }
        }

    // ---------- consoles（数据源 → 多个命名控制台，每个绑定一个 .sql 文件） ----------

    fun listConsoles(connectionId: String): List<ConsoleRecord> {
        return conn.prepareStatement(
            "SELECT id, connection_id, name, file_path, sort_order, updated_at, target, caret_start, caret_end, closed FROM consoles WHERE connection_id = ? ORDER BY sort_order, created_at",
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
            "SELECT id, connection_id, name, file_path, sort_order, updated_at, target, caret_start, caret_end, closed FROM consoles WHERE id = ?",
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) mapConsole(rs) else null }
        }
    }

    /** 新建控制台：插元数据行 + 建空白 .sql 文件。返回完整记录。 */
    fun createConsole(connectionId: String, name: String): ConsoleRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val file = consolesDir.resolve("$id.sql")
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
        ConsoleFiles.write(file, "")
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

    /**
     * 记录控制台的光标/选区偏移（重启后回到上次焦点所在行）。
     * 刻意不碰 updated_at：它表达“内容/元数据变更”，被光标移动刷新会让
     * activateForProfile/activateMostRecent 的“最近改动”启发式失真。
     */
    fun setConsoleCaret(id: String, start: Int, end: Int) {
        conn.prepareStatement("UPDATE consoles SET caret_start = ?, caret_end = ? WHERE id = ?").use { ps ->
            ps.setInt(1, start)
            ps.setInt(2, end)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    /**
     * 标记控制台关闭/重新打开（仅改可见性，保留元数据行与 .sql 文件）。
     * 刻意不碰 updated_at（与光标同理），避免污染“最近改动的控制台”启发式。
     */
    fun setConsoleClosed(id: String, closed: Boolean) {
        conn.prepareStatement("UPDATE consoles SET closed = ? WHERE id = ?").use { ps ->
            ps.setInt(1, if (closed) 1 else 0)
            ps.setString(2, id)
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
        caretStart = rs.getInt("caret_start"),
        caretEnd = rs.getInt("caret_end"),
        closed = rs.getInt("closed") != 0,
    )

    // ---------- workspaces（控制台的虚拟分组） ----------

    fun listWorkspaces(): List<WorkspaceRecord> =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, name, auto_named, sort_order, created_at, last_active_console_id " +
                    "FROM workspaces ORDER BY sort_order, created_at",
            ).use { rs -> buildList { while (rs.next()) add(mapWorkspace(rs)) } }
        }

    fun createWorkspace(name: String, autoNamed: Boolean): WorkspaceRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val order = nextSortOrder("workspaces")
        conn.prepareStatement(
            "INSERT INTO workspaces(id, name, auto_named, sort_order, created_at) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, name)
            ps.setInt(3, if (autoNamed) 1 else 0)
            ps.setInt(4, order)
            ps.setLong(5, now)
            ps.executeUpdate()
        }
        return WorkspaceRecord(id = id, name = name, autoNamed = autoNamed, sortOrder = order, createdAt = now)
    }

    /** 重命名并清除 auto_named（此后名字不再跟随语言）。 */
    fun renameWorkspace(id: String, newName: String) {
        conn.prepareStatement("UPDATE workspaces SET name = ?, auto_named = 0 WHERE id = ?").use { ps ->
            ps.setString(1, newName)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun deleteWorkspace(id: String) {
        conn.prepareStatement("DELETE FROM workspaces WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    fun setWorkspaceLastActiveConsole(id: String, consoleId: String?) {
        conn.prepareStatement("UPDATE workspaces SET last_active_console_id = ? WHERE id = ?").use { ps ->
            if (consoleId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, consoleId)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** 全部成员关系：workspaceId → 有序列 consoleId（标签顺序）。 */
    fun listWorkspaceConsoleIds(): Map<String, List<String>> =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT workspace_id, console_id FROM workspace_consoles ORDER BY workspace_id, sort_order, added_at",
            ).use { rs ->
                val out = LinkedHashMap<String, MutableList<String>>()
                while (rs.next()) out.getOrPut(rs.getString(1)) { mutableListOf() }.add(rs.getString(2))
                out
            }
        }

    /** 加入工作区（幂等）：返回 true = 本次真正新增。 */
    fun addConsoleToWorkspace(workspaceId: String, consoleId: String): Boolean {
        val order = conn.prepareStatement(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM workspace_consoles WHERE workspace_id = ?",
        ).use { ps ->
            ps.setString(1, workspaceId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        return conn.prepareStatement(
            "INSERT OR IGNORE INTO workspace_consoles(workspace_id, console_id, sort_order, added_at) " +
                "VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, workspaceId)
            ps.setString(2, consoleId)
            ps.setInt(3, order)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate() > 0
        }
    }

    fun removeConsoleFromWorkspace(workspaceId: String, consoleId: String) {
        conn.prepareStatement("DELETE FROM workspace_consoles WHERE workspace_id = ? AND console_id = ?").use { ps ->
            ps.setString(1, workspaceId)
            ps.setString(2, consoleId)
            ps.executeUpdate()
        }
    }

    private fun mapWorkspace(rs: java.sql.ResultSet): WorkspaceRecord = WorkspaceRecord(
        id = rs.getString("id"),
        name = rs.getString("name"),
        autoNamed = rs.getInt("auto_named") != 0,
        sortOrder = rs.getInt("sort_order"),
        createdAt = rs.getLong("created_at"),
        lastActiveConsoleId = rs.getString("last_active_console_id"),
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
        keySeparator = rs.getString("key_separator") ?: ":",
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
