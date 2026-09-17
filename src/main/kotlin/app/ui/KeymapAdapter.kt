package app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.settings.KeyChord
import app.settings.Keymap
import app.settings.ShortcutCommand
import app.settings.ShortcutKey
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent as AwtKeyEvent

/** 当前快捷键表；由 `Main` 提供，深层组件（编辑器 / 结果网格）直接读取，避免层层传参。 */
val LocalKeymap = staticCompositionLocalOf { Keymap.Default }

/**
 * 窗口级 Ctrl 是否按下（由 `Main` 的 AWT KeyEventDispatcher 维护，不依赖结果网格是否聚焦）。
 * 结果表格的 Ctrl+双击=编辑 用它判定——只挂在网格焦点上会漏（焦点不在网格时第一次 Ctrl+双击
 * 会误入“查看”）。
 */
val LocalCtrlHeld = staticCompositionLocalOf { false }

// ───────────────────────── Compose 侧 ─────────────────────────

fun ShortcutKey.toComposeKey(): Key = when (this) {
    ShortcutKey.ENTER -> Key.Enter
    ShortcutKey.NUMPAD_ENTER -> Key.NumPadEnter
    ShortcutKey.SPACE -> Key.Spacebar
    ShortcutKey.ESCAPE -> Key.Escape
    ShortcutKey.TAB -> Key.Tab
    ShortcutKey.BACKSPACE -> Key.Backspace
    ShortcutKey.DELETE -> Key.Delete
    ShortcutKey.HOME -> Key.MoveHome
    ShortcutKey.END -> Key.MoveEnd
    ShortcutKey.PAGE_UP -> Key.PageUp
    ShortcutKey.PAGE_DOWN -> Key.PageDown
    ShortcutKey.UP -> Key.DirectionUp
    ShortcutKey.DOWN -> Key.DirectionDown
    ShortcutKey.LEFT -> Key.DirectionLeft
    ShortcutKey.RIGHT -> Key.DirectionRight
    ShortcutKey.A -> Key.A
    ShortcutKey.B -> Key.B
    ShortcutKey.C -> Key.C
    ShortcutKey.D -> Key.D
    ShortcutKey.E -> Key.E
    ShortcutKey.F -> Key.F
    ShortcutKey.G -> Key.G
    ShortcutKey.H -> Key.H
    ShortcutKey.I -> Key.I
    ShortcutKey.J -> Key.J
    ShortcutKey.K -> Key.K
    ShortcutKey.L -> Key.L
    ShortcutKey.M -> Key.M
    ShortcutKey.N -> Key.N
    ShortcutKey.O -> Key.O
    ShortcutKey.P -> Key.P
    ShortcutKey.Q -> Key.Q
    ShortcutKey.R -> Key.R
    ShortcutKey.S -> Key.S
    ShortcutKey.T -> Key.T
    ShortcutKey.U -> Key.U
    ShortcutKey.V -> Key.V
    ShortcutKey.W -> Key.W
    ShortcutKey.X -> Key.X
    ShortcutKey.Y -> Key.Y
    ShortcutKey.Z -> Key.Z
    ShortcutKey.DIGIT_0 -> Key.Zero
    ShortcutKey.DIGIT_1 -> Key.One
    ShortcutKey.DIGIT_2 -> Key.Two
    ShortcutKey.DIGIT_3 -> Key.Three
    ShortcutKey.DIGIT_4 -> Key.Four
    ShortcutKey.DIGIT_5 -> Key.Five
    ShortcutKey.DIGIT_6 -> Key.Six
    ShortcutKey.DIGIT_7 -> Key.Seven
    ShortcutKey.DIGIT_8 -> Key.Eight
    ShortcutKey.DIGIT_9 -> Key.Nine
    ShortcutKey.F1 -> Key.F1
    ShortcutKey.F2 -> Key.F2
    ShortcutKey.F3 -> Key.F3
    ShortcutKey.F4 -> Key.F4
    ShortcutKey.F5 -> Key.F5
    ShortcutKey.F6 -> Key.F6
    ShortcutKey.F7 -> Key.F7
    ShortcutKey.F8 -> Key.F8
    ShortcutKey.F9 -> Key.F9
    ShortcutKey.F10 -> Key.F10
    ShortcutKey.F11 -> Key.F11
    ShortcutKey.F12 -> Key.F12
    ShortcutKey.MINUS -> Key.Minus
    ShortcutKey.EQUALS -> Key.Equals
    ShortcutKey.COMMA -> Key.Comma
    ShortcutKey.PERIOD -> Key.Period
    ShortcutKey.SLASH -> Key.Slash
    ShortcutKey.SEMICOLON -> Key.Semicolon
    ShortcutKey.QUOTE -> Key.Apostrophe
    ShortcutKey.BACKTICK -> Key.Grave
    ShortcutKey.BRACKET_LEFT -> Key.LeftBracket
    ShortcutKey.BRACKET_RIGHT -> Key.RightBracket
    ShortcutKey.BACKSLASH -> Key.Backslash
}

