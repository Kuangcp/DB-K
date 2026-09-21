# 命名主题系统 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「深/浅」两套主题升级为**命名主题系统**：内置 Dark/Light/Monokai/Dracula/Sublime（只读）+ 用户可另存的自定义主题（11 项配色），顶栏 toggle 变主题下拉，编辑器/界面统一由主题色板驱动。

**Architecture:** 新增 `ThemeColors`（11 语义色）+ `ThemeSpec`（id/名/是否内置/色板）注册表；`ThemeColors.toMaterialColors()` 注入 `MaterialTheme.colors`（界面代码零改动）；语法色/编辑区底色通过 `LocalThemeColors` CompositionLocal 下发；自定义主题存 `<dataDir>/themes.json`（kotlinx-serialization，原子写），当前主题 id 存 `theme.properties`。

**Tech Stack:** Kotlin 2.4 / Compose Desktop（Material2）/ kotlinx-serialization-json 1.8.0 / Gradle 9.4.1 / JDK 25.0.3-jbr / JUnit5 + kotlin.test。

**Spec:** `doc/superpowers/specs/2026-09-21-named-themes-design.md`

## Global Constraints

- 工具链：`JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`，`PATH` 加 `/home/zk/.sdkman/candidates/gradle/9.4.1/bin`；用 `gradle`（无 wrapper）。
- 界面取色**只能**走 `MaterialTheme.colors.*`（语义色：状态点绿/红/黄/灰、对象徽章色保持固定，不随主题变）。
- 语法/编辑区色只能走 `LocalThemeColors.current`（不得再用 `MaterialTheme.colors.isLight` 反推）。
- i18n：用户可见文案走 `Str` key（zh+en 同时补，`when` 穷尽）；`Dark`/`Light` 用 key（`ThemeNameDark`/`ThemeNameLight`），`Monokai`/`Dracula`/`Sublime` 与自定义主题名**不进 catalog**（专有名/用户数据）。
- 迁移只增不改；`CURRENT_VERSION` 逐任务递增（本计划不动 SQLite）。
- 分层：`app/settings` 不依赖 Compose（`ThemesStore` 用 hex 字符串 DTO，不 import Color）。
- UI 验证：不跑 GUI 自动化；纯逻辑跑 `gradle test`，配色观感给人工步骤。

## Review Focus

五类最易漏、且测试不覆盖的输入/失败模式，各自钉在归属任务：

1. **`themes.json` 损坏/半写**：文件被截断或非法 JSON → 启动不崩、无自定义主题、日志一条 error（原子写保证不产生半写）。
2. **颜色字段非法/缺失**：`"background": "zzz"` 或 key 缺失 → 该项回退 `baseId` 主题；`baseId` 未知 → 回退 Light。
3. **`activeThemeId` 悬空**（自定义被删 / 文件损坏）→ 回退 `light` 并重写 `theme.properties`。
4. **内置只读**：内置主题的任何颜色都不可被写进 `themes.json`（保存动作只能产出自定义主题）。
5. **`theme.properties` 老档迁移**：只有 `dark=true|false` 时映射为 `dark`/`light`；两个 key 都在时以 `theme` 为准。

---

## 文件结构

- 新增 `src/main/kotlin/app/ui/AppThemes.kt` —— `ThemeColors` / `ThemeSpec` / 内置注册表 / `toMaterialColors()` / hex 解析 / `LocalThemeColors`。
- 修改 `src/main/kotlin/app/ui/SqlHighlight.kt`、`src/main/kotlin/app/ui/JsonSupport.kt` —— 色板函数改吃 `ThemeColors`。
- 新增 `src/main/kotlin/app/settings/ThemesStore.kt` —— `themes.json` 读写（DTO + 原子写）。
- 修改 `src/main/kotlin/app/settings/ThemePrefs.kt` —— 存主题 id + 迁移。
- 修改 `src/main/kotlin/i18n/Str.kt` / `CatalogZh.kt` / `CatalogEn.kt` —— 主题文案。
- 修改 `src/main/kotlin/app/ui/DbIcons.kt` —— `Palette` 图标。
- 修改 `src/main/kotlin/app/ui/SqlEditorPane.kt`、`app/dialog/ViewerDialogs.kt`、`app/dialog/JsonTreeView.kt` —— 语法/底色改读 `LocalThemeColors`。
- 修改 `src/main/kotlin/app/ui/EditorArea.kt`、`app/ui/ConsoleHeaderBar.kt` —— 主题状态参数 + 下拉。
- 新增 `src/main/kotlin/app/dialog/ThemeDialog.kt` —— 主题管理对话框。
- 修改 `src/main/kotlin/app/dialog/SettingsDialog.kt` —— `isDark` → `theme`。
- 修改 `src/main/kotlin/app/state/DialogState.kt` —— `showThemeDialog`。
- 修改 `src/main/kotlin/app/core/Main.kt` —— 主题状态/接线。
- 测试：新增 `src/test/kotlin/app/ui/AppThemesTest.kt`、`src/test/kotlin/app/settings/ThemeStoreTest.kt`；改 `SqlHighlightTest.kt`、`JsonSupportTest.kt`。

---

## Task 1: 主题模型与内置配色（纯逻辑）

