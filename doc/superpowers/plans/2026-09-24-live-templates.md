# Live Templates（SQL 片段模板）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 SQL 编辑器里实现 DataGrip 风格的 Live Templates：缩写 + `Tab` 展开成 SQL 骨架，`Tab`/`Shift+Tab` 在 `$占位符$` 间跳转，`Esc` 退出；内置一批模板，用户可通过 `<dataDir>/live-templates.json` 覆盖/追加。

**Architecture:** 一个与 Compose 解耦的纯逻辑模块 `app/ui/LiveTemplate.kt`（解析 `$NAME$`/`$END$`、建会话、导航、按 diff 平移占位区间）；一个持久化模块 `app/settings/LiveTemplatesStore.kt`（JSON 叠加内置）；编辑器 `SqlEditorPane` 挂 Tab 分支与会话状态、复用 `outputTransformation` 高亮活动占位符；补全层把模板作为 `CompletionKind.TEMPLATE` 候选列出。

**Tech Stack:** Kotlin 2.x / Compose Desktop 1.12.0（Material2）/ kotlinx.serialization / JUnit5 + kotlin.test / Gradle 9.4.1 + JDK 25。

**Spec:** `doc/superpowers/specs/2026-09-24-live-templates-design.md`

## Global Constraints

- 构建前必须：`export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`、`export PATH="$JAVA_HOME/bin:/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$PATH"`；不引入 Gradle wrapper。
- `app/ui/LiveTemplate.kt` 是**纯逻辑叶模块**：禁止 import `androidx.compose.*` / coroutines；便于单测。
- `app/settings/LiveTemplatesStore.kt` 禁止 import compose/coroutines。
- 新增用户可见文案**必须**同时改 `Str.kt` + `CatalogZh.kt` + `CatalogEn.kt`；`I18nCatalogTest` 会校验占位符一致与非空，漏一种语言编译不过。
- 主题：可见文字一律 `MaterialTheme.colors.onSurface`（或 `.copy(alpha=…)`）；新增颜色只允许语义色 / 色点背景；背景走 `background`/`surface`。
- Kotlin 字符串里的 `$` 是模板入口：模板 body 一律写 `\$NAME\$` 转义。
- UI 行为**不做自动化验证**：只给人工验收步骤；程序化可验部分跑 `gradle compileKotlin` / `gradle test` / `gradle smokeJdbc`。
- 提交信息中文、conventional commits 前缀。
- 全部任务完成后 `gradle test` + `gradle smokeJdbc` 必须通过。

## Review Focus

- **占位符区间在编辑后漂移**：在 stop 前/内/后插入删除、整选区替换、undo 后，后续 stop 的跳转位置必须仍精确（Task 4 表驱动测试）。
- **畸形模板体**：未闭合 `$`、`$A$B$`、空 `$NAME$`、`$$`、无占位符、重复名都不许抛异常或崩（Task 2 测试）。
- **缩写 vs 补全冲突**：输入 `sel` 时模板候选与关键词 `SELECT` 同时存在且互不吞没；缩写字面敲全后模板候选仍在（Task 6 测试）。
- **Tab 误触发边界**：字符串/注释内、有选区、多光标模式下 Tab 不得展开模板（既有 `SqlEditingTest` 的 `sqlCompletionWord` 字符串/注释用例 + Task 10 人工验收）。
- **模板文件损坏**：`live-templates.json` 缺失/非法 JSON/未知字段/未知 context 一律回落内置而不崩（Task 5 测试）。

---

### Task 1: i18n 文案（zh + en）

**Files:**
- Modify: `src/main/kotlin/i18n/Str.kt`（枚举追加 key）
- Modify: `src/main/kotlin/i18n/CatalogZh.kt`
- Modify: `src/main/kotlin/i18n/CatalogEn.kt`
- Test: `src/test/kotlin/i18n/I18nCatalogTest.kt`（既有，自动覆盖）

**Interfaces:**
- Consumes: 无
- Produces: `Str.EditorCompletionTemplate`、`Str.SettingsLiveTemplates`、`Str.SettingsLiveTemplatesCreate`

- [ ] **Step 1: 加枚举 key**

在 `Str.kt` 的 `EditorCompletionExpandColumns,` 之后插入：

```kotlin
    EditorCompletionTemplate,
```

在 `SettingsOpenDataDir,` 之后插入：

```kotlin
    SettingsLiveTemplates,
    SettingsLiveTemplatesCreate,
```

- [ ] **Step 2: 写 zh catalog**

`CatalogZh.kt` 中 `Str.EditorCompletionExpandColumns -> "展开为 {0} 列"` 一行之后加：

```kotlin
    Str.EditorCompletionTemplate -> "模板"
```

`Str.SettingsOpenDataDir -> "打开数据目录"` 一行之后加：

```kotlin
    Str.SettingsLiveTemplates -> "Live Templates"
    Str.SettingsLiveTemplatesCreate -> "生成示例并打开目录"
```

- [ ] **Step 3: 写 en catalog**

`CatalogEn.kt` 中 `Str.EditorCompletionExpandColumns -> "Expand to {0} columns"` 之后加：

```kotlin
    Str.EditorCompletionTemplate -> "Template"
```

`Str.SettingsOpenDataDir -> "Open data directory"` 之后加：

```kotlin
    Str.SettingsLiveTemplates -> "Live Templates"
    Str.SettingsLiveTemplatesCreate -> "Create sample and open folder"
```

- [ ] **Step 4: 跑 i18n 测试**

