package db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 连接档案导出 / 导入（换机迁移、备份）。
 *
 * 范围：**连接档案 + 文件夹层级**；不含控制台正文、SQL 历史、元数据缓存。
 * 内容格式：JSON（UTF-8），带 `formatVersion` / `app` / `exportedAt` 便于向后兼容。
 *
 * 文件加密（导出必做）：整份 JSON 用用户口令派生的 AES-256-GCM 加密后 Base64 落盘，
 * 文件扩展名 `.dbk`，形如 `dbk-enc:v1:<base64(salt || iv || ciphertext+tag)>`；口令不落盘，导入时重新输入。
 * `read` 兼容早期**明文 JSON** 文件（内容以 `{` 开头时直接解析，不要求口令）。
 *
 * 密码策略：
 * - 默认**不导出密码**（字段直接省略，导入后重填）；
 * - 可选导出**明文**密码（`includePasswords=true`），但外层仍受口令加密保护——
 *   本机 `<dataDir>/secret.key` 是机器本地密钥，无法跨机解密，因此不能直接搬密文。
 *
 * 本类只做序列化/反序列化、加解密与文件 IO（纯逻辑，可单测）；实际入库由
 * [ConnectionsRepository.importProfiles] 负责，UI 编排在 `Main.kt`。
 */
object ProfileTransfer {

    const val FORMAT_VERSION = 1
    private const val APP_TAG = "db-k"

    /** 加密文件前缀（用于区分旧版明文 JSON）。 */
    const val ENCRYPTED_PREFIX = "dbk-enc:v1:"
    private const val PBKDF2_ITERATIONS = 200_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 反序列化后的可导入内容（已转回领域模型）。 */
    data class ProfileBundle(
        val formatVersion: Int,
        val exportedAt: Long,
        val folders: List<FolderRow>,
        val connections: List<ConnectionProfile>,
        /** 因数据库类型未知而跳过的连接数（导出文件来自更新版本时可能发生）。 */
        val skipped: Int,
    )

    /** 序列化为 JSON 文本。 */
    fun encode(
        folders: List<FolderRow>,
        connections: List<ConnectionProfile>,
        includePasswords: Boolean,
    ): String {
        val dto = BundleDto(
            formatVersion = FORMAT_VERSION,
            app = APP_TAG,
            exportedAt = System.currentTimeMillis(),
            folders = folders.map { FolderDto(id = it.id, name = it.name, sortOrder = it.sortOrder) },
            connections = connections.map { p ->
                ConnectionDto(
                    id = p.id,
                    name = p.name,
                    dbType = p.dbType.name,
                    folderId = p.folderId,
                    host = p.host,
                    port = p.port,
                    database = p.database,
                    user = p.user,
                    password = if (includePasswords) p.password else null,
                    extraParams = p.extraParams,
                    color = p.color,
                    sortOrder = p.sortOrder,
                )
            },
        )
        return json.encodeToString(BundleDto.serializer(), dto)
    }

    /**
     * 解析 JSON 文本为可导入内容。
     * @throws IllegalArgumentException 格式非法 / 版本高于当前支持。
     */
    fun decode(text: String): ProfileBundle {
        val dto = json.decodeFromString(BundleDto.serializer(), text)
        require(dto.formatVersion <= FORMAT_VERSION) {
            "档案文件版本 ${dto.formatVersion} 高于当前支持的 $FORMAT_VERSION，请升级 db-k"
        }
        val folders = dto.folders.map { FolderRow(id = it.id, name = it.name, parentId = null, sortOrder = it.sortOrder) }
        val folderIds = folders.map { it.id }.toSet()
        var skipped = 0
        val connections = dto.connections.mapNotNull { c ->
            val type = runCatching { DbType.valueOf(c.dbType) }.getOrNull()
            if (type == null) {
                skipped++
                return@mapNotNull null
            }
            ConnectionProfile(
                id = c.id,
                name = c.name,
                // 只保留 bundle 内真实存在的文件夹引用，避免悬空 FK
                folderId = c.folderId?.takeIf { it in folderIds },
                dbType = type,
                host = c.host,
                port = c.port,
                database = c.database,
                user = c.user,
                password = c.password,
                extraParams = c.extraParams,
                color = c.color,
                sortOrder = c.sortOrder,
            )
        }
        return ProfileBundle(dto.formatVersion, dto.exportedAt, folders, connections, skipped)
    }

