package jdbc

import db.ConnectionProfile
import db.DbType
import org.h2.tools.Server
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 连接健康：服务端单方面断开后，下一次执行应自动重连。
 * 用 H2 TCP 服务器「停服再起」模拟对端消失（旧连接失效、连接对象自身仍以为在线）。
 */
class LiveConnectionReconnectTest {

    @Test
    fun `reconnects after server side connection loss`() {
        val port = ServerSocket(0).use { it.localPort }
        var server = startServer(port)
        val profile = ConnectionProfile(
            id = "rc", name = "rc", dbType = DbType.H2,
            host = "localhost", port = port, database = "mem:reconn",
            user = "sa", password = "", extraParams = "DB_CLOSE_DELAY=-1",
        )
        try {
            LiveConnection(profile).use { session ->
                session.open()
                assertEquals(1, session.runStatement("SELECT 1", null).rows.size)
                // 对端消失：停服（连接对象不会立刻知道自己已死）
                server.stop()
                server = startServer(port)
                // 下一次执行：用前校验/断连重试应自动重建连接并成功
                assertEquals(1, session.runStatement("SELECT 1", null).rows.size)
                assertTrue(session.isOpen)
            }
        } finally {
            runCatching { server.stop() }
        }
    }

    private fun startServer(port: Int): Server =
        Server.createTcpServer("-tcpPort", port.toString(), "-tcpDaemon", "-ifNotExists").start()
}