**Files:**
- Create: `src/main/kotlin/app/ui/AppThemes.kt`
- Modify: `src/main/kotlin/app/ui/SqlHighlight.kt`、`src/main/kotlin/app/ui/JsonSupport.kt`
- Test: `src/test/kotlin/app/ui/AppThemesTest.kt`

**Interfaces:**
- Produces:
  - `data class ThemeColors(background, surface, onSurface, primary, editorBackground, editorForeground, keyword, string, number, comment, punctuation: Color)`；`val isLight: Boolean`；`fun toMaterialColors(): Colors`；`companion object { fun parse(hex: String): Color?; fun toHex(c: Color): String }`
  - `data class ThemeSpec(id, nameKey: Str? = null, name: String = "", builtIn: Boolean, baseId: String? = null, colors: ThemeColors)`
  - `val builtInThemes: List<ThemeSpec>`、`const val defaultThemeId = "light"`、`fun themeById(id: String): ThemeSpec?`
  - `val LocalThemeColors = staticCompositionLocalOf { lightColors }`（默认 Light 色板）
  - `internal fun sqlSyntaxPalette(colors: ThemeColors): SqlSyntaxPalette`
  - `internal fun jsonSyntaxPalette(colors: ThemeColors): JsonSyntaxPalette`

- [ ] **Step 1: 写失败测试**

创建 `src/test/kotlin/app/ui/AppThemesTest.kt`：

```kotlin
package app.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppThemesTest {

    @Test
    fun `registry has five builtins with unique ids and default`() {
        assertEquals(5, builtInThemes.size)
        assertEquals(5, builtInThemes.map { it.id }.toSet().size)
        assertNotNull(themeById(defaultThemeId))
        assertTrue(builtInThemes.all { it.builtIn })
        assertEquals(listOf("dark", "light", "monokai", "dracula", "sublime"), builtInThemes.map { it.id })
        assertEquals("ThemeNameLight", themeById("light")!!.nameKey!!.name)
        assertNull(themeById("monokai")!!.nameKey)
        assertEquals("Monokai", themeById("monokai")!!.name)
    }

    @Test
    fun `dark and light keep existing values`() {
        val dark = themeById("dark")!!.colors
        assertEquals(Color(0xFF292B2E), dark.background)
        assertEquals(Color(0xFF32353B), dark.surface)
        assertEquals(Color(0xFFFFFFFF), dark.onSurface)
        assertEquals(Color(0xFF90CAF9), dark.primary)
        assertEquals(Color(0xFF569CD6), dark.keyword)
        assertEquals(Color(0xFFCE9178), dark.string)
        assertEquals(Color(0xFFB5CEA8), dark.number)
        assertEquals(Color(0xFF6A9955), dark.comment)
        assertEquals(Color(0xFFD4D4D4), dark.punctuation)

        val light = themeById("light")!!.colors
        assertEquals(Color(0xFFEBECF0), light.background)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFF1F2328), light.onSurface)
        assertEquals(Color(0xFF1976D2), light.primary)
    }

    @Test
    fun `isLight derives from background luminance`() {
        assertTrue(themeById("light")!!.colors.isLight)
        assertFalse(themeById("dark")!!.colors.isLight)
        assertFalse(themeById("monokai")!!.colors.isLight)
        assertFalse(themeById("dracula")!!.colors.isLight)
        assertFalse(themeById("sublime")!!.colors.isLight)
    }

    @Test
    fun `toMaterialColors maps roles and error per lightness`() {
        val dark = themeById("dark")!!.colors.toMaterialColors()
        assertFalse(dark.isLight)
        assertEquals(Color(0xFFFFFFFF), dark.onSurface)
        assertEquals(Color(0xFFCF6679), dark.error)
        assertEquals(themeById("dark")!!.colors.primary, dark.primary)

        val light = themeById("light")!!.colors.toMaterialColors()
        assertTrue(light.isLight)
        assertEquals(Color(0xFF1F2328), light.onSurface)
        assertEquals(Color(0xFFB00020), light.error)
    }

    @Test
    fun `hex parse accepts forms and roundtrips`() {
        assertEquals(Color(0xFF1E1F22), ThemeColors.parse("#1e1f22"))
        assertEquals(Color(0xFF1E1F22), ThemeColors.parse("1e1f22"))
        assertEquals(Color(0xFF11EE22), ThemeColors.parse("#1E2"))
        assertNull(ThemeColors.parse("zzz"))
        assertNull(ThemeColors.parse("#12345"))
        assertEquals("#1E1F22", ThemeColors.toHex(Color(0xFF1E1F22)))
        assertEquals("#000000", ThemeColors.toHex(Color(0xFF000000)))
    }

    @Test
    fun `syntax palettes map from theme colors`() {
        val c = themeById("monokai")!!.colors
        val sql = sqlSyntaxPalette(c)
        assertEquals(c.keyword, sql.keyword)
        assertEquals(c.string, sql.string)
        assertEquals(c.number, sql.number)
        assertEquals(c.comment, sql.comment)
        assertEquals(c.punctuation, sql.punctuation)

        val json = jsonSyntaxPalette(c)
        assertEquals(c.string, json.string)
        assertEquals(c.number, json.number)
        assertEquals(c.keyword, json.key)
        assertEquals(c.keyword, json.boolean)
        assertEquals(c.keyword, json.nullLiteral)
        assertEquals(c.punctuation, json.punctuation)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH=/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$JAVA_HOME/bin:$PATH
gradle test --tests 'app.ui.AppThemesTest'
```
Expected: 编译失败（`ThemeColors` / `builtInThemes` 未定义）。

