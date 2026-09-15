package jdbc

import db.ConnectionProfile
import db.DbType

/**
 * H2：与 Generic（DatabaseMetaData）行为一致即可 —— md.schemas 给出
 * PUBLIC + INFORMATION_SCHEMA（系统名已在 Generic 过滤），触发器由 getTriggers 兜底。
 */
object H2Dialect : GenericDialect(DbType.H2, "org.h2.Driver") {
    /** 仅 tcp 远程形态需要探活；嵌入式（host 空＝file/mem）跳过。 */
    override fun healthFor(profile: ConnectionProfile): ConnectionHealth? =
        if (profile.host.isBlank()) null else ConnectionHealth()

    /** H2 的 schema 即作用域：SET SCHEMA 切过去（引号保大小写）。 */
    override fun sessionContextSql(schema: engine.model.SchemaMeta): String? =
        schema.schema?.let { "SET SCHEMA ${quoteIdent(it)}" }
}
