package app.settings

import app.ui.LiveTemplate
import app.ui.LiveTemplateContext
import app.ui.defaultLiveTemplates
import db.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tinylog.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 活模板持久化：`<dataDir>/live-templates.json`（可选）。
 * 语义：内置模板永远在，用户文件按 `(context, abbreviation)` 覆盖或追加；文件缺失/损坏回落内置。
 */
object LiveTemplatesStore {

    @Serializable
    private data class TemplatesFile(
        val fileVersion: Int = 1,
        val templates: List<EntryDto> = emptyList(),
    )

    @Serializable
    private data class EntryDto(
        val abbreviation: String = "",
        val body: String = "",
        val description: String? = null,
        val context: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun templateFile(): File = AppPaths.dataDirectory().resolve("live-templates.json").toFile()

    fun load(): List<LiveTemplate> = load(templateFile())

    internal fun load(f: File): List<LiveTemplate> {
        if (!f.exists()) return defaultLiveTemplates()
        val parsed = runCatching { json.decodeFromString<TemplatesFile>(f.readText()) }
            .onFailure { Logger.error(it, "live-templates.json unreadable; using built-ins") }
            .getOrNull() ?: return defaultLiveTemplates()
        // 文件一旦存在即为权威（整份列表）：允许用户删除内置模板。
        // 空/全部非法条目 → 回落内置，避免误清空。
        return parsed.templates.mapNotNull { it.toModel() }.ifEmpty { defaultLiveTemplates() }
    }

    /** 保存整份列表（清洗后原子写），供设置窗表格用。 */
    fun save(templates: List<LiveTemplate>) = save(templateFile(), templates)

    internal fun save(f: File, templates: List<LiveTemplate>) {
        val dto = TemplatesFile(templates = sanitizeTemplates(templates).map { it.toDto() })
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(TemplatesFile.serializer(), dto))
            Files.move(
                tmp.toPath(), f.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { Logger.error(it, "live-templates.json save failed") }
    }

    private fun LiveTemplate.toDto(): EntryDto =
        EntryDto(abbreviation = abbreviation, body = body, description = description, context = context.token)

    /** 种子文件（不存在时写入完整内置列表）；诊断区「生成示例并打开目录」用。 */
    internal fun createSample(f: File) {
        if (f.exists()) return
        val seed = TemplatesFile(templates = defaultLiveTemplates().map { it.toDto() })
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(TemplatesFile.serializer(), seed))
            Files.move(
                tmp.toPath(), f.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { Logger.error(it, "live-templates.json sample write failed") }
    }

    private fun EntryDto.toModel(): LiveTemplate? {
        val abbrev = abbreviation.trim()
        if (abbrev.isEmpty() || body.isEmpty()) return null
        val ctx = context?.trim()?.lowercase()?.let { token ->
            LiveTemplateContext.entries.firstOrNull { it.token == token }
        } ?: if (context == null) LiveTemplateContext.SQL else return null
        return LiveTemplate(abbrev, body, description, ctx)
    }
}

/** 清洗：去首尾空白、丢空缩写/空模板体、按 (context, 缩写小写) 去重（保留先出现者）。 */
internal fun sanitizeTemplates(templates: List<LiveTemplate>): List<LiveTemplate> {
    val seen = HashSet<String>()
    val out = ArrayList<LiveTemplate>(templates.size)
    for (t in templates) {
        val abbrev = t.abbreviation.trim()
        if (abbrev.isEmpty() || t.body.isEmpty()) continue
        if (!seen.add("${t.context.token}|${abbrev.lowercase()}")) continue
        out.add(t.copy(abbreviation = abbrev))
    }
    return out
}