Run: `gradle test --tests "i18n.I18nCatalogTest"`
Expected: PASS（zh/en 均覆盖新 key 且非空）

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/i18n/Str.kt src/main/kotlin/i18n/CatalogZh.kt src/main/kotlin/i18n/CatalogEn.kt
git commit -m "feat(live-template): i18n 文案（zh/en）"
```

---

### Task 2: `LiveTemplate.kt` — 解析 `expandTemplate` + 内置模板

**Files:**
- Create: `src/main/kotlin/app/ui/LiveTemplate.kt`
- Test: `src/test/kotlin/app/ui/LiveTemplateTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class LiveTemplateContext(val token: String) { SQL("sql") }`
  - `data class LiveTemplate(abbreviation: String, body: String, description: String? = null, context: LiveTemplateContext = SQL)`
  - `data class TemplateStop(name: String, start: Int, end: Int)`（`end` 为 exclusive）
  - `data class TemplateExpansion(text: String, stops: List<TemplateStop>, endOffset: Int)`
  - `fun expandTemplate(body: String): TemplateExpansion`
  - `fun defaultLiveTemplates(): List<LiveTemplate>`

- [ ] **Step 1: 写失败测试**

创建 `src/test/kotlin/app/ui/LiveTemplateTest.kt`：

```kotlin
package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTemplateExpansionTest {

    @Test
    fun `placeholder becomes empty with a stop`() {
        val e = expandTemplate("SELECT \$columns\$ FROM \$table\$")
        assertEquals("SELECT  FROM ", e.text)
        assertEquals(listOf(TemplateStop("columns", 7, 7), TemplateStop("table", 13, 13)), e.stops)
        assertEquals(13, e.endOffset)
    }

    @Test
    fun `END sets final offset and is not a stop`() {
        val e = expandTemplate("A\$END\$B")
        assertEquals("AB", e.text)
        assertEquals(emptyList<TemplateStop>(), e.stops)
        assertEquals(1, e.endOffset)
    }

    @Test
    fun `missing END defaults to text end`() {
        val e = expandTemplate("SELECT 1")
        assertEquals("SELECT 1", e.text)
        assertEquals(emptyList<TemplateStop>(), e.stops)
        assertEquals(8, e.endOffset)
    }

    @Test
    fun `double dollar is a literal dollar`() {
        assertEquals("a\$b", expandTemplate("a\$\$b").text)
    }

    @Test
    fun `unclosed markers stay literal and well-formed placeholder is removed`() {
        assertEquals("a\$b", expandTemplate("a\$b").text)
        assertEquals("B\$", expandTemplate("\$A\$B\$").text)
        assertEquals("", expandTemplate("\$A\$").text)
    }

    @Test
    fun `repeated names are separate stops`() {
        val e = expandTemplate("\$a\$-\$a\$")
        assertEquals("-", e.text)
        assertEquals(listOf(TemplateStop("a", 0, 0), TemplateStop("a", 1, 1)), e.stops)
    }

    @Test
    fun `builtins expose the documented abbreviations`() {
        val abbrevs = defaultLiveTemplates().map { it.abbreviation }
        assertEquals(
            listOf("sel", "selw", "selc", "ins", "upd", "del", "whe", "ob", "gb", "case"),
            abbrevs,
        )
        assertEquals("UPDATE \$table\$ SET \$column\$ = \$value\$ WHERE \$condition\$", defaultLiveTemplates()[4].body)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests "app.ui.LiveTemplateExpansionTest"`
Expected: FAIL（`unresolved reference: expandTemplate`）

- [ ] **Step 3: 实现**

创建 `src/main/kotlin/app/ui/LiveTemplate.kt`：

```kotlin
package app.ui

/*
 * Live Templates 纯逻辑（无 Compose / 无 IO 依赖，便于单测）。
 * 只负责：解析模板体 → 展开文本 + 占位符；内置模板表。（会话/导航/区间平移见同文件后续任务。）
 */

/** 模板生效范围；首版只有 SQL，字段预留以便后续扩展（Redis 命令 / JSON DSL）。 */
enum class LiveTemplateContext(val token: String) { SQL("sql") }

/** 一条活模板：缩写 + 模板体（含 `$NAME$` / `$END$`）。 */
data class LiveTemplate(
    val abbreviation: String,
    val body: String,
    val description: String? = null,
    val context: LiveTemplateContext = LiveTemplateContext.SQL,
)

/** 命名占位符在展开文本中的区间；`end` 为 exclusive，空占位符时 `start == end`。 */
data class TemplateStop(val name: String, val start: Int, val end: Int)

/** 展开结果：去标记文本、命名的 Tab stop（按 start 升序）、`$END$` 落点。 */
data class TemplateExpansion(val text: String, val stops: List<TemplateStop>, val endOffset: Int)

private fun isPlaceholderName(name: String): Boolean =
    name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }

/**
 * 解析模板体：
 * - `$NAME$` → 空占位符（Tab stop）；
 * - `$END$` → 最终光标位（不入 stops）；
 * - `$$` → 字面量 `$`；
 * - 未闭合或名字非法的 `$` → 当字面量处理（不抛异常）。
 * 无 `$END$` 时 endOffset = 文本末尾。
 */
fun expandTemplate(body: String): TemplateExpansion {
    val sb = StringBuilder()
    val stops = ArrayList<TemplateStop>()
    var endOffset = -1
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '$') {
            sb.append(c); i++; continue
        }
        if (body.getOrNull(i + 1) == '$') {
            sb.append('$'); i += 2; continue
        }
        val close = body.indexOf('$', i + 1)
        val name = if (close < 0) null else body.substring(i + 1, close)
        if (name == null || !isPlaceholderName(name)) {
            sb.append('$'); i++; continue
        }
        if (name.equals("END", ignoreCase = true)) {
            endOffset = sb.length
        } else {
            stops.add(TemplateStop(name, sb.length, sb.length))
        }
        i = close + 1
    }
    if (endOffset < 0) endOffset = sb.length
    return TemplateExpansion(sb.toString(), stops, endOffset)
}

