package jdbc

import db.ConnectionProfile
import db.DbType
import engine.model.ColumnMeta
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import org.tinylog.Logger
import java.sql.Connection
import java.sql.ResultSet

/**
 * PostgreSQL：单 catalog（当前库）、多 schema。
 * 直接用 pg_catalog 查询，比 JDBC getTables 对分区表/物化视图/触发器更可控。
 *
 * P6：支持按对象组懒加载——关系类（表/视图/物化视图/序列）作为「核心组」随连接预取，
 * 例程/聚合/操作符/类型等重目录只取计数，组展开才拉正文（见 [lazyObjectGroups]）。
 */
object PostgresDialect : GenericDialect(DbType.POSTGRES, "org.postgresql.Driver") {

    /** PG 必须 autoCommit=false + fetchSize 才走服务端 portal，否则整表进内存（doc/EXPORT.md §2）。 */
    override val cursorStrategy: CursorStrategy get() = CursorStrategy.TRANSACTION_PORTAL

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

    /**
     * 类型：只列用户可感知的自定义类型（复合/枚举/域/范围/多范围），排除内部数组镜像。
     * 同时排除 PG 为每张表/视图/物化视图/外部表/分区/序列自动创建的同名"行类型"：
     * 这类复合类型的 `typrelid` 指向 `relkind IN ('r','p','f','v','m','S')` 的 pg_class 条目，
     * 而真正 `CREATE TYPE ... AS (...)` 的独立复合类型其 relkind 为 `'c'`（域/枚举等 typrelid = 0）。
     */
    private val typesSql = """
        SELECT DISTINCT t.typname
        FROM pg_catalog.pg_type t
        JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        LEFT JOIN pg_catalog.pg_class c ON c.oid = t.typrelid
        WHERE n.nspname = ? AND t.typtype IN ('c','e','d','r','m')
            AND t.typname NOT LIKE '\_%'
            AND (t.typrelid = 0 OR c.relkind = 'c')
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

    /**
     * 一次性取全部组计数（不拉正文）；7 处 `?` 均填 schema 名。
     * 懒加载下连接阶段每个 schema 只跑这一条 + 一条关系查询。
     */
    private val countsSql = """
        SELECT 'rel' AS k, c.relkind::text AS sub, count(*)::int AS cnt
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relkind IN ('r','p','f','v','m','S')
        GROUP BY c.relkind
        UNION ALL
        SELECT 'trg', '', count(*)::int
        FROM pg_catalog.pg_trigger t
        JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND NOT t.tgisinternal
        UNION ALL
        SELECT 'proc', p.prokind::text, count(DISTINCT p.proname)::int
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = ?
        GROUP BY p.prokind
        UNION ALL
        SELECT 'opr', '', count(DISTINCT o.oprname)::int
        FROM pg_catalog.pg_operator o
        JOIN pg_catalog.pg_namespace n ON n.oid = o.oprnamespace
        WHERE n.nspname = ?
        UNION ALL
        SELECT 'typ', '', count(DISTINCT t.typname)::int
        FROM pg_catalog.pg_type t
        JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        LEFT JOIN pg_catalog.pg_class c ON c.oid = t.typrelid
        WHERE n.nspname = ? AND t.typtype IN ('c','e','d','r','m')
            AND t.typname NOT LIKE '\_%'
            AND (t.typrelid = 0 OR c.relkind = 'c')
        UNION ALL
        SELECT 'opc', '', count(DISTINCT c.opcname)::int
        FROM pg_catalog.pg_opclass c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.opcnamespace
        WHERE n.nspname = ?
        UNION ALL
        SELECT 'opf', '', count(DISTINCT f.opfname)::int
        FROM pg_catalog.pg_opfamily f
        JOIN pg_catalog.pg_namespace n ON n.oid = f.opfnamespace
        WHERE n.nspname = ?
    """.trimIndent()

    override fun loadSchemas(conn: Connection): List<SchemaMeta> =
        queryStrings(conn, schemasSql) { it.getString(1) }
            .map { SchemaMeta(catalog = null, schema = it) }

    // ---------- 懒加载组 ----------

    override val lazyObjectGroups: Boolean get() = true

    /** 核心组：关系对象（表/物化视图/视图/序列），供编辑器补全与树首屏。 */
    override fun loadCoreObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val schemaName = schema.schema ?: "public"
        return SchemaObjects(queryRelations(conn, schemaName))
    }

    override fun loadObjectCounts(conn: Connection, schema: SchemaMeta): Map<ObjectKind, Int> {
        val schemaName = schema.schema ?: "public"
        val out = linkedMapOf<ObjectKind, Int>()
        fun add(kind: ObjectKind, n: Int) = out.merge(kind, n, Int::plus)
        runCatching {
            conn.prepareStatement(countsSql).use { ps ->
                repeat(7) { ps.setString(it + 1, schemaName) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val label = rs.getString(1)
                        val sub = rs.getString(2)
                        val cnt = rs.getInt(3)
                        when (label) {
                            "rel" -> add(relationKindOf(sub), cnt)
                            "trg" -> add(ObjectKind.TRIGGER, cnt)
                            "proc" -> add(routineKindOf(sub), cnt)
                            "opr" -> add(ObjectKind.OPERATOR, cnt)
                            "typ" -> add(ObjectKind.TYPE, cnt)
                            "opc" -> add(ObjectKind.OPERATOR_CLASS, cnt)
                            "opf" -> add(ObjectKind.OPERATOR_FAMILY, cnt)
                        }
                    }
                }
            }
        }.onFailure { Logger.warn(it, "pg loadObjectCounts failed for {}", schemaName) }
        return out
    }

    override fun loadObjectsForKind(conn: Connection, schema: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> {
        val schemaName = schema.schema ?: "public"
        return when (kind) {
            ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW, ObjectKind.SEQUENCE ->
                queryRelations(conn, schemaName)[kind].orEmpty()
            ObjectKind.TRIGGER -> queryTriggers(conn, schemaName)
            ObjectKind.ROUTINE, ObjectKind.AGGREGATE -> queryRoutines(conn, schemaName)[kind].orEmpty()
            ObjectKind.OPERATOR, ObjectKind.TYPE, ObjectKind.OPERATOR_CLASS, ObjectKind.OPERATOR_FAMILY ->
                queryCatalogGroups(conn, schemaName)[kind].orEmpty()
            ObjectKind.KEY, ObjectKind.INDEX, ObjectKind.ALIAS -> emptyList()
        }
    }

    /** 全量组织探测（非懒加载调用方 / 兜底用）。 */
    override fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val schemaName = schema.schema ?: "public"
        val grouped = linkedMapOf<ObjectKind, MutableList<DbObjectMeta>>()
        fun merge(map: Map<ObjectKind, List<DbObjectMeta>>) {
            map.forEach { (k, v) -> grouped.getOrPut(k) { mutableListOf() } += v }
        }
        merge(queryRelations(conn, schemaName))
        val triggers = queryTriggers(conn, schemaName)
        if (triggers.isNotEmpty()) merge(mapOf(ObjectKind.TRIGGER to triggers))
        merge(queryRoutines(conn, schemaName))
        merge(queryCatalogGroups(conn, schemaName))
        return SchemaObjects(grouped.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.sortedBy { it.name } })
    }

    private fun queryRelations(conn: Connection, schemaName: String): Map<ObjectKind, List<DbObjectMeta>> {
        val grouped = linkedMapOf<ObjectKind, MutableList<DbObjectMeta>>()
        conn.prepareStatement(relationsSql).use { ps ->
            ps.setString(1, schemaName)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    val kind = relationKindOf(rs.getString(2))
                    grouped.getOrPut(kind) { mutableListOf() } += DbObjectMeta(name, kind)
                }
            }
        }
        return grouped.mapValues { (_, v) -> v.sortedBy { it.name } }
    }

    private fun queryTriggers(conn: Connection, schemaName: String): List<DbObjectMeta> {
        val out = mutableListOf<DbObjectMeta>()
        runCatching {
            conn.prepareStatement(triggersSql).use { ps ->
                ps.setString(1, schemaName)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        out += DbObjectMeta(name, ObjectKind.TRIGGER, rs.getString(2))
                    }
                }
            }
        }.onFailure { Logger.warn(it, "pg triggers query failed for {}", schemaName) }
        return out.sortedBy { it.name }
    }

    private fun queryRoutines(conn: Connection, schemaName: String): Map<ObjectKind, List<DbObjectMeta>> {
        val grouped = linkedMapOf<ObjectKind, MutableList<DbObjectMeta>>()
        runCatching {
            conn.prepareStatement(routinesSql).use { ps ->
                ps.setString(1, schemaName)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        val kind = routineKindOf(rs.getString(2))
                        grouped.getOrPut(kind) { mutableListOf() } += DbObjectMeta(name, kind)
                    }
                }
            }
        }.onFailure { Logger.warn(it, "pg routines query failed (prokind 需 PG11+) for {}", schemaName) }
        return grouped.mapValues { (_, v) -> v.sortedBy { it.name } }
    }

    /** 操作符 / 类型 / 操作符类 / 操作符族：按 schema 过滤（修复此前未绑定 `?` 导致整块失败的问题）。 */
    private fun queryCatalogGroups(conn: Connection, schemaName: String): Map<ObjectKind, List<DbObjectMeta>> {
        val grouped = linkedMapOf<ObjectKind, MutableList<DbObjectMeta>>()
        fun load(sql: String, kind: ObjectKind) {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, schemaName)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        rs.getString(1)?.let { name -> grouped.getOrPut(kind) { mutableListOf() } += DbObjectMeta(name, kind) }
                    }
                }
            }
        }
        runCatching {
            load(operatorsSql, ObjectKind.OPERATOR)
            load(typesSql, ObjectKind.TYPE)
            load(opClassesSql, ObjectKind.OPERATOR_CLASS)
            load(opFamiliesSql, ObjectKind.OPERATOR_FAMILY)
        }.onFailure { Logger.warn(it, "pg object catalog queries failed for {}", schemaName) }
        return grouped.mapValues { (_, v) -> v.sortedBy { it.name } }
    }

    private val columnsSql = """
        SELECT column_name, data_type, is_nullable, ordinal_position
        FROM information_schema.columns
        WHERE table_schema = ? AND table_name = ?
        ORDER BY ordinal_position
    """.trimIndent()

    /**
     * PG 列探测走 information_schema.columns（比 JDBC getColumns 的复杂 join 快且稳）。
     * schema 未限定或表名大小写不匹配（引用标识符）时回落通用实现（带大小写兜底）。
     */
    override fun loadColumns(conn: Connection, schema: SchemaMeta?, table: String): List<ColumnMeta> {
        val schemaName = schema?.schema
        if (schemaName != null) {
            val cols = runCatching {
                conn.prepareStatement(columnsSql).use { ps ->
                    ps.setString(1, schemaName)
                    ps.setString(2, table)
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<ColumnMeta>()
                        while (rs.next()) {
                            out += ColumnMeta(
                                name = rs.getString(1) ?: continue,
                                typeName = rs.getString(2),
                                nullable = rs.getString(3)?.equals("YES", ignoreCase = true) ?: true,
                                ordinal = rs.getInt(4),
                            )
                        }
                        out
                    }
                }
            }.onFailure { Logger.warn(it, "pg loadColumns failed {}.{}", schemaName, table) }
                .getOrDefault(emptyList())
            if (cols.isNotEmpty()) return markPrimaryKeys(conn, schema, table, cols)
        }
        return queryTableColumns(conn, schema, table)
    }

    /** 列定义（含默认值）与主键——用于 [tableDdl] 重建。 */
    private val ddlColumnsSql = """
        SELECT a.attname,
               pg_catalog.format_type(a.atttypid, a.atttypmod) AS col_type,
               a.attnotnull,
               pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS col_default
        FROM pg_catalog.pg_attribute a
        JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
        WHERE c.relname = ? AND n.nspname = ? AND a.attnum > 0 AND NOT a.attisdropped
        ORDER BY a.attnum
    """.trimIndent()

    private val ddlPrimaryKeySql = """
        SELECT a.attname
        FROM pg_catalog.pg_index i
        JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
        WHERE c.relname = ? AND n.nspname = ? AND i.indisprimary
        ORDER BY array_position(i.indkey::int2[], a.attnum)
    """.trimIndent()

    /**
     * PG 无 SHOW CREATE TABLE：用 pg_catalog 重建（列名/类型/默认值/NOT NULL + 主键）。
     * 不含索引、外键、检查约束、注释（精确转储仍需 pg_dump）；无列时回落通用实现。
     */
    override fun tableDdl(conn: Connection, schema: SchemaMeta?, name: String): String? {
        val ns = schema?.schema ?: "public"
        val lines = mutableListOf<String>()
        runCatching {
            conn.prepareStatement(ddlColumnsSql).use { ps ->
                ps.setString(1, name)
                ps.setString(2, ns)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val col = quoteIdent(rs.getString(1) ?: continue)
                        val type = rs.getString(2) ?: "?"
                        val def = rs.getString(4)
                        lines += buildString {
                            append("  ").append(col).append(' ').append(type)
                            if (def != null) append(" DEFAULT ").append(def)
                            if (rs.getBoolean(3)) append(" NOT NULL")
                        }
                    }
                }
            }
        }.onFailure { Logger.warn(it, "pg tableDdl columns failed {}.{}", ns, name) }
        if (lines.isEmpty()) return super.tableDdl(conn, schema, name)
        runCatching {
            val pk = mutableListOf<String>()
            conn.prepareStatement(ddlPrimaryKeySql).use { ps ->
                ps.setString(1, name)
                ps.setString(2, ns)
                ps.executeQuery().use { rs ->
                    while (rs.next()) rs.getString(1)?.let { pk += quoteIdent(it) }
                }
            }
            if (pk.isNotEmpty()) lines += "  PRIMARY KEY (${pk.joinToString(", ")})"
        }.onFailure { Logger.warn(it, "pg tableDdl pk failed {}.{}", ns, name) }
        return "CREATE TABLE ${quoteIdent(ns)}.${quoteIdent(name)} (\n${lines.joinToString(",\n")}\n);"
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
    override fun sessionContextSql(schema: engine.model.SchemaMeta): String? =
        schema.schema?.let { "SET search_path TO ${quoteIdent(it)}" }
}

/** pg_class.relkind → 对象类型（纯函数，供组计数与关系探测共用）。 */
internal fun relationKindOf(relkind: String?): ObjectKind = when (relkind) {
    "m" -> ObjectKind.MATERIALIZED_VIEW
    "v" -> ObjectKind.VIEW
    "S" -> ObjectKind.SEQUENCE
    else -> ObjectKind.TABLE // r / p / f
}

/** pg_proc.prokind → 对象类型（a 聚合，其余函数/过程/窗口统一归 ROUTINE）。 */
internal fun routineKindOf(prokind: String?): ObjectKind =
    if (prokind == "a") ObjectKind.AGGREGATE else ObjectKind.ROUTINE
