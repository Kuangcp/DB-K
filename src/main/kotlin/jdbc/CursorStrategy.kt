package jdbc

/**
 * 全量导出的服务端游标策略（N8）。JDBC 没有统一的「服务端游标」开关，各驱动机制不同，
 * 这里把差异显式化，由 [StreamingQuery] 按策略配置 [java.sql.Statement]。
 * 详见 `doc/EXPORT.md` §2 游标支持矩阵。
 */
enum class CursorStrategy {
    /**
     * 无服务端游标：结果集在驱动/内存中物化。
     * 内嵌库（SQLite / H2 本地）本就顺序扫描本地文件，不涉及网络放大。
     */
    NONE,

    /**
     * 事务内命名游标：`autoCommit=false` + 正 `fetchSize`，驱动以服务端 portal 逐批 `FETCH`。
     * **PostgreSQL 专属**——`autoCommit=true` 时 pgjdbc 会把整个结果集拉进内存。
     * [StreamingQuery] 在结束后 rollback 并恢复 `autoCommit`（只读导出无副作用）。
     */
    TRANSACTION_PORTAL,

    /**
     * 逐行流式结果集：`fetchSize = Integer.MIN_VALUE`（Connector/J / MariaDB 约定），
     * 边读边从网络取；流式期间该连接不可执行其他语句（导出独占至读完）。
     */
    MYSQL_STREAM,

    /**
     * 批量预取：`fetchSize` = 正数，驱动按批从网络取。
     * 适用于 SQL Server（adaptive buffering）/ Oracle（row prefetch）/ ClickHouse（HTTP 流）。
     */
    PREFETCH,
}
