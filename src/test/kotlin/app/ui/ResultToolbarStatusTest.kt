package app.ui

import app.state.ConsoleRunUi
import app.state.StatementOutcome
import engine.model.QueryColumn
import engine.model.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 单语句结果状态点判定：仅单语句、非执行中时给结果（true=成功绿、false=失败红）；
 * 多语句由左侧芯片自带状态点，不在这里显示。
 */
class ResultToolbarStatusTest {

    private fun result() = QueryResult(
        sql = "select 1",
        columns = listOf(QueryColumn("c")),
        rows = listOf(listOf("1")),
    )

    private fun ok() = StatementOutcome(sql = "select 1", result = result())
    private fun failed() = StatementOutcome(sql = "select 1", error = "boom")

    @Test
    fun `single success is green`() {
        assertEquals(true, singleResultStatus(ConsoleRunUi(outcomes = listOf(ok()))))
    }

    @Test
    fun `single failure is red`() {
        assertEquals(false, singleResultStatus(ConsoleRunUi(outcomes = listOf(failed()))))
    }

    @Test
    fun `executing shows nothing`() {
        assertNull(singleResultStatus(ConsoleRunUi(executing = true, outcomes = listOf(ok()))))
    }

    @Test
    fun `multi statement shows nothing`() {
        assertNull(singleResultStatus(ConsoleRunUi(outcomes = listOf(ok(), failed()))))
    }

    @Test
    fun `no outcome shows nothing`() {
        assertNull(singleResultStatus(ConsoleRunUi()))
    }
}
