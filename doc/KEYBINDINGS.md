# 快捷键统一与可配置（设计稿）

目标：把散落在 `Main.kt` / `SqlWorkspace.kt` / 编辑器里的字面量按键判断，
收敛到**一份注册表 + 一份用户覆盖配置**；设置窗口新增「快捷键」分区，
只暴露**扩展业务功能**（执行、格式化、查看定义、转置、刷新、显隐结果）可改，
**基础编辑键**（保存 / 复制粘贴 / 查找替换 / 补全召唤 / 方向导航 / 取消执行）固定、不进设置，
但同样登记在注册表里（单一来源，消除字面量散落）。

---

## 1. 现状盘点与分类

### 1.1 可配置（扩展业务功能 → 出现在设置里）

| 命令 id | 动作 | 默认键 | 当前拦截位置 | scope |
|---|---|---|---|---|
| `execute` | 执行选中 SQL | Ctrl+Enter | 编辑区 `BasicTextField.onPreviewKeyEvent` | EDITOR |
| `formatSql` | 格式化 SQL（选区/整段） | Ctrl+Alt+L | `SqlWorkspace` 根 Column `onPreviewKeyEvent` | EDITOR |
| `viewDdl` | 查看对象定义 DDL | Ctrl+Q | `Main.kt` AWT `KeyEventDispatcher` | WINDOW |
| `toggleResults` | 显示/隐藏结果区 | Alt+D | `Main.kt` AWT `KeyEventDispatcher` | WINDOW |
| `transpose` | 结果行列转置 | Ctrl+T | `SqlWorkspace` 根 Column | RESULT |
| `refreshResult` | 刷新当前结果 Tab | F5 | 同上 | RESULT |

> `findReplace` 当前有两个默认键（Ctrl+F / Ctrl+H），因此模型用
> **每个命令 → 一组 chord**，而不是单键。

### 1.2 固定：基础编辑键（登记但**不在设置里改**）

| 命令 id | 键 | 动作 | 拦截位置 |
|---|---|---|---|
| `saveConsole` | Ctrl+S | 保存控制台 | `SqlWorkspace` 根 Column |
| `findReplace` | Ctrl+F / Ctrl+H | 查找替换 | 同上 |
| `complete` | Ctrl+Space | 召唤补全 | 编辑区 |
| `copyCell` | Ctrl+C | 复制选中单元格 | 结果网格 |
| `cancelRun` | Esc | 取消当前执行 | `SqlWorkspace` 根 Column |

这些是标准编辑器语义，用户预期固定；改动它们只会增加误配风险。
仍放进注册表（`configurable = false`），这样 `SqlWorkspace`/编辑器里不再写字面量。

### 1.3 纯模态交互（不登记，保留就地字面量）

补全弹层 `Tab/Enter/Esc/↑↓`、结果网格方向键、行内编辑 `Enter/Esc`、
`EditCellDialog` 的 `Ctrl+Enter/Esc`、单字段弹窗 `Enter`——这些不是「快捷键绑定」
而是「交互语义」，就地判断即可，强行抽象反而绕。

### 1.4 文档中已有但未实现

`doc/DESIGN.md` §10 列的 `Ctrl+B`（收起/展开树）、`Ctrl+1..9`（切结果 Tab）、
`Ctrl+↑/↓`（历史）本次不实现；注册表可预留条目，后续补实现即自动出现在设置里。

---

## 2. 数据模型（纯逻辑，`app/settings`）

拆成 `ShortcutModels.kt`（模型）+ `Keymap.kt`（集合与解析）+ `KeymapPrefs.kt`（持久化），
**不 import compose / awt**，可被 `src/test` 纯逻辑测试。

