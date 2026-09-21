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

        val overrides = mutableMapOf<ShortcutCommand, List<KeyChord>>()
        for (command in ShortcutCommand.configurableCommands) {
            val raw = props.getProperty(command.id) ?: continue
            if (raw.isBlank()) {
                overrides[command] = emptyList()
                continue
            }
            val chords = raw.split(',').mapNotNull { KeyChord.parse(it) }
            if (chords.isEmpty()) {
                Logger.warn("keymap.properties: cannot parse value \"{}\" for command {}; keeping default", command.id, raw)
                continue
            }
            overrides[command] = chords
        }
        return Keymap.fromOverrides(overrides)
    }

    fun save(keymap: Keymap) {
        runCatching {
            val props = Properties()
            for (command in ShortcutCommand.configurableCommands) {
                val chords = keymap.chordsOf(command)
                if (chords == command.defaultChords) continue
                props.setProperty(command.id, chords.joinToString(",") { it.toConfigString() })
            }
            val f = prefsFile()
            if (props.isEmpty) {
                if (f.exists()) f.delete()
            } else {
                f.outputStream().use { props.store(it, "db-k keymap overrides (empty value = unbound)") }
            }
        }.onFailure { Logger.warn(it, "failed to save keymap.properties") }
    }
}
