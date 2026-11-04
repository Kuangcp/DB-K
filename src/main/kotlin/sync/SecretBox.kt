package sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 同步口令派生与条目加解密（纯 JDK，无状态）。
 *
 * 与连接档案导出（`db.ProfileTransfer`）共用同一套参数：PBKDF2-HMAC-SHA256 200k 轮 → 256bit
 * AES 密钥；AES/GCM/NoPadding，12B 随机 IV，128bit tag；密文前缀标识格式版本。
 *
 * 注意：GCM 随机 IV 让相同明文每次产生不同密文，因此**不能**用「文件字节是否相同」判断内容变更——
 * 导出侧必须先解密比对明文（见 `SyncEngine.writeTree`）。
 */
object SecretBox {

    /** 密文前缀（版本化；后续换算法时用新前缀区分）。 */
    const val ENC_PREFIX: String = "dbk-sync:v1:"

    /** 口令校验串：加密后写入仓库 `secret.check`，拉取前解密比对即可验证口令。 */
    const val CHECK_PLAINTEXT: String = "db-k-sync-v1"

    /** PBKDF2 盐长度（字节）。 */
    const val SALT_BYTES: Int = 16

    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    /** 新随机盐（首次在仓库里创建 `salt.bin` 时用）。 */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** 加密为 `ENC_PREFIX + base64(iv || ciphertext+tag)`。 */
    fun encrypt(key: SecretKey, plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain)
        return ENC_PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun isEncrypted(text: String): Boolean = text.startsWith(ENC_PREFIX)

    /**
     * 解密；前缀缺失、base64 非法、口令错误（GCM tag 校验失败）一律抛
     * [IllegalArgumentException]，调用方不得把「解不开」当成空值处理。
     */
    fun decrypt(key: SecretKey, stored: String): ByteArray {
        require(isEncrypted(stored)) { "bad ciphertext" }
        val raw = runCatching { Base64.getDecoder().decode(stored.removePrefix(ENC_PREFIX)) }
            .getOrElse { throw IllegalArgumentException("bad ciphertext", it) }
        require(raw.size > IV_BYTES) { "bad ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_BYTES)))
        return runCatching { cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES) }
            .getOrElse { throw IllegalArgumentException("bad ciphertext", it) }
    }
}
