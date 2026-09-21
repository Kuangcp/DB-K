package app.settings

import androidx.compose.ui.graphics.Color
import app.ui.ThemeColors
import app.ui.ThemeSpec
import app.ui.themeById
import db.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tinylog.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 自定义主题持久化：`<dataDir>/themes.json`。
 * 原子写（tmp + ATOMIC_MOVE）；解析失败视为无自定义主题（不崩）。
 */
class ThemesStore(
    private val file: File = AppPaths.dataDirectory().resolve("themes.json").toFile(),
) {

    @Serializable
    private data class ThemesFile(val version: Int = 1, val themes: List<ThemeDto> = emptyList())

    @Serializable
    private data class ThemeDto(
        val id: String,
        val name: String,
        val baseId: String? = null,
        val colors: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(24)

    fun load(): List<ThemeSpec> {
        if (!file.exists()) return emptyList()
        val parsed = runCatching { json.decodeFromString<ThemesFile>(file.readText()) }
            .onFailure { Logger.error(it, "themes.json unreadable; ignoring custom themes") }
            .getOrNull() ?: return emptyList()
        return parsed.themes.map { dto ->
            val base = themeById(dto.baseId.orEmpty()) ?: themeById("light")!!
            ThemeSpec(
                id = dto.id,
                name = dto.name,
                builtIn = false,
                baseId = dto.baseId,
                colors = ThemeColors(
                    background = color(dto, "background", base.colors.background),
                    surface = color(dto, "surface", base.colors.surface),
                    onSurface = color(dto, "onSurface", base.colors.onSurface),
                    primary = color(dto, "primary", base.colors.primary),
                    editorBackground = color(dto, "editorBackground", base.colors.editorBackground),
                    editorForeground = color(dto, "editorForeground", base.colors.editorForeground),
                    keyword = color(dto, "keyword", base.colors.keyword),
                    string = color(dto, "string", base.colors.string),
                    number = color(dto, "number", base.colors.number),
                    comment = color(dto, "comment", base.colors.comment),
                    punctuation = color(dto, "punctuation", base.colors.punctuation),
                ),
            )
        }
    }

    fun save(themes: List<ThemeSpec>) {
        val dto = ThemesFile(
            themes = themes.filter { !it.builtIn }.map { t ->
                ThemeDto(
                    id = t.id,
                    name = t.name,
                    baseId = t.baseId,
                    colors = mapOf(
                        "background" to ThemeColors.toHex(t.colors.background),
                        "surface" to ThemeColors.toHex(t.colors.surface),
                        "onSurface" to ThemeColors.toHex(t.colors.onSurface),
                        "primary" to ThemeColors.toHex(t.colors.primary),
                        "editorBackground" to ThemeColors.toHex(t.colors.editorBackground),
                        "editorForeground" to ThemeColors.toHex(t.colors.editorForeground),
                        "keyword" to ThemeColors.toHex(t.colors.keyword),
                        "string" to ThemeColors.toHex(t.colors.string),
                        "number" to ThemeColors.toHex(t.colors.number),
                        "comment" to ThemeColors.toHex(t.colors.comment),
                        "punctuation" to ThemeColors.toHex(t.colors.punctuation),
                    ),
                )
            },
        )
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ThemesFile.serializer(), dto))
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { Logger.error(it, "themes.json save failed") }
    }

    private fun color(dto: ThemeDto, key: String, fallback: Color): Color =
        dto.colors[key]?.let { ThemeColors.parse(it) } ?: fallback
}
