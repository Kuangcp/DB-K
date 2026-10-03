package jdbc

import engine.model.QueryColumn
import java.sql.Timestamp as SqlTimestamp
import java.sql.Date as SqlDate
import java.sql.Time as SqlTime
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 纯时间值渲染/换算逻辑（jdbc 层，无 compose 依赖）。
 *
 * 两种模式：
 * - Source（数据源时区）：原样格式统一，不换算
 * - Local/Custom（应用/指定时区）：墙钟按 source→display 换算；瞬时值→display 区
 */
object TemporalCells {

    private val FMT_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FMT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val FMT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun nanoSuffix(nano: Int): String {
        if (nano == 0) return ""
        return "." + nano.toString().padStart(9, '0').trimEnd('0')
    }

    private fun fmtLdt(dt: LocalDateTime) = FMT_DT.format(dt) + nanoSuffix(dt.nano)
    private fun fmtLd(d: LocalDate) = FMT_DATE.format(d)
    private fun fmtLt(t: LocalTime) = FMT_TIME.format(t) + nanoSuffix(t.nano)

    private fun offsetLabel(offset: ZoneOffset): String =
        if (offset.totalSeconds == 0) "Z" else offset.toString()

    private fun isTemporalSqlType(t: Int): Boolean = when (t) {
        Types.DATE, Types.TIME, Types.TIMESTAMP,
        Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> true
        else -> false
    }

    // ========== 公开入口 ==========

    /**
     * 渲染单元格值。
     * @param ctx null → 等价 v.toString()（兼容旧路径）
     * @param col 可空；为空时不做类型判断
     */
    fun render(
        v: Any?,
        col: QueryColumn? = null,
        ctx: TemporalContext? = null,
    ): String? {
        if (v == null) return null
        if (v is ByteArray) return "[${v.size} bytes]"
        if (ctx == null) return v.toString()
        if (ctx.isSourceMode) return rawFormat(v)
        return convert(v, col, ctx)
    }

    // ========== raw format（不换算 = Source 模式）==========

    private fun rawFormat(v: Any): String = when (v) {
        is SqlTimestamp -> fmtLdt(v.toLocalDateTime())
        is SqlDate -> fmtLd(v.toLocalDate())
        is SqlTime -> fmtLt(v.toLocalTime())
        is LocalDateTime -> fmtLdt(v)
        is OffsetDateTime -> fmtLdt(v.toLocalDateTime()) + offsetLabel(v.offset)
        is ZonedDateTime -> fmtLdt(v.toLocalDateTime()) + offsetLabel(v.offset)
        is Instant -> fmtLdt(LocalDateTime.ofInstant(v, ZoneId.of("UTC"))) + "Z"
        is LocalDate -> fmtLd(v)
        is LocalTime -> fmtLt(v)
        is OffsetTime -> v.toString()
        else -> v.toString()
    }

    // ========== converted（换算）==========

    private fun convert(v: Any, col: QueryColumn?, ctx: TemporalContext): String {
        val dz = ctx.effectiveDisplayZone
        val st = col?.sqlType ?: Types.OTHER
        return when (v) {
            is SqlTimestamp -> {
                if (st == Types.DATE) fmtLd(v.toLocalDateTime().toLocalDate())
                else fmtLdt(instantToLocal(v.toInstant(), dz))
            }
            is SqlDate -> fmtLd(v.toLocalDate())
            is SqlTime -> fmtLt(v.toLocalTime())
            is LocalDateTime -> when {
                st == Types.DATE -> fmtLd(v.toLocalDate())
                st == Types.TIME -> fmtLt(v.toLocalTime())
                ctx.isSameZone() -> fmtLdt(v)
                else -> convertLdt(v, ctx.sourceZone, dz)
            }
            is OffsetDateTime -> fmtLdt(instantToLocal(v.toInstant(), dz))
            is ZonedDateTime -> fmtLdt(instantToLocal(v.toInstant(), dz))
            is Instant -> fmtLdt(instantToLocal(v, dz))
            is LocalDate -> fmtLd(v)
            is LocalTime -> fmtLt(v)
            is OffsetTime -> fmtLt(v.toLocalTime())
            is String -> convertText(v, col, ctx) ?: v
            else -> v.toString()
        }
    }