```kotlin
/** 可识别的物理键。id 用于持久化，display 用于 UI。 */
enum class ShortcutKey(val id: String, val display: String) {
    ENTER("enter", "Enter"),
    NUMPAD_ENTER("numEnter", "Num Enter"),
    SPACE("space", "Space"),
    ESCAPE("escape", "Esc"),
    TAB("tab", "Tab"),
    BACKSPACE("backspace", "Backspace"),
    DELETE("delete", "Delete"),
    HOME("home", "Home"), END("end", "End"),
    PAGE_UP("pageUp", "Page Up"), PAGE_DOWN("pageDown", "Page Down"),
    UP("up", "↑"), DOWN("down", "↓"), LEFT("left", "←"), RIGHT("right", "→"),
    A("a", "A"), /* ... Z */,
    DIGIT_0("0", "0"), /* ... DIGIT_9 */,
    F1("f1", "F1"), /* ... F12 */,
    MINUS("minus", "-"), EQUALS("equals", "="), COMMA("comma", ","), PERIOD("period", "."),
    SLASH("slash", "/"), SEMICOLON("semicolon", ";"), QUOTE("quote", "'"),
    BACKTICK("backtick", "`"), BRACKET_LEFT("bracketLeft", "["), BRACKET_RIGHT("bracketRight", "]"),
    BACKSLASH("backslash", "\\");
    companion object { fun fromId(id: String): ShortcutKey? }
}

/** 一个按键组合：修饰键（精确匹配，多/少都算不匹配）+ 物理键。 */
data class KeyChord(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val key: ShortcutKey,
) {
    /** 规范串：固定顺序 Ctrl+Alt+Shift+Key，如 "Ctrl+Alt+L" / "F5"。持久化与 UI 共用。 */
    fun format(): String
    companion object { fun parse(s: String): KeyChord? }   // 大小写不敏感，容错未知键
}

enum class ShortcutScope { WINDOW, EDITOR, RESULT }

/** 命令注册表：id 稳定（持久化按 id）；label/scope/defaultChords/configurable 在此唯一声明。 */
enum class ShortcutCommand(
    val id: String,
    val label: String,
    /** 业务/扩展功能，出现在设置窗口。false = 基础编辑键，仅登记。 */
    val configurable: Boolean,
    val scope: ShortcutScope,
    val defaultChords: List<KeyChord>,
) {
    // ── 可配置：扩展业务功能 ──
    EXECUTE("execute", "执行选中 SQL", true, ShortcutScope.EDITOR,
        listOf(chord(ctrl = true, Key.ENTER))),
    FORMAT_SQL("formatSql", "格式化 SQL", true, ShortcutScope.EDITOR,
        listOf(chord(ctrl = true, alt = true, Key.L))),
    VIEW_DDL("viewDdl", "查看对象定义", true, ShortcutScope.WINDOW,
        listOf(chord(ctrl = true, Key.Q))),
    TOGGLE_RESULTS("toggleResults", "显示/隐藏结果区", true, ShortcutScope.WINDOW,
        listOf(chord(alt = true, Key.D))),
    TRANSPOSE("transpose", "行列转置", true, ShortcutScope.RESULT,
        listOf(chord(ctrl = true, Key.T))),
    REFRESH_RESULT("refreshResult", "刷新结果", true, ShortcutScope.RESULT,
        listOf(chord(Key.F5))),

    // ── 固定：基础编辑键（登记不展示） ──
    SAVE_CONSOLE("saveConsole", "保存控制台", false, ShortcutScope.EDITOR,
        listOf(chord(ctrl = true, Key.S))),
    FIND_REPLACE("findReplace", "查找替换", false, ShortcutScope.EDITOR,
        listOf(chord(ctrl = true, Key.F), chord(ctrl = true, Key.H))),
    COMPLETE("complete", "召唤补全", false, ShortcutScope.EDITOR,
        listOf(chord(ctrl = true, Key.SPACE))),
    COPY_CELL("copyCell", "复制单元格", false, ShortcutScope.RESULT,
        listOf(chord(ctrl = true, Key.C))),
    CANCEL_RUN("cancelRun", "取消执行", false, ShortcutScope.RESULT,
        listOf(chord(Key.ESCAPE))),
    ;
    companion object {
        fun fromId(id: String): ShortcutCommand?
        /** 设置窗口只列这些。 */
        val configurableCommands: List<ShortcutCommand> get() = entries.filter { it.configurable }
    }
}
```

### Keymap 集合

```kotlin
/** 全量绑定（命令 → chord 列表，空列表 = 显式解绑）。 */
data class Keymap(val bindings: Map<ShortcutCommand, List<KeyChord>>) {