    /**
     * 导出：序列化为 JSON 并用 [passphrase] 派生的 AES-256-GCM 密钥加密后写文件（UTF-8）。
     * 口令不得为空（调用方应先在弹窗中校验）。
     */
    fun write(
        file: File,
        folders: List<FolderRow>,
        connections: List<ConnectionProfile>,
        includePasswords: Boolean,
        passphrase: String,
    ) {
        val plain = encode(folders, connections, includePasswords)
        file.writeText(encrypt(plain, passphrase), Charsets.UTF_8)
    }

    /**
     * 导入：读文件并按需解密后解析。
     * - 加密文件：用 [passphrase] 解密（口令错误 → [IllegalArgumentException]）；
     * - 旧版明文 JSON：直接解析，忽略 [passphrase]。
     */
    fun read(file: File, passphrase: String): ProfileBundle {
        val text = file.readText(Charsets.UTF_8)
        val plain = if (isEncrypted(text)) decrypt(text, passphrase) else text
        return decode(plain)
    }

    // ---------- AES 口令加密（PBKDF2-HMAC-SHA256 派生密钥 + GCM 认证加密） ----------

    /** 内容是否为 db-k 加密文件。 */
    fun isEncrypted(text: String): Boolean = text.startsWith(ENCRYPTED_PREFIX)

    /** 加密任意文本，返回带前缀的 Base64 密文串。 */
    fun encrypt(plain: String, passphrase: String): String {
        require(passphrase.isNotBlank()) { "加密口令不能为空" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(salt + iv + ct)
    }

    /**
     * 解密 [encrypt] 产物。
     * @throws IllegalArgumentException 非加密内容 / 口令错误 / 文件损坏。
     */
    fun decrypt(envelope: String, passphrase: String): String {
        require(isEncrypted(envelope)) { "不是 db-k 加密文件" }
        return try {
            val raw = Base64.getDecoder().decode(envelope.removePrefix(ENCRYPTED_PREFIX))
            require(raw.size > SALT_BYTES + IV_BYTES) { "加密内容过短" }
            val salt = raw.copyOfRange(0, SALT_BYTES)
            val iv = raw.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
            val ct = raw.copyOfRange(SALT_BYTES + IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("口令错误或文件已损坏", e)
        }
    }

    /** PBKDF2-HMAC-SHA256 → AES-256 密钥。 */
    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /**
     * 找出 [imported] 中与 [existingNames] 同名（忽略大小写 + 首尾空白）的连接。
     * 用于导入前交互式解决冲突（跳过 / 导入为新档案）。
     */
    fun findNameConflicts(
        imported: List<ConnectionProfile>,
        existingNames: Collection<String>,
    ): List<ConnectionProfile> {
        val existing = existingNames.map { it.trim().lowercase() }.toHashSet()
        return imported.filter { it.name.trim().lowercase() in existing }
    }

    // ---------- 私有 DTO（与领域模型解耦，字段可向后扩展） ----------

    @Serializable
    internal data class BundleDto(
        // formatVersion / app 无默认值 → 始终写出（encodeDefaults=false 不会省略）
        val formatVersion: Int,
        val app: String,
        val exportedAt: Long = 0,
        val folders: List<FolderDto> = emptyList(),
        val connections: List<ConnectionDto> = emptyList(),
    )

    @Serializable
    internal data class FolderDto(
        val id: String,
        val name: String,
        val sortOrder: Int = 0,
    )

    @Serializable
    internal data class ConnectionDto(
        val id: String,
        val name: String,
        val dbType: String,
        val folderId: String? = null,
        val host: String = "",
        val port: Int = 0,
        val database: String = "",
        val user: String? = null,
        /** 仅在 includePasswords=true 时写出（明文，见类注释）。 */
        val password: String? = null,
        val extraParams: String = "",
        val color: String? = null,
        val sortOrder: Int = 0,
    )
}
