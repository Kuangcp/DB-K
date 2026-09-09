package db

import org.tinylog.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 连接密码本机加密（AES-256-GCM，逐值随机 12B IV + 128bit tag）。
 *
 * 密钥文件 <dataDir>/secret.key：不存在则生成 32 随机字节并收紧权限（POSIX rw-------，
 * Windows 忽略）；已存在直接复用 —— 换密钥会让存量密文全部不可解，故不自动轮换，
 * 需要时人工删文件后（连接密码会失效需重填）重建。
 *
 * 存储格式：`enc:v1:<base64(iv + ciphertext)>`；空密码不落盘（null）。
 * 迁移入口 [migrateLegacyPasswords] 在仓库初始化时把存量明文行原地转密。
 */
object PasswordVault {

    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()
    /** keyFile -> SecretKey（进程内缓存；文件被删后下次仍是旧缓存——仓库生命周期内一致即可）。 */
    private val keyCache = ConcurrentHashMap<Path, javax.crypto.SecretKey>()

    /** 与 app.db 同目录的密钥文件（迁移/加解密共用同一把）。 */
    fun keyFileFor(dbPath: Path): Path = dbPath.toAbsolutePath().parent.resolve("secret.key")

    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    fun encrypt(keyFile: Path, plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey(keyFile), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(keyFile: Path, stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        if (!isEncrypted(stored)) return stored // 未加密旧值兜底（正常已由迁移转密）
        return runCatching {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadKey(keyFile),
                GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_BYTES)),
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            Logger.error(it, "password decrypt failed for {}", keyFile)
            null
        }
    }

    /** 存量明文迁移：connections.password 非空且未加密的值原地转成密文（幂等）。 */
    fun migrateLegacyPasswords(conn: Connection, keyFile: Path) {
        val rows = conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, password FROM connections WHERE password IS NOT NULL AND password <> ''",
            ).use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("id") to rs.getString("password"))
                }
            }
        }
        if (rows.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement("UPDATE connections SET password = ? WHERE id = ?").use { ps ->
                rows.forEach { (id, value) ->
                    if (isEncrypted(value)) return@forEach
                    ps.setString(1, encrypt(keyFile, value))
                    ps.setString(2, id)
                    ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ---------- helpers ----------

    private fun loadKey(file: Path): javax.crypto.SecretKey =
        keyCache.computeIfAbsent(file) {
            val bytes = if (Files.isRegularFile(it)) {
                Files.readAllBytes(it)
            } else {
                createKeyFile(it)
            }
            SecretKeySpec(bytes, "AES")
        }

    private fun createKeyFile(file: Path): ByteArray {
        val keyBytes = ByteArray(32).also(random::nextBytes)
        file.parent?.let(Files::createDirectories)
        Files.write(file, keyBytes)
        restrictToOwner(file)
        return keyBytes
    }

    /** POSIX 下收紧为 rw-------（600）；非 POSIX 平台静默忽略。 */
    private fun restrictToOwner(file: Path) {
        runCatching {
            val perms = Files.getPosixFilePermissions(file)
            perms.removeAll(
                listOf(
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
            Files.setPosixFilePermissions(file, perms)
        }
    }
}
