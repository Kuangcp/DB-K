# 编辑区语法高亮方案调研

> 触发：拖入 2973 行 / 224k 字符的 SQL 文件（`0917_1.5.8_dualExpert_school_data.sql`）后控制台卡死。
> 已先提交止血方案（`2aae080`：大文件关闭高亮）。本文调研根因与后续可选方案。

## 1. 现象与止血

- 拖入大文件后 UI 长时间无响应，进程还在跑；`jstack` 无死锁、无隐藏模态窗，AWT 线程曾累计大量 CPU 后回到空闲。
- 止血：`MAX_HIGHLIGHT_WORK = 20M`（高亮 span 数 × 行数），超限不再生成 span → 纯文本排版。
  - 实测该文件首帧 **2765ms → 394ms**（无头 `ImageComposeScene`）。
  - 代价：大文件没有语法高亮（右下角有提示）；**输入仍然慢**（见下）。

## 2. 根因：不是正则，是 Compose 的「整篇排版 × span 数」

### 2.1 三段成本拆分（无头实测）

| 阶段 | 复杂度 | 224k 字符实测 |
|---|---|---|
| 词法扫描 `sqlHighlightSpans`（手写，无正则） | O(n) | ~20–50ms |
| `AnnotatedString(text, spanStyles = spans)` 构造 | O(S) | < 5ms |
| Compose `TextMeasurer.measure` / `BasicTextField` 内部排版 | **近似 O(段数 P × span 数 S)** | 见下 |

排版实测（合成 SQL，固定宽度 1200px）：

| 文档 | span 数 | 单帧排版 |
|---|---|---|
| 3000 行 | 2.2 万 | 433ms |
| 6000 行 | 4.5 万 | 1348ms |
| 12000 行 | 9.2 万 | 7678ms |
| 3000 行（无 span） | 0 | 135ms |
| 12000 行（无 span） | 0 | 314ms |

同一文档固定段数时排版耗时随 span 数近似线性；固定 span 密度时随行数近似平方 → 即 **O(P×S)**。
原因：`MultiParagraph` 为每个段落收集/应用 `spanStyles`，逐段落扫描全部 span。

### 2.2 更根本的问题：`BasicTextField` 始终排版「整篇文档」

带高亮只是放大了问题。**关掉高亮后编辑延迟依然很高**：无头实测该文件每次文本编辑触发的整篇重排版：

```
keystroke#0 relayout=514ms
...
keystroke avg=482ms (plain, 224127 chars)
```

即 `BasicTextField`（`TextFieldState` 新 API）每次文本变更都重排整篇，**没有视口虚拟化、没有增量段落排版**。
真实编辑器在此之上还要再算一次 `textLayout`（行号槽/光标定位用），所以大文件输入延迟约 0.5–0.8s/键。

### 2.3 结论

- 「用不用正则」不是关键：现有扫描器已经是 O(n) 的无正则实现，词法本身不是瓶颈。
- 瓶颈是 **Compose 文本排版模型**：整篇 `MultiParagraph` + 逐段落 span 解析。
- 因此任何「仍把整篇塞进一个 `BasicTextField`」的方案，都无法在超大文件上同时做到「有高亮」和「输入跟手」。

## 3. 为什么 DataGrip / JetBrains 不卡

IntelliJ 编辑器（`EditorImpl`）是**虚拟化**的：

- 文档模型是 rope/piece-table；只在**可见区域**排版与绘制（`EditorPainter` 按可见行裁剪）。
- 高亮用**增量词法**（`LexerEditorHighlighter`）生成每行 `LineTokens`，只把可见行的 token 转成样式；编辑只重算受影响行。
- 行号、折叠、gutter 标记都是按可见行绘制。

所以它渲染几千行乃至几十万行都很轻。差距在**架构（虚拟化 + 增量）**，不在高亮算法本身。

## 4. 方案对比

| 方案 | 高亮 | 滚动 | 大文件输入 | 改动量/风险 | 备注 |
|---|---|---|---|---|---|
| A 现状：预算降级（已提交） | 大文件无 | 流畅 | ~0.5s/键 | 极小 / 低 | 止血；大文件丢高亮 |
| B 视口高亮覆盖层（保留 BasicTextField） | 有（仅可见行绘制） | 流畅 | ~0.5s/键 | 中 / 中 | 恢复颜色，但不解决输入延迟 |
| C 视口 span（`outputTransformation` 只给可见行） | 有 | **卡** | 差 | 小 / 中 | 滚动改 transformed text → 每次整篇重排，否决 |
| D 降 span 数（标点不着色/合并相邻同色） | 有 | 流畅 | 略好 | 极小 / 低 | span 数减半仍不够，治标 |
| E `SwingPanel` + RSyntaxTextArea | 有（成熟） | 流畅 | 流畅 | 中高 / 中高 | 借用 Swing 虚拟化；主题/IME/overlay 是坑 |
| F 自研虚拟化 Compose 编辑器 | 有 | 流畅 | 流畅 | 很高 / 高 | DataGrip 级；IME/选区/撤销全要自研 |
| G 现成 Compose 代码编辑器库 | 有 | 流畅 | 差 | 小 / 低 | 同为 `AnnotatedString`+`BasicTextField`，同样 O(P×S)，不解 |

