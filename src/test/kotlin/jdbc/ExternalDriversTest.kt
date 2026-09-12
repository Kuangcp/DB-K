package jdbc

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 外部驱动加载机制：把驱动 jar 放进目录 → 独立 classloader 加载 → 可直接建连。
 * 用 H2（纯 Java、自包含、jar 内含 `META-INF/services/java.sql.Driver`）模拟用户投放的驱动 jar。
 */
class ExternalDriversTest {

    @TempDir
    lateinit var tmp: Path

    private var oldProp: String? = null

    private fun driversDir(): Path = tmp.resolve("drivers")

    @BeforeTest
    fun setUp() {
        oldProp = System.getProperty("dbk.driversDir")
        System.setProperty("dbk.driversDir", driversDir().toString())
        ExternalDrivers.reset()
    }

    @AfterTest
    fun tearDown() {
        ExternalDrivers.reset()
        if (oldProp == null) System.clearProperty("dbk.driversDir")
        else System.setProperty("dbk.driversDir", oldProp)
    }

    @Test
    fun `unknown driver is not available and empty dir is safe`() {
        assertNull(ExternalDrivers.driverFor("com.example.NoSuchDriver"))
        assertFalse(ExternalDrivers.isAvailable("com.example.NoSuchDriver"))
        assertTrue(ExternalDrivers.ensureLoaded().isEmpty())
        assertTrue(Files.isDirectory(ExternalDrivers.driversDir()))
    }

    @Test
    fun `driver jar in directory is loaded and can open a connection`() {
        Files.createDirectories(driversDir())
        val h2Jar = Path.of(
            Class.forName("org.h2.Driver").protectionDomain.codeSource.location.toURI(),
        )
        Files.copy(h2Jar, driversDir().resolve("h2-driver.jar"))

        val names = ExternalDrivers.scanDirectory(driversDir())
        assertTrue(names.any { it == "org.h2.Driver" }, "loaded=$names")
        assertTrue(ExternalDrivers.isAvailable("org.h2.Driver"))

        val driver = ExternalDrivers.driverFor("org.h2.Driver")
        val db = tmp.resolve("ext-h2")
        driver!!.connect("jdbc:h2:$db", Properties().apply { setProperty("user", "sa") }).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE ext_probe (id INT PRIMARY KEY)")
                st.execute("INSERT INTO ext_probe VALUES (1)")
            }
            val n = conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM ext_probe").use { rs -> rs.next(); rs.getInt(1) }
            }
            assertTrue(n == 1)
        }
    }
}