/** 内置模板（对齐 DataGrip SQL 默认 + db-k 常用）。注意 Kotlin 字符串里 `$` 必须转义。 */
fun defaultLiveTemplates(): List<LiveTemplate> = listOf(
    LiveTemplate("sel", "SELECT \$columns\$ FROM \$table\$"),
    LiveTemplate("selw", "SELECT \$columns\$ FROM \$table\$ WHERE \$condition\$"),
    LiveTemplate("selc", "SELECT count(*) FROM \$table\$ WHERE \$condition\$"),
    LiveTemplate("ins", "INSERT INTO \$table\$ (\$columns\$) VALUES (\$values\$)"),
    LiveTemplate("upd", "UPDATE \$table\$ SET \$column\$ = \$value\$ WHERE \$condition\$"),
    LiveTemplate("del", "DELETE FROM \$table\$ WHERE \$condition\$"),
    LiveTemplate("whe", "WHERE \$condition\$"),
    LiveTemplate("ob", "ORDER BY \$column\$"),
    LiveTemplate("gb", "GROUP BY \$column\$"),
    LiveTemplate("case", "CASE WHEN \$condition\$ THEN \$result\$ ELSE \$result\$ END"),
)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests "app.ui.LiveTemplateExpansionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/app/ui/LiveTemplate.kt src/test/kotlin/app/ui/LiveTemplateTest.kt
git commit -m "feat(live-template): 模板解析与内置模板表"
```

---

### Task 3: 会话与占位符导航（`beginSession` / `activeStop` / `sessionNext` / `sessionPrev`）

**Files:**
- Modify: `src/main/kotlin/app/ui/LiveTemplate.kt`
- Test: `src/test/kotlin/app/ui/LiveTemplateTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `TemplateStop` / `TemplateExpansion` / `expandTemplate`
- Produces:
  - `data class TemplateSession(lastText: String, anchorStart: Int, stops: List<TemplateStop>, activeIndex: Int, endOffset: Int)`
  - `fun beginSession(doc: String, replaceRange: IntRange, body: String): Pair<String, TemplateSession>`
  - `fun activeStop(session: TemplateSession): TemplateStop?`
  - `fun sessionNext(session: TemplateSession): Pair<TemplateSession?, Pair<Int, Int>>`（Pair 第二个 = selection `(start, endExclusive)`）
  - `fun sessionPrev(session: TemplateSession): Pair<TemplateSession, Pair<Int, Int>>`

- [ ] **Step 1: 写失败测试**

在 `LiveTemplateTest.kt` 里追加：

```kotlin
class LiveTemplateSessionTest {

    private fun session() = beginSession("sel x", 0 until 3, "SELECT \$c\$ FROM \$t\$").second

    @Test
    fun `beginSession replaces the word and selects first stop`() {
        val (text, s) = beginSession("sel x", 0 until 3, "SELECT \$c\$ FROM \$t\$")
        assertEquals("SELECT  FROM  x", text)
        assertEquals(0, s.anchorStart)
        assertEquals(listOf(TemplateStop("c", 7, 7), TemplateStop("t", 13, 13)), s.stops)
        assertEquals(0, s.activeIndex)
        assertEquals(13, s.endOffset)
        assertEquals(TemplateStop("c", 7, 7), activeStop(s))
    }

    @Test
    fun `beginSession with no stops has no active stop`() {
        val (_, s) = beginSession("sel", 0 until 3, "SELECT 1")
        assertEquals(emptyList<TemplateStop>(), s.stops)
        assertEquals(-1, s.activeIndex)
        assertEquals(null, activeStop(s))
    }

    @Test
    fun `sessionNext walks stops then ends at endOffset`() {
        val s = session()
        val (n1, sel1) = sessionNext(s)
        assertEquals(1, n1!!.activeIndex)
        assertEquals(13 to 13, sel1)
        val (n2, sel2) = sessionNext(n1)
        assertEquals(null, n2)
        assertEquals(13 to 13, sel2)
    }

    @Test
    fun `sessionPrev walks back and clamps at first`() {
        val s = session()
        val (a, selA) = sessionPrev(s)
        assertEquals(0, a.activeIndex)
        assertEquals(7 to 7, selA)
        val (n1, _) = sessionNext(s)
        val (b, selB) = sessionPrev(n1!!)
        assertEquals(0, b.activeIndex)
        assertEquals(7 to 7, selB)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests "app.ui.LiveTemplateSessionTest"`
Expected: FAIL（`unresolved reference: beginSession`）

- [ ] **Step 3: 实现**

在 `LiveTemplate.kt` 末尾追加：

```kotlin
/**
 * 一次模板展开会话：`lastText` 为上次文本快照（供 [reconcileSession] 求 diff），
 * `anchorStart` 是整段插入文本的文档起点，`stops`/`endOffset` 均为文档坐标。
 */
data class TemplateSession(
    val lastText: String,
    val anchorStart: Int,
    val stops: List<TemplateStop>,
    val activeIndex: Int,
    val endOffset: Int,
)

/**
 * 用 [body] 展开替换 [doc] 的 [replaceRange]（Kotlin 闭区间；空区间即插入），
 * 返回新文本与会话。命中第一个占位符时会话可直接导航；无占位符时 `activeIndex = -1`，
 * 调用方应只落光标到 `endOffset`、不保留会话。
 */
fun beginSession(doc: String, replaceRange: IntRange, body: String): Pair<String, TemplateSession> {
    val exp = expandTemplate(body)
    val from = replaceRange.first.coerceIn(0, doc.length)
    val to = (replaceRange.last + 1).coerceIn(from, doc.length)
    val newText = doc.substring(0, from) + exp.text + doc.substring(to)
    val session = TemplateSession(
        lastText = newText,
        anchorStart = from,
        stops = exp.stops.map { TemplateStop(it.name, it.start + from, it.end + from) },
        activeIndex = if (exp.stops.isEmpty()) -1 else 0,
        endOffset = exp.endOffset + from,
    )
    return newText to session
}

/** 当前活动占位符；无（空模板）返回 null。 */
fun activeStop(session: TemplateSession): TemplateStop? = session.stops.getOrNull(session.activeIndex)

/** 下一个占位符；已越过最后一个 → 返回 `(null, endOffset..endOffset)`，调用方结束会话。 */
fun sessionNext(session: TemplateSession): Pair<TemplateSession?, Pair<Int, Int>> {
    val next = session.activeIndex + 1
    return if (next in session.stops.indices) {
        val s = session.stops[next]
        session.copy(activeIndex = next) to (s.start to s.end)
    } else {
        null to (session.endOffset to session.endOffset)
    }
}

/** 上一个占位符；已在第一个或空模板 → 原地返回（selection 仍是当前 stop 或 endOffset）。 */
fun sessionPrev(session: TemplateSession): Pair<TemplateSession, Pair<Int, Int>> {
    if (session.stops.isEmpty()) return session to (session.endOffset to session.endOffset)
    val prev = (session.activeIndex - 1).coerceAtLeast(0)
    val s = session.stops[prev]
    return session.copy(activeIndex = prev) to (s.start to s.end)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests "app.ui.LiveTemplateSessionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/app/ui/LiveTemplate.kt src/test/kotlin/app/ui/LiveTemplateTest.kt
git commit -m "feat(live-template): 展开会话与占位符导航"
```