### A. 预算降级（现状）

已提交。`shouldHighlight(spans, lines)` 超阈值即空 span。简单、可预期、无风险；缺点是失去高亮，且不解决输入延迟。

### B. 视口高亮覆盖层（推荐作为「恢复高亮」的下一步）

思路：`BasicTextField` 只渲染纯文本，但**文字色设为透明**；另用一个 `Canvas`/`drawBehind` 覆盖层，仅对**可见可视行**绘制带颜色的文本：

- 坐标来自已经存在的纯文本 `textLayout: TextLayoutResult`（`getLineTop/getLineLeft/getLineStart/End`）。
- 高亮 span 仍一次性 O(n) 扫出，但只为可见行（约 40 行）构造 `AnnotatedString` 片段并用 `TextMeasurer` 绘制。
- 滚动只重绘覆盖层，**不触发重排版**。

优点：改动局限在 `SqlEditorPane`，保留全部现有编辑/IME/补全/多光标行为；大文件恢复高亮且滚动流畅。
风险/注意：

- 覆盖层字体、字号、`letterSpacing`、`lineHeight` 必须与 `BasicTextField` 完全一致，否则错位；高亮样式**不得改变字形宽度**（不用 bold/italic，现有色板本来就是纯色）。
- 基线文字透明后，选区文字、IME 组合下划线由谁画需逐一验证。
- 仍是整篇 `BasicTextField`，**输入延迟不变**（~0.5s/键）。

### C. 视口 span（否决）

只用可见行的 span 喂 `outputTransformation`。滚动时 transformed text 变化 → `BasicTextField` 整篇重排，比整篇高亮还糟。

### D. 降 span 数

标点不着色、合并相邻同色 span、按行合并。能减 30–50%，但 O(P×S) 不变，大文件仍不可用。仅可作为 B 的补充（减少覆盖层每行片段数）。

### E. SwingPanel + RSyntaxTextArea

在 Compose 里用 `SwingPanel` 嵌 RSyntaxTextArea（Swing，BSD-3，成熟，天然只绘制可见行，自带高亮/行号/折叠/自动补全）。

优点：几乎立刻获得大文件流畅编辑；无需自研排版。
缺点/风险：

- 主题/字体/滚动条需对齐 Compose 主题（可自绘 theme，但目前项目主题体系无法直接复用）。
- **中文 IME**：`SwingPanel` 内 Swing 组件的输入法桥接在 Linux/Compose Desktop 上历史上不稳定，需先做 spike 验证。
- 现有 Compose overlay（补全弹层、查找栏、gutter 行标记、多光标、Live Template）要迁到 Swing 或改成窗口级浮层，工作量不小。
- 与 `ConsoleState`/`TextFieldState` 的接口要重接（文本/选区/撤销/插入）。

### F. 自研虚拟化 Compose 编辑器

文档模型（按行/rope）+ `LazyColumn` 只组合可见行 + 自绘光标/选区 + 自管 IME/撤销/剪贴板/查找/多光标。

优点：纯 Compose、主题/i18n 一致、DataGrip 级性能与体验。
缺点：工作量最大（数周），中文 IME、选区、撤销、跨行编辑、补全上下文都要自研；回归风险高。

### G. 现成 Compose 代码编辑器库

如已在依赖里的 `com.neoutils.highlight`（已确认产出 `AnnotatedString`）、Qawaz Compose-Code-Editor、moria 等，**底层都是 `BasicTextField` + `AnnotatedString`**，同样受 O(P×S) 与整篇排版限制，不能解决问题。

## 5. 建议（分期）

1. **已完成**：预算降级止血（大文件不卡死、滚动流畅）。
2. **近期（低风险，恢复高亮）**：落地 **B 视口高亮覆盖层**；同时减少每次编辑的额外整篇排版——当前编辑器除 `BasicTextField` 外还 `remember(content)` 重测一次 `textLayout`，对齐计算可优化（不换行时用固定行高，或延迟到需要时再算），把大文件输入从 ~0.7s 压到 ~0.5s。
3. **中期（若要求「大文件输入也跟手」）**：在 **E（Swing/RSyntaxTextArea）** 与 **F（自研虚拟化）** 之间决策：
   - 先做一个 E 的 **spike**：`SwingPanel` 里嵌 RSyntaxTextArea，验证 Linux 中文 IME、主题、与 Compose 弹层共存；
   - spike 通过 → 走 E（省时）；不通过 → 走 F（慢但可控）。

## 附：复现/测量方法

无头 `ImageComposeScene`（`androidx.compose.ui`）组合 `BasicTextField`，`render(nanos)` 计时；用合成 SQL 与真实文件分别测。属于程序化性能验证，不启动 GUI，符合本项目「UI 行为人工验收」约定。
