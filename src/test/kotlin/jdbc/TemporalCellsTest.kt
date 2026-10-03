package jdbc

import db.DisplayZone
import engine.model.QueryColumn
import java.sql.Timestamp
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 固定时区：UTC+0 */
private val Z_UTC = ZoneId.of("UTC")
/** 固定时区：Asia/Shanghai +08:00 */
private val Z_SH = ZoneId.of("Asia/Shanghai")
/** Source = Shanghai, Display = Shanghai (same) */
private val CTX_SAME = TemporalContext(Z_SH, DisplayZone.Local)
/** Source = UTC, Display = Shanghai */
private val CTX_UTC2SH = TemporalContext(Z_UTC, DisplayZone.Local)
/** Source = Shanghai, Display = Source (no conversion) */
private val CTX_SRC = TemporalContext(Z_UTC, DisplayZone.Source)

class TemporalCellsTest {

    // ==================== null / 非时间类型 ====================

    @Test fun `null returns null`() {
        assertNull(TemporalCells.render(null))
    }

    @Test fun `non-temporal values pass through`() {
        assertEquals("abc", TemporalCells.render("abc"))
        assertEquals("42", TemporalCells.render(42))
        assertEquals("true", TemporalCells.render(true))
        assertEquals("[3 bytes]", TemporalCells.render(byteArrayOf(1, 2, 3)))
    }

    @Test fun `non-temporal with context passes through`() {
        assertEquals("hello", TemporalCells.render("hello", null, CTX_SAME))
    }

    // ==================== SQL Timestamp ====================

    @Test fun `timestamp with same zone keeps value`() {
        val ts = Timestamp.from(LocalDateTime.of(2026, 10, 3, 14, 30, 0).atZone(CTX_SAME.sourceZone).toInstant())
        assertEquals("2026-10-03 14:30:00", TemporalCells.render(ts, qc(Types.TIMESTAMP), CTX_SAME))
    }

