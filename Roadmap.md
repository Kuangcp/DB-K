# db-k Roadmap（核心发展计划）

> 执行计划主文档：按阶段描述「要做什么、为什么这个顺序、怎么算完成」。
> 每阶段验收必须过 `AGENTS.md` 验证习惯（compileKotlin / smokeJdbc / test / 人工 UI 验收）。
> 零散即时事项在 `TODO.md`；架构与数据模型依据在 `doc/DESIGN.md`（里程碑章节与本文对应）。

---

## 1. 现状总览（截至当前）

单窗口 JDBC 数据库客户端，主闭环 + 体验打磨 + 工程化均已落地；剩余工作集中在
**发布验证（P8）**一条主线，以及若干可选深化项（集群级目录 / 行锁 / 诊断入口 P9）。

| 维度 | 现状 |
|---|---|
| 对象树 | 文件夹 → 连接（状态点/懒加载）→ schema → 对象按类型分组计数；PG 细分 11 类；组可折叠；展开持久化；**PG 组正文按需懒加载 + 计数前显（P6）** |
| 编辑执行 | 高亮 + 行号 + 当前行高亮 + 关键字/表/列补全；选中执行、多语句多 Tab；Ctrl+Q DDL；Ctrl+S 保存 |
| 结果区 | 网格滚动/列宽拖动/单元格选中复制/转置/CSV（含全量流式）；**单元格编辑 + 提交（带 UPDATE 预览，P7）+ 刷新 + 撤销**（Phase 0–3） |
| 查看器 | 长文本弹窗、JSON 树视图高亮折叠、Base64 图片预览 + MD5 |
| 控制台 | 跨源多标签；独立执行目标；光标记忆（持久化到 `consoles.caret_start/end`）；关闭可重开 |
| 安全/存储 | app.db SQLite v8（迁移 + FK 级联）；列缓存 v7；密码 AES-256-GCM；连接档案 JSON 导出/导入；控制台正文独立 .sql |
| 数据源 | PG / MySQL / MariaDB / SQLite / H2 / ClickHouse（HTTP，默认关压缩）；SQL Server / Oracle 走 `<dataDir>/drivers` 外部驱动（P4）|
| 工程 | 单测体系（jdbc/tree/db/app.state/app.ui）；smokeJdbc；Deb/AppImage/MSI 打包配置；AppImage 已产出 |

## 2. 里程碑状态（对齐 DESIGN §11）

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 脚手架 | ✅ | — |
| M1 元数据+树 | ✅ | — |
| M2 JDBC 运行时 | ✅ | 6 种内置数据源 + 2 种外部驱动数据源（P4） |
| M3 编辑执行 | ✅ | 取消/历史/多语句均已并入 |
| M4 体验打磨 | ✅ | 远超原范围（补全/编辑结果/查看器/设置/多控制台） |
| M5 打包发布 | ⏳ 配置就绪 | AppImage 已产出；Deb 未构建、干净环境未验证；MSI 需 Windows |

---

## 3. 已完成能力速览（压缩记录）

> 详细实现与踩坑见 `doc/COLUMN_COMPLETION.md`、`doc/EDITABLE_RESULT.md`，此处仅留检索锚点。

- **P1 执行闭环** ✅：长查询取消（Esc）、`sql_history` 落库 + 历史面板（查看/插入光标处）、>1000 行截断提示 + 全量流式 CSV 导出。
- **P2 编辑/结果体验** ✅：关键字 + 表/视图 + **列名**补全（`app/ui/SqlEditing.kt`，按需拉取 + 磁盘缓存 `column_cache`）、`Ctrl+T` 转置、单元格选中/Ctrl+C/右键复制与「复制本行 → INSERT」、行号/当前行高亮、`Ctrl+Q` DDL（语法高亮）、拖拽到边缘自动滚动。
- **P3 树深化 + 连接安全** ✅：`ObjectKind` 11 类分组计数（PG 生效，其余库只出自身类型）、密码 AES-256-GCM（密钥 `secret.key` 600，`enc:v1:`，存量明文自动迁移）。
- **P6 树懒加载** ✅：`SchemaObjects.counts` + 方言能力 `lazyObjectGroups`（PG 开启）——schema 展开只取计数 + 核心类型（表/视图/序列），重类型组展开时才拉；非 PG 默认回落全量、行为不变；缓存无迁移。
- **结果可编辑** ✅（Phase 0–3）：`QueryColumn` 来源元数据、`ColumnMeta.primaryKey`（无 PK 回落唯一索引）、`ResultEditPlan`/`RowUpdater` 纯逻辑 + 单测；行内编辑（Ctrl+双击）、长值/多行对话框（Phase 3）、提交（参数化 UPDATE + 单事务 + affected=1 校验）、**提交前 UPDATE 预览确认（P7）**、提交后固定刷新、撤销全部、退出/切换守卫（`DiscardResultEdits`）、未提交琥珀底纹。
- **查看器增强** ✅：JSON 识别 + 树视图、Base64 图片 + MD5（`app/dialog/JsonTreeView.kt`、`ViewerDialogs.kt`）。
- **控制台模型** ✅（原 M4 扩展）：`consoles` v8（closed 隐藏可重开）、每控制台执行目标、光标记忆（v6，防抖 1.5s + 切换/退出强制落库，写光标不动 `updated_at`）。
- **设置窗口** ✅：顶栏齿轮 → 独立 DialogWindow，编辑器字体族/字号 + 实时预览，`editor.properties` 持久化，版本号 `v<NAME>-<COMMIT>`。
- **工程收敛** ✅（原 P5 测试部分）：`gradle test` 覆盖 jdbc/tree/db/app.state/app.ui；`gradle smokeJdbc`；打包配置 `nativeDistributions` + `makeAppImage`。

