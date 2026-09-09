package jdbc

import db.DbType

/**
 * H2：与 Generic（DatabaseMetaData）行为一致即可 —— md.schemas 给出
 * PUBLIC + INFORMATION_SCHEMA（系统名已在 Generic 过滤），触发器由 getTriggers 兜底。
 */
object H2Dialect : GenericDialect(DbType.H2, "org.h2.Driver")
