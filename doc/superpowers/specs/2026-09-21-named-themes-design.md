# 命名主题系统（Named Themes）

日期：2026-09-21
状态：设计已确认，待评审 spec

## 背景与动机

现在主题只有「深/浅」两套（`appMaterialColors(isDark)`），顶栏是 Moon/Sun 一对一 toggle。
用户希望：

- 编辑器不再只是一个背景色，而是**一整套配色方案**（编辑器正文/行号/语法 token）；
- 而且这些方案是 **app 全局主题**：树、标签、结果区、弹窗、顶栏都随之切换（不只是编辑区）；
- 顶栏的明暗 toggle 升级为**主题下拉**；
- 内置主题**不可修改**；改动内置配色时，引导用户「另存为新主题」（命名并保存）。

## 已确认的需求决策（用户确认）

1. **app 全主题**（方案 1b）：每个主题 = 一整套色板，作用于整个界面。
2. **内置 5 套**：`Dark`、`Light`（= 现有两套）、`Monokai`、`Dracula`、`Sublime`。**只读**。
3. **自定义主题可改的配色 = 精选 11 项**（方案 X）：
   - 界面 4 项：`background`、`surface`、`onSurface`、`primary`
   - 编辑区 2 项：`editorBackground`、`editorForeground`
   - 语法 5 项：`keyword`、`string`、`number`、`comment`、`punctuation`
   - 行号 / 选区 / 当前行 / 边框 / 光标等**派生色不开放**，由上述 11 项按透明度导出。
4. **改内置 → 另存为新主题**：编辑内置主题的颜色时弹「另存为新主题」，命名后保存为自定义主题并切换；内置永不被写坏。
5. **顶栏入口**：Moon/Sun toggle 改为**调色板图标 + 下拉**（列出全部主题、当前项 ✓、点选即时切换；分隔线 + 「管理主题…」打开主题对话框）。新增 `DbIcons.Palette`。
6. **自定义主题持久化**：`<dataDir>/themes.json`（kotlinx-serialization-json；原子写）。当前主题 id 存 `<dataDir>/theme.properties`。
7. **i18n**：`Dark`/`Light` 显示为可翻译名（深色/浅色）；`Monokai`/`Dracula`/`Sublime` 与自定义主题名**不翻译**（专有名/用户数据）。
8. **语义色不随主题变**：状态点（绿/红/黄/灰）、对象徽章色等仍是固定语义色。
9. **不做**：逐角色全开放（方案 Y）、主题导入/导出、跟随系统主题、每主题独立字体。

## 数据模型

### 主题色板（唯一取色来源）

```kotlin
// app/ui/AppThemes.kt
data class ThemeColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val primary: Color,
    val editorBackground: Color,
    val editorForeground: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val punctuation: Color,
) {
    /** 由背景亮度判定明暗（驱动 Material isLight、光标等）。 */
    val isLight: Boolean get() = background.luminance() > 0.5f

    /** 映射到 Material `Colors`（现存界面代码只依赖它）。 */
    fun toMaterialColors(): Colors
}

data class ThemeSpec(
    val id: String,
    /** Dark/Light → 可翻译；Monokai/Dracula/Sublime 与自定义 → null（用 [name]）。 */
    val nameKey: Str? = null,
    val name: String = "",
    val builtIn: Boolean,
    val colors: ThemeColors,
)
```

- `toMaterialColors()`：`onBackground = onSurface`；`primaryVariant`/`secondary` 由 `primary` 派生；`onPrimary` 由 `primary` 亮度取黑/白；`error`/`onError` 按 `isLight` 用固定语义红；其余用 `lightColors(...)`/`darkColors(...)` 承载（保证 `MaterialTheme.colors.isLight` 正确）。
- 派生色（保持现有手法，改从主题取）：
  - 行号 `= editorForeground.copy(alpha = 0.35f)`；当前行号 `= editorForeground`；当前行号底 `= primary.copy(alpha = 0.16f)`
  - 光标 `cursorBrush = editorForeground`（取代 `if (isDark) White else Black`）
  - 选区沿用 `LocalTextSelectionColors` 默认（基于 `primary`）；边框 `= onSurface.copy(alpha = 0.18f)`
