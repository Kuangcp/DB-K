package app.settings

import i18n.Str

/**
 * 可识别的物理键（快捷键绑定的原子单位）。
 *
 * - [token]：配置持久化用（纯 ASCII，避免 properties 编码问题）。
 * - [display]：设置窗口展示用（如 `↑` / `Esc`）。
 *
 * 与 Compose `Key` / AWT `VK_*` 的映射集中在 `app/ui/KeymapAdapter.kt`，
 * 本文件保持零 UI 依赖，可被单元测试直接覆盖。
 */
enum class ShortcutKey(val token: String, val display: String) {
    ENTER("ENTER", "Enter"),
    NUMPAD_ENTER("NUM_ENTER", "Num Enter"),
    SPACE("SPACE", "Space"),
    ESCAPE("ESC", "Esc"),
    TAB("TAB", "Tab"),
    BACKSPACE("BACKSPACE", "Backspace"),
    DELETE("DELETE", "Delete"),
    HOME("HOME", "Home"),
    END("END", "End"),
    PAGE_UP("PAGE_UP", "Page Up"),
    PAGE_DOWN("PAGE_DOWN", "Page Down"),
    UP("UP", "↑"),
    DOWN("DOWN", "↓"),
    LEFT("LEFT", "←"),
    RIGHT("RIGHT", "→"),

    A("A", "A"),
    B("B", "B"),
    C("C", "C"),
    D("D", "D"),
    E("E", "E"),
    F("F", "F"),
    G("G", "G"),
    H("H", "H"),
    I("I", "I"),
    J("J", "J"),
    K("K", "K"),
    L("L", "L"),
    M("M", "M"),
    N("N", "N"),
    O("O", "O"),
    P("P", "P"),
    Q("Q", "Q"),
    R("R", "R"),
    S("S", "S"),
    T("T", "T"),
    U("U", "U"),
    V("V", "V"),
    W("W", "W"),
    X("X", "X"),
    Y("Y", "Y"),
    Z("Z", "Z"),

    DIGIT_0("0", "0"),
    DIGIT_1("1", "1"),
    DIGIT_2("2", "2"),
    DIGIT_3("3", "3"),
    DIGIT_4("4", "4"),
    DIGIT_5("5", "5"),
    DIGIT_6("6", "6"),
    DIGIT_7("7", "7"),
    DIGIT_8("8", "8"),
    DIGIT_9("9", "9"),

    F1("F1", "F1"),
    F2("F2", "F2"),
    F3("F3", "F3"),
    F4("F4", "F4"),
    F5("F5", "F5"),
    F6("F6", "F6"),
    F7("F7", "F7"),
    F8("F8", "F8"),
    F9("F9", "F9"),
    F10("F10", "F10"),
    F11("F11", "F11"),
    F12("F12", "F12"),

    MINUS("MINUS", "-"),
    EQUALS("EQUALS", "="),
    COMMA("COMMA", ","),
    PERIOD("PERIOD", "."),
    SLASH("SLASH", "/"),
    SEMICOLON("SEMICOLON", ";"),
    QUOTE("QUOTE", "'"),
    BACKTICK("BACKTICK", "`"),
    BRACKET_LEFT("BRACKET_LEFT", "["),
    BRACKET_RIGHT("BRACKET_RIGHT", "]"),
    BACKSLASH("BACKSLASH", "\\"),
    ;

    /** 功能键：允许单独成键（无需 Ctrl/Alt）。 */
    val isFunctionKey: Boolean get() = this in F1..F12

    /** 该键对应的可打印字符（字母/数字）；其余为 null。用于 AWT KEY_TYPED 兜底消费。 */
    val keyChar: Char?
        get() = when (this) {
            in A..Z -> display[0].lowercaseChar()
            in DIGIT_0..DIGIT_9 -> display[0]
            // X11 下 Ctrl+Tab 会额外派发 KEY_TYPED（keyCode 未定义），用 '\t' 兜底识别
            TAB -> '\t'
            else -> null
        }

    companion object {
        fun fromToken(token: String): ShortcutKey? =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) || it.display.equals(token, ignoreCase = true) }
    }
}

