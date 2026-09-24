# Live Templates（SQL 片段模板）

日期：2026-09-24
状态：设计已确认（方案 B / Tab 优先 a），待评审 spec
关联：`TODO.md`「编辑区域 → live template」

## 背景与动机

对标 DataGrip 的 Live Templates：敲一个缩写（如 `sel`）按 `Tab` 展开成一段 SQL 骨架
（`SELECT $columns$ FROM $table$ WHERE $condition$`），光标落在第一个占位符上，继续按
`Tab`/`Shift+Tab` 在占位符之间跳转，`Esc` 退出。目标是「少打字、写常用语句更快」。

现状：编辑器（`SqlEditorPane` + `BasicTextField`）有补全、语法高亮、多光标，但没有任何
片段/模板机制；关键词只能逐个补全。用户希望**能预设一批模板**（内置 + 自己可增改）。

## 已确认的需求决策（用户确认）

1. **方案 B**：内置一批 SQL 模板（写死在代码里）+ 用户可在
   `<dataDir>/live-templates.json` 里增改（按缩写覆盖内置）；**不做图形化模板编辑界面**。
2. **Tab 优先级 (a)**：光标前是一个「精确等于某活模板缩写」的完整词且不在字符串/注释内时，
   `Tab` 展开模板；否则 `Tab` 走原逻辑（补全上屏）。规则一句话：
   > 精确缩写命中模板 → Tab 展开；否则 Tab 照旧上屏补全候选。
3. **补全集成纳入首版**：模板作为补全候选（`CompletionKind.TEMPLATE`）出现在弹层里，
   Enter/点击也能展开并进入跳转模式——这是「用户怎么知道有哪些缩写」的答案。
4. **可发现性入口**：不做编辑器 UI；文件位置通过 设置 → 通用设置 → 诊断 加一行
   「Live Templates 文件」+「生成示例并打开目录」，复用现有 `DesktopOpen` 与诊断行样式。
5. **不做（首版 YAGNI）**：变量表达式（`$DATE$` / `groovyScript` / `complete()`）、
   同名占位符实时联动、Surround-with（`Ctrl+Alt+T`）、多 context、图形化模板管理。
   数据模型预留 `context` 字段，将来加。

## 核心机制

### 占位符语法（DataGrip 兼容子集）

- `$NAME$` → 一个空占位符（Tab stop），`NAME` 大小写不敏感，仅用于排序去重，不做语义。
- `$END$` → 最终光标位（模板模式结束后落点）；可省，缺省 = 插入文本末尾；可出现在任意位置。
- `$$` → 字面量 `$`。
- 不闭合的单个 `$`（后面没有配对的 `$`）→ 当字面量处理，不报错。
- **无默认值**：展开后占位符是空的，等用户输入（与 DataGrip 默认一致）。

### 缩写触发与 Tab 优先级

编辑器 `onPreviewKeyEvent`（`SqlEditorPane.kt` 内 `BasicTextField`）按键顺序调整为：

1. **会话进行中**（`templateSession != null`）：
   - `Tab`（无 Shift）→ 下一个占位符；越过最后一个 → 结束会话、光标落 `$END$`（或末尾）。
   - `Shift+Tab` → 上一个占位符；已在第一个 → 不移动（消费，避免焦点遍历跑掉）。
   - `Esc` → 结束会话（光标留在当前位置）。
   - 其余键放行，由 `reconcile` 维护区间。
2. **无会话**且 `Tab`（无 Shift、无 Ctrl/Alt、无选区、未在字符串/注释内）：
   用现成的 `sqlCompletionWord(content, sel.start)` 取光标前的完整词；若该词**精确等于**
   某模板缩写（忽略大小写）→ 展开、建会话、`suppressTextActivation = true`（防补全弹层立刻弹回）；
   否则放行到原逻辑。
3. 上述两步插在现有 `if (popupOpen) { Tab/Enter → 上屏 }` **之前**。

### 会话与占位符漂移（本功能最容易出错处）

`TemplateSession` 记录「上一次文本快照 + 整段模板的 anchor 起点 + 各占位符的文档区间 + 当前下标」。
用户在占位符里打字/删字后，后续占位符的 offset 必须平移。做法（纯逻辑、可单测）：

对 `old = session.lastText` 与 `newText` 求最长公共前缀 `p` 与最长公共后缀 `s`，
得到改动区间 `[p, old.length - s)` 与长度差 `delta = (newText.length - s) - p`；对每个 stop：

