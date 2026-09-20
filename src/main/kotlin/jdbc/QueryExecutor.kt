package jdbc

import engine.model.QueryColumn
import engine.model.QueryResult
import i18n.I18n
import i18n.Lang
import i18n.Str
import org.tinylog.Logger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types

/** SQL 执行引擎（jdbc 层，不依赖 compose）：单语句执行，结果集最大 MAX_ROWS 行。 */
object QueryExecutor {

    const val MAX_ROWS = 1000
    private const val QUERY_TIMEOUT_SECONDS = 30

    /**
     * 单格展示字符上限：超过则截断并追加 [CELL_TRUNCATION_MARKER]。
     * 防单个超大字段（如整段 base64 图片 / 大 JSON）撑爆内存。
     */
    const val MAX_CELL_CHARS = 1_000_000

    /**
     * 一次查询结果保留的总字符预算（所有单元格累加）：超出即停止读取本结果并置 `truncated`。
     * 这是大结果内存的**主闸门**——即便单格不大，1000 行 × 数十 KB 也会爆。
     */
    const val MAX_RESULT_CHARS = 16_000_000

    /** 单格二进制读取上限（BLOB 仅展示字节数，无需全量）。 */
    const val MAX_CELL_BYTES = 1_000_000

    /** 单元格因超限被截断时追加的后缀标记（可被 [isTruncatedCell] 识别）。 */
    val CELL_TRUNCATION_MARKER: String get() = I18n.t(Str.CellTruncationMarker)

    /** 两种语言的截断标记——跨语言/历史结果都要能识别。 */
    private val TRUNCATION_MARKERS: List<String>
        get() = listOf(
            I18n.t(Lang.ZH, Str.CellTruncationMarker),
            I18n.t(Lang.EN, Str.CellTruncationMarker),
        )

    /** 该单元格值是否为被截断的显示值（截断后禁止就地编辑，避免把截断内容写回库）。 */
    fun isTruncatedCell(v: String?): Boolean = v != null && TRUNCATION_MARKERS.any { v.endsWith(it) }

    /** 可产生结果集的语句首关键字。 */
    private val QUERY_HEADS = setOf("select", "with", "show", "explain", "desc", "describe", "pragma", "table", "values")

    /** 首个标识符（关键字）词。 */
    private val LEADING_KEYWORD = Regex("^[A-Za-z]+")

    /**
     * 判断一条 SQL 是否产生结果集（决定走 executeQuery 还是 executeUpdate）。
     * 取关键字时必须按“任意空白”切词——SQL 常写成 `SELECT\n    a, b`，若按空格切会得到
     * `"select\n"` 而误判为非查询（ClickHouse 等驱动对 SELECT 走 executeUpdate 不报错，
     * 只返回 0，表现为“语句执行成功（非查询，未产生结果集）”）。
     * 另外跳过前导注释与左括号（如 `-- 注释\nselect`、`(select 1) union all …`）。
     */
    fun isQueryLike(sql: String): Boolean {
        var s = sql.trimStart()
        var stripped = true
        while (stripped) {
            stripped = false
            when {
                s.startsWith("--") -> { s = s.substringAfter('\n', "").trimStart(); stripped = true }
                s.startsWith("/*") -> { s = s.substringAfter("*/", "").trimStart(); stripped = true }
                s.startsWith("(") -> { s = s.substring(1).trimStart(); stripped = true }
            }
        }
        val head = LEADING_KEYWORD.find(s)?.value?.lowercase() ?: return false
        return head in QUERY_HEADS
    }

    /**
     * 执行单条 SQL。阻塞调用；调用方负责放到工作线程、保证连接可用。
     * 结果集在连接关闭前整体读出（上限 MAX_ROWS），随后释放语句。
     * [registerStatement]：可选——语句建立后回调一次、finally 中再回调 null，
     * 供 LiveConnection 登记当前执行语句以支持外部 cancel（Statement.cancel）。
     */
    fun execute(conn: Connection, sql: String, registerStatement: ((Statement?) -> Unit)? = null): QueryResult {
        val started = System.currentTimeMillis()
        val stmt = conn.createStatement()
        try {
            stmt.queryTimeout = QUERY_TIMEOUT_SECONDS
            registerStatement?.invoke(stmt)
            return if (isQueryLike(sql)) {
                stmt.executeQuery(sql).use { rs -> readResultSet(sql, rs, started) }
            } else {
                val affected = stmt.executeUpdate(sql)
                QueryResult(sql, emptyList(), emptyList(), affectedRows = affected, durationMs = System.currentTimeMillis() - started)
            }
        } finally {
            registerStatement?.invoke(null)
            runCatching { stmt.close() }
        }
    }

    /**
     * 执行会话上下文前导（目标库/schema 切换，如 USE `db` / SET search_path TO …）；
     * null/空白 = 无需切换。失败会抛异常（由调用方按执行错误展示）。
     */
    fun applyContext(conn: Connection, contextSql: String?) {
        if (contextSql.isNullOrBlank()) return
        conn.createStatement().use { st ->
            st.queryTimeout = QUERY_TIMEOUT_SECONDS
            st.execute(contextSql)
        }
    }

