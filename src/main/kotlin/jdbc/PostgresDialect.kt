package jdbc

import db.ConnectionProfile
import db.DbType
import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import org.tinylog.Logger
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * PostgreSQL：单 catalog（当前库）、多 schema。
 * 直接用 pg_catalog 查询，比 JDBC getTables 对分区表/物化视图/触发器更可控。
 */
object PostgresDialect : GenericDialect(DbType.POSTGRES, "org.postgresql.Driver") {

    private val schemasSql = """
        SELECT schema_name FROM information_schema.schemata
        WHERE schema_name NOT LIKE 'pg\_%' AND schema_name <> 'information_schema'
        ORDER BY 1
    """.trimIndent()

    // 关系对象（表/物化视图/视图/序列）：pg_class relkind（r 表 p 分区 f 外部 m 物化 v 视图 S 序列）
    private val relationsSql = """
        SELECT c.relname, c.relkind
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relkind IN ('r','p','f','v','m','S')
        ORDER BY c.relname
    """.trimIndent()

    private val triggersSql = """
        SELECT t.tgname, c.relname
        FROM pg_catalog.pg_trigger t
        JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND NOT t.tgisinternal
        ORDER BY t.tgname
    """.trimIndent()

    /** 例程（函数/过程/窗口函数）与聚合：pg_proc.prokind（f 函数 p 过程 w 窗口 a 聚合）。 */
    private val routinesSql = """
        SELECT DISTINCT p.proname, p.prokind
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = ?
        ORDER BY p.proname
    """.trimIndent()

    /** 操作符 / 类型 / 操作符类 / 操作符族：均带 namespace，按 schema 过滤。 */
    private val operatorsSql = """
        SELECT DISTINCT o.oprname
        FROM pg_catalog.pg_operator o
        JOIN pg_catalog.pg_namespace n ON n.oid = o.oprnamespace
        WHERE n.nspname = ?
        ORDER BY o.oprname
    """.trimIndent()

    /** 类型：只列用户可感知的自定义类型（复合/枚举/域/范围/多范围），排除内部数组镜像。 */
    private val typesSql = """
        SELECT DISTINCT t.typname
        FROM pg_catalog.pg_type t
        JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        WHERE n.nspname = ? AND t.typtype IN ('c','e','d','r','m')
            AND t.typname NOT LIKE '\_%'
        ORDER BY t.typname
    """.trimIndent()

    private val opClassesSql = """
        SELECT DISTINCT c.opcname
        FROM pg_catalog.pg_opclass c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.opcnamespace
        WHERE n.nspname = ?
        ORDER BY c.opcname
    """.trimIndent()

    private val opFamiliesSql = """
        SELECT DISTINCT f.opfname
        FROM pg_catalog.pg_opfamily f
        JOIN pg_catalog.pg_namespace n ON n.oid = f.opfnamespace
        WHERE n.nspname = ?
        ORDER BY f.opfname
    """.trimIndent()

    override fun loadSchemas(conn: Connection): List<SchemaMeta> =
        queryStrings(conn, schemasSql) { it.getString(1) }
            .map { SchemaMeta(catalog = null, schema = it) }

    override fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val schemaName = schema.schema ?: "public"
        val grouped = mutableMapOf<ObjectKind, MutableList<DbObjectMeta>>()
        fun bucket(kind: ObjectKind) = grouped.getOrPut(kind) { mutableListOf() }

        conn.prepareStatement(relationsSql).use { ps ->
            ps.setString(1, schemaName)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    val kind = when (rs.getString(2)) {
                        "m" -> ObjectKind.MATERIALIZED_VIEW
                        "v" -> ObjectKind.VIEW
                        "S" -> ObjectKind.SEQUENCE
                        else -> ObjectKind.TABLE // r / p / f
                    }
                    bucket(kind) += DbObjectMeta(name, kind)
                }
            }
        }
        runCatching {
            conn.prepareStatement(triggersSql).use { ps ->
                ps.setString(1, schemaName)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        bucket(ObjectKind.TRIGGER) += DbObjectMeta(name, ObjectKind.TRIGGER, rs.getString(2))
                    }
                }
            }
        }.onFailure { Logger.warn(it, "pg triggers query failed for {}", schemaName) }
        runCatching {
            conn.prepareStatement(routinesSql).use { ps ->
                ps.setString(1, schemaName)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        val kind = if (rs.getString(2) == "a") ObjectKind.AGGREGATE else ObjectKind.ROUTINE
                        bucket(kind) += DbObjectMeta(name, kind)
                    }
                }
            }
        }.onFailure { Logger.warn(it, "pg routines query failed (prokind 需 PG11+) for {}", schemaName) }
        runCatching {
            queryStrings(conn, operatorsSql) { it.getString(1) }
                .forEach { bucket(ObjectKind.OPERATOR) += DbObjectMeta(it, ObjectKind.OPERATOR) }
            queryStrings(conn, typesSql) { it.getString(1) }
                .forEach { bucket(ObjectKind.TYPE) += DbObjectMeta(it, ObjectKind.TYPE) }
            queryStrings(conn, opClassesSql) { it.getString(1) }
                .forEach { bucket(ObjectKind.OPERATOR_CLASS) += DbObjectMeta(it, ObjectKind.OPERATOR_CLASS) }
            queryStrings(conn, opFamiliesSql) { it.getString(1) }
                .forEach { bucket(ObjectKind.OPERATOR_FAMILY) += DbObjectMeta(it, ObjectKind.OPERATOR_FAMILY) }
        }.onFailure { Logger.warn(it, "pg object catalog queries failed for {}", schemaName) }
        val objects = grouped.mapValues { (_, v) -> v.sortedBy { it.name } }
            .filterValues { it.isNotEmpty() }
        return SchemaObjects(objects)
    }

    private fun queryStrings(conn: Connection, sql: String, extract: (ResultSet) -> String?): List<String> {
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) extract(rs)?.let { out += it }
                return out
            }
        }
    }

    /** PG 的 schema 即执行作用域：search_path 指向所选 schema（引号保大小写）。 */
    override fun sessionContextSql(schema: jdbc.model.SchemaMeta): String? =
        schema.schema?.let { "SET search_path TO ${quoteIdent(it)}" }
}
