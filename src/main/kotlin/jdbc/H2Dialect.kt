package jdbc

import db.ConnectionProfile
import db.DbType
import engine.model.SchemaMeta

/**
 * H2 双形态共用的方言基类：与 Generic（DatabaseMetaData）行为一致 —— md.schemas 给出
 * PUBLIC + INFORMATION_SCHEMA（系统名已在 Generic 过滤），触发器由 getTriggers 兜底。
 */
open class H2BaseDialect(dbType: DbType) : GenericDialect(dbType, "org.h2.Driver") {
    /** H2 的 schema 即作用域：SET SCHEMA 切过去（引号保大小写）。 */
    override fun sessionContextSql(schema: SchemaMeta): String? =
        schema.schema?.let { "SET SCHEMA ${quoteIdent(it)}" }
}

/**
 * H2 服务端（`jdbc:h2:tcp://host:port/db`）：网络库需探活。
 * host 为空的历史档案（早期版本允许留空 = 本地文件）也跳过探活。
 */
object H2Dialect : H2BaseDialect(DbType.H2) {
    override fun healthFor(profile: ConnectionProfile): ConnectionHealth? =
        if (profile.host.isBlank()) null else ConnectionHealth()
}

/** H2 本地文件（`jdbc:h2:<path>`）：嵌入式读写本地文件，跳过用前校验。 */
object H2LocalDialect : H2BaseDialect(DbType.H2LOCAL) {
    override fun healthFor(profile: ConnectionProfile): ConnectionHealth? = null
}