    @Test fun `timestamp converted from UTC to Shanghai`() {
        // 14:30 UTC → 22:30 Shanghai
        val ts = Timestamp.from(LocalDateTime.of(2026, 10, 3, 14, 30, 0).atZone(Z_UTC).toInstant())
        assertEquals("2026-10-03 22:30:00", TemporalCells.render(ts, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    @Test fun `timestamp on DATE column renders date-only`() {
        val ts = Timestamp.from(LocalDateTime.of(2026, 10, 3, 14, 30, 0).atZone(Z_UTC).toInstant())
        assertEquals("2026-10-03", TemporalCells.render(ts, qc(Types.DATE), CTX_UTC2SH))
    }

    @Test fun `timestamp with nanos`() {
        val ts = Timestamp.from(LocalDateTime.of(2026, 10, 3, 14, 30, 0, 123_000_000).atZone(Z_UTC).toInstant())
        assertEquals("2026-10-03 22:30:00.123", TemporalCells.render(ts, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    // ==================== LocalDateTime ====================

    @Test fun `localdatetime with same zone no conversion`() {
        val ldt = LocalDateTime.of(2026, 10, 3, 14, 30, 0)
        assertEquals("2026-10-03 14:30:00", TemporalCells.render(ldt, qc(Types.TIMESTAMP), CTX_SAME))
    }

    @Test fun `localdatetime UTC to Shanghai`() {
        val ldt = LocalDateTime.of(2026, 10, 3, 14, 30, 0) // treated as UTC wall clock
        assertEquals("2026-10-03 22:30:00", TemporalCells.render(ldt, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    @Test fun `localdatetime on DATE column renders date-only`() {
        val ldt = LocalDateTime.of(2026, 10, 3, 14, 30, 0)
        assertEquals("2026-10-03", TemporalCells.render(ldt, qc(Types.DATE), CTX_UTC2SH))
    }

    @Test fun `localdatetime source mode no conversion`() {
        val ldt = LocalDateTime.of(2026, 10, 3, 14, 30, 0)
        assertEquals("2026-10-03 14:30:00", TemporalCells.render(ldt, qc(Types.TIMESTAMP), CTX_SRC))
    }

    @Test fun `localdatetime with nanos`() {
        val ldt = LocalDateTime.of(2026, 10, 3, 14, 30, 5, 789_000_000)
        assertEquals("2026-10-03 22:30:05.789", TemporalCells.render(ldt, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    // ==================== OffsetDateTime / ZonedDateTime / Instant ====================

    @Test fun `offsetdatetime converts to display zone`() {
        val odt = OffsetDateTime.of(2026, 10, 3, 14, 30, 0, 0, ZoneOffset.of("+05:30"))
        // Instant = 09:00 UTC → display in Shanghai = 17:00
        assertEquals("2026-10-03 17:00:00", TemporalCells.render(odt, qc(Types.TIMESTAMP_WITH_TIMEZONE), CTX_UTC2SH))
    }

    @Test fun `zoneddatetime converts to display zone`() {
        val zdt = ZonedDateTime.of(2026, 10, 3, 14, 30, 0, 0, ZoneId.of("Europe/London"))
        // London is +01 (BST in Oct) → instant = 13:30 UTC → Shanghai = 21:30
        assertEquals("2026-10-03 21:30:00", TemporalCells.render(zdt, qc(Types.TIMESTAMP_WITH_TIMEZONE), CTX_UTC2SH))
    }

    @Test fun `instant renders in display zone`() {
        val inst = Instant.parse("2026-10-03T14:30:00Z")
        assertEquals("2026-10-03 22:30:00", TemporalCells.render(inst, qc(Types.TIMESTAMP_WITH_TIMEZONE), CTX_UTC2SH))
    }

    @Test fun `source mode shows offset suffix for offsetdatetime`() {
        val odt = OffsetDateTime.of(2026, 10, 3, 14, 30, 0, 0, ZoneOffset.of("+05:30"))
        assertEquals("2026-10-03 14:30:00+05:30", TemporalCells.render(odt, null, CTX_SRC))
    }

    // ==================== JDBC Date / Time ====================

    @Test fun `sql date renders as date-only`() {
        val d = java.sql.Date.valueOf("2026-10-03")
        assertEquals("2026-10-03", TemporalCells.render(d, qc(Types.DATE), CTX_UTC2SH))
    }

    @Test fun `sql time renders as time-only`() {
        val t = java.sql.Time.valueOf("14:30:00")
        assertEquals("14:30:00", TemporalCells.render(t, qc(Types.TIME), CTX_UTC2SH))
    }

    @Test fun `local date no conversion`() {
        val d = LocalDate.of(2026, 10, 3)
        assertEquals("2026-10-03", TemporalCells.render(d, qc(Types.DATE), CTX_UTC2SH))
    }

    @Test fun `local time no conversion`() {
        val t = LocalTime.of(14, 30, 0)
        assertEquals("14:30:00", TemporalCells.render(t, qc(Types.TIME), CTX_UTC2SH))
    }

    // ==================== 文本时间值换算（SQLite / 无类型列） ====================

    @Test fun `text iso datetime on temporal column converts`() {
        // 2026-10-03 06:00:00 in UTC → Shanghai = 14:00
        val text = "2026-10-03 06:00:00"
        assertEquals("2026-10-03 14:00:00", TemporalCells.render(text, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    @Test fun `text iso datetime on non-temporal column does NOT convert`() {
        // TEXT column with sqlType=VARCHAR → no heuristic conversion
        val text = "2026-10-03 06:00:00"
        assertEquals("2026-10-03 06:00:00", TemporalCells.render(text, qc(Types.VARCHAR), CTX_UTC2SH))
    }

    @Test fun `text date-only on temporal column stays date`() {
        val text = "2026-10-03"
        assertEquals("2026-10-03", TemporalCells.render(text, qc(Types.DATE), CTX_UTC2SH))
    }

    @Test fun `text with offset on temporal column converts`() {
        val text = "2026-10-03T06:00:00+00:00" // UTC → Shanghai 14:00
        assertEquals("2026-10-03 14:00:00", TemporalCells.render(text, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    @Test fun `text with Z suffix converts`() {
        val text = "2026-10-03T06:00:00Z" // UTC → Shanghai 14:00
        assertEquals("2026-10-03 14:00:00", TemporalCells.render(text, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    @Test fun `plain text on temporal column returns text`() {
        val text = "not a date"
        assertEquals("not a date", TemporalCells.render(text, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    @Test fun `sqlite text heuristic applies via isTemporalSqlType`() {
        // SQLite declares DATETIME as Types.DATE → temporal → converts
        val text = "2026-10-03 06:00:00"
        assertEquals("2026-10-03 14:00:00", TemporalCells.render(text, qc(Types.DATE), CTX_UTC2SH))
    }

    @Test fun `text with nanos converts correctly`() {
        val text = "2026-10-03 06:00:00.123456"
        assertEquals("2026-10-03 14:00:00.123456", TemporalCells.render(text, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    // ==================== 格式 ====================

    @Test fun `drops ISO T separator`() {
        val ldt = LocalDateTime.of(2026, 10, 3, 14, 0, 0)
        assertEquals("2026-10-03 14:00:00", TemporalCells.render(ldt, qc(Types.TIMESTAMP), CTX_SAME))
    }

    @Test fun `drops trailing zero seconds`() {
        val ldt = LocalDateTime.of(2026, 10, 3, 14, 0, 0)
        assertEquals("2026-10-03 14:00:00", TemporalCells.render(ldt, null, CTX_SAME))
    }

    @Test fun `shows nanos when present`() {
        val ts = Timestamp.from(LocalDateTime.of(2026, 10, 3, 14, 0, 0, 100_000_000).atZone(Z_UTC).toInstant())
        assertEquals("2026-10-03 22:00:00.1", TemporalCells.render(ts, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    @Test fun `trailing zeros stripped in nanos`() {
        val ts = Timestamp.from(LocalDateTime.of(2026, 10, 3, 14, 0, 0, 120_000_000).atZone(Z_UTC).toInstant())
        assertEquals("2026-10-03 22:00:00.12", TemporalCells.render(ts, qc(Types.TIMESTAMP), CTX_UTC2SH))
    }

    // ==================== sourceOnly helper ====================

    @Test fun `source only context renders no conversion`() {
        val ctx = TemporalContext.sourceOnly()
        val text = "2026-10-03 06:00:00"
        assertEquals("2026-10-03 06:00:00", TemporalCells.render(text, qc(Types.TIMESTAMP), ctx))
    }

    // ==================== looksLikeDateTime ====================

    @Test fun `looksLikeDateTime matches valid formats`() {
        assertTrue(TemporalCells.looksLikeDateTime("2026-10-03 06:00:00"))
        assertTrue(TemporalCells.looksLikeDateTime("2026-10-03T06:00:00"))
        assertTrue(TemporalCells.looksLikeDateTime("2026-10-03T06:00:00.123"))
        assertTrue(TemporalCells.looksLikeDateTime("2026-10-03 06:00:00Z"))
        assertTrue(TemporalCells.looksLikeDateTime("2026-10-03 06:00:00+08:00"))
        assertTrue(TemporalCells.looksLikeDateTime("2026-10-03 06:00:00+0800"))
    }

    @Test fun `looksLikeDateTime rejects non-temporal`() {
        kotlin.test.assertFalse(TemporalCells.looksLikeDateTime("plain text"))
        kotlin.test.assertFalse(TemporalCells.looksLikeDateTime("2026-10-03"))
        kotlin.test.assertFalse(TemporalCells.looksLikeDateTime("14:00:00"))
        kotlin.test.assertFalse(TemporalCells.looksLikeDateTime(""))
    }

    // ==================== helpers ====================

    private fun qc(sqlType: Int): QueryColumn = QueryColumn(
        name = "c", sqlType = sqlType,
    )
}