---

### Task 4: `reconcileSession` — 编辑后平移占位区间

**Files:**
- Modify: `src/main/kotlin/app/ui/LiveTemplate.kt`
- Test: `src/test/kotlin/app/ui/LiveTemplateTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `TemplateSession`
- Produces: `fun reconcileSession(session: TemplateSession, newText: String): TemplateSession?`（null = 会话失效）

- [ ] **Step 1: 写失败测试**

在 `LiveTemplateTest.kt` 里追加：

```kotlin
class LiveTemplateReconcileTest {

    /** 以 "sel" 展开 `SELECT $columns$ FROM $table$` 为基准：columns=[7,7], table=[13,13] */
    private fun base() = beginSession("sel", 0 until 3, "SELECT \$columns\$ FROM \$table\$").second

    @Test
    fun `unchanged text keeps session`() {
        val s = base()
        assertEquals(s, reconcileSession(s, s.lastText))
    }

    @Test
    fun `typing into a collapsed placeholder grows it and shifts later stops`() {
        val s = reconcileSession(base(), "SELECT a FROM ")!!
        assertEquals(7 to 8, activeStop(s)!!.let { it.start to it.end })
        assertEquals(14, s.stops[1].start)
        assertEquals(14, s.endOffset)
    }

    @Test
    fun `deleting inside placeholder shrinks it`() {
        val s1 = reconcileSession(base(), "SELECT a FROM ")!!
        val s2 = reconcileSession(s1, "SELECT  FROM ")!!
        assertEquals(7 to 7, activeStop(s2)!!.let { it.start to it.end })
        assertEquals(13, s2.stops[1].start)
        assertEquals(13, s2.endOffset)
    }

    @Test
    fun `edit before template invalidates session`() {
        // anchorStart=2 时删掉锚点前的空格：公共前缀 p=1 < 2
        val s = beginSession("x sel", 2 until 5, "SELECT \$c\$").second
        assertEquals(null, reconcileSession(s, "xSELECT "))
    }

    @Test
    fun `select-all replace of a stop keeps valid ranges`() {
        val s1 = reconcileSession(base(), "SELECT abc FROM ")!!
        // 选中 columns 占位 [7,10) 换成 "xy"
        val s2 = reconcileSession(s1, "SELECT xy FROM ")!!
        assertEquals(7 to 9, activeStop(s2)!!.let { it.start to it.end })
        assertEquals(15, s2.stops[1].start)
    }

