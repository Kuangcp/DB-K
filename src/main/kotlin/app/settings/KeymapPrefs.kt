package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties
import org.tinylog.Logger

/**
 * 快捷键覆盖配置持久化（`<dataDir>/keymap.properties`）。
 *
 * 只写与默认不同的**可配置**命令；空值 = 显式解绑。基础编辑键（configurable=false）
 * 永远不写入也不读取，保证它们始终是默认。这样未改动的命令未来能自动吃到新默认值。
 *
 * 例：
 * ```
 * execute=CTRL+ENTER
 * transpose=CTRL+SHIFT+T
 * cancelRun=            # 空值 = 解绑
 * ```
 *
 * 编解码抽成纯函数 [encodeOverrides] / [decodeOverrides]，云同步直接复用（`settings/keymap.json`）。
 */
object KeymapPrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("keymap.properties").toFile()

    fun load(): Keymap {
        val f = prefsFile()
        if (!f.exists()) return Keymap.Default
        val props = runCatching {
            Properties().apply { f.inputStream().use { load(it) } }
        }.onFailure { Logger.warn(it, "failed to read keymap.properties; using default shortcuts") }
            .getOrElse { return Keymap.Default }

        val raw = buildMap {
            for (command in ShortcutCommand.configurableCommands) {
                props.getProperty(command.id)?.let { put(command.id, it) }
            }
        }
        return decodeOverrides(raw)
    }

    fun save(keymap: Keymap) {
        runCatching {
            val props = Properties()
            encodeOverrides(keymap).forEach { (id, value) -> props.setProperty(id, value) }
            val f = prefsFile()
            if (props.isEmpty) {
                if (f.exists()) f.delete()
            } else {
                f.outputStream().use { props.store(it, "db-k keymap overrides (empty value = unbound)") }
            }
        }.onFailure { Logger.warn(it, "failed to save keymap.properties") }
    }

    /** 命令 id → 键位串（空串 = 解绑）；只含与默认不同的可配置命令。 */
    fun encodeOverrides(keymap: Keymap): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (command in ShortcutCommand.configurableCommands) {
            val chords = keymap.chordsOf(command)
            if (chords == command.defaultChords) continue
            out[command.id] = chords.joinToString(",") { it.toConfigString() }
        }
        return out
    }

    /** 反向解析；未知命令 id 忽略，无法解析的键位串保留默认（与文件读取行为一致）。 */
    fun decodeOverrides(raw: Map<String, String>): Keymap {
        val overrides = mutableMapOf<ShortcutCommand, List<KeyChord>>()
        for (command in ShortcutCommand.configurableCommands) {
            val value = raw[command.id] ?: continue
            if (value.isBlank()) {
                overrides[command] = emptyList()
                continue
            }
            val chords = value.split(',').mapNotNull { KeyChord.parse(it) }
            if (chords.isEmpty()) {
                Logger.warn("keymap: cannot parse value \"{}\" for command {}; keeping default", value, command.id)
                continue
            }
            overrides[command] = chords
        }
        return Keymap.fromOverrides(overrides)
    }
}
