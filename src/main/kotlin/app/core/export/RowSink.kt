package app.core.export

import engine.model.QueryColumn
import java.io.Closeable

/**
 * 流式行写出器：一次导出只遍历一遍数据，内存占用与结果行数无关。
 * 所有方法在调用线程同步执行；[row] 收到的列表可能被复用，必须即时消费。
 */
interface RowSink : Closeable {

    /** 表头 / 列元数据（首行前调用一次）。 */
    fun begin(columns: List<QueryColumn>)

    /** 写入一行数据。 */
    fun row(values: List<String?>)

    /** 收尾（写页脚 / flush / 清理临时文件），返回写入的数据行数（不含表头）。 */
    fun finish(): Long

    override fun close() {}
}
