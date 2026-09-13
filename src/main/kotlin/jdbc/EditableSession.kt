package jdbc

/**
 * 结果写回能力（当前仅 JDBC 实现）。
 *
 * 把「JDBC 事务 + 参数化 UPDATE」封在实现内部，app 层据此能力位（[engine.BackendCapabilities.editableResult]）
 * 判断是否可编辑，**不直接接触 `java.sql.Connection`**。Redis / ES 等非 SQL 后端不实现本接口。
 */
interface EditableSession {

    /**
     * 在**单事务**中执行多条 UPDATE（每条要求恰好影响 1 行，否则整体回滚）。
     * [sessionContextSql] 非空时先切会话；返回总影响行数。
     */
    fun applyUpdatePlans(plans: List<UpdatePlan>, sessionContextSql: String?): Int
}