- [ ] **Step 3: 实现 AppThemes + 色板函数**

创建 `src/main/kotlin/app/ui/AppThemes.kt`：

```kotlin
package app.ui

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import i18n.Str
import kotlin.math.roundToInt

/**
 * 一个主题的 11 个语义色（界面 4 + 编辑区 2 + 语法 5）。
 * 行号/选区/当前行/边框/光标等派生色不在此，由这些按透明度导出（见 EditorPane）。
 */
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
    /** 背景亮度决定明暗：驱动 Material isLight 与语法色板。 */
    val isLight: Boolean get() = background.luminance() > 0.5f

    /** 映射到 Material `Colors`（界面代码只依赖它）。 */
    fun toMaterialColors(): Colors {
        val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
        return if (isLight) {
            lightColors(
                primary = primary,
                primaryVariant = primary,
                secondary = primary,
                secondaryVariant = primary,
                background = background,
                surface = surface,
                error = Color(0xFFB00020),
                onPrimary = onPrimary,
                onSecondary = onPrimary,
                onBackground = onSurface,
                onSurface = onSurface,
                onError = Color.White,
            )
        } else {
            darkColors(
                primary = primary,
                primaryVariant = primary,
                secondary = primary,
                secondaryVariant = primary,
                background = background,
                surface = surface,
                error = Color(0xFFCF6679),
                onPrimary = onPrimary,
                onSecondary = onPrimary,
                onBackground = onSurface,
                onSurface = onSurface,
                onError = Color.Black,
            )
        }
    }

    companion object {
        /** 解析 `#RRGGBB` / `RRGGBB` / `#RGB`；非法返回 null。 */
        fun parse(hex: String): Color? {
            val raw = hex.trim().removePrefix("#")
            val full = when (raw.length) {
                3 -> raw.map { "$it$it" }.joinToString("")
                6 -> raw
                else -> return null
            }
            val value = full.toLongOrNull(16) ?: return null
            if (full.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
            return Color(0xFF000000L or value)
        }

        /** `Color` → `#RRGGBB`（丢 alpha）。 */
        fun toHex(c: Color): String {
            val r = (c.red * 255f).roundToInt().coerceIn(0, 255)
            val g = (c.green * 255f).roundToInt().coerceIn(0, 255)
            val b = (c.blue * 255f).roundToInt().coerceIn(0, 255)
            return "#%02X%02X%02X".format(r, g, b)
        }
    }
}

/**
 * 主题 = 一整套色板。内置主题只读；自定义主题由内置克隆而来（[baseId] 记录来源）。
 * [nameKey] 非空 = 名字走 i18n（Dark/Light）；否则用 [name]（专有名/用户数据）。
 */
data class ThemeSpec(
    val id: String,
    val nameKey: Str? = null,
    val name: String = "",
    val builtIn: Boolean,
    val baseId: String? = null,
    val colors: ThemeColors,
)

private val DarkTheme = ThemeSpec(
    id = "dark", nameKey = Str.ThemeNameDark, name = "Dark", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF292B2E), surface = Color(0xFF32353B),
        onSurface = Color(0xFFFFFFFF), primary = Color(0xFF90CAF9),
        editorBackground = Color(0xFF32353B), editorForeground = Color(0xFFFFFFFF),
        keyword = Color(0xFF569CD6), string = Color(0xFFCE9178), number = Color(0xFFB5CEA8),
        comment = Color(0xFF6A9955), punctuation = Color(0xFFD4D4D4),
    ),
)

private val LightTheme = ThemeSpec(
    id = "light", nameKey = Str.ThemeNameLight, name = "Light", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFFEBECF0), surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F2328), primary = Color(0xFF1976D2),
        editorBackground = Color(0xFFFFFFFF), editorForeground = Color(0xFF1F2328),
        keyword = Color(0xFF0000FF), string = Color(0xFFA31515), number = Color(0xFF098658),
        comment = Color(0xFF008000), punctuation = Color(0xFF242424),
    ),
)

private val MonokaiTheme = ThemeSpec(
    id = "monokai", name = "Monokai", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF272822), surface = Color(0xFF2E2F27),
        onSurface = Color(0xFFF8F8F2), primary = Color(0xFFA6E22E),
        editorBackground = Color(0xFF272822), editorForeground = Color(0xFFF8F8F2),
        keyword = Color(0xFFF92672), string = Color(0xFFE6DB74), number = Color(0xFFAE81FF),
        comment = Color(0xFF75715E), punctuation = Color(0xFFF8F8F2),
    ),
)

