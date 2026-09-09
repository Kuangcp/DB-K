# db-k Roadmap（核心发展计划）

> 执行计划主文档：按阶段描述「要做什么、为什么这个顺序、怎么算完成」。
> 每阶段验收必须过 `AGENTS.md` 验证习惯（compileKotlin / smokeJdbc / 人工 UI 验收）。
> 零散即时事项在 `TODO.md`；架构与数据模型依据在 `doc/DESIGN.md`（里程碑章节与本文对应）。

---

## 1. 现状总览（截至当前）

单窗口 JDBC 数据库客户端已完成主闭环：

| 能力 | 落点 |
|---|---|
| 本地元数据存储 | `db/`：app.db（连接/文件夹/控制台/sql_history 表）、仓库、控制台 .sql 文件 |
| 左树 | 文件夹 → 连接（状态点/懒加载）→ schema → 对象分组（表/视图/触发器），展开持久化 |
| 编辑执行 | 高亮编辑器 + 控制台标签（每连接多控制台）+ Ctrl+Enter 执行选中（无选中不执行）+ 结果网格 |
| 数据源 | PG / MySQL / MariaDB / SQLite / H2 / ClickHouse（HTTP，默认关压缩兼容反代链路） |
| 体验 | 双击表预览、导出 CSV、深色主题（持久化）、Toast、窗口几何持久化、会话日志 |
| 验证 | `gradle smokeJdbc`（SQLite+H2 实测 + 各驱动加载） |

## 2. 里程碑状态（对齐 DESIGN §11）

| 里程碑 | 状态 | 剩余缺口 |
|---|---|---|
| M0 脚手架 | ✅ | — |
| M1 元数据+树 | ✅ | — |
| M2 JDBC 运行时 | ✅ | ClickHouse 已并入（compress=0 反代兼容已修） |
| M3 编辑执行 | ✅ 主闭环 | 取消执行/历史落库已并入 P1 完成（待人工验收）；P2 起转向体验增强 |
| M4 体验打磨 | ✅ | 右键菜单/预览/CSV/几何/主题齐；单元测试缺位（→ P5） |
| M5 打包发布 | ⏳ 配置就绪未产出 | 首次构建 Deb + 干净环境安装验证（→ P5） |

## 3. 阶段计划（核心发展顺序）

优先级逻辑：先把「执行→看结果」闭环的粗糙边磨平（P1/P2），再动树与存储安全
（P3），随后按需扩数据源（P4），最后收敛工程债并发布（P5）。远期能力不进核心线
（§5），与 DESIGN「第一版不做」边界保持一致。

### P1 执行闭环补全 ✅（已实现，待人工验收）
范围：
- 长查询可取消（UI「取消 (Esc)」+ Esc），语义：单线程执行器上放弃等待并复位状态，不误伤同连接后续查询
- sql_history 落库（执行时间/连接/SQL/成功与否/耗时/行数）+ 右侧历史面板（双击回填编辑器、可清空）
- 结果 >1000 行截断时醒目提示；「导出全量 CSV」重跑 SQL 流式导出（不受上限影响）
落点：`jdbc/QueryExecutor|LiveConnection`（registerStatement/cancelCurrentQuery）、
`app/state/ConsoleState`（代次守卫 + recordHistory）、`app/ui/HistoryPanel`、
`app/core/CsvExport.exportAll`、`db/AppDatabase v3`、`JdbcSmoke`（cancel + v2→v3 自检）。
验收口径：跑一个超长查询可取消且连接仍可用；重开应用后历史仍在且能回填；smokeJdbc 绿。

### P2 编辑器与结果体验 ✅（已实现，待人工验收；与 P1 一并验收）
范围：
- 自动补全：关键字 + 当前连接已加载的表/视图名候选，Enter/Tab 上屏，Esc 关闭，箭头选择（`app/ui/SqlEditing.kt` 纯逻辑 + `SqlWorkspace` 弹层；连接元数据由 Main 懒预取首个 schema，列名级补全需先补元数据列缓存 → 归 P3）
- 结果 Ctrl+T 行列转制（仅视图切换，不清缓存/历史）；单元格单击复制值（NULL → 空串）；右键“复制单元格值 / 复制本行 → INSERT”（转置视图下隐藏后者）
- 编辑器可选小增强：行号、当前行高亮 → 未做（Roadmap 原标注可选，验收清单不含）
落点：`SqlEditing.kt`（新增，smoke 直测）、`SqlWorkspace.kt`（EditorPane/ExecBar/ResultTable）、`Main.kt`（identifiers + 预取）、`JdbcSmoke.smokeEditorUtils`
验收：人工验证补全在深色/浅色下可读；Ctrl+T 转制与单击/右键复制数据无错位；浅色/深色均无黑字沉底。

### P3 树深化 + 连接安全（给“看库”与“存档案”补课）
范围：
- DataGrip 式多类型分组：PG 的物化视图/序列/routines/… 分类 + 计数（参考 TODO 示例结构）
- app.db 密码本机加密（AES-GCM + chmod 600 密钥文件），含存量明文迁移
验收：PG 库树展开到细分类型；重启后连接可用（加密不破坏现有会话）；明文不再落盘。

### P4 数据源扩展机制（为 Oracle/SQL Server 铺路）
范围：
- `<dataDir>/drivers` 外部驱动目录 + 独立 classloader 注册（Oracle license 限制不进内置 classpath）
- SQL Server 方言（Generic 兜底先通，再覆写分页/引号）；Oracle 按需
验收：把驱动 jar 放入 drivers 目录即能建连并展开树，无需改代码重启（或文档明确需重启）。

### P5 工程收敛与发布
范围：
- 单测起步：urlPreview / quoteIdent / isSystemSchemaName / 树行派生（jdbc、tree、db 层均可测）
- 清理：RightPane.kt 死代码（TODO 已列）
- M5 打包：首次 `gradle createDistributable`/Deb，干净环境安装自检（jlink 补 java.sql 等模块、图标、启动即出窗口）
验收：CI 习惯可跑 `compileKotlin + smokeJdbc + test`；Deb 安装后 demo 连接（SQLite）可开可查。

## 4. 阶段粒度建议

每阶段拆 2~5 次提交为佳；单提交=一个可感知的小能力（见 AGENTS 分层纪律与"提交前删调试代码"）。
UI 可感知变化需人工切深色复检（无黑字沉底、无过曝白块）后再交付。

## 5. 远期（观察项，不排期）

- 表数据编辑（DESIGN 明确第一版不做）
- SSH 隧道连接内网库
- ER 图 / 多窗口 / 插件体系（DESIGN 不做范围）
- 结果集虚拟滚动与流式分页（当前 1000 行上限 + 截断已够用，遇到大数据场景再升级）

## 6. 关联文档

- `AGENTS.md`：工具链、分层、主题硬性规则、验证习惯（验收口径）
- `doc/DESIGN.md`：数据模型、方言抽象、线程模型、打包风险
- `TODO.md`：零散即时事项（随做随删）