- 语法色：`sqlSyntaxPalette(colors: ThemeColors): SqlSyntaxPalette`、`jsonSyntaxPalette(colors)` 改为从主题取 5 项（不再用 `isDark` 反推）。

### 内置配色（参考值；可在人工验色时微调）

| | Dark | Light | Monokai | Dracula | Sublime |
|---|---|---|---|---|---|
| background | `#292B2E` | `#EBECF0` | `#272822` | `#282A36` | `#343D46` |
| surface | `#32353B` | `#FFFFFF` | `#2E2F27` | `#343746` | `#3E4750` |
| onSurface | `#FFFFFF` | `#1F2328` | `#F8F8F2` | `#F8F8F2` | `#D8DEE9` |
| primary | `#90CAF9` | `#1976D2` | `#A6E22E` | `#BD93F9` | `#6699CC` |
| editorBackground | `#32353B` | `#FFFFFF` | `#272822` | `#282A36` | `#343D46` |
| editorForeground | `#FFFFFF` | `#1F2328` | `#F8F8F2` | `#F8F8F2` | `#D8DEE9` |
| keyword | `#569CD6` | `#0000FF` | `#F92672` | `#FF79C6` | `#C594C5` |
| string | `#CE9178` | `#A31515` | `#E6DB74` | `#F1FA8C` | `#99C794` |
| number | `#B5CEA8` | `#098658` | `#AE81FF` | `#BD93F9` | `#F99157` |
| comment | `#6A9955` | `#008000` | `#75715E` | `#6272A4` | `#65737E` |
| punctuation | `#D4D4D4` | `#242424` | `#F8F8F2` | `#F8F8F2` | `#D8DEE9` |

`Dark`/`Light` 的值必须与现有 `appMaterialColors` + 现有语法色板**逐项一致**（行为不变）。

## 持久化

### `<dataDir>/theme.properties`

- 新：key `theme = <id>`（`dark`/`light`/`monokai`/`dracula`/`sublime`/自定义 uuid）。
- 迁移：读到旧 key `dark=true|false` 而 `theme` 缺失 → 映射 `dark`/`light` 并重写文件；两者都缺 → 默认 `light`（与现状一致）。

### `<dataDir>/themes.json`

```json
{
  "version": 1,
  "themes": [
    {
      "id": "<uuid>",
      "name": "我的主题",
      "baseId": "monokai",
      "colors": {
        "background": "#272822", "surface": "#2E2F27",
        "onSurface": "#F8F8F2", "primary": "#A6E22E",
        "editorBackground": "#272822", "editorForeground": "#F8F8F2",
        "keyword": "#F92672", "string": "#E6DB74", "number": "#AE81FF",
        "comment": "#75715E", "punctuation": "#F8F8F2"
      }
    }
  ]
}
```

- 用 `@Serializable` DTO + `kotlinx.serialization.json.Json`。
- 颜色以 `#RRGGBB` 字符串存；解析失败该项回退 `baseId` 主题的同项。
- **原子写**：先写 `themes.json.tmp` 再 `move(ATOMIC_MOVE)`，失败只告警不改判定。
- 文件缺失/损坏 → 视为无自定义主题（`Logger.error`），不崩。
- `baseId` 仅作元数据（记录克隆来源，便于日后「重置为该基础主题」；首版不做重置）。

## 组件与数据流

### 注册表与状态

