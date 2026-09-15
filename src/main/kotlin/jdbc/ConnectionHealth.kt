package jdbc

import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

/**
 * 连接健康策略（按方言；`DbDialect.healthFor` 提供）。设计见 `doc/CONNECTION_HEALTH.md`。
 *
 * 本项目不做手动事务，连接上没有未提交状态，会话上下文（`USE`/`SET search_path`）也由
 * [QueryExecutor.applyContext] 在每条语句前重新下发，因此「使用前探活 + 死连接重建」在语义上安全。
 */
data class ConnectionHealth(
    /**
     * 校验语句；null = 用 JDBC4 `Connection.isValid(timeout)`（多数现代驱动都支持）。
     * 老驱动/个别库可给 `SELECT 1` / `SELECT 1 FROM DUAL`。
     */
    val validationQuery: String? = null,
    /** 空闲超过该毫秒数才值得探活（避免每条语句都多一次往返）；期间直接复用连接。 */
    val idleBeforeCheckMs: Long = 60_000,
    /** 探活超时（秒）；同时约束 `isValid` 与半开 TCP 的最坏等待。 */
    val validationTimeoutSeconds: Int = 3,
)

/**
 * 是否为「连接已断」类异常（方言无关）：
 * JDBC 三种标准 Connection 异常，或 SQLState 以 `08` 开头（连接例外类）。
 * 另外兜底匹配 MySQL Connector/J 的 `CJCommunicationsException` / `ConnectionIsClosedException`
 * ——它们继承 `RuntimeException` 而非 `SQLException`，SQLState 未必带上，故按类名补齐。
 */
fun isConnectionLost(t: Throwable?): Boolean =
    generateSequence(t) { it.cause }.any { e ->
        e is SQLNonTransientConnectionException ||
            e is SQLRecoverableException ||
            e is SQLTransientConnectionException ||
            (e is SQLException && e.sqlState?.startsWith("08") == true) ||
            e.javaClass.name.let {
                it.endsWith("CommunicationsException") || it.endsWith("ConnectionIsClosedException")
            }
    }