/**
 * 一个按键组合。修饰键按**精确相等**匹配（`Ctrl+S` 不会命中 `Ctrl+Shift+S`），
 * 这也顺带规避了 AltGr（= Ctrl+Alt）被误判成 Alt 组合。
 */
data class KeyChord(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val key: ShortcutKey,
) {
    /** UI 展示（如 `Ctrl+Alt+L` / `F5`）。 */
    fun format(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key.display)
    }

    /** 持久化串（纯 ASCII，如 `CTRL+ALT+L` / `F5`）。 */
    fun toConfigString(): String = buildString {
        if (ctrl) append("CTRL+")
        if (alt) append("ALT+")
        if (shift) append("SHIFT+")
        append(key.token)
    }

    /**
     * 是否为**可安全绑定**的组合：必须含 Ctrl 或 Alt，或使用功能键（F1–F12）。
     * 拒绝纯字母/数字/符号单键，避免全局拦截后吞掉正常输入。
     */
    fun isValidBinding(): Boolean = ctrl || alt || key.isFunctionKey

    companion object {
        /** 解析持久化串或 `format()` 展示串；大小写不敏感，未知 token 返回 null。 */
        fun parse(raw: String): KeyChord? {
            val parts = raw.trim().split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var ctrl = false
            var alt = false
            var shift = false
            for (modifier in parts.dropLast(1)) {
                when (modifier.uppercase()) {
                    "CTRL", "CONTROL" -> ctrl = true
                    "ALT" -> alt = true
                    "SHIFT" -> shift = true
                    else -> return null
                }
            }
            val key = ShortcutKey.fromToken(parts.last()) ?: return null
            return KeyChord(ctrl = ctrl, alt = alt, shift = shift, key = key)
        }
    }
}

/** 命令的作用域：决定由哪个拦截点处理，也是冲突检测的分组依据（跨作用域允许同键）。 */
enum class ShortcutScope(val labelKey: Str) {
    EDITOR(Str.ShortcutScopeEditor),
    WINDOW(Str.ShortcutScopeWindow),
    RESULT(Str.ShortcutScopeResult),
}

/**
 * 快捷键命令注册表——**唯一来源**。
 *
 * [configurable] = true 的才是「扩展业务功能」，出现在设置窗口、可被用户覆盖；
 * false 的是「基础编辑键」（保存 / 查找替换 / 补全 / 复制），只登记不复写，始终走默认。
 *
 * 新增业务功能：在此加一条，并在对应 composable 用 `keymap.matches(命令, e)` 判断即可，
 * 设置窗口会自动列出。
 */
enum class ShortcutCommand(
    val id: String,
    val labelKey: Str,
    val configurable: Boolean,
    val scope: ShortcutScope,
    val defaultChords: List<KeyChord>,
) {
    // ── 可配置：扩展业务功能 ──
    EXECUTE("execute", Str.CmdExecute, true, ShortcutScope.EDITOR, listOf(chord(ctrl = true, key = ShortcutKey.ENTER))),
    FORMAT_SQL("formatSql", Str.CmdFormatSql, true, ShortcutScope.EDITOR, listOf(chord(ctrl = true, alt = true, key = ShortcutKey.L))),
    VIEW_DDL("viewDdl", Str.CmdViewDdl, true, ShortcutScope.WINDOW, listOf(chord(ctrl = true, key = ShortcutKey.Q))),
    TOGGLE_RESULTS("toggleResults", Str.CmdToggleResults, true, ShortcutScope.WINDOW, listOf(chord(alt = true, key = ShortcutKey.D))),
    TRANSPOSE("transpose", Str.CmdTranspose, true, ShortcutScope.RESULT, listOf(chord(ctrl = true, key = ShortcutKey.T))),
    REFRESH_RESULT("refreshResult", Str.CmdRefreshResult, true, ShortcutScope.RESULT, listOf(chord(key = ShortcutKey.F5))),
    SWITCH_CONSOLE_NEXT("switchConsoleNext", Str.CmdSwitchConsoleNext, true, ShortcutScope.EDITOR, listOf(chord(ctrl = true, key = ShortcutKey.TAB))),
    SWITCH_CONSOLE_PREV("switchConsolePrev", Str.CmdSwitchConsolePrev, true, ShortcutScope.EDITOR, listOf(chord(ctrl = true, shift = true, key = ShortcutKey.TAB))),
    DUPLICATE_LINE("duplicateLine", Str.CmdDuplicateLine, true, ShortcutScope.EDITOR, listOf(chord(ctrl = true, key = ShortcutKey.Y))),

    // ── 固定：基础编辑键（登记、消字面量，不进设置） ──
    SAVE_CONSOLE("saveConsole", Str.CmdSaveConsole, false, ShortcutScope.EDITOR, listOf(chord(ctrl = true, key = ShortcutKey.S))),
    FIND_REPLACE(
        "findReplace", Str.CmdFindReplace, false, ShortcutScope.EDITOR,
        listOf(chord(ctrl = true, key = ShortcutKey.F), chord(ctrl = true, key = ShortcutKey.H)),
    ),
    COMPLETE("complete", Str.CmdComplete, false, ShortcutScope.EDITOR, listOf(chord(ctrl = true, key = ShortcutKey.SPACE))),
    COPY_CELL("copyCell", Str.CmdCopyCell, false, ShortcutScope.RESULT, listOf(chord(ctrl = true, key = ShortcutKey.C))),
    CANCEL_RUN("cancelRun", Str.CmdCancelRun, false, ShortcutScope.RESULT, listOf(chord(key = ShortcutKey.ESCAPE))),
    ;

    companion object {
        fun fromId(id: String): ShortcutCommand? = entries.firstOrNull { it.id == id }

        /** 设置窗口列出的命令。 */
        val configurableCommands: List<ShortcutCommand> get() = entries.filter { it.configurable }
    }
}