/** Compose 事件是否精确命中该组合键（修饰键要求完全相等）。 */
fun KeyEvent.matches(chord: KeyChord): Boolean =
    type == KeyEventType.KeyDown &&
        key == chord.key.toComposeKey() &&
        isCtrlPressed == chord.ctrl &&
        isAltPressed == chord.alt &&
        isShiftPressed == chord.shift

fun Keymap.matches(command: ShortcutCommand, event: KeyEvent): Boolean =
    chordsOf(command).any { event.matches(it) }

// ───────────────────────── AWT 侧 ─────────────────────────

fun ShortcutKey.toAwtKeyCode(): Int = when (this) {
    ShortcutKey.ENTER -> AwtKeyEvent.VK_ENTER
    ShortcutKey.NUMPAD_ENTER -> AwtKeyEvent.VK_ENTER
    ShortcutKey.SPACE -> AwtKeyEvent.VK_SPACE
    ShortcutKey.ESCAPE -> AwtKeyEvent.VK_ESCAPE
    ShortcutKey.TAB -> AwtKeyEvent.VK_TAB
    ShortcutKey.BACKSPACE -> AwtKeyEvent.VK_BACK_SPACE
    ShortcutKey.DELETE -> AwtKeyEvent.VK_DELETE
    ShortcutKey.HOME -> AwtKeyEvent.VK_HOME
    ShortcutKey.END -> AwtKeyEvent.VK_END
    ShortcutKey.PAGE_UP -> AwtKeyEvent.VK_PAGE_UP
    ShortcutKey.PAGE_DOWN -> AwtKeyEvent.VK_PAGE_DOWN
    ShortcutKey.UP -> AwtKeyEvent.VK_UP
    ShortcutKey.DOWN -> AwtKeyEvent.VK_DOWN
    ShortcutKey.LEFT -> AwtKeyEvent.VK_LEFT
    ShortcutKey.RIGHT -> AwtKeyEvent.VK_RIGHT
    ShortcutKey.A -> AwtKeyEvent.VK_A
    ShortcutKey.B -> AwtKeyEvent.VK_B
    ShortcutKey.C -> AwtKeyEvent.VK_C
    ShortcutKey.D -> AwtKeyEvent.VK_D
    ShortcutKey.E -> AwtKeyEvent.VK_E
    ShortcutKey.F -> AwtKeyEvent.VK_F
    ShortcutKey.G -> AwtKeyEvent.VK_G
    ShortcutKey.H -> AwtKeyEvent.VK_H
    ShortcutKey.I -> AwtKeyEvent.VK_I
    ShortcutKey.J -> AwtKeyEvent.VK_J
    ShortcutKey.K -> AwtKeyEvent.VK_K
    ShortcutKey.L -> AwtKeyEvent.VK_L
    ShortcutKey.M -> AwtKeyEvent.VK_M
    ShortcutKey.N -> AwtKeyEvent.VK_N
    ShortcutKey.O -> AwtKeyEvent.VK_O
    ShortcutKey.P -> AwtKeyEvent.VK_P
    ShortcutKey.Q -> AwtKeyEvent.VK_Q
    ShortcutKey.R -> AwtKeyEvent.VK_R
    ShortcutKey.S -> AwtKeyEvent.VK_S
    ShortcutKey.T -> AwtKeyEvent.VK_T
    ShortcutKey.U -> AwtKeyEvent.VK_U
    ShortcutKey.V -> AwtKeyEvent.VK_V
    ShortcutKey.W -> AwtKeyEvent.VK_W
    ShortcutKey.X -> AwtKeyEvent.VK_X
    ShortcutKey.Y -> AwtKeyEvent.VK_Y
    ShortcutKey.Z -> AwtKeyEvent.VK_Z
    ShortcutKey.DIGIT_0 -> AwtKeyEvent.VK_0
    ShortcutKey.DIGIT_1 -> AwtKeyEvent.VK_1
    ShortcutKey.DIGIT_2 -> AwtKeyEvent.VK_2
    ShortcutKey.DIGIT_3 -> AwtKeyEvent.VK_3
    ShortcutKey.DIGIT_4 -> AwtKeyEvent.VK_4
    ShortcutKey.DIGIT_5 -> AwtKeyEvent.VK_5
    ShortcutKey.DIGIT_6 -> AwtKeyEvent.VK_6
    ShortcutKey.DIGIT_7 -> AwtKeyEvent.VK_7
    ShortcutKey.DIGIT_8 -> AwtKeyEvent.VK_8
    ShortcutKey.DIGIT_9 -> AwtKeyEvent.VK_9
    ShortcutKey.F1 -> AwtKeyEvent.VK_F1
    ShortcutKey.F2 -> AwtKeyEvent.VK_F2
    ShortcutKey.F3 -> AwtKeyEvent.VK_F3
    ShortcutKey.F4 -> AwtKeyEvent.VK_F4
    ShortcutKey.F5 -> AwtKeyEvent.VK_F5
    ShortcutKey.F6 -> AwtKeyEvent.VK_F6
    ShortcutKey.F7 -> AwtKeyEvent.VK_F7
    ShortcutKey.F8 -> AwtKeyEvent.VK_F8
    ShortcutKey.F9 -> AwtKeyEvent.VK_F9
    ShortcutKey.F10 -> AwtKeyEvent.VK_F10
    ShortcutKey.F11 -> AwtKeyEvent.VK_F11
    ShortcutKey.F12 -> AwtKeyEvent.VK_F12
    ShortcutKey.MINUS -> AwtKeyEvent.VK_MINUS
    ShortcutKey.EQUALS -> AwtKeyEvent.VK_EQUALS
    ShortcutKey.COMMA -> AwtKeyEvent.VK_COMMA
    ShortcutKey.PERIOD -> AwtKeyEvent.VK_PERIOD
    ShortcutKey.SLASH -> AwtKeyEvent.VK_SLASH
    ShortcutKey.SEMICOLON -> AwtKeyEvent.VK_SEMICOLON
    ShortcutKey.QUOTE -> AwtKeyEvent.VK_QUOTE
    ShortcutKey.BACKTICK -> AwtKeyEvent.VK_BACK_QUOTE
    ShortcutKey.BRACKET_LEFT -> AwtKeyEvent.VK_OPEN_BRACKET
    ShortcutKey.BRACKET_RIGHT -> AwtKeyEvent.VK_CLOSE_BRACKET
    ShortcutKey.BACKSLASH -> AwtKeyEvent.VK_BACK_SLASH
}

