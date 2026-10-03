package jdbc

import db.DisplayZone as Dz
import java.time.ZoneId

/**
 * 单元格时区换算上下文：携带数据源时区 + 用户的显示时区选择。
 * 由 [jdbc.LiveConnection] 在连接建立时构造并缓存。
 *
 * [sourceZone] 来自 [jdbc.DbDialect.detectSessionZone]；
 * [displayZone] 来自连接档案的 [db.ConnectionProfile.displayZone]。
 */
data class TemporalContext(
    val sourceZone: ZoneId,
    val displayZone: Dz,
    /** 是否启用文本启发式：非时间类型列的值也尝试按 ISO 日期时间解析并换算。
     *  仅为 SQLite 启用（其时间值以 TEXT 形式返回）。 */
    val enableTextHeuristic: Boolean = false,
) {
    /** 数据源时区模式（不换算，只格式化） */
    val isSourceMode: Boolean get() = displayZone is Dz.Source

    /** 实际渲染用的时区 */
    val effectiveDisplayZone: ZoneId get() = when (displayZone) {
        is Dz.Local -> ZoneId.systemDefault()
        is Dz.Source -> sourceZone
        is Dz.Custom -> ZoneId.of(displayZone.zoneId)
    }

    /** 数据源时区与显示时区相同 → 换算恒等（跳过运算） */
    fun isSameZone(): Boolean = sourceZone == effectiveDisplayZone

    companion object {
        /**
         * 纯不换算模式（等价于今天的行为 + 格式统一）。
         * 用于 [sourceZone] 探测失败或旧代码路径回退。
         */
        fun sourceOnly(zone: ZoneId = ZoneId.of("UTC")): TemporalContext =
            TemporalContext(zone, Dz.Source)
    }
}