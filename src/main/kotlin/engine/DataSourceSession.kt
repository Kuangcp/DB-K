package engine

import engine.model.ColumnMeta
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.QueryResult
import engine.model.SchemaMeta
import engine.model.SchemaObjects

/**
 * 通用数据源会话（引擎层契约）：JDBC / Redis / Elasticsearch 各自实现。
 *
 * - **不依赖 compose/coroutines**：全部方法阻塞式，由 app 层放到 `Dispatchers.IO` 执行。
 * - **不出现 JDBC 专属概念**（`Connection` / `Statement` / 主键定位 / 事务）：
 *   这类能力留在具体实现内的独立接口（如 `jdbc.EditableSession`），app 层按能力位判断。
 * - **命名空间**沿用 [SchemaMeta]（SQL schema / Redis DB / ES 集群），对象沿用 [SchemaObjects]。
 *
 * 现有实现：`jdbc.LiveConnection`（协议 [Protocol.JDBC]）。
 */
interface DataSourceSession : AutoCloseable {

    val profileId: String
    val protocol: Protocol
    val capabilities: BackendCapabilities

    /** 连接是否已建立（未建立时 [runStatement] 等会失败）。 */
    val isOpen: Boolean

    /** 建立连接（幂等：已连接则跳过）。阻塞，勿在 UI 线程调用。 */
    fun open()

    /** 关闭连接并释放资源。 */
    override fun close()

    // ---------- 元数据（树 / 补全） ----------

    /** 探测「库」层级（顶层节点，对应 UI 树的 schema 行）。 */
    fun loadNamespaces(): List<SchemaMeta>

    /** 探测某命名空间下的对象（表 / 视图 / 触发器…）。全量实现。 */
    fun loadObjects(ns: SchemaMeta): SchemaObjects

    /** 各对象组的计数（不拉正文）；配合 [capabilities.lazyObjectGroups]。 */
    fun loadObjectCounts(ns: SchemaMeta): Map<ObjectKind, Int>

    /** 懒加载的「核心组」正文（补全 / 首屏必需的类型）。 */
    fun loadCoreObjects(ns: SchemaMeta): SchemaObjects

    /** 单一类型组的正文（组展开时调用）。 */
    fun loadObjectsForKind(ns: SchemaMeta, kind: ObjectKind): List<DbObjectMeta>

    /** 探测单表列清单（编辑器补全 / 结果编辑定位）。 */
    fun loadColumns(ns: SchemaMeta?, table: String): List<ColumnMeta>

    /** 取对象定义（DDL）；null = 取不到。仅当 [capabilities.objectDdl]。 */
    fun objectDdl(ns: SchemaMeta?, name: String): String?

    /** 生成整对象预览语句 / 命令。仅当 [capabilities.objectPreview]。 */
    fun previewQuery(ns: SchemaMeta?, name: String): String

    /** 会话级目标切换 SQL / 命令；null = 无需切换。 */
    fun sessionContextSql(ns: SchemaMeta): String?

    // ---------- 执行 ----------

    /**
     * 执行**单条**语句并返回结果；[sessionContextSql] 非空时先切换会话。
     * 失败抛异常（由 app 层转为可读错误）。执行期应登记当前语句以便 [cancel]。
     */
    fun runStatement(statement: String, sessionContextSql: String?): QueryResult

    /** 取消当前执行；返回是否发出了取消请求（正在排队 / 驱动不支持时 false）。 */
    fun cancel(): Boolean
}