/**
 * AWT 事件是否属于该组合键（**不区分事件类型**）。
 *
 * X11 下修饰键+字母除 `KEY_PRESSED` 外还会派发一次 `KEY_TYPED`（`keyCode=VK_UNDEFINED`），
 * 必须在事件进入 Compose 前一并消费，否则会漏进编辑器文本会话。`KEY_TYPED` 用 `keyChar`
 * 兜底：字母本身（Alt+字母）或 Ctrl+字母的控制字符（如 Ctrl+Q → 0x11）。
 */
fun AwtKeyEvent.matchesAwt(chord: KeyChord): Boolean {
    if (isControlDown != chord.ctrl || isAltDown != chord.alt || isShiftDown != chord.shift) return false
    if (keyCode == chord.key.toAwtKeyCode()) return true
    val ch = chord.key.keyChar ?: return false
    return keyChar.equals(ch, ignoreCase = true) || (chord.ctrl && keyChar == controlChar(ch))
}

/** 首个命中的可配置命令（按注册表顺序），供窗口级派发器定位并整颗消费。 */
fun Keymap.matchAnyAwt(event: AwtKeyEvent): ShortcutCommand? =
    ShortcutCommand.configurableCommands.firstOrNull { command -> chordsOf(command).any { event.matchesAwt(it) } }