---

## 4. 待实施路线（详细）

优先级逻辑：P4–P7 已落地；下一步收发布闭环（Deb + 干净环境 → P8），
再做诊断入口（P9）与两端可选深化（集群级目录 / 行锁）。
剩余阶段状态：P4 ✅ / P5 ✅ / P6 ✅（按组懒加载）/ P7 部分（预览 ✅，行锁未做）/ P8–P9 未开始。

### P4 数据源扩展机制（外部驱动，SQL Server / Oracle）✅ 已实现（待真实库人工验收）

**目标**：让 SQL Server / Oracle（Oracle 驱动有 license，不能进内置 classpath）在**不改代码**的前提下接入；
把驱动 jar 放进目录即可建连、展开树、执行查询。

**已落地**
- `<dataDir>/drivers/` 扫描 `*.jar`，独立 `URLClassLoader`（parent = `java.sql.Driver` 的 classloader）加载；
  驱动类来自 jar 内 `META-INF/services/java.sql.Driver` + `DbType` 声明的外部驱动类。
- **不走 DriverManager 注册**，直接 `driver.connect(url, props)`，规避 `DriverManager` 的 caller-classloader
  可见性校验；内置驱动仍走原 `Class.forName + DriverManager`。幂等、失败仅记日志、`-Ddbk.driversDir` 可覆盖。
- `DbType` 新增 `SQLSERVER` / `ORACLE`（`externalDriver=true`）+ URL 模板（SQL Server `;databaseName=`，
  Oracle thin `@host:port/service`）；`ConnectionEditorDialog` 未加载驱动时给黄字提示与目录路径。
- 方言：`SqlServerDialect`（`[]` 引号、`TOP` 预览、schema 列表、无会话 schema 切换、`supportsTargetSwitch=false`）；
  `OracleDialect`（`all_users` 过滤、`ALTER SESSION SET CURRENT_SCHEMA`、`ALL_TAB_COLUMNS` 列探测、
  `DBMS_METADATA.GET_DDL` + 回落、`FETCH FIRST` 预览）。
- 启动时 `ExternalDrivers.ensureLoaded()`（`Main.kt`）；新增 jar 需重启。
- 验证：`gradle test`（`ExternalDriversTest` 用 H2 jar 模拟投放驱动并真建连；URL/引号/预览用例）；
  `gradle smokeJdbc` 新增 external-drivers 段（扫描/加载/连接 + 两库 URL/预览）。

**待人工验收（需真实库）**
- 放入 SQL Server / Oracle 官方驱动 jar → 重启 → 新建连接可连、树可展开、查询出结果；
- 移除 jar 后对应驱动不可用但不影响其余库启动；
- 验收限制：SQL Server 暂按「schema 内对象」建模（跨 database 需改档案 database）；Oracle 仅 thin service 形式。

**关键坑**
- 驱动 jar 必须与运行 JDK 25 兼容；驱动自带依赖（如需）一并放 `drivers/`；
- 子 classloader 必须 parent=平台层，`parent=null` 会导致 `java.sql.Driver` 接口不可见而实例化失败。

---

### P5 连接档案导出 / 导入（可移植与备份）✅ 已实现

**目标**：换机迁移 / 备份连接档案；把散落的连接与文件夹一次性带走，而不暴露密码。

