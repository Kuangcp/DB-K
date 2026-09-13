package engine

/**
 * 后端能力位：app 层据此启用/禁用功能，**避免把 JDBC 专属概念泄漏到通用层**。
 * 新增协议（Redis / ES）只需正确声明能力，UI 按位降级即可。
 */
data class BackendCapabilities(
    /** 结果可改格 / 增删行（写回需要主键定位等 SQL 语义）。 */
    val editableResult: Boolean = false,
    /** 编辑器关键字 / 表 / 列补全。 */
    val sqlCompletion: Boolean = false,
    /** 支持查看对象定义（DDL）。 */
    val objectDdl: Boolean = false,
    /** 支持生成对象预览语句 / 命令。 */
    val objectPreview: Boolean = true,
    /** 支持会话级执行目标切换（`USE` / `SET search_path` 等）。 */
    val sessionContext: Boolean = false,
    /** 对象组按需懒加载（P6）。 */
    val lazyObjectGroups: Boolean = false,
    /** 命名空间作为「过滤器」而非树层级（如 Redis 的 DB）：树不铺命名空间行，仅看当前一个。 */
    val namespaceAsFilter: Boolean = false,
    /** 编辑器语言（决定高亮 / 补全 / 语句切分）。 */
    val editorLanguage: EditorLanguage = EditorLanguage.SQL,
)