/** 同一作用域内两条可配置命令共用同一组合键。 */
data class KeymapConflict(val first: ShortcutCommand, val second: ShortcutCommand, val chord: KeyChord)

/**
 * 全量绑定（命令 → 组合键列表，空列表 = 显式解绑）。
 *
 * canonical 形式 = [Default] 叠加用户覆盖（见 [fromOverrides]）。
 * 只有 [ShortcutCommand.configurable] 的命令会被持久化/覆盖；基础键永远取默认。
 */
data class Keymap(val bindings: Map<ShortcutCommand, List<KeyChord>> = emptyMap()) {

    fun chordsOf(command: ShortcutCommand): List<KeyChord> = bindings[command].orEmpty()

    /** 用录制到的新组合键替换该命令的全部绑定。 */
    fun withChord(command: ShortcutCommand, chord: KeyChord): Keymap =
        copy(bindings = bindings + (command to listOf(chord)))

    /** 解绑（清空绑定）。 */
    fun unbind(command: ShortcutCommand): Keymap =
        copy(bindings = bindings + (command to emptyList()))

    /** 恢复单条命令为默认。 */
    fun resetCommand(command: ShortcutCommand): Keymap =
        copy(bindings = bindings + (command to command.defaultChords))

    /** 全部恢复默认（含基础键，但基础键本就等于默认）。 */
    fun resetAll(): Keymap = Default

    fun isDefault(command: ShortcutCommand): Boolean = chordsOf(command) == command.defaultChords

    /** 同作用域内可配置命令之间的键冲突（跨作用域同键合法，如 Esc）。 */
    fun conflicts(): List<KeymapConflict> {
        val result = mutableListOf<KeymapConflict>()
        val commands = ShortcutCommand.configurableCommands
        for (i in commands.indices) {
            for (j in i + 1 until commands.size) {
                val a = commands[i]
                val b = commands[j]
                if (a.scope != b.scope) continue
                val shared = chordsOf(a).firstOrNull { chordA -> chordsOf(b).any { it == chordA } }
                if (shared != null) result += KeymapConflict(a, b, shared)
            }
        }
        return result
    }

    companion object {
        val Default: Keymap = Keymap(ShortcutCommand.entries.associateWith { it.defaultChords })

        fun fromOverrides(overrides: Map<ShortcutCommand, List<KeyChord>>): Keymap =
            Default.copy(bindings = Default.bindings + overrides)
    }
}

private fun chord(
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
    key: ShortcutKey,
): KeyChord = KeyChord(ctrl = ctrl, alt = alt, shift = shift, key = key)
