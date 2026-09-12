package jdbc

import db.ConnectionProfile
import db.DbType
import jdbc.model.ColumnMeta
import jdbc.model.SchemaObjects
import jdbc.model.SchemaMeta
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet

/**
 * 目标库方言：封装“建连、库探测、对象探测、标识符引用、语句拼装”的差异。
 * 默认实现 GenericDialect 用 JDBC DatabaseMetaData 兜底；
 * 各库按需覆写（探测走 SQL 更可控的库优先覆写）。
 */
interface DbDialect {
    val dbType: DbType

    /** 建立连接（阻塞；DriverManager + 显式 Class.forName 保证驱动已注册）。 */
    fun openConnection(profile: ConnectionProfile): Connection

    /** 探测“库”层级（顶层节点，对应 UI 树的 schema 行）。 */
    fun loadSchemas(conn: Connection): List<SchemaMeta>

    /** 探测某 schema 下的表/视图/触发器。 */
    fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects

    /**
     * 探测单表列清单（编辑器列补全）。默认走 JDBC [DatabaseMetaData.getColumns]。
     * **必须传精确表名**（绝不 `%`），否则大库上是全库扫描。
     * schema 语义与 [loadSchemas] 一致：[SchemaMeta.catalog] 供 MySQL 形态，
     * [SchemaMeta.schema] 供 PG/H2 形态；SQLite 伪 schema "main" 需转 null。
     * 大小写兜底：精确名取不到时再试大写/小写（H2 折大写、PG 折小写）。
     */
    fun loadColumns(conn: Connection, schema: SchemaMeta?, table: String): List<ColumnMeta> =
        queryTableColumns(conn, schema, table)

    /** 标识符（表/列/schema 名）加引号。 */
    fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * 取对象定义 DDL（Ctrl+Q 浮窗）。
     * 默认：由 [loadColumns] 近似重建 `CREATE TABLE`（仅列名/类型/NOT NULL，不含索引与约束）。
     * 能给出精确 DDL 的方言（SQLite/MySQL/MariaDB/ClickHouse）覆写；PostgreSQL 用 pg_catalog 重建。
     * 返回 null = 取不到（无列/权限不足），由调用方显示空态。
     */
    fun tableDdl(conn: Connection, schema: SchemaMeta?, name: String): String? {
        val cols = runCatching { loadColumns(conn, schema, name) }.getOrDefault(emptyList())
        if (cols.isEmpty()) return null
        return reconstructDdl(schema, name, cols) { quoteIdent(it) }
    }

    /** 生成整表预览 SQL（M3 编辑器中执行用；M2 供右键复制）。 */
    fun previewSelect(schema: SchemaMeta?, name: String): String {
        val prefix = buildString {
            schema?.let {
                when {
                    it.schema != null && it.schema != "main" -> append(quoteIdent(it.schema)).append('.')
                    it.catalog != null -> append(quoteIdent(it.catalog)).append('.')
                }
            }
        }
        return "SELECT * FROM $prefix${quoteIdent(name)} LIMIT 100"
    }

    /**
     * 把会话切到某库/schema 的前导 SQL（每次执行前发一次，保证并发/复切也生效）；
     * null = 无需切换（该方言没有可切的目标，或连接默认即目标）。
     */
    fun sessionContextSql(schema: SchemaMeta): String? = null

    /** 是否支持在控制台内选择执行目标库/schema（SQLite 单文件无意义）。 */
    val supportsTargetSwitch: Boolean get() = true
}

/**
 * 通用列探测（JDBC [DatabaseMetaData.getColumns]）：精确表名（绝不 `%`）+ 大小写兜底。
 * 供 [DbDialect.loadColumns] 默认实现与各方言覆写的回落路径复用。
 */
internal fun queryTableColumns(conn: Connection, schema: SchemaMeta?, table: String): List<ColumnMeta> {
    val md = conn.metaData
    val catalog = schema?.catalog
    val schemaPattern = schema?.schema?.takeIf { it != "main" }
    var cols = queryColumns(md, catalog, schemaPattern, table)
    if (cols.isEmpty() && table.uppercase() != table) {
        cols = queryColumns(md, catalog, schemaPattern, table.uppercase())
    }
    if (cols.isEmpty() && table.lowercase() != table) {
        cols = queryColumns(md, catalog, schemaPattern, table.lowercase())
    }
    return markPrimaryKeys(conn, schema, table, cols.sortedBy { it.ordinal })
}

/**
 * 用 JDBC [DatabaseMetaData.getPrimaryKeys] 给列打主键标记（大小写不敏感）；
 * **无主键时回落到最小的唯一索引**（列数最少者），让「唯一键表」也能定位行编辑。
 * 各库 fast path（PG/MySQL/CK）与通用 [queryTableColumns] 都应在返回前调用，保证
 * `ColumnMeta.primaryKey` 可信；查不到/驱动不支持时原样返回（不抛）。
 */
internal fun markPrimaryKeys(
    conn: Connection,
    schema: SchemaMeta?,
    table: String,
    cols: List<ColumnMeta>,
): List<ColumnMeta> {
    if (cols.isEmpty()) return cols
    // 表名可能带引号/大小写不符（引用标识符、H2 折大写、PG 折小写），逐个变体试到命中
    val bare = table.trim('"', '`', '[', ']')
    val keys = runCatching {
        val md = conn.metaData
        val catalog = schema?.catalog
        val schemaPattern = schema?.schema?.takeIf { it != "main" }
        val variants = linkedSetOf(bare, bare.uppercase(), bare.lowercase())
        val pk = variants.firstNotNullOfOrNull { readPrimaryKeys(md, catalog, schemaPattern, it).takeIf { k -> k.isNotEmpty() } }
        when {
            !pk.isNullOrEmpty() -> pk
            else -> variants.firstNotNullOfOrNull { readUniqueKey(md, catalog, schemaPattern, it) } ?: emptySet()
        }
    }.getOrDefault(emptySet())
    if (keys.isEmpty()) return cols
    return cols.map { if (it.name.lowercase() in keys) it.copy(primaryKey = true) else it }
}