    @Test
    fun `editing at or after stops keeps ranges consistent`() {
        val s1 = reconcileSession(base(), "SELECT a FROM ")!!
        // 在末尾（恰好是 table 空占位处）追加 " t"
        val s2 = reconcileSession(s1, "SELECT a FROM  t")!!
        assertEquals(7 to 8, s2.stops[0].let { it.start to it.end })
        assertEquals(14, s2.stops[1].start)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests "app.ui.LiveTemplateReconcileTest"`
Expected: FAIL（`unresolved reference: reconcileSession`）

- [ ] **Step 3: 实现**

在 `LiveTemplate.kt` 末尾追加：

```kotlin
/**
 * 文本变化后重算占位区间：对 old/new 求最长公共前缀 `p` 与最长公共后缀 `s`，得到改动区间
 * `[p, old.length - s)` 与长度差 `delta`，把停靠点边界按 [mapBoundary] 映射。返回 null 表示
 * 会话失效（模板锚点之前被删、或区间非法），调用方应清空会话。
 *
 * 边界映射约定：位于改动区间之前的原样保留；位于之后的整体平移；恰好落在区间边界时，
 * 起点贴到 `p`、终点贴到改动后的区间末尾——这样「在空占位符处输入」表现为占位符增长。
 */
fun reconcileSession(session: TemplateSession, newText: String): TemplateSession? {
    val old = session.lastText
    if (newText == old) return session

    var p = 0
    val maxP = minOf(old.length, newText.length)
    while (p < maxP && old[p] == newText[p]) p++

    var s = 0
    val maxS = minOf(old.length - p, newText.length - p)
    while (s < maxS && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++

    if (p < session.anchorStart) return null

    val oldEditEnd = old.length - s
    val delta = newText.length - old.length
    val newEditEnd = oldEditEnd + delta

    fun mapBoundary(pos: Int, isEnd: Boolean): Int = when {
        pos < p -> pos
        pos > oldEditEnd -> pos + delta
        isEnd -> (pos + delta).coerceIn(p, maxOf(p, newEditEnd))
        else -> p
    }

    val stops = session.stops.map { st ->
        val ns = mapBoundary(st.start, isEnd = false)
        val ne = mapBoundary(st.end, isEnd = true).coerceAtLeast(ns)
        TemplateStop(st.name, ns, ne)
    }
    if (stops.any { it.start < session.anchorStart || it.end < it.start }) return null
    return session.copy(
        lastText = newText,
        stops = stops,
        endOffset = mapBoundary(session.endOffset, isEnd = true).coerceAtLeast(session.anchorStart),
    )
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests "app.ui.LiveTemplateReconcileTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/app/ui/LiveTemplate.kt src/test/kotlin/app/ui/LiveTemplateTest.kt
git commit -m "feat(live-template): 编辑后按 diff 平移占位符区间"
```

---

### Task 5: `LiveTemplatesStore.kt` — 内置 + 用户文件叠加

**Files:**
- Create: `src/main/kotlin/app/settings/LiveTemplatesStore.kt`
- Test: `src/test/kotlin/app/settings/LiveTemplatesStoreTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `LiveTemplate` / `defaultLiveTemplates`
- Produces:
  - `object LiveTemplatesStore { fun templateFile(): File; fun load(): List<LiveTemplate>; internal fun load(f: File): List<LiveTemplate>; internal fun createSample(f: File) }`
  - `internal fun overlayTemplates(builtins: List<LiveTemplate>, user: List<LiveTemplate>): List<LiveTemplate>`

- [ ] **Step 1: 写失败测试**

创建 `src/test/kotlin/app/settings/LiveTemplatesStoreTest.kt`：

```kotlin
package app.settings

import app.ui.LiveTemplate
import app.ui.LiveTemplateContext
import app.ui.defaultLiveTemplates
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTemplatesStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun file() = dir.resolve("live-templates.json").toFile()

    @Test
    fun `missing file yields builtins`() {
        assertEquals(defaultLiveTemplates(), LiveTemplatesStore.load(file()))
    }

    @Test
    fun `user entry overrides builtin by abbreviation`() {
        file().writeText(
            """{"fileVersion":1,"templates":[{"abbreviation":"SEL","body":"SELECT 1","description":"mine"}]}""",
        )
        val loaded = LiveTemplatesStore.load(file())
        val sel = loaded.first { it.abbreviation.equals("sel", ignoreCase = true) }
        assertEquals("SELECT 1", sel.body)
        assertEquals("mine", sel.description)
        // 数量不变（同名覆盖，不追加）
        assertEquals(defaultLiveTemplates().size, loaded.size)
    }

    @Test
    fun `user entry appends unknown abbreviation`() {
        file().writeText("""{"templates":[{"abbreviation":"zzz","body":"SELECT \$x\$"}]}""")
        val loaded = LiveTemplatesStore.load(file())
        assertTrue(loaded.any { it.abbreviation == "zzz" })
        assertEquals(defaultLiveTemplates().size + 1, loaded.size)
    }

    @Test
    fun `corrupt json falls back to builtins`() {
        file().writeText("{ not json")
        assertEquals(defaultLiveTemplates(), LiveTemplatesStore.load(file()))
    }

    @Test
    fun `blank fields and unknown context are skipped`() {
        file().writeText(
            """{"templates":[
              {"abbreviation":"","body":"SELECT 1"},
              {"abbreviation":"a","body":""},
              {"abbreviation":"b","body":"SELECT 1","context":"redis"}
            ]}""",
        )
        assertEquals(defaultLiveTemplates(), LiveTemplatesStore.load(file()))
    }

    @Test
    fun `createSample writes a loadable file once`() {
        val f = file()
        LiveTemplatesStore.createSample(f)
        assertTrue(f.exists())
        val loaded = LiveTemplatesStore.load(f)
        assertTrue(loaded.any { it.abbreviation == "sel2" })
        // 已存在时不覆盖
        LiveTemplatesStore.createSample(f)
        assertEquals(loaded, LiveTemplatesStore.load(f))
    }

    @Test
    fun `overlay keeps builtin order and appends user entries`() {
        val out = overlayTemplates(
            listOf(LiveTemplate("a", "A"), LiveTemplate("b", "B")),
            listOf(LiveTemplate("b", "B2", context = LiveTemplateContext.SQL), LiveTemplate("c", "C")),
        )
        assertEquals(listOf("a", "b", "c"), out.map { it.abbreviation })
        assertEquals("B2", out[1].body)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests "app.settings.LiveTemplatesStoreTest"`
Expected: FAIL（`unresolved reference: LiveTemplatesStore`）

- [ ] **Step 3: 实现**

创建 `src/main/kotlin/app/settings/LiveTemplatesStore.kt`：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests "app.settings.LiveTemplatesStoreTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/app/settings/LiveTemplatesStore.kt src/test/kotlin/app/settings/LiveTemplatesStoreTest.kt
git commit -m "feat(live-template): 用户模板文件叠加内置"
```

---

### Task 6: 补全集成（`CompletionKind.TEMPLATE` + 弹层语义色 + `completionItems`）

> 说明：`CompletionPopup.completionKindColor` 是对 `CompletionKind` 的**穷尽 `when`**，
> 新增枚举值必须与颜色分支同一提交落地，否则主源码编译不过。

**Files:**
- Modify: `src/main/kotlin/app/ui/SqlEditing.kt`
- Modify: `src/main/kotlin/app/ui/CompletionPopup.kt`
- Test: `src/test/kotlin/app/SqlEditingTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `LiveTemplate` / `expandTemplate`
- Produces:
  - `CompletionKind.TEMPLATE`
  - `fun completionItems(word: String, expands: …, templates: List<LiveTemplate> = emptyList(), columns: …, …)`

- [ ] **Step 1: 写失败测试**

在**既有** `src/test/kotlin/app/SqlEditingTest.kt`（package `app.ui`，class `SqlEditingTest`）里追加两个方法；`CompletionKind`/`LiveTemplate`/`completionItems` 与之同包，无需新 import，`assertTrue` 已在既有 import 中：

```kotlin
    @Test
    fun `template candidate shows on prefix and on exact abbreviation`() {
        val templates = listOf(LiveTemplate("sel", "SELECT \$c\$ FROM \$t\$", "my select"))
        val byPrefix = completionItems(word = "se", templates = templates)
        assertEquals(CompletionKind.TEMPLATE, byPrefix.first { it.text == "sel" }.kind)
        // 缩写字面敲全后模板仍在（区别于其它候选「已输完整即隐藏」）
        val exact = completionItems(word = "sel", templates = templates)
        assertTrue(exact.any { it.text == "sel" && it.kind == CompletionKind.TEMPLATE })
        // 关键词 SELECT 同时仍在
        assertTrue(exact.any { it.text == "SELECT" })
    }

    @Test
    fun `template insertText is expanded without markers`() {
        val templates = listOf(LiveTemplate("sel", "SELECT \$c\$ FROM \$t\$"))
        val item = completionItems(word = "sel", templates = templates).first { it.kind == CompletionKind.TEMPLATE }
        assertEquals("SELECT  FROM ", item.insertText)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests "app.ui.SqlEditingTest"`
Expected: FAIL（`unresolved reference: TEMPLATE` / 无 `templates` 参数）

- [ ] **Step 3: 实现**

`SqlEditing.kt`：

1) `enum class CompletionKind { COLUMN, ALIAS, FUNCTION, TABLE, KEYWORD, EXPAND, TEMPLATE }`

2) `completionItems` 签名加参数（放在 `expands` 之后）并在早退守卫里加 `templates`：

```kotlin
fun completionItems(
    word: String,
    expands: List<CompletionItem> = emptyList(),
    templates: List<LiveTemplate> = emptyList(),
    columns: List<CompletionItem> = emptyList(),
    aliases: List<CompletionItem> = emptyList(),
    functions: List<CompletionItem> = emptyList(),
    objects: List<CompletionItem> = emptyList(),
    includeKeywords: Boolean = true,
): List<CompletionItem> {
    val prefix = word.lowercase()
    if (prefix.isEmpty() && expands.isEmpty() && templates.isEmpty() && columns.isEmpty() &&
        aliases.isEmpty() && functions.isEmpty() && objects.isEmpty()
    ) {
        return emptyList()
    }
    val out = ArrayList<CompletionItem>()
    fun add(items: List<CompletionItem>) {
        for (item in items) if (matchesPrefix(item.text, prefix)) out.add(item)
    }
    add(expands)
    // 模板：按缩写前缀匹配（含相等），缩写字面敲全后仍保留候选
    for (tmpl in templates) {
        if (prefix.isEmpty() || tmpl.abbreviation.lowercase().startsWith(prefix)) {
            out.add(
                CompletionItem(
                    text = tmpl.abbreviation,
                    detail = tmpl.description ?: I18n.t(Str.EditorCompletionTemplate),
                    kind = CompletionKind.TEMPLATE,
                    insertText = expandTemplate(tmpl.body).text,
                ),
            )
        }
    }
    add(columns)
    add(aliases)
    add(functions)
    add(objects)
    if (includeKeywords) {
        for (kw in SQL_KEYWORDS) {
            if (matchesPrefix(kw, prefix)) out.add(CompletionItem(kw, kind = CompletionKind.KEYWORD))
        }
    }
    return out.distinctBy { it.text }
}
```

3) `CompletionPopup.kt` 的 `completionKindColor`（穷尽 `when`）追加分支——**必须与上面的枚举新值同一提交**：

```kotlin
    CompletionKind.TEMPLATE -> Color(0xFF5C6BC0)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests "app.ui.SqlEditingTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/app/ui/SqlEditing.kt src/main/kotlin/app/ui/CompletionPopup.kt src/test/kotlin/app/SqlEditingTest.kt
git commit -m "feat(live-template): 模板进入补全候选 + 弹层语义色（CompletionKind.TEMPLATE）"
```

---

### Task 7: 编辑器接线（Tab 展开 / 会话导航 / 活动占位符高亮）

**Files:**
- Modify: `src/main/kotlin/app/ui/SqlEditorPane.kt`

**Interfaces:**
- Consumes: Task 2/3/4 的 `LiveTemplate` / `beginSession` / `activeStop` / `sessionNext` / `sessionPrev` / `reconcileSession` / `TemplateSession`；Task 6 的 `completionItems(templates=…)`
- Produces: `EditorPane(..., liveTemplates: List<LiveTemplate> = emptyList())`；`rememberHighlightTransformation(..., templateStop: Pair<Int, Int>?, templateStopBg: Color)`

- [ ] **Step 1: 加 EditorPane 参数**

`EditorPane` 形参，在 `completionFunctions: List<String>,` 之后加：

```kotlin
    /** Live Templates：SQL 语言下 Tab 展开 + 补全候选。 */
    liveTemplates: List<LiveTemplate> = emptyList(),
```

- [ ] **Step 2: 高亮函数加参数**

`rememberHighlightTransformation` 签名与实现改为：

```kotlin
@Composable
internal fun rememberHighlightTransformation(
    spans: List<AnnotatedString.Range<SpanStyle>>,
    currentLineRange: Pair<Int, Int>?,
    lineBgColor: Color,
    templateStop: Pair<Int, Int>? = null,
    templateStopBg: Color = Color.Transparent,
): OutputTransformation {
    val latestSpans = rememberUpdatedState(spans)
    val latestLineRange = rememberUpdatedState(currentLineRange)
    val latestLineBg = rememberUpdatedState(lineBgColor)
    val latestTemplateStop = rememberUpdatedState(templateStop)
    val latestTemplateBg = rememberUpdatedState(templateStopBg)
    return remember {
        OutputTransformation {
            for (s in latestSpans.value) {
                val start = s.start.coerceIn(0, length)
                val end = s.end.coerceIn(0, length)
                if (start < end) addStyle(s.item, start, end)
            }
            latestLineRange.value?.let { (a, b) ->
                val start = a.coerceIn(0, length)
                val end = b.coerceIn(0, length)
                if (start < end) addStyle(SpanStyle(background = latestLineBg.value), start, end)
            }
            latestTemplateStop.value?.let { (a, b) ->
                val start = a.coerceIn(0, length)
                val end = b.coerceIn(0, length)
                if (start < end) addStyle(SpanStyle(background = latestTemplateBg.value), start, end)
            }
        }
    }
}
```

- [ ] **Step 3: 调用点传参 + 会话状态**

`SqlEditorPane` 顶部状态区（`var multiCursors by remember …` 附近）加：

```kotlin
    // Live Template 会话：展开后 Tab/Shift+Tab 在 $占位符$ 间跳转，Esc 结束。切控制台由 key(consoleId) 重置。
    var templateSession by remember { mutableStateOf<TemplateSession?>(null) }
```

`snapshotFlow` 收集器（`LaunchedEffect(state) { … }`）内：先把解构的占位参数 `_` 改成 `selection`，
即 `.collect { (text, selection) ->`；然后在 `if (multiCursors.isNotEmpty() && text != expectedMultiText)` 之前插入会话维护：

```kotlin
                val session = templateSession
                if (session != null) {
                    if (typed) {
                        templateSession = reconcileSession(session, text)
                    } else {
                        val lo = session.anchorStart
                        val hi = session.stops.lastOrNull()?.end ?: session.endOffset
                        val s = minOf(selection.start, selection.end)
                        val e = maxOf(selection.start, selection.end)
                        if (s < lo || e > hi) templateSession = null
                    }
                }
```

`outputTransformation` 调用点改为：

```kotlin
    val templateStopBg = MaterialTheme.colors.primary.copy(alpha = 0.25f)
    val templateStopSel = templateSession?.let { activeStop(it) }?.let { it.start to it.end }
    val outputTransformation = rememberHighlightTransformation(
        highlightSpans, currentLineRange, lineBgColor, templateStopSel, templateStopBg,
    )
```

- [ ] **Step 4: 展开/接受逻辑**

在 `accept(item)` 之前加：

```kotlin
    /** 用模板替换 [wordStart, wordEnd) 并进入会话（选区落到第一个占位符；无占位符只落 endOffset）。 */
    fun startTemplate(tmpl: LiveTemplate, wordStart: Int, wordEnd: Int) {
        val (newText, session) = beginSession(content, wordStart until wordEnd, tmpl.body)
        suppressTextActivation = true
        editing = false
        state.edit {
            replace(0, length, newText)
            val stop = activeStop(session)
            selection = if (stop != null) TextRange(stop.start, stop.end) else TextRange(session.endOffset)
        }
        templateSession = session.takeIf { it.stops.isNotEmpty() }
    }

    /** 光标前单词精确等于某模板缩写则展开；否则 false，交回原逻辑。 */
    fun expandTemplateAtWord(): Boolean {
        if (editorLanguage != EditorLanguage.SQL) return false
        val w = sqlCompletionWord(content, sel.start) ?: return false
        val tmpl = liveTemplates.firstOrNull { it.abbreviation.equals(w.text, ignoreCase = true) } ?: return false
        startTemplate(tmpl, w.start, w.end)
        return true
    }
```

修改 `accept(item)`，完整改为（模板分支提前返回，其余保持原样）：

```kotlin
    fun accept(item: CompletionItem) {
        val w = word ?: return
        if (item.kind == CompletionKind.TEMPLATE) {
            liveTemplates.firstOrNull { it.abbreviation.equals(item.text, ignoreCase = true) }
                ?.let { startTemplate(it, w.start, w.end) }
            return
        }
        val insert = item.insertText ?: item.text
        // 抑制“本次文本变化”重新激活补全，接受后不自动重开（等下一次敲键或 Ctrl+Space）
        suppressTextActivation = true
        editing = false
        state.edit {
            replace(w.start, w.end, insert)
            selection = TextRange(w.start + insert.length)
        }
    }
```

- [ ] **Step 5: 补全候传模板**

`candidates` 的 `completionItems(...)` 调用加参数：

```kotlin
        sqlWord != null -> completionItems(
            word = sqlWord.text,
            templates = if (editorLanguage == EditorLanguage.SQL) liveTemplates else emptyList(),
            columns = columnItems,
            aliases = aliasItems,
            functions = functionItems,
            objects = objectItems,
            includeKeywords = completionEnabled && qualified == null && (!forceComplete || sqlWord.text.isNotEmpty()),
        )
```

- [ ] **Step 6: 按键分支**

`BasicTextField` 的 `onPreviewKeyEvent` 内，`SWITCH_CONSOLE_PREV` 分支之后、`if (e.isAltPressed && e.isShiftPressed …)` 之前插入：

```kotlin
                            // Live Templates：会话内 Tab/Shift+Tab/Esc；无会话时「精确缩写 + Tab」展开。
                            // 精确缩写优先于补全上屏（DataGrip 语义）；其余 Tab 放行到下面的弹层分支。
                            if (editorLanguage == EditorLanguage.SQL) {
                                val session = templateSession
                                if (session != null) {
                                    when (e.key) {
                                        Key.Tab -> {
                                            if (e.isShiftPressed) {
                                                val (ns, target) = sessionPrev(session)
                                                templateSession = ns
                                                state.edit { selection = TextRange(target.first, target.second) }
                                            } else {
                                                val (ns, target) = sessionNext(session)
                                                templateSession = ns
                                                state.edit { selection = TextRange(target.first, target.second) }
                                            }
                                            return@onPreviewKeyEvent true
                                        }
                                        Key.Escape -> { templateSession = null; return@onPreviewKeyEvent true }
                                    }
                                } else if (e.key == Key.Tab && !e.isShiftPressed && !e.isCtrlPressed &&
                                    !e.isAltPressed && sel.collapsed
                                ) {
                                    if (expandTemplateAtWord()) return@onPreviewKeyEvent true
                                }
                            }
```

- [ ] **Step 7: 编译**

Run: `gradle compileKotlin`
Expected: 无错（若报 `TextRange` / `Key` 未导入，确认既有 import 已覆盖——`Key` 与 `TextRange` 本文件已用）

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/app/ui/SqlEditorPane.kt
git commit -m "feat(live-template): 编辑器 Tab 展开、会话跳转与占位符高亮"
```

---

### Task 8: 透传（`EditorArea` + `Main`）

**Files:**
- Modify: `src/main/kotlin/app/ui/EditorArea.kt`
- Modify: `src/main/kotlin/app/core/Main.kt`

**Interfaces:**
- Consumes: Task 7 的 `EditorPane(liveTemplates=…)`；Task 5 的 `LiveTemplatesStore.load()`
- Produces: `EditorArea(..., liveTemplates: List<LiveTemplate> = emptyList())`

- [ ] **Step 1: EditorArea 加参数**

在 `EditorArea` 形参 `completionFunctions: List<String> = emptyList(),` 之后加：

```kotlin
    /** Live Templates（内置 + 用户文件叠加），透传给编辑器。 */
    liveTemplates: List<LiveTemplate> = emptyList(),
```

并在其调用 `EditorPane(...)` 处（`completionFunctions = completionFunctions,` 附近）加：

```kotlin
                            liveTemplates = liveTemplates,
```

- [ ] **Step 2: Main 加载并透传**

`Main.kt` 中找到这一行（用于定位）：

```kotlin
    val completionIdentifiers: List<String> = completionTables.map { it.name }.distinct().sorted()
```

在其之后加：

```kotlin
    // Live Templates：内置 + 用户 live-templates.json 叠加；读失败回落内置。
    val liveTemplates = remember {
        runCatching { LiveTemplatesStore.load() }.getOrElse { defaultLiveTemplates() }
    }
```

并加 import（**不要** import `LiveTemplate`：未引用会触发 `allWarningsAsErrors` 失败）：

```kotlin
import app.settings.LiveTemplatesStore
import app.ui.defaultLiveTemplates
```

在 `EditorArea(...)` 调用里找到 `completionIdentifiers = completionIdentifiers,` 一行，在其后加：

```kotlin
                        liveTemplates = liveTemplates,
```

- [ ] **Step 3: 编译**

Run: `gradle compileKotlin`
Expected: 无错

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/app/ui/EditorArea.kt src/main/kotlin/app/core/Main.kt
git commit -m "feat(live-template): 启动加载并透传到编辑器"
```

---

### Task 9: 设置诊断区「Live Templates 文件」

**Files:**
- Modify: `src/main/kotlin/app/dialog/SettingsDialog.kt`

**Interfaces:**
- Consumes: Task 5 的 `LiveTemplatesStore.templateFile()` / `createSample()`；Task 1 的 `Str.SettingsLiveTemplates` / `Str.SettingsLiveTemplatesCreate`
- Produces: 诊断区新增一行 + 按钮

- [ ] **Step 1: 加 import**

`SettingsDialog.kt` 顶部加：

```kotlin
import app.settings.LiveTemplatesStore
```

- [ ] **Step 2: 诊断区渲染**

`DiagnosticsSection` 内，现有 `hint?.let { … }` 之前插入：

```kotlin
    val templateFile = remember { LiveTemplatesStore.templateFile() }
    Text(
        t(Str.SettingsLiveTemplates),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface,
    )
    Text(
        templateFile.toString(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            runCatching { LiveTemplatesStore.createSample(templateFile) }
            val dir = templateFile.parentFile ?: AppPaths.dataDirectory().toFile()
            openOrCopy(dir.toPath(), t(Str.SettingsLiveTemplates)) { hint = it }
        }) {
            Text(t(Str.SettingsLiveTemplatesCreate))
        }
    }
```

（`AppPaths` / `File` 已在 `SettingsDialog.kt` 使用；`createSample` 是 `internal`，与 `SettingsDialog` 同 module，可访问。）

- [ ] **Step 3: 编译**

Run: `gradle compileKotlin`
Expected: 无错

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/app/dialog/SettingsDialog.kt
git commit -m "feat(live-template): 设置诊断区入口（生成示例/打开目录）"
```

---

### Task 10: 全量验证与收尾

**Files:**
- Modify: `TODO.md`（删除已完成的 `live template` 条目）
- Modify: `src/main/kotlin/app/ui/LiveTemplate.kt` 等（如需修复验证暴露的问题）

- [ ] **Step 1: 全量编译与测试**

Run: `gradle compileKotlin && gradle test && gradle smokeJdbc`
Expected: 全绿（`smokeJdbc` 打印通过或 SKIP，不得 FAILED）

- [ ] **Step 2: 纯逻辑自检（可选，直查行为）**

Run: `gradle test --tests "app.ui.LiveTemplate*" --tests "app.settings.LiveTemplatesStoreTest" --tests "app.SqlEditingTest"`
Expected: PASS

- [ ] **Step 3: 清理 TODO**

删除 `TODO.md` 里 **编辑区域** 下的 `- live template` 一行（AGENTS：完成即删）。

- [ ] **Step 4: Commit**

```bash
git add TODO.md src/main/kotlin src/test/kotlin
git commit -m "chore(live-template): 收尾（全量验证）"
```

- [ ] **Step 5: 交付人工验收**

按 spec「人工验收」小节给用户步骤（不做自动化）：`sel`+Tab 展开与高亮、Tab/Shift+Tab/Esc、编辑后跳转仍准、字符串/注释内不触发、补全候选点击展开、诊断区生成示例文件 + 自定义模板重启生效、中英/深浅色各过一遍。请用户执行并反馈。
