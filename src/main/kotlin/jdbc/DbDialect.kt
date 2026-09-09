package jdbc

import db.ConnectionProfile
import db.DbType
import jdbc.model.SchemaObjects
import jdbc.model.SchemaMeta
import java.sql.Connection

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

    /** 标识符（表/列/schema 名）加引号。 */
    fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

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