private fun readPrimaryKeys(
    md: DatabaseMetaData,
    catalog: String?,
    schemaPattern: String?,
    table: String,
): Set<String> {
    val out = mutableSetOf<String>()
    runCatching {
        md.getPrimaryKeys(catalog, schemaPattern, table).use { rs ->
            while (rs.next()) rs.getString("COLUMN_NAME")?.let { out += it.lowercase() }
        }
    }
    return out
}

/**
 * 无主键时的回落：选**列数最少**的唯一索引作为行定位键（多列唯一索引取全列）。
 * 只利用索引列名，不假设非空；真正写回时有「影响行数=1」校验兜底，不会误改多行。
 * 驱动不支持 `getIndexInfo` 或没有唯一索引时返回 null。
 */
private fun readUniqueKey(
    md: DatabaseMetaData,
    catalog: String?,
    schemaPattern: String?,
    table: String,
): Set<String>? {
    val candidates = mutableListOf<List<String>>()
    runCatching {
        md.getIndexInfo(catalog, schemaPattern, table, true, true).use { rs ->
            val byName = linkedMapOf<String, MutableList<Pair<Int, String>>>()
            while (rs.next()) {
                val indexName = rs.getString("INDEX_NAME") ?: continue
                val colName = rs.getString("COLUMN_NAME") ?: continue
                if (colName.isBlank()) continue
                val ordinal = runCatching { rs.getInt("ORDINAL_POSITION") }.getOrDefault(0)
                byName.getOrPut(indexName) { mutableListOf() } += ordinal to colName
            }
            byName.values.forEach { cols ->
                if (cols.isNotEmpty()) candidates += cols.sortedBy { it.first }.map { it.second }
            }
        }
    }
    return candidates.minByOrNull { it.size }?.map { it.lowercase() }?.toSet()
}

private fun queryColumns(
    md: DatabaseMetaData,
    catalog: String?,
    schemaPattern: String?,
    table: String,
): List<ColumnMeta> = md.getColumns(catalog, schemaPattern, table, null).use { rs -> readColumnRows(rs) }

/** 从 JDBC 元数据列结果集读 [ColumnMeta]（结果列名按 JDBC 规范）。 */
internal fun readColumnRows(rs: ResultSet): List<ColumnMeta> {
    val out = mutableListOf<ColumnMeta>()
    while (rs.next()) {
        val name = rs.getString("COLUMN_NAME") ?: continue
        out += ColumnMeta(
            name = name,
            typeName = runCatching { rs.getString("TYPE_NAME") }.getOrNull(),
            nullable = runCatching { rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls }
                .getOrDefault(true),
            ordinal = runCatching { rs.getInt("ORDINAL_POSITION") }.getOrDefault(0),
        )
    }
    return out
}

/**
 * 由列元数据近似重建 `CREATE TABLE`（[DbDialect.tableDdl] 的默认/回落路径）。
 * 只保证列名 + 类型 + NOT NULL；主键、索引、默认值、注释需方言精确实现。
 */
internal fun reconstructDdl(
    schema: SchemaMeta?,
    name: String,
    columns: List<ColumnMeta>,
    quote: (String) -> String,
): String {
    val prefix = buildString {
        schema?.let {
            when {
                it.schema != null && it.schema != "main" -> append(quote(it.schema)).append('.')
                it.catalog != null -> append(quote(it.catalog)).append('.')
            }
        }
    }
    val body = columns.joinToString(",\n") { c ->
        val type = c.typeName?.takeIf { it.isNotBlank() } ?: "?"
        "  ${quote(c.name)} $type" + if (!c.nullable) " NOT NULL" else ""
    }
    return "CREATE TABLE $prefix${quote(name)} (\n$body\n);"
}

/** 方言注册表：dbType -> 单例方言（无状态，可共享）。 */
object DialectRegistry {
    private val dialects: Map<DbType, DbDialect> = mapOf(
        DbType.POSTGRES to PostgresDialect,
        DbType.MYSQL to MySqlDialect,
        DbType.MARIADB to MariaDbDialect,
        DbType.SQLITE to SQLiteDialect,
        DbType.H2 to H2Dialect,
        DbType.CLICKHOUSE to ClickHouseDialect,
    )

    fun forType(type: DbType): DbDialect = dialects[type]
        ?: error("No dialect registered for ${type.name}")

    /** 连接档案（可能存自旧版本）使用的方言不存在时兜底。 */
    fun forProfile(profile: ConnectionProfile): DbDialect = forType(profile.dbType)
}

/** 常见系统 schema/catalog 名（大小写不敏感）。 */
private val SYSTEM_NAMES = setOf(
    "information_schema", "pg_catalog", "mysql", "performance_schema", "sys",
    "system", "pg_toast", "pg_temp_1", "pg_toast_temp_1", "INFORMATION_SCHEMA",
)

fun String.isSystemSchemaName(): Boolean {
    val lower = lowercase()
    if (lower in SYSTEM_NAMES) return true
    // PG 内部 schema 前缀
    return lower.startsWith("pg_")
}
