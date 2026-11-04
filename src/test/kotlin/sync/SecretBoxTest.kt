package sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretBoxTest {

    private val salt = ByteArray(16) { it.toByte() }
    private val key = SecretBox.deriveKey("correct horse", salt)
    private val otherKey = SecretBox.deriveKey("wrong horse", salt)

    @Test
    fun `roundtrip preserves bytes and uses prefix`() {
        val plain = """{"id":"c1","password":"p@ss"}""".toByteArray()
        val stored = SecretBox.encrypt(key, plain)
        assertTrue(stored.startsWith(SecretBox.ENC_PREFIX))
        assertTrue(plain.contentEquals(SecretBox.decrypt(key, stored)))
    }

    @Test
    fun `random iv makes ciphertext differ for same plaintext`() {
        val plain = "same".toByteArray()
        assertTrue(SecretBox.encrypt(key, plain) != SecretBox.encrypt(key, plain))
    }

    @Test
    fun `wrong passphrase fails to decrypt`() {
        val stored = SecretBox.encrypt(key, SecretBox.CHECK_PLAINTEXT.toByteArray())
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(otherKey, stored) }
    }

    @Test
    fun `same passphrase different salt derives different key`() {
        val otherSalt = ByteArray(16) { (it + 1).toByte() }
        val k2 = SecretBox.deriveKey("correct horse", otherSalt)
        val stored = SecretBox.encrypt(key, "x".toByteArray())
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(k2, stored) }
    }

    @Test
    fun `malformed input rejected`() {
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(key, "not-a-blob") }
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(key, SecretBox.ENC_PREFIX + "!!!") }
        assertFalse(SecretBox.isEncrypted("not-a-blob"))
    }

    @Test
    fun `salt is fresh random bytes of expected size`() {
        val a = SecretBox.newSalt()
        assertEquals(SecretBox.SALT_BYTES, a.size)
        assertFalse(a.contentEquals(SecretBox.newSalt()))
    }
}
