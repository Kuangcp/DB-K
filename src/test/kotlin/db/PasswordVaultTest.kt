package db

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordVaultTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `encrypt decrypt round trip`() {
        val key = dir.resolve("secret.key")
        val enc = PasswordVault.encrypt(key, "hunter2")
        assertTrue(enc!!.startsWith("enc:v1:"))
        assertFalse(enc.contains("hunter2"))
        assertEquals("hunter2", PasswordVault.decrypt(key, enc))
    }

    @Test
    fun `empty password not stored`() {
        val key = dir.resolve("secret.key")
        assertNull(PasswordVault.encrypt(key, ""))
        assertNull(PasswordVault.encrypt(key, null))
        assertNull(PasswordVault.decrypt(key, null))
    }

    @Test
    fun `unencrypted value passes through decrypt`() {
        assertEquals("plain", PasswordVault.decrypt(dir.resolve("k.key"), "plain"))
    }

    @Test
    fun `decrypt with different key fails`() {
        val enc = PasswordVault.encrypt(dir.resolve("k1.key"), "hunter2")!!
        assertNull(PasswordVault.decrypt(dir.resolve("k2.key"), enc))
    }

    @Test
    fun `isEncrypted detects prefix only`() {
        assertTrue(PasswordVault.isEncrypted("enc:v1:xxx"))
        assertFalse(PasswordVault.isEncrypted("plain"))
        assertFalse(PasswordVault.isEncrypted(null))
    }

    @Test
    fun `migrateLegacyPasswords converts plaintext and is idempotent`() {
        val db = dir.resolve("app.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE connections (id TEXT PRIMARY KEY, password TEXT)")
                st.execute("INSERT INTO connections VALUES ('a', 'plain1'), ('b', 'enc:v1:xxx')")
            }
        }
        val key = PasswordVault.keyFileFor(db)
        repeat(2) { // 跑两遍验证幂等
            DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
                PasswordVault.migrateLegacyPasswords(c, key)
            }
        }
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT password FROM connections WHERE id='a'").use { rs ->
                    rs.next()
                    val v = rs.getString(1)
                    assertTrue(v.startsWith("enc:v1:"))
                    assertFalse(v.contains("plain1"))
                    assertEquals("plain1", PasswordVault.decrypt(key, v))
                }
                st.executeQuery("SELECT password FROM connections WHERE id='b'").use { rs ->
                    rs.next()
                    assertEquals("enc:v1:xxx", rs.getString(1)) // 已加密值不动
                }
            }
        }
    }
}
