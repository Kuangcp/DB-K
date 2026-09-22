package app.state

import kotlin.test.Test
import kotlin.test.assertEquals

/** 执行语句区间 → 控制台正文行号的映射（gutter 行状态锚定用）。 */
class StatementLinesTest {

    @Test
    fun `lineIndexAt counts preceding newlines`() {
        val text = "a\nbb\nccc"
        assertEquals(0, lineIndexAt(text, 0))
        assertEquals(0, lineIndexAt(text, 1))
        assertEquals(1, lineIndexAt(text, 2))
        assertEquals(1, lineIndexAt(text, 4))
        assertEquals(2, lineIndexAt(text, 5))
        // 越界夹取
        assertEquals(2, lineIndexAt(text, 999))
        assertEquals(0, lineIndexAt(text, -5))
    }

    @Test
    fun `statementLines maps local ranges through anchor`() {
        val text = "SELECT 1;\nSELECT 2;\nSELECT 3"
        // 本地文本 = 从正文 offset 10 起的 "SELECT 2;\nSELECT 3"
        val local = text.substring(10)
        val ranges = listOf(0..7, 10..17)
        assertEquals(listOf(1, 2), statementLines(text, ranges, anchor = 10))
        // 本地区间映射回正文后与原文一致
        assertEquals(
            listOf("SELECT 2", "SELECT 3"),
            ranges.map { local.substring(it.first, it.last + 1) },
        )
    }

    @Test
    fun `statementLines yields nulls when anchor unknown or out of range`() {
        val text = "SELECT 1"
        assertEquals(listOf<Int?>(null, null), statementLines(text, listOf(0..7, 0..7), anchor = null))
        assertEquals(listOf<Int?>(null), statementLines(text, listOf(0..7), anchor = 999))
    }

    @Test
    fun `aggregateLineMarkers groups by line and takes worst status`() {
        assertEquals(emptyMap(), aggregateLineMarkers(emptyList()))
        // 行号 null 忽略
        assertEquals(
            mapOf(2 to LineMarker(RunStatus.OK, 1)),
            aggregateLineMarkers(listOf(StatementProgress(null, RunStatus.PENDING), StatementProgress(2, RunStatus.OK))),
        )
        // 一行多语句：取最严重（FAILED > RUNNING > PENDING > SKIPPED > OK），count = 语句数
        assertEquals(
            mapOf(0 to LineMarker(RunStatus.FAILED, 2)),
            aggregateLineMarkers(listOf(StatementProgress(0, RunStatus.OK), StatementProgress(0, RunStatus.FAILED))),
        )
        assertEquals(
            mapOf(0 to LineMarker(RunStatus.RUNNING, 2)),
            aggregateLineMarkers(listOf(StatementProgress(0, RunStatus.PENDING), StatementProgress(0, RunStatus.RUNNING))),
        )
        assertEquals(
            mapOf(1 to LineMarker(RunStatus.SKIPPED, 2)),
            aggregateLineMarkers(listOf(StatementProgress(1, RunStatus.SKIPPED), StatementProgress(1, RunStatus.SKIPPED))),
        )
        // 不同行各自聚合
        assertEquals(
            mapOf(
                0 to LineMarker(RunStatus.OK, 1),
                1 to LineMarker(RunStatus.PENDING, 1),
            ),
            aggregateLineMarkers(listOf(StatementProgress(0, RunStatus.OK), StatementProgress(1, RunStatus.PENDING))),
        )
    }
}