**已落地**
- 范围：**连接 + 文件夹层级**（不含控制台正文 / SQL 历史 / 元数据缓存）。
- 格式：单个 JSON（UTF-8），带 `formatVersion` / `app` / `exportedAt`；`decode` 拒绝更高版本、
  跳过未知 `dbType`（计入 `skipped`）、丢弃 bundle 内不存在的 folder 引用。
- 密码：**默认不导出**（`encodeDefaults=false` 直接省略 `password` 字段）；可选「含明文密码」
  导出，先弹风险确认（`ConfirmRequest.ExportWithPasswords`）。
- 导入：**幂等合并** —— id 冲突自动生成新 id，**绝不覆盖现有**；文件夹被重映射时其下连接的
  `folder_id` 同步重映射；单事务，失败全回滚。
- UI：树工具栏「⋮」溢出菜单：导出（不含密码）/ 导出（含明文密码）/ 导入；AWT `FileDialog`（同 CSV 导出）。
- 落点：`db/ProfileTransfer.kt`（DTO + 序列化/文件 IO）、`ConnectionsRepository.importProfiles`、
  `TreeState.importProfiles`、`app/core/Main.kt`（FileDialog + 接线）。

**验证**：`gradle test` 覆盖不含密码时无 `password` 字段 / 含密码回环 / 版本拒绝 / 未知类型跳过 /
 dangling folder 清理 / 二次导入 id 重映射不覆盖且 linkage 保持；`smokeJdbc` 绿。

**未做（可选）**：同名冲突的交互式「跳过 / 导入为新档案」选择（现统一导入为新档案，已满足不覆盖）；
加密导出（口令派生密钥）本轮不做。

---

### P6 树深化（大库性能 + 集群级对象）✅ 按组懒加载已实现

**背景**：组折叠早已实现（对象行只在组展开时渲染），但**对象清单仍随 schema 展开一次性拉取**
（`loadObjects` 一次返回全部类型）——routines 上千时首屏慢。本轮把「清单」也拆成按组懒加载。

**已落地**
- `SchemaObjects` 新增 `counts: Map<ObjectKind, Int>` 与 `countOf` / `isLoaded` 辅助：
  支持「有计数、无正文」的增量状态（已加载组以实际条数为准，未加载组用计数）。
- 方言新增能力开关 `DbDialect.lazyObjectGroups`（默认 false）与三个可覆写方法
  `loadObjectCounts` / `loadCoreObjects` / `loadObjectsForKind`（**默认回落全量 `loadObjects`**，
  非懒加载库行为完全不变）。
- **Postgres 唯一开启懒加载**：counts 走单条 UNION 计数查询；核心类型
  （表/视图/物化视图/序列，补全与首屏需要）随 schema 展开预取，重类型
  （触发器/例程/聚合/操作符/类型/操作符类/操作符族）**组展开时才查**；每条查询都绑定 schema 参数
  （顺带修掉旧版 `queryStrings` 未绑定 `?` 导致目录组静默失败的潜伏 bug）。
- `ConnectionsState`：懒加载分支（连接就绪时拉 schema + counts + core）、新增 `ensureGroupObjects`
  与 `groupObjectsLoadingOf`（内存 `groupLoading`）。
- 树：组行显示 `countOf`（未加载也有计数）；展开未加载组时出「正在加载…」占位，未触发时出「尚未加载」；
  `Main.kt` 的 `OBJECT_GROUP` 展开回调触发 `ensureGroupObjects`。
- `MetaCache` 载荷本就按 `SchemaObjects` 序列化（`encodeDefaults=true`），`counts` 自动持久化，**无需迁移**。

**未做（保留，低优先）**
- 集群级目录（Database Objects：casts/extensions/languages；Server Objects：roles/tablespaces 等）
  需跨 schema 聚合，未排期。
- 表子节点（列/索引/约束展开）——列信息仍只服务补全缓存，树不展示。

**验收**：`gradle test`（`SchemaObjects` 计数/占位、PG relkind/prokind 映射、树行计数与占位、
SQLite 默认计数/按组回落）+ `gradle smokeJdbc` 绿；非 PG 库视觉与行为不变。
含上千例程的实库人工验收待做。

---

### P7 可编辑结果后续（UPDATE 预览 / 行锁 / 边界）

**已实现**：行内 + 对话框编辑、提交（参数化 + 单事务 + affected=1）、提交后刷新、撤销全部、守卫（见 `doc/EDITABLE_RESULT.md` §13）。

**本轮新增**：提交前 UPDATE 预览（已完成）。