```
stop.end <= p            → 不变
stop.start >= old.length - s → start+delta, end+delta
否则（与改动区间相交）    → start = min(stop.start, p), end = (stop.end + delta).coerceAtLeast(start)
```

`endOffset` 按同一规则平移（落在改动区间之前不变、之后 `+delta`、相交则夹取）。

失效（返回 null、清空会话）条件：

- `p < session.anchorStart`（模板开头被删，整段已不完整）；
- 任一 stop 落到非法区间（`start < 0 || end < start`）；
- 纯选区变化时，selection 落在 `[anchorStart, 最后 stop.end]` 之外（用户点了别处/方向键移出）。

## 数据模型与持久化

```kotlin
// app/ui/LiveTemplate.kt
enum class LiveTemplateContext(val token: String) { SQL("sql") }

data class LiveTemplate(
    val abbreviation: String,       // "sel"，匹配大小写不敏感
    val body: String,               // "SELECT $columns$ FROM $table$"
    val description: String? = null,
    val context: LiveTemplateContext = LiveTemplateContext.SQL,
)

data class TemplateStop(val name: String, val start: Int, val end: Int)
// stops 只含命名的 Tab stop（按 start 升序）；$END$ 不入 stops，单独记 endOffset
data class TemplateExpansion(val text: String, val stops: List<TemplateStop>, val endOffset: Int)

data class TemplateSession(
    val lastText: String,
    val anchorStart: Int,          // 整段插入文本的起点
    val stops: List<TemplateStop>, // 文档坐标，按 start 升序
    val activeIndex: Int,
    val endOffset: Int,            // $END$ 的文档坐标（无 $END$ = 插入文本末尾）
)
```

模板文件 `<dataDir>/live-templates.json`（**可选**；JSON 与 `ThemesStore`/`ProfileTransfer`
同款 `kotlinx.serialization`，`ignoreUnknownKeys = true`）：

```json
{
  "fileVersion": 1,
  "templates": [
    { "abbreviation": "sel2", "body": "SELECT $columns$\nFROM $table$\nLIMIT $n$", "description": "我的查询" }
  ]
}
```

**叠加语义**：内置模板写死在代码（永远在）；用户文件按 `(context, abbreviation)` **覆盖或追加**
内置。文件缺失/损坏/字段非法 → 只用内置（不崩）。删文件 = 恢复出厂。`context` 省略默认 `sql`，
未知 token 的条目跳过。

## 实现（组件与接线）

### 1. `app/ui/LiveTemplate.kt`（新增，纯逻辑，无 compose 依赖）

沿用 `MultiCursorEdit.kt` / `SqlEditing.kt` 的「纯逻辑 + 单测」范式：

```kotlin
fun expandTemplate(body: String): TemplateExpansion
fun beginSession(doc: String, replaceRange: IntRange, body: String): Pair<String, TemplateSession>
// null=已越过最后一个 stop → 调用方结束会话、光标落 session.endOffset
fun sessionNext(session: TemplateSession): Pair<TemplateSession?, IntRange>
fun sessionPrev(session: TemplateSession): Pair<TemplateSession, IntRange>
fun activeStopRange(session: TemplateSession): IntRange?
// 同时平移 stops 与 endOffset；失效返回 null
fun reconcileSession(session: TemplateSession, newText: String): TemplateSession?
fun defaultLiveTemplates(): List<LiveTemplate>
```

### 2. `app/settings/LiveTemplatesStore.kt`（新增）

```kotlin
object LiveTemplatesStore {
    fun templateFile(): File                                  // <dataDir>/live-templates.json
    fun load(): List<LiveTemplate>                            // 内置 overlay 用户文件
    internal fun load(f: File): List<LiveTemplate>            // 便于单测注入临时文件
    internal fun createSample(f: File)                        // 诊断按钮：不存在则写示例
}
```

### 3. `app/ui/SqlEditing.kt`（补全集成）

- `CompletionKind` 新增 `TEMPLATE`。
- `completionItems(...)` 新增 `templates: List<LiveTemplate> = emptyList()` 参数；
  在 `expands` 之后、`columns` 之前插入候选：
  `CompletionItem(text = abbreviation, detail = description ?: t(Str.EditorCompletionTemplate),
  kind = TEMPLATE, insertText = expandTemplate(body).text)`。
  **匹配规则与其它候选不同**：`abbrev.lowercase().startsWith(prefix)`（**含相等**）——
  即缩写字面敲全时模板仍留在候选里（其它候选是「已输完整即隐藏」）。

