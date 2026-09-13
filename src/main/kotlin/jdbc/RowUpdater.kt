package jdbc

import engine.model.SchemaMeta
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types

/**
 * 查询结果单元格写回（jdbc 层，阻塞、无 compose 依赖）。
 *
 * 设计要点（对应 `doc/EDITABLE_RESULT.md`）：
 * - 只做**单表按主键/唯一键定位**的 UPDATE；值一律走 `PreparedStatement` 参数，绝不拼字符串；
 * - 一次提交的多个格子放在**同一事务**里，任一失败整体回滚；
 * - 每条 UPDATE 必须影响**恰好 1 行**，否则视为定位失败（0=行没了，>1=不唯一）并回滚。
 * 值类型转换在 [bindValue] 内按目标列 JDBC 类型做，失败抛 [CellValueException]（提交前展示给用户）。
 */

/** 一个待写单元格值；[raw] == null 表示 SQL NULL（与空串严格区分）。 */
data class CellValue(val raw: String?)

/** 单列写入项：列名 + 值 + 目标库类型（绑定/校验用）。 */
data class ColumnValue(val column: String, val value: CellValue, val sqlType: Int)

/** 单表 UPDATE 计划：sets = 目标列→新值；keys = 定位键列→原始值（WHERE 用旧值）。 */
data class UpdatePlan(
    val schema: SchemaMeta?,
    val table: String,
    val sets: List<ColumnValue>,
    val keys: List<ColumnValue>,
)

/** 值无法按目标列类型转换（或列不支持编辑）时抛出，供 UI 逐格提示。 */
class CellValueException(message: String) : IllegalArgumentException(message)

/** 哪些 JDBC 类型属于二进制/大对象（当前单元格值是有损的 `[N bytes]`，不开放编辑）。 */
fun isBinarySqlType(sqlType: Int): Boolean = when (sqlType) {
    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> true
    else -> false
}

object RowUpdater {

    private const val UPDATE_TIMEOUT_SECONDS = 30

    /**
     * 渲染可读的 UPDATE（用于 SQL 预览 / 执行历史）：值转成 JSQL 字面量（单引号翻倍、NULL 直写），
     * 标识符走方言引用。**仅用于展示**，真正执行走参数化 [executeBatch]。
     */
    fun renderUpdateSql(plan: UpdatePlan, dialect: DbDialect): String {
        val table = qualify(plan.schema, plan.table, dialect)
        val sets = plan.sets.joinToString(", ") { "${dialect.quoteIdent(it.column)} = ${literal(it.value)}" }
        val where = plan.keys.joinToString(" AND ") {
            if (it.value.raw == null) "${dialect.quoteIdent(it.column)} IS NULL"
            else "${dialect.quoteIdent(it.column)} = ${literal(it.value)}"
        }
        return "UPDATE $table SET $sets WHERE $where;"
    }

    /**
     * 在**单事务**中顺序执行多个 [UpdatePlan]，每条要求恰好影响 1 行；否则整体回滚并抛异常。
     * 返回总影响行数。调用方须保证在 `LiveConnection` 的单线程执行器上运行（同一连接串行）。
     */
    fun executeBatch(
        conn: Connection,
        plans: List<UpdatePlan>,
        dialect: DbDialect,
        registerStatement: ((Statement?) -> Unit)? = null,
    ): Int {
        if (plans.isEmpty()) return 0
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        var total = 0
        try {
            for (plan in plans) {
                val affected = executeOne(conn, plan, dialect, registerStatement)
                check(affected == 1) {
                    "WHERE 未能唯一定位：影响 $affected 行（期望 1 行），已回滚"
                }
                total += affected
            }
            conn.commit()
            return total
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            runCatching { conn.autoCommit = previousAutoCommit }
        }
    }

