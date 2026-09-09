package db

/**
 * SQL 执行历史行（sql_history 表，profile_id 指向连接档案；连接删除时置 NULL）。
 * 记录执行时间/SQL/成败/耗时/行数，供历史面板展示与回填编辑器。
 */
data class SqlHistoryRow(
    val id: String,
    val profileId: String?,
    val sqlText: String,
    val ok: Boolean,
    val executedAtMs: Long,
    val durationMs: Long,
    /** 查询=返回行数（可能被 MAX_ROWS 截断）；更新=受影响行数。 */
    val rowCount: Int,
    val errorMessage: String? = null,
)
