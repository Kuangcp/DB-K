# 全局界面缩放（UI Scale）

日期：2026-09-23
状态：设计已确认（方案 A），待评审 spec

## 背景与动机

同一份 db-k 在不同机器上观感差别很大：常规显示器上字体/图标/边框「刚刚好」，但在
2.5K 笔记本上整个界面明显偏小。希望提供一个**全局缩放比例配置**，让用户按自己的屏幕
把整个界面等比放大，而不是逐个调字号。

### 根因（已调研确认）

Compose Desktop 的密度不来自应用配置，而来自 AWT：

```kotlin
// androidx.compose.ui.window.LayoutConfiguration.desktop.kt（1.12.0）
getDensity(GraphicsConfiguration) = Density(transform.scaleX, fontScale = 1f)
```

`transform.scaleX` 由 JDK/GDK/`sun.java2d.uiScale` 决定，且 **`fontScale` 写死为 1.0**。
db-k 自身没有任何缩放逻辑，因此 2.5K 屏上系统若报 1.0（或分数缩放未被 JDK 读到），
所有 `dp`/`sp` 都按 1:1 物理像素绘制，视觉上就偏小。当前没有可调旋钮。

## 已确认的需求决策（用户确认）

1. **方案 A：运行期 `LocalDensity` 覆盖**。即时生效、无需重启、跨平台、纯 Compose。
   - 不采用「启动期 `sun.java2d.uiScale`」（需重启、JDK 内部属性、可能与系统缩放叠加）。
   - 不采用「把所有 `.dp` 换成 `Dimens*scale` 的 token 方案」（500+ 处字面量，维护灾难）。
2. **默认 100% = 完全不改变现状**，零回归风险；缩放与系统已有缩放是**乘算**关系。
3. **覆盖全部 Compose 窗口**：主窗口 + 全部独立 `DialogWindow`（含设置/主题/查看器/单元格编辑）。
4. **设置入口**：设置窗口 →「通用设置」新增「界面缩放」，**实时预览**（拖动即时全局生效），
   取消回退、保存落盘（与现有编辑器字号「预览/取消不改」一致）。
5. **不做（首版 YAGNI）**：Ctrl+=/-/0 快捷键缩放、按屏幕自动推荐比例、原生 `FileDialog`
   缩放、逐显示器独立比例。

## 核心机制

所有 `dp`/`sp` 最终都乘以 `Density.density`。因此在每棵 composition 的根提供缩放后的
`LocalDensity` 即可让整棵界面等比放大：

```kotlin
CompositionLocalProvider(
    LocalDensity provides Density(base.density * s, base.fontScale),
    content = content,
)
```

（上面是示意；落地用幂等式实现，见「实现 → 1.」的 `ProvideUiScale`。）

**只放大 `density`，绝不动 `fontScale`**：`sp → px = value * fontScale * density`，
若两者都乘 `s` 会变成平方。仅乘 `density` 时 `dp` 与 `sp` 恰好同时放大 `s`。

### 鼠标/选区坐标为什么不坏（已核对 1.12.0 输入链路）

`ComposeSceneMediator` 把 AWT 点转场景坐标用的是 `contentComponent.density`（GC 密度），
得到的是**场景物理像素**；布局产出的也是场景物理像素。覆盖 `LocalDensity` 只改变
「`dp` → 场景物理像素」的比例，不改变指针映射的正交关系，因此点击/拖拽/选区仍对齐。
**实现后必须人工在 150% 下点选/拖拽验收（见测试与验收）。**

### 关键约束：`DialogWindow` 的继承行为不确定，包裹必须**幂等**

项目现有注释（`app/i18n/LocalI18n.kt`）称「`DialogWindow` 是独立 composition，不继承主窗口的
CompositionLocal，各自都要 `ProvideI18n`」。但 1.12.0 源码里 `SwingDialog` 会读取
`currentCompositionLocalContext` 并设到 `ComposeDialog.compositionLocalContext`（且随重组更新），
即**实际上会继承并会跟随更新**。两种结论都可能（取决于版本/调用路径），因此设计上：

- **仍然逐个窗口显式包裹**（对「不继承」的情形兜底）；
- 包裹**必须幂等**——若窗口已从父 composition 继承了已放大的 `LocalDensity`，再次 `* scale`
  会得到 `s²`。用一个 `LocalUiScale`（已应用倍率）先把祖先的倍率除掉再叠加目标倍率。

`Popup` / `DropdownMenu` / `TooltipArea` 是主 composition 的**子 composition**（`ComposeSceneLayer`
+ 父 CompositionContext），会继承主窗口的缩放密度，无需逐个包裹（作为人工验收项确认）。

## 数据模型与持久化

### 偏好（`<dataDir>/app.properties`，key `ui.scale`）

