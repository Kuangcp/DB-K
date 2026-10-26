package db

/**
 * 生成标准连接 URI（供「复制连接信息」使用，可直接被 DBeaver / DataGrip / 终端工具识别）。
 *
 * 格式规范：
 * - PostgreSQL → `postgresql://user:pass@host:port/db`
 * - MySQL/MariaDB → `mysql://user:pass@host:port/db`
 * - SQLite → `sqlite:///<abs_path>`（Unix）/ `sqlite:///C:/path`（Windows）
 * - H2 → `h2:tcp://host:port/db` 或 `h2:file:path`
 * - ClickHouse → `clickhouse://user:pass@host:port/db`
 * - SQL Server → `sqlserver://user:pass@host:port;databaseName=db`
 * - Oracle → `oracle:thin:@host:port/db`
 * - Redis → `redis://:pass@host:port?db=N`
 * - ES → `https://user:apikey@host:port`
 */
fun ConnectionProfile.connectionUri(): String {
    val p = if (port > 0) port else dbType.defaultPort
    return when (dbType) {
        DbType.POSTGRES, DbType.MYSQL, DbType.MARIADB -> buildCommonUri(p)
        DbType.CLICKHOUSE -> {
            val auth = buildAuth(user, password)
            if (auth.isEmpty()) "" else "clickhouse://${auth}$host:$p/$database"
        }
        DbType.SQLSERVER -> buildSqlServerUri(p)
        DbType.ORACLE -> buildOracleUri(p)
        DbType.SQLITE -> buildSqliteUri()
        DbType.H2, DbType.H2LOCAL -> buildH2Uri(p)
        DbType.REDIS -> buildRedisUri(p)
        DbType.ELASTICSEARCH -> buildEsUri()
    }
}

/** 对单个组件（用户名或密码）进行百分号编码。 */
private fun encodeComponent(s: String): String = s.replace("%", "%25").replace(":", "%3A").replace("@", "%40")

/** 对用户和密码分别编码后拼接为 user:pass@。 */
private fun buildAuth(u: String?, pw: String?): String {
    val hasU = u?.isNotEmpty() == true
    val hasPw = pw?.isNotEmpty() == true
    if (!hasU && !hasPw) return ""
    val encodedU = u?.takeIf { it.isNotEmpty() }?.let(::encodeComponent) ?: ""
    val encodedPw = pw?.takeIf { it.isNotEmpty() }?.let(::encodeComponent) ?: ""
    val sep = if (encodedU.isNotEmpty() && encodedPw.isNotEmpty()) ":" else ""
    return if (encodedPw.isNotEmpty()) "$encodedU$sep$encodedPw@" else "$encodedU@"
}

private fun ConnectionProfile.buildCommonUri(port: Int): String {
    val protocol = when (dbType) {
        DbType.POSTGRES -> "postgresql"
        DbType.MYSQL, DbType.MARIADB -> "mysql"
        else -> return ""
    }
    val auth = buildAuth(user, password)
    if (auth.isEmpty()) return ""
    val p = if (port > 0) ":$port" else ""
    return "$protocol://${auth}$host$p/$database"
}

private fun ConnectionProfile.buildSqlServerUri(port: Int): String {
    val p = if (port > 0) port else dbType.defaultPort
    val auth = buildAuth(user, password)
    val params = extraParams.ifEmpty { "" }.prependIfNeeded(";")
    return "sqlserver://${auth}$host:$p;databaseName=$database$params"
}

private fun ConnectionProfile.buildOracleUri(port: Int): String {
    val p = if (port > 0) port else dbType.defaultPort
    val auth = buildAuth(user, password)
    return "oracle:thin:${auth}$host:$p/$database"
}

private fun ConnectionProfile.buildSqliteUri(): String {
    val path = database
    val abs = java.io.File(path).absolutePath
    return if (System.getProperty("os.name").lowercase().contains("win")) {
        "sqlite:///${abs.replace("\\", "/")}"
    } else {
        "sqlite:///$abs"
    }
}

private fun ConnectionProfile.buildH2Uri(port: Int): String {
    if (dbType == DbType.H2LOCAL || host.isBlank()) {
        return "h2:file:$database"
    }
    val p = if (port > 0) port else dbType.defaultPort
    val auth = buildAuth(user, password)
    return "h2:tcp://${auth}$host:$p/$database"
}

private fun ConnectionProfile.buildRedisUri(port: Int): String {
    val p = if (port > 0) port else dbType.defaultPort
    val pass = password?.takeIf { it.isNotEmpty() }
    val db = database.takeUnless { it.isEmpty() } ?: "0"
    return if (pass != null) {
        // redis://:encoded_pass@host:port?db=N（冒号开头表示无用户名只有密码）
        "redis://:${encodeComponent(pass)}@$host:$p?db=$db"
    } else {
        "redis://$host:$p?db=$db"
    }
}

private fun ConnectionProfile.buildEsUri(): String {
    val p = if (port > 0) port else dbType.defaultPort
    val u = user ?: ""
    val pw = password ?: ""
    val hasHttps = extraParams.contains("scheme=https", ignoreCase = true)
    val scheme = if (hasHttps) "https" else "http"
    return "$scheme://${encodeComponent(u)}:${encodeComponent(pw)}@$host:$p"
}

private fun String.prependIfNeeded(prefix: String): String =
    if (startsWith(prefix)) this else prefix + this