    fun chordsOf(cmd: ShortcutCommand): List<KeyChord> = bindings[cmd].orEmpty()

    fun withChord(cmd: ShortcutCommand, chord: KeyChord): Keymap
    fun unbind(cmd: ShortcutCommand): Keymap
    fun resetCommand(cmd: ShortcutCommand): Keymap
    fun resetAll(): Keymap

    /** 冲突：同一 scope 内两个**可配置**命令共用同一 chord；跨 scope 允许（如 Esc）。 */
    fun conflicts(): List<Conflict>       // Conflict(a, b, chord)

    companion object {
        val Default: Keymap = ShortcutCommand.entries.associateWith { it.defaultChords }
    }
}
```

`Keymap` 的 canonical 形式 = `Default` 叠加用户覆盖。

---

## 3. 持久化（`KeymapPrefs`）

按 `AGENTS.md` 分层：应用级全局偏好 → `<dataDir>/*.properties`。

文件：`<dataDir>/keymap.properties`

```
# 仅存可配置命令中与默认不同的项；空值 = 显式解绑
# 格式：<commandId>=<CHORD>[,<CHORD>...]
execute=CTRL+ENTER
transpose=CTRL+SHIFT+T
cancelRun=
```

- `load()`：先 `Keymap.Default`，再逐行覆盖（空值 → 空列表）。未改动命令未来能自动吃到新默认。
- `save(keymap)`：只在**可配置命令**上做 diff，只写覆盖项；无覆盖则不写文件。
- 非可配置命令（save/find/complete/copy/cancel）**永不写入/读取**，保证基础键始终是默认。
- 解析失败的行忽略并 `Logger.warn`，坏配置不阻塞启动。

`Main.kt`：`var keymap by remember { mutableStateOf(KeymapPrefs.load()) }`，
保存后 `keymap = saved` + `KeymapPrefs.save(saved)`。

---

## 4. 匹配与适配（`app/ui/KeymapAdapter.kt`，唯一碰 compose/awt 的地方）

```kotlin
val LocalKeymap = staticCompositionLocalOf { Keymap.Default }   // 供深层组件读取

fun ShortcutKey.toComposeKey(): Key
fun ShortcutKey.toAwtKeyCode(): Int

fun KeyEvent.matches(chord: KeyChord): Boolean =
    type == KeyEventType.KeyDown &&
        key == chord.key.toComposeKey() &&
        isCtrlPressed == chord.ctrl && isAltPressed == chord.alt && isShiftPressed == chord.shift

fun java.awt.event.KeyEvent.matches(chord: KeyChord): Boolean =
    id == java.awt.event.KeyEvent.KEY_PRESSED &&
        keyCode == chord.key.toAwtKeyCode() &&
        isControlDown == chord.ctrl && isAltDown == chord.alt && isShiftDown == chord.shift

fun Keymap.matches(cmd: ShortcutCommand, e: KeyEvent): Boolean = chordsOf(cmd).any { e.matches(it) }
fun Keymap.matchAnyAwt(e: AwtEvent): ShortcutCommand?   // 供窗口级派发器一次定位
```

要点：

- **精确修饰键匹配**（`==` 而非 `<`）：`Ctrl+S` 不误命中 `Ctrl+Shift+S`；
  同时天然规避 AltGr（= Ctrl+Alt）被误判成 Alt 组合。
- **AWT 用 `keyCode`**（物理键，跨键盘布局稳定），不再依赖 `keyChar == 'd'` 兜底。
- AWT 无法区分主 Enter / 小键盘 Enter（同 `VK_ENTER`）：两者同时配置会碰撞，UI 给提示即可。

### 为什么仍保留 AWT 派发器

`Main.kt` 注释已说明：X11 下修饰键+字母除 `KEY_PRESSED` 外还会派发 `KEY_TYPED`
字符事件，绕过 Compose 的 `KeyDown` 消费进入编辑器文本会话（失焦也漏字）。
所以 **WINDOW scope（Alt+D / Ctrl+Q）继续在 AWT 派发器里整颗吃掉**；
EDITOR/RESULT scope 沿用现有 Compose `onPreviewKeyEvent`（本来就工作）。
**统一的是一份定义表，不是执行点**——各 scope 的可用性判断留在各自 composable，
不强造命令总线。

---

## 5. 各拦截点改造

统一模式：把字面量判断替换成 `keymap.matches(cmd, e)`。

### `Main.kt`
```kotlin
CompositionLocalProvider(
    LocalContentColor provides MaterialTheme.colors.onSurface,
    LocalKeymap provides keymap,          // 新增
) { ... }
```
AWT 派发器（只处理 WINDOW scope 的两个业务命令）：
```kotlin
val dispatcher = KeyEventDispatcher { e ->
    when (keymap.matchAnyAwt(e)) {
        TOGGLE_RESULTS -> { if (e.id == KEY_PRESSED) resultsVisible = !resultsVisible; true }
        VIEW_DDL -> { if (e.id == KEY_PRESSED) { /* 原逻辑 */ }; true }
        else -> false
    }
}
```
设置窗口调用：
```kotlin
SettingsDialog(
    visible = dialogState.showSettings,
    isDark = isDark,
    initial = SettingsSnapshot(editorSettings, keymap),
    onDismiss = { dialogState.showSettings = false },
    onSave = { snap ->
        editorSettings = snap.editor
        keymap = snap.keymap
        EditorPrefs.save(snap.editor)
        KeymapPrefs.save(snap.keymap)
        dialogState.showSettings = false
    },
)
```

### `SqlWorkspace.kt`
根 Column：`val keymap = LocalKeymap.current`，把 Ctrl+T / Ctrl+Alt+L / F5 / Esc
以及基础键 Ctrl+S / Ctrl+F·Ctrl+H 的字面量换成 `keymap.matches(TRANSPOSE, e)` 等。
编辑区 `BasicTextField`：Ctrl+Enter / Ctrl+Space 换成 `matches(EXECUTE, e)` / `matches(COMPLETE, e)`。
结果网格的 Ctrl+C 换成 `matches(COPY_CELL, e)`；方向键保持字面量（模态导航）。

### `SettingsDialog.kt`
签名由 `onSave: (EditorSettings) -> Unit` 改为 `onSave: (SettingsSnapshot) -> Unit`
（`data class SettingsSnapshot(val editor: EditorSettings, val keymap: Keymap)`）。
内部新增 `var keymap by remember { mutableStateOf(initial.keymap) }`。

---

## 6. 设置窗口「快捷键」分区

左导航新增第二项「快捷键」，右侧**只列 `configurableCommands`**，按 scope 分组：

```
[编辑器]  执行选中 SQL      [Ctrl+Enter]          重置
         格式化 SQL        [Ctrl+Alt+L]          重置