按 AGENTS「新增同类项合并进已有文件」，与 `language` 同放 `app.properties`（同文件内的
读-改-写，互不覆盖其它 key）。

```kotlin
// app/settings/UiScalePrefs.kt
object UiScalePrefs {
    const val MIN_SCALE = 1.0f          // 100%
    const val MAX_SCALE = 2.0f          // 200%
    const val STEP = 0.05f              // 5% 一档
    const val DEFAULT_SCALE = 1.0f

    fun sanitized(value: Float): Float = value.coerceIn(MIN_SCALE, MAX_SCALE)
    fun load(): Float                   // 缺文件/缺 key/非法 → DEFAULT_SCALE
    fun save(scale: Float)

    // 纯文件实现（便于单测注入临时文件）：
    internal fun load(f: File): Float
    internal fun save(f: File, scale: Float)
}
```

### 全局可观察状态（跨窗口）

主窗口与各 DialogWindow 是不同 composition，需要一处全局可观察状态来广播缩放。

```kotlin
// app/state/UiScaleState.kt
object UiScaleState {
    var scale by mutableStateOf(UiScalePrefs.DEFAULT_SCALE)
        private set
    fun set(value: Float) { scale = UiScalePrefs.sanitized(value) }
}
```

- 启动：`main()`/`AppRoot` 初始 `UiScaleState.set(UiScalePrefs.load())`（**非**响应式地读一次用于
  计算默认窗口尺寸，见下）。
- 保存时同时 `UiScalePrefs.save(...)` 与 `UiScaleState.set(...)`。
- 取消时 `UiScaleState.set(打开设置时的快照值)`。

`UiScaleState` 属 `app/state` 层（UI 编排），符合 AGENTS「状态即 snapshot，读写经 app/state」。

## 实现（组件与接线）

### 1. `app/ui/UiScale.kt`（新增，缩放提供者与尺寸助手）

```kotlin
/** 当前子树已经叠加过的缩放倍率（默认 1 = 未缩放）。用于让 [ProvideUiScale] 幂等。 */
val LocalUiScale = staticCompositionLocalOf { 1f }

/**
 * 在当前 composition 根提供缩放后的 LocalDensity。每个窗口都要各包一层；
 * **幂等**：先撤掉祖先窗口已应用的倍率，再叠加目标倍率（DialogWindow 可能已继承放大后的密度）。
 */
@Composable
fun ProvideUiScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val applied = LocalUiScale.current
    val scale = UiScaleState.scale
    val rawDensity = current.density / applied          // 还原系统密度
    CompositionLocalProvider(
        LocalDensity provides Density(rawDensity * scale, current.fontScale),
        LocalUiScale provides scale,
        content = content,
    )
}

/** 固定尺寸窗口/对话框的 Dp 尺寸同比放大，避免缩放后内容溢出固定窗口。 */
@Composable
fun scaledSize(width: Dp, height: Dp): DpSize =
    DpSize(width * UiScaleState.scale, height * UiScaleState.scale)
```

> `DialogState.size` 是 `mutableStateOf`，官方文档保证「应用改 state.size → 原生对话框随之
> 调整尺寸」，因此实时预览时对话框尺寸会跟随缩放变化。

### 2. 主窗口（`app/core/Main.kt`）

- `Window { ... }` 内容用 `ProvideUiScale { AppBody(...) }` 包裹。
- 无边框窗口缩放热区 `WindowDecoration.Undecorated(4.dp)` → `Undecorated(4.dp * UiScaleState.scale)`
  （该值在窗口层、用系统密度解释，乘 scale 即等比放大；避免历史「热区盖住滚动条」问题在放大后复发）。
- **默认初始窗口尺寸**（无存档几何时）：`1180×760` 物理像素乘以启动时读到的初始 scale，
  避免 2.5K + 150% 首次启动时窗口过于局促；**有存档几何时按原物理像素恢复，不受缩放影响**。
- `persistWindowGeometry()` 仍按系统密度保存物理像素，逻辑不变。

### 2b. 全部独立对话框（各自包一层 + 尺寸同比放大）

| 文件 | 位置 | 现尺寸 |
|---|---|---|
| `app/dialog/EditCellDialog.kt` | `:74` | `windowSize` 参数 |
| `app/dialog/SettingsDialog.kt` | `:86` | `760×520` |
| `app/dialog/ThemeDialog.kt` | `:131` | `780×560` |
| `app/dialog/ViewerDialogs.kt` | `:305` | `VIEWER_WINDOW_SIZE` |
| `app/dialog/ViewerDialogs.kt` | `:375` | `VIEWER_WINDOW_SIZE` |
| `app/dialog/ViewerDialogs.kt` | `:500` | `VIEWER_WINDOW_SIZE` |
| `app/dialog/ViewerDialogs.kt` | `:663` | `720×520` |

