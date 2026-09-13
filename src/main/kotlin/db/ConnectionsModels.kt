package db

import engine.Protocol

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
    /**
     * 驱动不在内置 classpath：需把 jar 放入 `<dataDir>/drivers`，由 [jdbc.ExternalDrivers]
     * 以独立 classloader 加载（SQL Server 可自由分发；Oracle 驱动受 license 限制不可内置）。
     */
    val externalDriver: Boolean = false,
) {
    POSTGRES("PostgreSQL", "PG", 5432, "org.postgresql.Driver", 0xFF336791),
    MYSQL("MySQL", "MY", 3306, "com.mysql.cj.jdbc.Driver", 0xFF00758F),
    MARIADB("MariaDB", "MA", 3306, "org.mariadb.jdbc.Driver", 0xFF5B3A8E),
    SQLITE("SQLite", "SQ", 0, "org.sqlite.JDBC", 0xFF0F80CC),
    H2("H2", "H2", 9092, "org.h2.Driver", 0xFF2E7D32),
    // ClickHouse 徽章白字需深黄底：官方黄 #FFCC00 对比度过低，取暗金黄
    CLICKHOUSE("ClickHouse", "CH", 8123, "com.clickhouse.jdbc.ClickHouseDriver", 0xFFB8860B),
    // 外部驱动：官方红底白字
    SQLSERVER("SQL Server", "MS", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver", 0xFFCC2927, externalDriver = true),
    ORACLE("Oracle", "OR", 1521, "oracle.jdbc.OracleDriver", 0xFFC74634, externalDriver = true),
    // 非 JDBC：Redis 后端（N5），driverClass 不适用
    REDIS("Redis", "RD", 6379, "", 0xFFD82C20),
    ;

    /** 数据源协议：决定由哪个后端实现 `engine.DataSourceSession`。 */
    val protocol: Protocol
        get() = when (this) {
            REDIS -> Protocol.REDIS
            else -> Protocol.JDBC
        }
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
            // SQL Server 用 `;` 分隔属性；database 即 databaseName
            DbType.SQLSERVER -> "jdbc:sqlserver://$host:$p;databaseName=$database"
            // Oracle thin：database 为 service name（非 SID）
            DbType.ORACLE -> "jdbc:oracle:thin:@$host:$p/$database"
            // Redis 无 JDBC URL；仅作编辑弹窗预览（实际连接走 Jedis）
            DbType.REDIS -> "redis://$host:$p/${database.ifBlank { "0" }}"
        }
        if (dbType == DbType.SQLITE || dbType == DbType.REDIS) return base
        // 拼接参数；ClickHouse 默认关 HTTP 压缩：驱动默认 compress=true，期望 ClickHouse-LZ4
        // 帧（0x82…），但经反代/网关/内网转发链路常返回未压缩体导致 “Magic is not correct”。
        // 用户在“附加参数”显式写 compress=… 时尊重其选择。
        var params = extraParams.trim().trimStart('?', '&', ';')
        if (dbType == DbType.CLICKHOUSE &&
            extraParams.split('&', ';', '?')
                .none { it.trim().startsWith("compress=", ignoreCase = true) }
        ) {
            params = if (params.isEmpty()) "compress=0" else "compress=0&$params"
        }
        if (params.isEmpty()) return base
        // SQL Server 的属性分隔符是 `;`（用户可能写成 ?a=1&b=2，统一归一化）
        if (dbType == DbType.SQLSERVER) {
            val normalized = params.split('&', ';', '?').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(";")
            return if (normalized.isEmpty()) base else "$base;$normalized"
        }
        return "$base?$params"
    }
}

/** 文件夹（存储模型，对应 folders 表一行）。M1 只使用根级文件夹。 */
data class FolderRow(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
)

/** 导入连接档案的结果统计（for toast / 日志）。 */
data class ProfileImportSummary(
    val foldersAdded: Int,
    val connectionsAdded: Int,
)