    private fun executeOne(
        conn: Connection,
        plan: UpdatePlan,
        dialect: DbDialect,
        registerStatement: ((Statement?) -> Unit)?,
    ): Int {
        require(plan.sets.isNotEmpty()) { "没有要更新的列" }
        require(plan.keys.isNotEmpty()) { "缺少行定位键" }
        val sql = buildUpdateSql(plan, dialect)
        conn.prepareStatement(sql).use { ps ->
            registerStatement?.invoke(ps)
            try {
                ps.queryTimeout = UPDATE_TIMEOUT_SECONDS
                var index = 1
                for (cv in plan.sets) bindValue(ps, index++, cv)
                for (cv in plan.keys) if (cv.value.raw != null) bindValue(ps, index++, cv)
                return ps.executeUpdate()
            } finally {
                registerStatement?.invoke(null)
            }
        }
    }

    private fun buildUpdateSql(plan: UpdatePlan, dialect: DbDialect): String {
        val table = qualify(plan.schema, plan.table, dialect)
        val sets = plan.sets.joinToString(", ") { "${dialect.quoteIdent(it.column)} = ?" }
        val where = plan.keys.joinToString(" AND ") {
            if (it.value.raw == null) "${dialect.quoteIdent(it.column)} IS NULL"
            else "${dialect.quoteIdent(it.column)} = ?"
        }
        return "UPDATE $table SET $sets WHERE $where"
    }

    /** 按目标列 JDBC 类型把字符串单元格值绑到参数上；类型不符抛 [CellValueException]。 */
    private fun bindValue(ps: PreparedStatement, index: Int, cv: ColumnValue) {
        val raw = cv.value.raw
        if (raw == null) {
            ps.setNull(index, cv.sqlType)
            return
        }
        if (isBinarySqlType(cv.sqlType)) throw CellValueException("列 ${cv.column}：二进制列不支持编辑")
        try {
            when (cv.sqlType) {
                Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                    ps.setLong(index, raw.trim().toLong())
                Types.NUMERIC, Types.DECIMAL ->
                    ps.setBigDecimal(index, BigDecimal(raw.trim()))
                Types.FLOAT, Types.REAL, Types.DOUBLE ->
                    ps.setDouble(index, raw.trim().toDouble())
                Types.BOOLEAN, Types.BIT ->
                    ps.setBoolean(index, parseBoolean(raw))
                Types.DATE ->
                    ps.setDate(index, java.sql.Date.valueOf(raw.trim()))
                Types.TIME ->
                    ps.setTime(index, java.sql.Time.valueOf(raw.trim()))
                Types.TIMESTAMP ->
                    ps.setTimestamp(index, java.sql.Timestamp.valueOf(raw.trim()))
                else -> ps.setString(index, raw)
            }
        } catch (e: CellValueException) {
            throw e
        } catch (_: Exception) {
            throw CellValueException("列 ${cv.column}：\"$raw\" 不是合法的${typeLabel(cv.sqlType)}")
        }
    }

    private fun parseBoolean(raw: String): Boolean = when (raw.trim().lowercase()) {
        "true", "t", "1", "yes", "y" -> true
        "false", "f", "0", "no", "n" -> false
        else -> throw IllegalArgumentException(raw)
    }

    private fun typeLabel(sqlType: Int): String = when (sqlType) {
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "整数"
        Types.NUMERIC, Types.DECIMAL -> "小数"
        Types.FLOAT, Types.REAL, Types.DOUBLE -> "浮点数"
        Types.BOOLEAN, Types.BIT -> "布尔值"
        Types.DATE -> "日期"
        Types.TIME -> "时间"
        Types.TIMESTAMP -> "时间戳"
        else -> "字符串"
    }

    private fun literal(v: CellValue): String =
        v.raw?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"

    /** `schema.` / `catalog.` 前缀 + 引用表名（与 [DbDialect.previewSelect] 语义一致）。 */
    private fun qualify(schema: SchemaMeta?, table: String, dialect: DbDialect): String {
        val prefix = schema?.let {
            when {
                it.schema != null && it.schema != "main" -> dialect.quoteIdent(it.schema) + "."
                it.catalog != null -> dialect.quoteIdent(it.catalog) + "."
                else -> ""
            }
        } ?: ""
        return prefix + dialect.quoteIdent(table)
    }
}