private val DraculaTheme = ThemeSpec(
    id = "dracula", name = "Dracula", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF282A36), surface = Color(0xFF343746),
        onSurface = Color(0xFFF8F8F2), primary = Color(0xFFBD93F9),
        editorBackground = Color(0xFF282A36), editorForeground = Color(0xFFF8F8F2),
        keyword = Color(0xFFFF79C6), string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9),
        comment = Color(0xFF6272A4), punctuation = Color(0xFFF8F8F2),
    ),
)

private val SublimeTheme = ThemeSpec(
    id = "sublime", name = "Sublime", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF343D46), surface = Color(0xFF3E4750),
        onSurface = Color(0xFFD8DEE9), primary = Color(0xFF6699CC),
        editorBackground = Color(0xFF343D46), editorForeground = Color(0xFFD8DEE9),
        keyword = Color(0xFFC594C5), string = Color(0xFF99C794), number = Color(0xFFF99157),
        comment = Color(0xFF65737E), punctuation = Color(0xFFD8DEE9),
    ),
)

val builtInThemes: List<ThemeSpec> = listOf(DarkTheme, LightTheme, MonokaiTheme, DraculaTheme, SublimeTheme)

const val defaultThemeId: String = "light"

fun themeById(id: String): ThemeSpec? = builtInThemes.firstOrNull { it.id == id }

/** 当前主题色板；默认 Light（未被 Main 覆盖时也能安全取用）。 */
val LocalThemeColors = staticCompositionLocalOf { LightTheme.colors }
```

修改 `src/main/kotlin/app/ui/SqlHighlight.kt`：把 `sqlSyntaxPalette(isDark: Boolean)` 替换为：

```kotlin
internal fun sqlSyntaxPalette(colors: ThemeColors): SqlSyntaxPalette = SqlSyntaxPalette(
    keyword = colors.keyword,
    string = colors.string,
    number = colors.number,
    comment = colors.comment,
    punctuation = colors.punctuation,
)
```

修改 `src/main/kotlin/app/ui/JsonSupport.kt`：把 `jsonSyntaxPalette(isDark: Boolean)` 替换为：

```kotlin
internal fun jsonSyntaxPalette(colors: ThemeColors): JsonSyntaxPalette = JsonSyntaxPalette(
    key = colors.keyword,
    string = colors.string,
    number = colors.number,
    boolean = colors.keyword,
    nullLiteral = colors.keyword,
    punctuation = colors.punctuation,
)
```

同时在本任务里保留两个**过渡重载**（Task 4 迁移完编辑器/查看器调用点后删除），保证本任务可独立编译：

```kotlin
// TODO(Task 4): 迁移调用点后删这两个重载
internal fun sqlSyntaxPalette(isDark: Boolean): SqlSyntaxPalette =
    sqlSyntaxPalette((if (isDark) themeById("dark") else themeById("light"))!!.colors)
internal fun jsonSyntaxPalette(isDark: Boolean): JsonSyntaxPalette =
    jsonSyntaxPalette((if (isDark) themeById("dark") else themeById("light"))!!.colors)
```

- [ ] **Step 4: 更新既有测试（色板函数签名变了）**

`src/test/kotlin/app/ui/SqlHighlightTest.kt`：把 `sqlSyntaxPalette(isDark = false)` 与 `sqlSyntaxPalette(dark)` 改为基于内置主题：

```kotlin
    private val pal = sqlSyntaxPalette(themeById("light")!!.colors)
```
（第 89 行的 `val p = sqlSyntaxPalette(dark)` 改为 `val p = sqlSyntaxPalette(if (dark) themeById("dark")!!.colors else themeById("light")!!.colors)`。）

`src/test/kotlin/app/ui/JsonSupportTest.kt`：`private val pal = jsonSyntaxPalette(themeById("light")!!.colors)`。

- [ ] **Step 5: 跑测试确认通过**

Run: `gradle test --tests 'app.ui.AppThemesTest' --tests 'app.ui.SqlHighlightTest' --tests 'app.ui.JsonSupportTest'`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/app/ui/AppThemes.kt src/main/kotlin/app/ui/SqlHighlight.kt \
  src/main/kotlin/app/ui/JsonSupport.kt src/test/kotlin/app/ui/AppThemesTest.kt \
  src/test/kotlin/app/ui/SqlHighlightTest.kt src/test/kotlin/app/ui/JsonSupportTest.kt
git commit -m "feat(theme): ThemeColors/ThemeSpec 与内置 5 套配色 + 主题化语法色板"
```

---

## Task 2: i18n 主题文案

**Files:**
- Modify: `src/main/kotlin/i18n/Str.kt`、`CatalogZh.kt`、`CatalogEn.kt`

**Interfaces:**
- Produces：spec「i18n」表里的全部 `Theme*` key（`ThemeNameDark`/`ThemeNameLight` 等 30 个）。

- [ ] **Step 1: 照 spec 表格加 key（三处同步）**

按 `doc/superpowers/specs/2026-09-21-named-themes-design.md` 的 i18n 表，在 `Str.kt` 新增 `// ── 主题 ──` 区块并逐条列出；`CatalogZh.kt` / `CatalogEn.kt` 各补对应文案（`when` 穷尽即编译校验）。

- [ ] **Step 2: 跑测试确认通过**

Run: `gradle test --tests 'i18n.I18nCatalogTest'`
Expected: PASS（非空 + 占位符一致）。

- [ ] **Step 3: 提交**

