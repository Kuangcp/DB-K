package jdbc

import db.ConnectionProfile
import db.DbType
import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import java.sql.Connection
import java.sql.DriverManager

/**
 * 通用兜底方言：完全基于 java.sql.DatabaseMetaData。
 * 任何未专门实现的库先用它跑通；若探测结果不理想再覆写。
 */
open class GenericDialect(
    override val dbType: DbType,
    private val driverClass: String,
) : DbDialect {

    override fun openConnection(profile: ConnectionProfile): Connection {
        Class.forName(driverClass)
        val url = profile.urlPreview()
        return if (profile.user.isNullOrBlank()) {
            DriverManager.getConnection(url)
        } else {
            DriverManager.getConnection(url, profile.user, profile.password ?: "")
        }
    }

    override fun loadSchemas(conn: Connection): List<SchemaMeta> {
        val md = conn.metaData
        // 1) 优先 schema 列表（PG/H2/SQLite 形态）
        // 结果列按 JDBC 规范：第 1 列 TABLE_SCHEM，第 2 列 TABLE_CATALOG（部分驱动无第 2 列/列名不标准）
        val schemas = mutableListOf<SchemaMeta>()
        md.schemas.use { rs ->
            while (rs.next()) {
                val schema = rs.getString(1)
                val catalog = runCatching { rs.getString(2) }.getOrNull()
                schemas += SchemaMeta(catalog, schema)
            }
        }
        val userSchemas = schemas.filter {
            it.schema != null && !it.schema.isSystemSchemaName() &&
                (it.catalog == null || !it.catalog.isSystemSchemaName())
        }
        if (userSchemas.isNotEmpty()) return userSchemas

        // 2) 无 schema 语义（MySQL 形态）→ 列 catalog
        val catalogs = mutableListOf<String>()
        md.catalogs.use { rs -> while (rs.next()) rs.getString(1)?.let { catalogs += it } }
        val userCatalogs = catalogs.filter { !it.isSystemSchemaName() }
        if (userCatalogs.isNotEmpty()) return userCatalogs.map { SchemaMeta(it, null) }

        // 3) 都没有 → 单默认节点
        return listOf(SchemaMeta(null, null))
    }

    override fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val md = conn.metaData
        val tables = mutableListOf<String>()
        val views = mutableListOf<String>()
        val matViews = mutableListOf<String>()

        runCatching {
            md.getTables(schema.catalog, schema.schema, "%", null).use { rs ->
                while (rs.next()) {
                    val name = rs.getString("TABLE_NAME") ?: continue
                    val type = rs.getString("TABLE_TYPE") ?: continue
                    when (type.uppercase()) {
                        "TABLE", "BASE TABLE", "PARTITIONED TABLE", "FOREIGN TABLE",
                        "GLOBAL TEMPORARY", "LOCAL TEMPORARY" -> if (name.isNotBlank()) tables += name
                        "MATERIALIZED VIEW" -> if (name.isNotBlank()) matViews += name
                        "VIEW" -> if (name.isNotBlank()) views += name
                    }
                }
            }
        }

        // 标准 JDBC 无 getTriggers；触发器由各专用方言（PG/MySQL/SQLite）以 SQL 探测，
        // Generic 兜底库暂不列触发器。
        return SchemaObjects.simple(
            tables = tables.sorted(),
            views = views.sorted(),
            extra = mapOf(ObjectKind.MATERIALIZED_VIEW to matViews.sorted().map { DbObjectMeta(it, ObjectKind.MATERIALIZED_VIEW) }),
        )
    }
}