- `app/ui/AppThemes.kt`：`builtInThemes: List<ThemeSpec>`（5 套）+ `defaultThemeId = "light"`。
- 新增 `app/settings/ThemesStore.kt`：`loadCustom(): List<ThemeSpec>`、`saveCustom(list)`、`newThemeId()`（uuid）。
- `Main`（AppBody）：
  - `var customThemes by remember { mutableStateOf(ThemesStore.loadCustom()) }`
  - `var activeThemeId by remember { mutableStateOf(ThemePrefs.load() ?: defaultThemeId) }`
  - `val themes = builtInThemes + customThemes`；`val activeTheme = themes.firstOrNull { it.id == activeThemeId } ?: lightTheme`
  - `MaterialTheme(colors = activeTheme.colors.toMaterialColors()) { CompositionLocalProvider(LocalThemeColors provides activeTheme.colors, LocalActiveTheme provides activeTheme) { … } }`
  - 切换：`fun selectTheme(id) { activeThemeId = id; ThemePrefs.save(id) }`
  - **解析回退**：`activeThemeId` 在 `themes` 里找不到（自定义被删/`themes.json` 损坏）→ 回退 `light`，并把 `theme.properties` 重写为 `light`。
  - `isDark` 变量、`appMaterialColors(isDark)` 被主题色板取代（`appMaterialColors` 删除；Dark/Light 的值改由 `ThemeColors` 表达，与现有逐项一致）。
  - `EditorArea` / `SqlEditorPane` / `ConsoleHeaderBar` 的 `isDark` 参数删除（语法色/光标改读 `LocalThemeColors`）。

### CompositionLocal（语法色 plumbing）

- `val LocalThemeColors = staticCompositionLocalOf { builtInThemes.first { it.id == "light" }.colors }`
- 编辑器（`SqlEditorPane`）、DDL/JSON 查看器（`ViewerDialogs` / `JsonTreeView`）、`SettingsDialog` / `ThemeDialog` 的预览都读它取语法色与编辑区底色；不再用 `MaterialTheme.colors.isLight` 反推语法色板。
- 每个自建 `MaterialTheme` 的 `DialogWindow`（Settings / ThemeDialog）同样包一层 `CompositionLocalProvider(LocalThemeColors provides …)`。

### 顶栏（`ConsoleHeaderBar`）

- 移除 `isDark: Boolean` + `onToggleTheme`；改为：
  - `themes: List<ThemeSpec>`、`activeTheme: ThemeSpec`、`onSelectTheme: (String) -> Unit`、`onManageThemes: () -> Unit`。
- 渲染：调色板 icon（`DbIcons.Palette`，新增）+ `DropdownMenu`：主题项（当前项 primary + ✓；名 = `nameKey?.let { t(it) } ?: name`）、分隔线、「管理主题…」。

### 主题对话框（新增 `app/dialog/ThemeDialog.kt`，独立 `DialogWindow`）

- 左侧：主题列表（内置在前，自定义在后，当前项高亮）；底部「新建（克隆当前）…」。
- 右侧：11 个字段（label + `#RRGGBB` 输入 + 色块）；每项实时校验（非法红字、不应用）。
- 内置主题：字段只读 + 提示「内置主题不可修改，点『另存为新主题』」；「另存为新主题」→ 输入名字（复用单字段弹窗）→ 以当前 11 项克隆为自定义主题并切换。
- 自定义主题：可编辑，底部「保存」（写 `themes.json`）、「重命名」、「删除」（删除若为当前 → 切回 `light`）。
- **应用时机**：在左侧列表点选主题 = 立即切到该主题（含主窗口，与顶栏下拉一致）；编辑自定义主题的 11 个色值时，**仅对话框内预览** 实时更新，主窗口不变；点「保存」才落盘并生效。关闭对话框不保存 → 丢弃草稿改动。
- 实时预览：示例 SQL 代码块 + 常规文字行，用当前编辑中的 11 项渲染。
- 关闭即刷新主窗口主题（编辑中的草稿不跨窗口保存，未保存改动丢弃并提示？——**首版：直接编辑并即时切换预览，点「保存」才落 `themes.json`**）。

### `SettingsDialog`

- 依赖从 `isDark: Boolean` 改为 `theme: ThemeSpec`（用它建自己的 `MaterialTheme` + 提供 `LocalThemeColors`）。
- 不新增主题分区（主题管理在 `ThemeDialog`）；「通用设置」保持字体/语言 + 诊断。

## i18n（zh + en 新增 key）

