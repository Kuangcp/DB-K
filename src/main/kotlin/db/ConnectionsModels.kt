package db

/**
 * 目标数据库类型。存储层枚举，连接档案持久化用其 name；
 * jdbc 层的方言注册表与 UI 层的徽章展示都依赖它。
 */
enum class DbType(
    val label: String,
    /** UI 徽章短名（2 字符内）。 */
    val badge: String,
    val defaultPort: Int,
    val driverClass: String,
    /** 徽章底色 ARGB。 */
    val badgeColor: Long,
) {
    POSTGRES("PostgreSQL", "PG", 5432, "org.postgresql.Driver", 0xFF336791),
    MYSQL("MySQL", "MY", 3306, "com.mysql.cj.jdbc.Driver", 0xFF00758F),
    MARIADB("MariaDB", "MA", 3306, "org.mariadb.jdbc.Driver", 0xFF5B3A8E),
    SQLITE("SQLite", "SQ", 0, "org.sqlite.JDBC", 0xFF0F80CC),
    H2("H2", "H2", 9092, "org.h2.Driver", 0xFF2E7D32),
    // ClickHouse 徽章白字需深黄底：官方黄 #FFCC00 对比度过低，取暗金黄
    CLICKHOUSE("ClickHouse", "CH", 8123, "com.clickhouse.jdbc.ClickHouseDriver", 0xFFB8860B),
}

/** 本地 JDBC 连接档案（存储模型，对应 connections 表一行）。 */
data class ConnectionProfile(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val dbType: DbType,
    val host: String = "",
    val port: Int = 0,
    /** 数据库名；SQLite 类型时是文件路径。 */
    val database: String = "",
    val user: String? = null,
    val password: String? = null,
    val extraParams: String = "",
    val color: String? = null,
    val sortOrder: Int = 0,
) {
    /** 生成 JDBC URL（编辑弹窗实时预览用；M2 连接时以方言实现为准）。 */
    fun urlPreview(): String {
        // port=0（未显式设置）时回落类型默认端口，避免拼出 :0
        val p = if (port > 0) port else dbType.defaultPort
        val base = when (dbType) {
            DbType.POSTGRES -> "jdbc:postgresql://$host:$p/$database"
            DbType.MYSQL -> "jdbc:mysql://$host:$p/$database"
            DbType.MARIADB -> "jdbc:mariadb://$host:$p/$database"
            DbType.SQLITE -> "jdbc:sqlite:$database"
            // host 为空 → 本地文件模式（database 即文件路径）；否则 tcp 远程
            DbType.H2 -> if (host.isBlank()) "jdbc:h2:$database" else "jdbc:h2:tcp://$host:$p/$database"
            DbType.CLICKHOUSE -> "jdbc:clickhouse://$host:$p/$database"
        }
        return if (extraParams.isNotBlank() && dbType != DbType.SQLITE) {
            "$base?${extraParams.trim().trimStart('?', '&')}"
        } else base
    }
}

/** 文件夹（存储模型，对应 folders 表一行）。M1 只使用根级文件夹。 */
data class FolderRow(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
)
