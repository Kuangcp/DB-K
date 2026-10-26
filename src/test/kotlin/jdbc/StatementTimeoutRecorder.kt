package jdbc

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/** 记录 `setQueryTimeout` 实际下发的秒数（-1 = 从未调用），用于验证超时传递链路（不需要真数据库）。 */
class TimeoutRecorder {
    var statementTimeout: Int = -1
    var preparedTimeout: Int = -1

    fun connection(): Connection {
        val stmt = proxyStatement(Statement::class.java, prepared = false)
        val ps = proxyStatement(PreparedStatement::class.java, prepared = true)
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "createStatement" -> stmt
                "prepareStatement" -> ps
                "getAutoCommit" -> true
                "toString" -> "TimeoutRecorderConnection"
                else -> defaultValue(method.returnType)
            }
        } as Connection
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Statement> proxyStatement(type: Class<T>, prepared: Boolean): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, args ->
            when (method.name) {
                "setQueryTimeout" -> {
                    val seconds = args!![0] as Int
                    if (prepared) preparedTimeout = seconds else statementTimeout = seconds
                    null
                }
                "executeUpdate" -> 1
                "close" -> null
                "toString" -> "TimeoutRecorderStatement"
                else -> defaultValue(method.returnType)
            }
        } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