private fun controlChar(ch: Char): Char? = if (ch in 'a'..'z') (ch - 'a' + 1).toChar() else null

/** 仅修饰键按下（Ctrl/Alt/Shift/Meta/AltGr/Win）：录制时忽略、继续等待。 */
private fun AwtKeyEvent.isModifierOnly(): Boolean = keyCode in MODIFIER_KEY_CODES

private val MODIFIER_KEY_CODES = setOf(
    AwtKeyEvent.VK_CONTROL,
    AwtKeyEvent.VK_ALT,
    AwtKeyEvent.VK_SHIFT,
    AwtKeyEvent.VK_META,
    AwtKeyEvent.VK_ALT_GRAPH,
    AwtKeyEvent.VK_WINDOWS,
)

/** 由 AWT keyCode 反查物理键；主 Enter 与小键盘 Enter 同码，统一归 [ShortcutKey.ENTER]。 */
fun shortcutKeyFromAwt(keyCode: Int): ShortcutKey? =
    ShortcutKey.entries.firstOrNull { it.toAwtKeyCode() == keyCode }

/**
 * 快捷键录制：激活时挂一个临时 AWT [KeyEventDispatcher]（窗口级，早于 Compose 收到事件，
 * 能可靠抓到 Alt+字母——Compose 对 Alt 组合的 `key` 可能是 Unknown）。
 *
 * - 仅修饰键 → 忽略、继续录制；
 * - Esc（无其它修饰）→ [onCancel]；
 * - Delete/Backspace（无修饰）→ [onClear]（解绑）；
 * - 其它 → 组合 [onChord]；不合法的单键（[KeyChord.isValidBinding] 为 false）忽略并继续等。
 *
 * 录制期间吞掉所有按键事件（含随后的 KEY_TYPED），避免漏进主窗口。
 */
@Composable
fun KeyCaptureEffect(
    active: Boolean,
    onChord: (KeyChord) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        val dispatcher = KeyEventDispatcher { e ->
            if (e.id != AwtKeyEvent.KEY_PRESSED) {
                true
            } else if (e.keyCode == AwtKeyEvent.VK_ESCAPE && !e.isControlDown && !e.isAltDown) {
                onCancel()
                true
            } else if (e.isModifierOnly()) {
                true
            } else if (
                (e.keyCode == AwtKeyEvent.VK_DELETE || e.keyCode == AwtKeyEvent.VK_BACK_SPACE) &&
                !e.isControlDown && !e.isAltDown && !e.isShiftDown
            ) {
                onClear()
                true
            } else {
                val key = shortcutKeyFromAwt(e.keyCode)
                val chord = key?.let { KeyChord(ctrl = e.isControlDown, alt = e.isAltDown, shift = e.isShiftDown, key = it) }
                if (chord != null && chord.isValidBinding()) onChord(chord)
                true
            }
        }
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        kfm.addKeyEventDispatcher(dispatcher)
        onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
    }
}
