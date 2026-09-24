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
        val builtins = defaultLiveTemplates()
        if (!f.exists()) return builtins
        val parsed = runCatching { json.decodeFromString<TemplatesFile>(f.readText()) }
            .onFailure { Logger.error(it, "live-templates.json unreadable; using built-ins") }
            .getOrNull() ?: return builtins
        return overlayTemplates(builtins, parsed.templates.mapNotNull { it.toModel() })
    }

    /** 生成示例文件（已存在则不动）；诊断区「生成示例并打开目录」用。 */
    internal fun createSample(f: File) {
        if (f.exists()) return
        val sample = TemplatesFile(
            templates = listOf(
                EntryDto(
                    abbreviation = "sel2",
                    body = "SELECT \$columns\$\nFROM \$table\$\nLIMIT \$n\$",
                    description = "Sample: select with limit",
                ),
            ),
        )
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(TemplatesFile.serializer(), sample))
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

/** 内置叠加用户：按 `context|缩写(小写)` 去重，用户覆盖同名，新名追加到末尾并保持内置顺序。 */
internal fun overlayTemplates(
    builtins: List<LiveTemplate>,
    user: List<LiveTemplate>,
): List<LiveTemplate> {
    fun key(t: LiveTemplate) = "${t.context.token}|${t.abbreviation.lowercase()}"
    val map = LinkedHashMap<String, LiveTemplate>()
    for (t in builtins) map[key(t)] = t
    for (t in user) map[key(t)] = t
    return map.values.toList()
}