```bash
git add src/main/kotlin/i18n/Str.kt src/main/kotlin/i18n/CatalogZh.kt src/main/kotlin/i18n/CatalogEn.kt
git commit -m "feat(i18n): 主题系统文案（zh + en）"
```

---

## Task 3: 自定义主题持久化 + `theme.properties` 迁移

**Files:**
- Create: `src/main/kotlin/app/settings/ThemesStore.kt`
- Modify: `src/main/kotlin/app/settings/ThemePrefs.kt`
- Test: `src/test/kotlin/app/settings/ThemeStoreTest.kt`

**Interfaces:**
- Produces:
  - `class ThemesStore(file: java.io.File = AppPaths.dataDirectory().resolve("themes.json").toFile())`：`fun load(): List<ThemeSpec>`、`fun save(themes: List<ThemeSpec>)`、`fun newId(): String`
  - `ThemePrefs.load(): String?`、`ThemePrefs.save(id: String)`、`internal fun ThemePrefs.readActive(f: java.io.File): String?`

- [ ] **Step 1: 写失败测试**

创建 `src/test/kotlin/app/settings/ThemeStoreTest.kt`：

```kotlin
package app.settings

import app.ui.ThemeColors
import app.ui.ThemeSpec
import app.ui.builtInThemes
import app.ui.themeById
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun store() = ThemesStore(dir.resolve("themes.json").toFile())

    private fun custom(name: String, baseId: String = "monokai", bg: String = "#101112") = ThemeSpec(
        id = "t-$name",
        name = name,
        builtIn = false,
        baseId = baseId,
        colors = builtInThemes.first { it.id == baseId }.colors.copy(background = ThemeColors.parse(bg)!!),
    )

    @Test
    fun `save then load roundtrips custom themes`() {
        val s = store()
        s.save(listOf(custom("我的主题"), custom("Second", baseId = "dracula", bg = "#1E1F22")))
        val loaded = s.load()
        assertEquals(listOf("我的主题", "Second"), loaded.map { it.name })
        assertTrue(loaded.all { !it.builtIn })
        assertEquals("#1E1F22", ThemeColors.toHex(loaded[1].colors.background))
        assertEquals("dracula", loaded[1].baseId)
    }

    @Test
    fun `corrupt file yields empty list`() {
        val f = dir.resolve("themes.json").toFile()
        f.writeText("{ not json ")
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `missing file yields empty list`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `invalid color falls back to base theme and unknown base falls back to light`() {
        val f = dir.resolve("themes.json").toFile()
        f.writeText(
            """{"version":1,"themes":[
              {"id":"a","name":"A","baseId":"dracula","colors":{"background":"zzz"}},
              {"id":"b","name":"B","baseId":"nope","colors":{"background":"#010203"}}
            ]}""".trimIndent(),
        )
        val loaded = store().load()
        assertEquals(themeById("dracula")!!.colors.background, loaded[0].colors.background)
        assertEquals(ThemeColors.parse("#010203"), loaded[1].colors.background)
        assertEquals(themeById("light")!!.colors.keyword, loaded[1].colors.keyword)
    }

    @Test
    fun `theme prefs migrate legacy dark flag and prefer theme key`() {
        val f = File(dir.toFile(), "theme.properties")
        f.writeText("dark=true\n")
        assertEquals("dark", ThemePrefs.readActive(f))
        f.writeText("dark=false\n")
        assertEquals("light", ThemePrefs.readActive(f))
        f.writeText("theme=monokai\n")
        assertEquals("monokai", ThemePrefs.readActive(f))
        f.writeText("dark=true\ntheme=dracula\n")
        assertEquals("dracula", ThemePrefs.readActive(f))
        f.writeText("")
        assertNull(ThemePrefs.readActive(f))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'app.settings.ThemeStoreTest'`
Expected: 编译失败（`ThemesStore` / `readActive` 未定义）。

- [ ] **Step 3: 实现 ThemesStore 与 ThemePrefs**

创建 `src/main/kotlin/app/settings/ThemesStore.kt`：

```kotlin
package app.settings

import app.ui.ThemeColors
import app.ui.ThemeSpec
import app.ui.builtInThemes
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

    private fun color(dto: ThemeDto, key: String, fallback: androidx.compose.ui.graphics.Color) =
        dto.colors[key]?.let { ThemeColors.parse(it) } ?: fallback
}
```

> 注：`ThemesStore` 出现在 `app/settings`（AGENTS 未禁止），但它 import 了 `app.ui.ThemeColors/ThemeSpec` 与 Compose `Color`。若想保持 settings 层「不依赖 Compose」，可把 `ThemeColors`/`ThemeSpec` 移到 `app/theme/` 或 `engine`——本计划选择就近放 `app/ui`（项目已有 `app/settings` 依赖 UI 类型的先例：`EditorSettings` 被 UI 消费，但反向没有）。**执行者按此实现即可；若要更严可改为 `app/theme` 包**，见下方 Ruling。

修改 `src/main/kotlin/app/settings/ThemePrefs.kt` 为：