每个 `DialogWindow` 的 **content lambda 内**用 `ProvideUiScale { ... }` 包裹（幂等，重复包裹安全）；
其 `rememberDialogState(...)` 的尺寸改为 `scaledSize(...)`（或共用常量后再放大）。

### 3. 设置界面（`app/dialog/SettingsDialog.kt`）

- `SettingsSnapshot` 增加 `uiScale: Float`。
- 通用设置新增一块「界面缩放」：`Slider(valueRange = 1.0f..2.0f, steps = 19)` + 百分比标签
  （`t(Str.SettingsUiScale, "%.0f".format(scale * 100))`），紧随编辑器字号之后或之前。
- 拖动回调里**同时**更新本地 state 与 `UiScaleState.set(v)`（全局实时预览）。
- `onSave`：写回 `SettingsSnapshot.uiScale`；Main 侧 `UiScalePrefs.save` + `UiScaleState.set`。
- `onDismiss`（取消/关闭）：Main 侧 `UiScaleState.set(打开时的快照值)` 回退。
- 打开设置时在 Main 侧快照当前值：`onOpenSettings = { settingsInitialScale = UiScaleState.scale; dialogState.showSettings = true }`，
  并作为 `initial` 传入，避免预览变化污染「初始值」。

### 4. i18n（zh + en 同步）

新增 key（`i18n/Str.kt` + `CatalogZh` + `CatalogEn`）：

- `Str.SettingsUiScale` = 「界面缩放（{0}%）」/ "UI scale ({0}%)"
- `Str.SettingsUiScaleHint` = 「放大整个界面（字体、图标、边框），即时生效，取消不改」/
  "Scale the entire UI (text, icons, borders). Applies immediately; cancel reverts."

`I18nCatalogTest` 会校验占位符一致与非空，漏一种语言编译不过。

## 错误处理与边界

- `load()` 任何异常/非法值 → 回落 `1.0`（100%），绝不因偏好损坏导致启动失败或无缩放界面。
- 缩放后不触碰主题色板（颜色不缩放）；语义色（状态点、徽章）不受影响。
- 不缩放：原生 `FileDialog`（OS 绘制，通常已跟随 GTK 缩放）、AWT 剪贴板、窗口物理几何。
- 分数缩放（1.05/1.25…）下 `dp*scale` 可能产生小数像素，1px 分隔线可能落成 1 或 2 物理像素，
  属可接受渲染差异。

## 测试与验收

### 程序化（自己跑）

- `gradle compileKotlin` 无错（-Werror 开着）。
- `gradle test`：
  - 新增 `UiScalePrefsTest`：缺文件 → 1.0；写入-读取往返；越界 `sanitized` 夹取；
    **与 `language` 同文件互不覆盖**（先写 language 再写 scale，反之亦然）。
  - `I18nCatalogTest` 覆盖新 key。
- `gradle smokeJdbc` 照旧（确认无回归）。

### 人工验收（按 AGENTS「UI 验证方式」由用户执行）

前置：正常启动 db-k。
1. 设置 → 通用设置 → 拖动「界面缩放」到 150%：主窗口树/标签/结果区/图标/边框即时放大，
   设置窗自身也随之放大且内容不溢出/不被裁切。
2. 点「取消」→ 界面回到打开设置前的比例；再打开 → 滑块回到原值。
3. 调到 150% 保存 → 重启 → 仍为 150%；`~/.local/share/db-k/app.properties` 含 `ui.scale=1.5`，
   且 `language` key 未丢失。
4. 150% 下覆盖命中：点树节点展开、拖左树/结果分隔条、编辑器点选/拖选、`Ctrl+Enter` 执行、
   右键菜单与补全弹层点选——位置无偏移、无「点不中」。
5. 中英各切一遍：新文案无残留、无占位符错位。
6. 深色/浅色各看一遍：放大后无黑字沉底、无过曝白块。
7. 弹层类缩放：下拉菜单（主题/工作区/新建）、树右键菜单、结果筛选/表头菜单、Tooltip、
   补全弹层——文字与命中区域同步放大、位置不偏移（验证子 composition 继承缩放密度）。
   若发现某类弹层未放大，记录具体入口，再决定是否对其单独包裹。

## 明确不做（首版）

- Ctrl+= / Ctrl+- / Ctrl+0 快捷键缩放（可作为后续增量，走既有 `KeymapPrefs`）。
- 按屏幕分辨率/`GraphicsConfiguration` 自动推荐比例。
- 原生 `FileDialog`、AWT 组件的缩放。
- 逐显示器独立比例、窗口物理尺寸随缩放变化（仅默认初始尺寸随 scale 放大）。