**剩余**
1. ~~**UPDATE 预览**~~ ✅ 已实现：点「提交」不再直接执行——先弹 `CommitPreviewDialog`
   （显式尺寸 `DialogWindow`，SQL 高亮只读）展示 `RowUpdater.renderUpdateSql` 渲染的逐行 UPDATE，
   确认后才真正提交；取消不产生任何修改。`ConsoleState.previewCommit` 为纯只读渲染
   （与实际提交共用 `buildUpdatePlans`，两者天然一致）。
2. **行锁**（可选，未做）：刷新编辑结果时可用 `SELECT … FOR UPDATE`（PG/MySQL/Oracle 支持）降低并发覆盖；
   当前靠提交时 `affected==1` 检测（`0`=行被删/改、`>1`=定位不唯一）。需评估对只读查询的副作用。
3. **明确仍不做**：新增行 / 删除行；富类型（BLOB/图片/JSON 结构）就地编辑仍走查看器。
   若后续要支持，需先设计主键生成、批量删除二次确认与事务边界。

**验收**：预览 SQL 与实际提交一致；取消预览不产生修改；深浅色下弹窗文字可读。

---

### P8 发布验证与分发（Deb / AppImage / MSI）

**目标**：把「配置就绪」变成「产出可安装包并在干净环境验证」，完成 M5。

**范围**
1. **Deb**：`gradle packageDeb` 产出 `build/compose/binaries/main/deb/db-k_<ver>-1_amd64.deb`；
   干净环境（临时用户 / 容器）`dpkg -i` 后启动。
2. **AppImage**：`build/compose/binaries/main/appimage/db-k-1.0.0-x86_64.AppImage` 已产出，
   补一次干净环境运行验证（glibc ≥ 2.28，见 `doc/PACKAGING.md`）。
3. **MSI**：仅 Windows + WiX Toolset 3.x 可构建（jpackage 不支持交叉打包），需在 Windows 机器产出并安装验证。
4. **干净环境自检清单**：
   - 启动即出窗口；图标/菜单/快捷方式正确；
   - jlink 运行时含 `java.sql/java.naming/java.management`（已在 `build.gradle.kts` 配置）；
   - SQLite 演示连接可开可查；内置 6 驱动均可加载；
   - 数据目录 / 日志目录按平台约定创建。
5. **CI 习惯**：一条命令跑 `compileKotlin + test + smokeJdbc`（可加 `Makefile` target）。

**验收**：Deb 安装后可跑 demo 查询；AppImage 在干净环境可启动；MSI 在 Windows 安装可跑；
打包产物版本号与设置窗口 `v<ver>` 一致。

---

### P9 诊断与易用性小项

1. **「打开日志目录」入口**（`TODO.md`）：设置窗口「通用设置」显示日志路径 + 按钮，
   `Desktop.getDesktop().open(logDir)`；无桌面环境时回落复制路径 + Toast。
   可顺带加「打开数据目录」。
2. **（可选）保存的查询**：DESIGN 的后置里程碑 `saved_queries`（命名查询收藏）尚未实现，
   与 sql_history 不同，需要文件夹/命名/编辑 UI；按需排期（见 §6）。

**验收**：点击能打开对应目录（或正确提示）；不影响设置窗口深浅色可读性。

---

## 5. 阶段粒度建议

每阶段拆 2~5 次提交为佳；单提交 = 一个可感知的小能力（见 `AGENTS.md` 分层纪律与「提交前删调试代码」）。
UI 可感知变化需人工切深色复检（无黑字沉底、无过曝白块）后再交付。

## 6. 远期（观察项，不排期）

- 表数据编辑：新增行 / 删除行（当前只支持改格；DESIGN 第一版明确不做）。
- SSH 隧道连接内网库。
- 保存的查询（saved_queries）收藏与分组。
- 结果集虚拟滚动与流式分页（当前 1000 行上限 + 截断已够用，遇大数据场景再升级）。
- 集群级对象目录（若 P6 未覆盖：casts/extensions/languages、roles/tablespaces 等跨 schema 聚合）。
- ER 图 / 多窗口 / 插件体系（DESIGN 明确不做范围）。

## 7. 关联文档

- `AGENTS.md`：工具链、分层、主题硬性规则、持久化分层、验证习惯（验收口径）。
- `doc/DESIGN.md`：数据模型、方言抽象、线程模型、打包风险。
- `doc/EDITABLE_RESULT.md`：可编辑结果设计 + Phase 0–3 实施进度。
- `doc/COLUMN_COMPLETION.md`：列补全设计 + P0–P4 实施进度。
- `doc/PACKAGING.md`：Deb / AppImage / MSI 构建矩阵与前置条件。
- `TODO.md`：零散即时事项（随做随删）。