```kotlin
package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/** 主题偏好：当前主题 id（`<dataDir>/theme.properties`，key `theme`）。 */
object ThemePrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("theme.properties").toFile()

    fun load(): String? = readActive(prefsFile())

    fun save(id: String) {
        runCatching {
            val props = Properties()
            props.setProperty("theme", id)
            prefsFile().parentFile?.mkdirs()
            prefsFile().outputStream().use { props.store(it, "theme preference (active theme id)") }
        }
    }

    /** 纯读取 + 老档迁移：`theme` 优先；否则 `dark=true|false` → `dark`/`light`；都没有 → null。 */
    internal fun readActive(f: File): String? {
        if (!f.exists()) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("theme")?.takeIf { it.isNotBlank() }
                ?: props.getProperty("dark")?.toBooleanStrictOrNull()?.let { if (it) "dark" else "light" }
        }.getOrNull()
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests 'app.settings.ThemeStoreTest'`
Expected: PASS（5 用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/settings/ThemesStore.kt src/main/kotlin/app/settings/ThemePrefs.kt \
  src/test/kotlin/app/settings/ThemeStoreTest.kt
git commit -m "feat(theme): 自定义主题 themes.json 持久化 + theme.properties 迁移"
```

---

## Task 4: 主题状态接线 + CompositionLocal plumbing

**Files:**
- Modify: `src/main/kotlin/app/core/Main.kt`、`app/dialog/SettingsDialog.kt`、`app/ui/SqlEditorPane.kt`、`app/dialog/ViewerDialogs.kt`、`app/dialog/JsonTreeView.kt`、`app/ui/AppTheme.kt`（删 `appMaterialColors`）
- Test: `SqlHighlightTest`/`JsonSupportTest`（删过渡重载后仍绿）

**Interfaces:**
- Consumes: `ThemeSpec`/`ThemeColors`/`builtInThemes`/`defaultThemeId`/`LocalThemeColors`（Task 1）、`ThemesStore`/`ThemePrefs`（Task 3）。
- Produces: `Main` 持有 `customThemes`/`activeThemeId`/`activeTheme`/`selectTheme(id)`；`SettingsDialog(theme: ThemeSpec, …)`。

- [ ] **Step 1: 编辑器/查看器改读 LocalThemeColors**

`SqlEditorPane.kt` 的 `isDark` 删除：
```kotlin
    val themeColors = LocalThemeColors.current
    val editorStyle = TextStyle(
        fontFamily = editorFontFamily(editorSettings.fontFamilyName),
        fontSize = editorSettings.fontSizeSp.sp,
        lineHeight = EditorSettings.lineHeightSp(editorSettings.fontSizeSp).sp,
        color = themeColors.editorForeground,
    )
```
- 高亮：`sqlSyntaxPalette(themeColors)` / `jsonSyntaxPalette(themeColors)`（`remember(themeColors, …)`）。
- 行号：`gutterColor = themeColors.editorForeground.copy(alpha = 0.35f)`；`gutterCurColor = themeColors.editorForeground`；`gutterCurBg = themeColors.primary.copy(alpha = 0.16f)`。
- 表面：`.background(themeColors.editorBackground)`；边框 `themeColors.onSurface.copy(alpha = 0.18f)`。
- 光标：`cursorBrush = SolidColor(themeColors.editorForeground)`（取代 `if (isDark) White else Black`）。

`ViewerDialogs.kt` / `JsonTreeView.kt`：把 `val isDark = MaterialTheme.colors.isLight.not()` 换成 `val pal = LocalThemeColors.current` 对应的 `sqlSyntaxPalette(LocalThemeColors.current)` / `jsonSyntaxPalette(LocalThemeColors.current)`（remember 以 `LocalThemeColors.current` 为 key）。

- [ ] **Step 2: 删除过渡重载 + 更新测试**

`SqlHighlight.kt` / `JsonSupport.kt` 删除 `sqlSyntaxPalette(isDark: Boolean)` / `jsonSyntaxPalette(isDark: Boolean)` 重载。`SqlHighlightTest.kt` 第 89 行改 `val p = sqlSyntaxPalette(if (dark) themeById("dark")!!.colors else themeById("light")!!.colors)`；`JsonSupportTest.kt` 用 `themeById("light")!!.colors`。

- [ ] **Step 3: Main 主题状态 + 注入**

`Main.kt`（AppBody）：

```kotlin
    val themesStore = remember { ThemesStore() }
    var customThemes by remember { mutableStateOf(themesStore.load()) }
    var activeThemeId by remember { mutableStateOf(ThemePrefs.load() ?: defaultThemeId) }
    fun selectTheme(id: String) {
        activeThemeId = id
        ThemePrefs.save(id)
    }