    private fun readResultSet(sql: String, rs: ResultSet, started: Long): QueryResult {
        val meta = rs.metaData
        val count = meta.columnCount
        val columns = columnMetas(meta)
        val rows = ArrayList<List<String?>>(minOf(MAX_ROWS, 256))
        var truncated = false
        var charsUsed = 0L
        while (rs.next()) {
            if (rows.size >= MAX_ROWS) {
                truncated = true
                Logger.info("query truncated at {} rows: {}", MAX_ROWS, sql.substringBefore('\n').take(80))
                break
            }
            val row = ArrayList<String?>(count)
            var rowChars = 0L
            for (i in 1..count) {
                val v = readCell(rs, i, columns[i - 1].sqlType)
                rowChars += v?.length ?: 0
                row.add(v)
            }
            // 总字符预算：防大字段累积（本行已读完，超预算则不保留该行并停止）
            if (rows.isNotEmpty() && charsUsed + rowChars > MAX_RESULT_CHARS) {
                truncated = true
                Logger.info(
                    "query truncated by memory budget at {} rows / {} chars: {}",
                    rows.size, charsUsed, sql.substringBefore('\n').take(80),
                )
                break
            }
            charsUsed += rowChars
            rows.add(row)
        }
        return QueryResult(sql, columns, rows, durationMs = System.currentTimeMillis() - started, truncated = truncated)
    }

    /**
     * 读一个单元格（带内存阀）：二进制走流式只数字节；字符类走字符流限读上限；
     * 其余走 `getObject` 后按规定长度截断。
     */
    private fun readCell(rs: ResultSet, i: Int, sqlType: Int): String? = when {
        isBinaryColumn(sqlType) -> readBinaryCell(rs, i)
        isCharColumn(sqlType) -> readTextCell(rs, i)
        else -> clampCell(cellToString(runCatching { rs.getObject(i) }.getOrNull()))
    }

    /** 字符类：用 `getCharacterStream` 限读，避免超大 CLOB/TEXT 一次性进内存；驱动不支持时回退 `getString`。 */
    private fun readTextCell(rs: ResultSet, i: Int): String? {
        val reader = runCatching { rs.getCharacterStream(i) }.getOrNull()
            ?: return clampCell(runCatching { rs.getString(i) }.getOrNull())
        reader.use { r ->
            val sb = StringBuilder(256)
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                if (sb.length + n > MAX_CELL_CHARS) {
                    sb.append(buf, 0, MAX_CELL_CHARS - sb.length)
                    return sb.append(CELL_TRUNCATION_MARKER).toString()
                }
                sb.append(buf, 0, n)
            }
            return sb.toString()
        }
    }

    /** 二进制：流式读取只统计字节数（结果本来就是有损的 `[N bytes]`，无需全量）。 */
    private fun readBinaryCell(rs: ResultSet, i: Int): String? {
        val stream = runCatching { rs.getBinaryStream(i) }.getOrNull()
            ?: return cellToString(runCatching { rs.getObject(i) }.getOrNull())
        stream.use { s ->
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total >= MAX_CELL_BYTES) return "[≥$MAX_CELL_BYTES bytes]"
            }
            return "[$total bytes]"
        }
    }

    private fun clampCell(s: String?): String? {
        if (s == null || s.length <= MAX_CELL_CHARS) return s
        return s.take(MAX_CELL_CHARS) + CELL_TRUNCATION_MARKER
    }

    private fun isBinaryColumn(sqlType: Int): Boolean = when (sqlType) {
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> true
        else -> false
    }

    private fun isCharColumn(sqlType: Int): Boolean = when (sqlType) {
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB, Types.SQLXML,
        -> true

        else -> false
    }

    /** 读整表列元数据（供普通结果读取与 N8 流式导出复用）。 */
    fun columnMetas(meta: ResultSetMetaData): List<QueryColumn> =
        (1..meta.columnCount).map { readColumnMeta(meta, it) }

    /**
     * 读单列元数据；各取值独立 runCatching，个别驱动不支持的 API 不影响整体（降级为不可编辑）。
     * 注意：部分驱动对表达式列的 `getTableName`/`getColumnName` 可能给出别名而非空——
     * 编辑判定在 app 层还会用 `ColumnCatalog` 的已知列名二次校验，双保险。
     */
    private fun readColumnMeta(meta: ResultSetMetaData, i: Int): QueryColumn {
        val label = runCatching { meta.getColumnLabel(i) }.getOrNull().orEmpty()
        val baseName = runCatching { meta.getColumnName(i) }.getOrNull().orEmpty()
        fun textOf(read: (Int) -> String): String? =
            runCatching { read(i) }.getOrNull()?.takeIf { it.isNotBlank() }
        val tableName = textOf { meta.getTableName(it) }
        // SQLite(xerial) 的 getCatalogName 返回的是表名（非 catalog），与表名相同则视为无 catalog，
        // 否则会给 UPDATE 拼出 `"users"."users"` 这种错误限定名。
        val catalogName = textOf { meta.getCatalogName(it) }
        return QueryColumn(
            name = label.ifBlank { baseName },
            catalog = catalogName?.takeUnless { it.equals(tableName, ignoreCase = true) },
            schema = textOf { meta.getSchemaName(it) },
            table = tableName,
            baseColumn = baseName.takeIf { it.isNotBlank() },
            sqlType = runCatching { meta.getColumnType(i) }.getOrDefault(Types.OTHER),
            nullable = runCatching { meta.isNullable(i) != ResultSetMetaData.columnNoNulls }.getOrDefault(true),
            autoIncrement = runCatching { meta.isAutoIncrement(i) }.getOrDefault(false),
            readOnly = runCatching { meta.isReadOnly(i) }.getOrDefault(false),
        )
    }

    /** 单元格值 → 展示/导出字符串（与 CSV 全量导出共用同一转换）。 */
    fun cellToString(v: Any?): String? = when (v) {
        null -> null
        is ByteArray -> "[${v.size} bytes]"
        is Boolean -> if (v) "true" else "false"
        else -> v.toString()
    }
}