[窗口]    查看对象定义      [Ctrl+Q]              重置
         显示/隐藏结果区    [Alt+D]              重置
[结果区]  行列转置          [Ctrl+T]              重置
         刷新结果          [F5]                  重置
              ⚠ 冲突：与「xxx」重复（同一作用域）
 [全部恢复默认]
```

交互：

- 点 chord 胶囊 → 进入**录制态**，胶囊显示「按下组合键…」。设置窗口是独立
  `DialogWindow`，录制期间挂一个**临时 AWT `KeyEventDispatcher`**（结束 `onDispose` 移除），
  只认 `KEY_PRESSED`：仅修饰键→忽略继续等；Esc（无其它修饰）→取消录制；
  Delete/Backspace（无修饰）→清除该命令绑定；其余→`ShortcutKey.fromAwt(e.keyCode)`
  构造 chord 并吞掉该事件（含随后 `KEY_TYPED`）。用 AWT 是因为 Compose 抓不到 Alt+字母。
- 每行「重置」→ `resetCommand`；底部「全部恢复默认」→ `resetAll()`。
- 冲突即时高亮（红字 + 行内文案），**不阻断保存**（同 scope 也允许自负）。
- 与编辑器字体设置共用底部「保存/取消」，保存才落盘。

UI 细节遵循 `AGENTS.md` 主题规范：文字一律 `onSurface` / `onSurface.copy(alpha=…)`，
语义色只用于冲突警示，背景走 `background/surface`；深浅色都要人工过一遍。

---

## 7. 文件清单

新增：
- `src/main/kotlin/app/settings/ShortcutModels.kt`（ShortcutKey / KeyChord / ShortcutCommand / Keymap 及冲突）
- `src/main/kotlin/app/settings/KeymapPrefs.kt`
- `src/main/kotlin/app/ui/KeymapAdapter.kt`（LocalKeymap + Compose/AWT 匹配 + 录制捕获）
- `src/main/kotlin/app/dialog/ShortcutSettingsSection.kt`（设置分区 UI；或并入 SettingsDialog.kt）
- `src/test/kotlin/app/settings/KeymapTest.kt`

修改：
- `src/main/kotlin/app/core/Main.kt`（load/provide/save + AWT 派发器改 keymap 匹配）
- `src/main/kotlin/app/ui/SqlWorkspace.kt`（字面量 → keymap 匹配）
- `src/main/kotlin/app/dialog/SettingsDialog.kt`（新增分区 + snapshot 签名）
- `doc/DESIGN.md` §10、`AGENTS.md`（持久化清单加 `keymap.properties`）、`README.md`（快捷键小节）

---

## 8. 边界与决策

| 情形 | 决策 |
|---|---|
| 基础编辑键 | 不进设置；注册表登记 `configurable=false`，读写都跳过，始终用默认 |
| 多个默认键（Ctrl+F/Ctrl+H） | 模型支持每命令多 chord（当前用于固定项） |
| 用户解绑业务命令 | 空列表持久化为空值；匹配自然不命中 |
| 冲突 | 同 scope 警告不阻断；跨 scope 合法 |
| 配置损坏 | 逐行忽略 + `Logger.warn`，回落默认 |
| AltGr 误判 | 精确修饰键相等匹配规避 |
| Enter / 小键盘 Enter | AWT 同码，UI 提示；Compose 可区分 |
| 未来改默认值 | 只存覆盖项 → 未改动命令自动跟随新默认 |
| 新增业务功能 | 在枚举加一条 + 在对应 composable 用 `matches` 判断，设置里自动出现 |

---

## 9. 实施顺序

1. 纯逻辑层：`ShortcutModels` + `KeymapPrefs` + 单测（`KeymapTest`）。
2. 适配层 `KeymapAdapter`（Compose/AWT 映射 + LocalKeymap）。
3. 接入现有拦截点（行为不变，仅改用 keymap 匹配）→ `gradle compileKotlin` + `gradle test`。
4. 设置窗口「快捷键」分区 + 录制交互 + 快照保存。
5. 持久化联调：改键 → 保存 → 重启 → 生效；解绑 / 重置验证；确认基础键不可改。
6. 文档更新（DESIGN §10 / AGENTS 持久化 / README）。

## 10. 验证

- `gradle compileKotlin` 无错；`gradle test` 通过。
- 单测覆盖：`KeyChord.parse/format` round-trip、大小写与非法输入、覆盖/解绑、
  `conflicts` 只报同 scope 可配置命令、`configurableCommands` 恰为业务 6 条、
  `toComposeKey()/toAwtKeyCode()` 对全部枚举不抛异常。
- 手工：把「执行」改成 Ctrl+Alt+Enter、把「刷新结果」解绑、重启后确认；
  冲突告警；全部恢复默认；确认 Ctrl+S / Ctrl+F 不可改。
- 深浅色切换检查设置窗口无黑字沉底。