    private fun convertLdt(dt: LocalDateTime, sz: ZoneId, dz: ZoneId): String =
        fmtLdt(instantToLocal(dt.atZone(sz).toInstant(), dz))

    private fun instantToLocal(instant: Instant, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(instant, zone)

    // ========== 文本时间值（SQLite / 无类型列）==========

    /**
     * ISO 格式：yyyy-MM-dd[ T]HH:mm[:ss][.f…][Z|±HH[:mm]]
     * 分组: 1=year 2=month 3=day 4=hour 5=minute 6=second 7=fraction 8=Z/sign 9=minute-offset
     */
    private val ISO_PATTERN = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d{1,9}))?(Z|[+-]\d{2}(?::\d{2})?|[+-]\d{4})?$"""
    )

    /**
     * 换算文本时间值。
     * - 列类型是时间类的：无条件尝试解析换算
     * - 非时间类列：仅当 [ctx.enableTextHeuristic] 为 true 且文字含时分秒才尝试
     * @return 换算后字符串，或 null（保持原值）
     */
    private fun convertText(text: String, col: QueryColumn?, ctx: TemporalContext): String? {
        val trimmed = text.trim()
        val m = ISO_PATTERN.matchEntire(trimmed) ?: return null
        val isTemporal = isTemporalSqlType(col?.sqlType ?: Types.OTHER)

        if (!isTemporal) {
            // 非时间类列：仅当启用文本启发式且有至少时:分信息才换算
            if (!ctx.enableTextHeuristic) return null
            val h = m.groupValues[4]
            if (h.isEmpty()) return null
        }

        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].let { if (it.isEmpty()) 0 else it.toInt() }
        val min = m.groupValues[5].let { if (it.isEmpty()) 0 else it.toInt() }
        val sec = m.groupValues[6].let { if (it.isEmpty()) 0 else it.toInt() }
        val fracStr = m.groupValues[7]
        val nano = if (fracStr.isEmpty()) 0 else fracStr.padEnd(9, '0').take(9).toInt()
        val dt = LocalDateTime.of(year, month, day, hour, min, sec, nano)

        val zoneStr = m.groupValues[8]
        val dz = ctx.effectiveDisplayZone

        if (zoneStr.isNotEmpty()) {
            // 文本自带 offset → 以此为源
            val offset = parseOffset(zoneStr)
            val instant = dt.atZone(ZoneId.ofOffset("", offset)).toInstant()
            return fmtLdt(instantToLocal(instant, dz))
        }

        // 无 offset → 视作 source zone
        if (ctx.isSameZone()) return fmtLdt(dt)
        val instant = dt.atZone(ctx.sourceZone).toInstant()
        return fmtLdt(instantToLocal(instant, dz))
    }

    private fun parseOffset(s: String): ZoneOffset {
        return when {
            s == "Z" || s == "+0" || s == "-0" || s == "+00" || s == "-00" -> ZoneOffset.UTC
            s.contains(':') -> {
                // "+08:00" or "-05:30"
                ZoneOffset.of(s)
            }
            else -> {
                // "+0800" → "+08:00"; "-0530" → "-05:30"
                val sign = when {
                    s.startsWith('+') || s.startsWith('-') -> s[0]
                    else -> '+'
                }
                val rest = s.dropWhile { it == '+' || it == '-' }
                val padded = rest.padStart(4, '0')
                ZoneOffset.of("$sign${padded.substring(0, 2)}:${padded.substring(2, 4)}")
            }
        }
    }

    /** 外部检测文本是否匹配 ISO 日期时间 */
    fun looksLikeDateTime(text: String): Boolean =
        ISO_PATTERN.matchEntire(text.trim()) != null
}