```
- `val themes = builtInThemes + customThemes`；`val activeTheme = themes.firstOrNull { it.id == activeThemeId } ?: themeById(defaultThemeId)!!`
- 解析回退（Review Focus 3）：`LaunchedEffect(themes)` 若 `themes.none { it.id == activeThemeId }` → `selectTheme(defaultThemeId)`。
- 删除 `var isDark`；`MaterialTheme(colors = activeTheme.colors.toMaterialColors()) {`，并在 `CompositionLocalProvider(...)` 里加 `LocalThemeColors provides activeTheme.colors`。
- 传给 `EditorArea(isDark = !activeTheme.colors.isLight, onToggleTheme = { selectTheme(if (activeTheme.colors.isLight) "dark" else "light") })`（Task 6 换成主题下拉参数）。
- `SettingsDialog(theme = activeTheme, …)`。

`SettingsDialog.kt`：签名 `theme: ThemeSpec` 取代 `isDark: Boolean`；`MaterialTheme(colors = theme.colors.toMaterialColors())` + `CompositionLocalProvider(LocalThemeColors provides theme.colors, LocalContentColor provides MaterialTheme.colors.onSurface)`。

`app/ui/AppTheme.kt`：删除 `appMaterialColors(isDark)`（不再被引用）。

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle compileKotlin && gradle test`
Expected: PASS（全量）。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "refactor(theme): 界面/编辑器取色改为当前主题（LocalThemeColors），删 appMaterialColors/isDark"
```

---

## Task 5: 主题管理对话框（ThemeDialog）

**Files:**
- Modify: `src/main/kotlin/app/state/DialogState.kt`、`app/dialog/SettingsDialog.kt`（加「管理主题…」按钮）、`app/core/Main.kt`
- Create: `src/main/kotlin/app/dialog/ThemeDialog.kt`

**Interfaces:**
- Consumes: `themes`/`customThemes`/`activeThemeId`/`selectTheme`（Task 4）、`ThemesStore`、`Str.Theme*`。
- Produces: `@Composable fun ThemeDialog(visible, themes: List<ThemeSpec>, activeTheme: ThemeSpec, store: ThemesStore, onSelectTheme: (String) -> Unit, onCustomThemesChange: (List<ThemeSpec>) -> Unit, onDismiss: () -> Unit)`；`DialogState.showThemeDialog: Boolean`。

- [ ] **Step 1: DialogState + 入口**

`DialogState.kt`：加 `var showThemeDialog by mutableStateOf(false)`。
`SettingsDialog` 的 `GeneralSection` 底部（诊断上方）加一个 `TextButton(onClick = onManageThemes) { Text(t(Str.ThemeManage)) }`，`SettingsDialog` 增参 `onManageThemes: () -> Unit`，Main 传 `{ dialogState.showThemeDialog = true }`。

- [ ] **Step 2: 实现 ThemeDialog**

创建 `app/dialog/ThemeDialog.kt`（`DialogWindow` 760×560，自带 `MaterialTheme(activeTheme.colors.toMaterialColors())` + `LocalThemeColors` + `LocalContentColor`；`ProvideI18n`）：

- 左列（180dp）：`themes` 列表（内置在前、自定义在后；当前项高亮），底部「新建（克隆当前）…」。
- 右列：若选中主题 `builtIn` → 11 个字段只读 + 提示 `Str.ThemeBuiltInReadOnly`；否则 11 个 `OutlinedTextField`（`#RRGGBB`，`submitOnEnter` 不需要——非单行提交；非法输入红字 `Str.ThemeColorInvalid` 且不写入草稿）。
- 底部：`保存`（仅自定义；写 `store.save(newList)` + `onCustomThemesChange`）、`另存为新主题`（弹单字段名字 → 以当前草稿克隆为 `builtIn=false` 新主题、`baseId=当前 id`）、`重命名`、`删除`（确认框；删当前 → `selectTheme(defaultThemeId)`）。
- 预览：一段示例 SQL + 一行普通文字，用**草稿色板**渲染（`sqlSyntaxPalette(draft)`）。
- 应用时机：左列点选 → `onSelectTheme(id)`（立即切主窗口）；色值编辑只更新对话框内草稿与预览；`保存` 才落盘。

- [ ] **Step 3: Main 渲染**

`Main.kt` 的 `DialogHost` / AppBody 弹窗区渲染：
```kotlin
        ThemeDialog(
            visible = dialogState.showThemeDialog,
            themes = themes,
            activeTheme = activeTheme,
            store = themesStore,
            onSelectTheme = ::selectTheme,
            onCustomThemesChange = { customThemes = it },
            onDismiss = { dialogState.showThemeDialog = false },
        )
```
（`themesStore`/`themes`/`selectTheme`/`customThemes` 从 AppBody 作用域传入；若 `DialogHost` 不便访问，则在 AppBody 的弹窗渲染区直接调用。）

- [ ] **Step 4: 编译确认**

Run: `gradle compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(theme): 主题管理对话框（内置只读 + 另存为自定义 + 保存/重命名/删除）"
```

---

## Task 6: 顶栏主题下拉（替换 Moon/Sun toggle）

**Files:**
- Modify: `src/main/kotlin/app/ui/DbIcons.kt`、`app/ui/ConsoleHeaderBar.kt`、`app/ui/EditorArea.kt`、`app/core/Main.kt`

**Interfaces:**
- Produces: `DbIcons.Palette`；`HeaderBar`/`EditorArea` 参数 `themes: List<ThemeSpec>`、`activeTheme: ThemeSpec`、`onSelectTheme: (String) -> Unit`、`onManageThemes: () -> Unit`（取代 `isDark`/`onToggleTheme`）。

- [ ] **Step 1: 图标**

`DbIcons.kt` 加：
```kotlin
    /** 调色板图标（主题下拉）。 */
    val Palette: ImageVector by lazy {
        vector(
            "palette",
            "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5S18.33 12 17.5 12z",
        )
    }
```

- [ ] **Step 2: HeaderBar 下拉**

`ConsoleHeaderBar.kt`：删除 `isDark`/`onToggleTheme`，新增 `themes`/`activeTheme`/`onSelectTheme`/`onManageThemes`；把原 IconButton 换成：

```kotlin
                Box {
                    var themeMenuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { themeMenuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = DbIcons.Palette,
                            contentDescription = t(Str.ThemeMenuTooltip),
                            tint = topBarIconTint,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                        themes.forEach { th ->
                            val label = th.nameKey?.let { t(it) } ?: th.name
                            DropdownMenuItem(onClick = { themeMenuOpen = false; onSelectTheme(th.id) }) {
                                Icon(
                                    Icons.Filled.Check, null,
                                    tint = if (th.id == activeTheme.id) MaterialTheme.colors.primary else Color.Transparent,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(label, fontSize = 13.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                                    modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
                        DropdownMenuItem(onClick = { themeMenuOpen = false; onManageThemes() }) {
                            Text(t(Str.ThemeManage), fontSize = 13.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f))
                        }
                    }
                }
```
（需 `androidx.compose.runtime.*`、`material.Divider`、`material.DropdownMenu*`、`icons.filled.Check`、`db` 无关，`app.ui.ThemeSpec` 同包。）

- [ ] **Step 3: EditorArea 透传 + Main 接线**

`EditorArea.kt`：`isDark`/`onToggleTheme` → `themes`/`activeTheme`/`onSelectTheme`/`onManageThemes`，透传给 `HeaderBar`。
`Main.kt`：`EditorArea(themes = themes, activeTheme = activeTheme, onSelectTheme = ::selectTheme, onManageThemes = { dialogState.showThemeDialog = true }, …)`。

- [ ] **Step 4: 编译 + 测试**

Run: `gradle compileKotlin && gradle test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(theme): 顶栏 Moon/Sun toggle 改为主题下拉（切换 + 管理主题）"
```

---

## Task 7: 端到端自检与人工验证

- [ ] **Step 1: 全量自检**

```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH=/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$JAVA_HOME/bin:$PATH
gradle compileKotlin && gradle test && gradle smokeJdbc
grep -rn "Color.Black\|Color.White" src/main/kotlin --include=*.kt | grep -v "AppTheme\|AppThemes"
```
Expected: 全绿；grep 只剩既有色板/图标 fill/编辑器光标（无新增）。

- [ ] **Step 2: 人工验证（交给用户，不自跑 GUI）**

1. 顶栏调色板下拉切换 Dark/Light/Monokai/Dracula/Sublime → 树/标签/结果/弹窗整体变色；重启保持。
2. 中英切换：Dark/Light 显示「深色/浅色」，Monokai/Dracula/Sublime 名不变。
3. Monokai 下编辑器：背景/正文/关键字/字符串/注释可读；行号/选区/光标可见；DDL 查看器与 JSON 树用同主题语法色。
4. 管理主题：内置字段只读 + 提示；「另存为新主题」→ 命名 → 成为当前；改色→保存→切走切回仍在；重启仍在。
5. 自定义主题重命名/删除；删当前 → 回退深色/浅色。
6. 手工破坏 `themes.json` → 启动不崩、无自定义主题、日志一条 error。
7. 5 主题 × 深/浅界面下检查弹窗/下拉/输入框：无黑字沉底、无过曝白块；语义色（状态点/徽章）仍可辨。

---

## Self-Review 记录

- **Spec 覆盖**：决策 1-9 → Task 1（模型/内置/映射/色板）、Task 2（i18n）、Task 3（themes.json + theme.properties 迁移）、Task 4（全主题接线 + LocalThemeColors）、Task 5（ThemeDialog 另存/只读/CRUD）、Task 6（顶栏下拉）。
- **Review Focus 归属**：①→Task 3 `corrupt file`；②→Task 3 `invalid color falls back`；③→Task 4 Step 3 回退 LaunchedEffect（+ 人工步骤 5）；④→Task 5 Step 2（内置只读、保存只能产出自定义）+ Task 3 `save` 过滤 builtIn；⑤→Task 3 `theme prefs migrate`。
- **类型一致性**：`ThemeColors` 字段名（`editorBackground`/`editorForeground`/`onSurface`…）在 Task 1/3/4/5 一致；`ThemeSpec.baseId` 在 Task 1 定义、Task 3 读写、Task 5 克隆时写入。
- **已知偏差（Ruling，见 ledger）**：`ThemesStore` 放在 `app/settings` 但 import `app/ui` 的 `ThemeColors`（Compose 类型）。若要求 `settings` 不依赖 Compose，可改包名 `app/theme`；本计划选定就近实现。
- **JSON 语法键色偏差**：`jsonSyntaxPalette` 的 `key/boolean/null` 改为取主题 `keyword`（原 Dark 用 `#9CDCFE`、Light 用 `#0451A5`）——Dark/Light 的 JSON 属性名色会略变（同为蓝色系）。这是「11 项 curated」的必然取舍，已在 spec 决策 3 覆盖。