### 4. `app/ui/SqlEditorPane.kt`（接线）

- 新增参数 `liveTemplates: List<LiveTemplate> = emptyList()`。
- 新增 `var templateSession by remember { mutableStateOf<TemplateSession?>(null) }`；
  因 `EditorArea` 已用 `key(consoleId)` 包住 `EditorPane`，切换控制台自动丢弃会话。仅 `SQL` 语言启用。
- 按键分支按「核心机制 → 缩写触发与 Tab 优先级」插入。
- 在**已有的** `snapshotFlow { text to selection }` 收集器里维护会话：
  文本变化 → `templateSession = reconcileSession(session, text)`；
  纯选区变化且移出模板跨度 → 清空会话。
- `accept(item)` 特判 `kind == TEMPLATE`：按 `item.text` 在 `liveTemplates` 里查 body →
  `beginSession` → 选区落到第一个 stop（而非行尾），其余候选行为不变。

### 5. 活动占位符高亮

`rememberHighlightTransformation(...)` 增加 `templateStop: IntRange?` 参数（内部同样走
`rememberUpdatedState`，**返回值保持稳定**，不会 reset 指针手势——这是既有硬约束）；
用 `SpanStyle(background = primary.copy(alpha = 0.25f))` 给活动 stop 上色（与选区色一致）。
`SqlEditorPane` 传入 `templateSession?.let(::activeStopRange)`。

### 6. `app/ui/CompletionPopup.kt`

`completionKindColor` 给 `TEMPLATE` 一个语义色（建议绿 `0xFF43A047`，与 EXPAND 区分可换青
`0xFF26A69A`）；候选项 `detail` 已显示 `description`/「模板」，无需额外改动。

### 7. `app/ui/EditorArea.kt` / `app/core/Main.kt`

- `EditorArea` 新增参数 `liveTemplates: List<LiveTemplate> = emptyList()`，透传给 `EditorPane`。
- `Main` 启动读一次 `LiveTemplatesStore.load()`（`remember`），传入 `EditorArea`
  （与 `completionIdentifiers` 同路径）。读失败回落到内置。

### 8. `app/dialog/SettingsDialog.kt`（诊断行）

`DiagnosticsSection` 增加一行 `Str.SettingsLiveTemplates`：显示 `LiveTemplatesStore.templateFile()`
路径 + 按钮 `Str.SettingsLiveTemplatesCreate`（不存在则 `createSample` 写示例文件，再 `DesktopOpen`
打开数据目录；无桌面环境沿用现有「复制路径 + 行内提示」回落）。

### 9. i18n（zh + en 同步）

新增 key（`i18n/Str.kt` + `CatalogZh` + `CatalogEn`）：

- `Str.EditorCompletionTemplate` = 「模板」/ "Template"
- `Str.SettingsLiveTemplates` = 「Live Templates」/ "Live Templates"
- `Str.SettingsLiveTemplatesCreate` = 「生成示例并打开目录」/ "Create sample and open folder"

`I18nCatalogTest` 校验占位符一致与非空，漏一种语言编译不过。

## 内置模板（首版，对齐 DataGrip SQL 默认 + db-k 常用）

| 缩写 | 模板体 |
|---|---|
| `sel` | `SELECT $columns$ FROM $table$` |
| `selw` | `SELECT $columns$ FROM $table$ WHERE $condition$` |
| `selc` | `SELECT count(*) FROM $table$ WHERE $condition$` |
| `ins` | `INSERT INTO $table$ ($columns$) VALUES ($values$)` |
| `upd` | `UPDATE $table$ SET $column$ = $value$ WHERE $condition$` |
| `del` | `DELETE FROM $table$ WHERE $condition$` |
| `whe` | `WHERE $condition$` |
| `ob` | `ORDER BY $column$` |
| `gb` | `GROUP BY $column$` |
| `case` | `CASE WHEN $condition$ THEN $result$ ELSE $result$ END` |

## 错误处理与边界

- 模板文件读取/反序列化异常 → 回落内置，绝不因模板损坏导致启动失败。
- `expandTemplate` 对畸形 body（未闭合 `$`、空 `$NAME$`）不抛异常，按字面量处理。
- Tab 在字符串/注释内、有选区、多光标模式下不触发模板（`sqlCompletionWord` 已排除前两者；
  多光标分支在模板分支之后，需确保会话/展开不与多光标叠加——展开前若 `multiCursors` 非空则放行）。
