package db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 连接档案导出 / 导入（换机迁移、备份）。
 *
 * 范围：**连接档案 + 文件夹层级**；不含控制台正文、SQL 历史、元数据缓存。
 * 格式：单个 JSON 文件（UTF-8），带 `formatVersion` / `app` / `exportedAt` 便于向后兼容。
 *
 * 密码策略：
 * - 默认**不导出密码**（字段直接省略，导入后重填）；
 * - 可选导出**明文**密码（`includePasswords=true`），调用方必须先给风险提示——
 *   本机 `<dataDir>/secret.key` 是机器本地密钥，无法跨机解密，因此不能直接搬密文。
 *
 * 本类只做序列化/反序列化与文件 IO（纯逻辑，可单测）；实际入库由
 * [ConnectionsRepository.importProfiles] 负责，UI 编排在 `Main.kt`。
 */
object ProfileTransfer {

    const val FORMAT_VERSION = 1
    private const val APP_TAG = "db-k"

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

    /** 写 JSON 文件（UTF-8）。 */
    fun write(
        file: File,
        folders: List<FolderRow>,
        connections: List<ConnectionProfile>,
        includePasswords: Boolean,
    ) {
        file.writeText(encode(folders, connections, includePasswords), Charsets.UTF_8)
    }

    /** 读 JSON 文件（UTF-8），语义同 [decode]。 */
    fun read(file: File): ProfileBundle = decode(file.readText(Charsets.UTF_8))

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