| key | zh | en |
|---|---|---|
| `ThemeMenuTooltip` | 主题 | Theme |
| `ThemeNameDark` | 深色 | Dark |
| `ThemeNameLight` | 浅色 | Light |
| `ThemeManage` | 管理主题… | Manage themes… |
| `ThemeManagerTitle` | 管理主题 | Manage themes |
| `ThemeNew` | 新建（克隆当前）… | New (clone current)… |
| `ThemeSaveAs` | 另存为新主题 | Save as new theme |
| `ThemeSaveAsTitle` | 另存为新主题 | Save as new theme |
| `ThemeNameLabel` | 主题名 | Theme name |
| `ThemeBuiltInReadOnly` | 内置主题不可修改，点「另存为新主题」保存为自定义主题。 | Built-in themes can’t be edited. Use “Save as new theme”. |
| `ThemeRenameTitle` | 重命名主题 | Rename theme |
| `ThemeDeleteTitle` | 删除主题 | Delete theme |
| `ThemeDeleteConfirm` | 删除主题「{0}」？ | Delete theme “{0}”? |
| `ThemeColorsSection` | 界面配色 | UI colors |
| `ThemeEditorSection` | 编辑区配色 | Editor colors |
| `ThemeSyntaxSection` | 语法配色 | Syntax colors |
| `ThemeColorBackground` | 背景 | Background |
| `ThemeColorSurface` | 表面 | Surface |
| `ThemeColorOnSurface` | 文字 | Text |
| `ThemeColorPrimary` | 主色 | Primary |
| `ThemeColorEditorBackground` | 编辑区背景 | Editor background |
| `ThemeColorEditorForeground` | 编辑区正文 | Editor text |
| `ThemeColorKeyword` | 关键字 | Keyword |
| `ThemeColorString` | 字符串 | String |
| `ThemeColorNumber` | 数字 | Number |
| `ThemeColorComment` | 注释 | Comment |
| `ThemeColorPunctuation` | 符号 | Punctuation |
| `ThemeColorInvalid` | 颜色格式应为 #RRGGBB | Expected #RRGGBB |
| `ThemeSaved` | 已保存主题「{0}」 | Saved theme “{0}” |

（`Monokai`/`Dracula`/`Sublime`、自定义主题名不进 catalog——专有名/用户数据。）

## 测试

- **纯逻辑单测**（`src/test/kotlin/app/settings/ThemeStoreTest.kt`、`app/ui/ThemeColorsTest.kt`）：
  - `#RRGGBB`/`#RGB`/无 `#` 解析；非法返回 null。
  - `ThemeColors.isLight` 由背景亮度判定；`toMaterialColors()` 的 `isLight`/`onSurface`/`primary`/`error` 映射。
  - `ThemeSpec` 名解析：`nameKey != null` → key，否则 `name`。
  - `themes.json` 往返（多个自定义主题、中文名）；损坏 JSON → 空列表 + 不抛；`baseId` 未知 → 颜色回退 `light`。
  - `ThemePrefs` 迁移：`dark=true` → `dark`；`dark=false` → `light`；缺 key → 默认；新 `theme` key 优先。
  - 内置注册表：5 套、id 唯一、Dark/Light 与现有色值逐项一致。
- **i18n**：`I18nCatalogTest` 覆盖新 key（zh+en 非空、占位符一致）。
- **UI 人工验收**（见下）。

## 人工验证步骤（交付时提供，由用户执行）

1. 顶栏调色板下拉能切换 Dark/Light/Monokai/Dracula/Sublime；整个界面（树/标签/结果/弹窗）随之变色，重启后保持。
2. 中英切换：Dark/Light 显示「深色/浅色」；Monokai/Dracula/Sublime 名不变。
3. 编辑器：Monokai 下背景/正文/关键字/字符串/注释可读；行号/选区/光标可见；DDL 查看器与 JSON 树同步用该主题语法色。
4. 管理主题：内置字段只读 + 提示；点「另存为新主题」→ 命名 → 新主题成为当前；改其颜色→保存→切走再切回仍在；重启仍在。
5. 自定义主题重命名/删除；删除当前主题 → 回退深色/浅色；`activeThemeId` 指向不存在的主题 → 回退浅色。
6. 手工破坏 `themes.json` → 启动不崩、无自定义主题、日志有 error。
7. 深/浅与 5 主题下检查弹窗/下拉/输入框：无黑字沉底、无过曝白块；语义色（状态点/徽章）仍可辨。