- 模板展开后若用户立即撤销（`Ctrl+Z`）：文本回到缩写态，`reconcile` 因前缀不匹配或区间非法结束会话。
- 补全弹层与模板的快捷键不冲突：弹层开着时 Tab 仍先判模板精确命中，未命中才上屏候选。

## 测试与验收

### 程序化（自己跑）

- `gradle compileKotlin` 无错。
- `gradle test`：
  - 新增 `LiveTemplateTest.kt`（表驱动）：
    - `expandTemplate`：`$A$` / `$END$` / `$$` / 未闭合 `$` / 无占位符 / 重复名；
    - `beginSession` 插入位置与首个 stop 选区；
    - `sessionNext` / `sessionPrev` 越界与结束；
    - `reconcileSession`：在 stop 前/内/后插入与删除、整选区替换、跨 stop 删除、`endOffset` 平移、
    anchor 起点被删 → null；
    - 缩写提取：字符串/注释内不触发、带选区不触发。
  - 新增 `LiveTemplatesStoreTest.kt`：内置 overlay 用户（覆盖同名、追加新名）、空/损坏 JSON 回落、
    未知字段忽略、未知 context 跳过。
  - 既有 `SqlEditingTest` 扩展模板候选（含「精确缩写仍显示」）。
- `gradle smokeJdbc` 照旧（确认无回归）。

### 人工验收（按 AGENTS「UI 验证方式」由用户执行）

前置：正常启动 db-k，连一个库并打开 SQL 控制台。
1. 输入 `sel` → 补全列表出现「sel / 模板」候选；按 `Tab` 展开为 `SELECT  FROM ...`，
   第一个占位符被高亮选中。
2. 在第一个占位符直接输入表名 → 替换；`Tab` 跳下一个（高亮跟随）；`Shift+Tab` 回退；
   到最后一个再按 `Tab` → 会话结束、光标落末尾；`Esc` 中途退出。
3. 占位符中间删字/打字后，后续占位符的跳转位置仍正确（不外移、不错位）。
4. 点列表里的模板候选（鼠标）→ 同样展开并进入跳转模式；回车上屏关键词 `SELECT` 仍正常。
5. 在字符串 `'sel'` 或注释 `-- sel` 内按 `Tab` → 不展开。
6. 设置 → 通用设置 → 诊断 →「生成示例并打开目录」：文件生成、目录打开；在其中加一条自定义
   模板并重启 → 新模板可 Tab 展开；同缩写覆盖内置生效。
7. 中英各切一遍新文案、深浅色各看一遍占位符高亮可读性。

## 明确不做（首版）

- 变量表达式 / 动态宏（`$DATE$`、`groovyScript`、`complete()`、`snakeCase()` 等）。
- 同名占位符实时联动（每个 `$NAME$` 出现处是独立 stop，改一处不联动）。
- Surround-with / 选区包裹模板（`Ctrl+Alt+T`）。
- 多 context（DDL/Redis 命令/JSON DSL）；`LiveTemplateContext` 已预留字段。
- 图形化模板管理界面、模板导入导出、按 context 分组展示。

## 后续变更（2026-09-24，用户验收后新增）

用户试用后要求两件事，据此实现：

1. **设置窗图形化模板管理**（原「明确不做」中的方案 C 落地）：设置窗左栏新增「模板」分区，
   右侧为可编辑表格（缩写 / 模板体 / 描述 + 删除行），底部「新增模板」「恢复默认」；
   顶部提示 `$名称$` / `$END$` / `$$` 语法。
2. **保存即时生效**：`Main` 的 `liveTemplates` 改为快照状态，`SettingsSnapshot` 携带模板列表，
   保存时更新状态并落盘 → 主编辑器立即重组生效，无需重启。打开设置时重读文件，手改 JSON
   也能被拾取。

**持久化语义随之修订**：原「内置永远在、用户文件覆盖/追加」改为**文件一旦存在即为权威**
（整份列表）。原因：A 方案下用户要能**删除内置模板**，而叠加模型下删除无法表达（删掉的内置
会被重新叠加回来）。现在 `load()`：文件缺失/损坏 → 内置；文件存在 → 用它（空/全非法 → 回落内置）。
`save()` 整份原子写并清洗（空缩写/空模板体/重复缩写丢弃）。诊断区「生成示例」改为写入完整内置列表。
