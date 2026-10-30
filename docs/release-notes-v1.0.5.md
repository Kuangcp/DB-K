# db-k v1.0.5 Release Notes

> 本次从 `v1.0.3` 起累计 **100 个提交**、**117 个文件**（+9509 / −822）。
> 重点：**Live Templates（SQL 速写模板）**、**控制台标签多行换行**、**AppImage 瘦身 ~11%**，以及一系列编辑器补全与结果区体验改进。

---

## 🆕 新功能

### Live Templates — SQL 速写模板

全新引入 Live Templates 体系，让用户可以用简短前缀快速展开常用 SQL 结构：

- **用户自定义 `.json` 模板文件**叠加内置模板表，编辑后按 diff 智能平移占位符区间。
- 编辑器中触发 **Tab 展开**、占位符高亮显示、会话跳转导航；模板也进入补全候选弹层并标记 `CompletionKind.TEMPLATE` 语义色。
- 设置窗口新增「模板管理」表格页 + 生成示例 / 打开目录入口；保存即时生效、无需重启。
- 多光标模式下不触发 Tab 展开，避免干扰；模板内容不进入限定符上下文，保证作用域干净。

### 控制台标签多行换行

- 控制台标签条支持**多行换行**，单行模式下的溢出部分支持横向滚动。
- 自动滚动至**当前激活的标签**，标签页多了不再需要反复左拉才能找到目标。

### 连接密码可见切换（👁️）

编辑连接时，密码输入框右侧新增眼睛按钮。点击可切换明文/圆点显示，手动查看后自行复制即可。默认仍为隐藏态。

### 复制连接信息

编辑连接弹窗底部新增「复制连接信息」按钮。一键将标准 URI 格式的连接串复制到剪贴板，可直接粘贴到 DBeaver、DataGrip、终端工具等：

| 类型 | 输出示例 |
|------|----------|
| PostgreSQL | `postgresql://user:pass@host:port/db` |
| MySQL/MariaDB | `mysql://user:pass@host:port/db` |
| ClickHouse | `clickhouse://user:pass@host:port/db` |
| SQL Server | `sqlserver://user:pass@host:port;databaseName=db` |
| Oracle | `oracle:thin:@user:pass@host:port/db` |
| H2 TCP | `h2:tcp://user:pass@host:port/db` |
| H2 Local | `h2:file:path` |
| SQLite | `sqlite:///abs/path.db` |
| Redis | `redis://:pass@host:port?db=N` |
| Elasticsearch | `http(s)://user:key@host:port` |

特殊字符（`:`, `@`, `%`）在用户/密码部分会被正确百分号编码。

---

## 🔧 底层改进

### 查询超时（Query Timeout）— 三后端统一

新增 `engine.QueryTimeout` 语义层，JDBC 执行/写回、Redis socket 超时、ES 请求超时统一走连接档案配置：

- 连接编辑弹窗支持可视化配置查询超时值，给可读提示而不把驱动原文暴露给用户。
- PostgreSQL 将 `57014` 错误归一成统一的超时类型，手动取消仍保留驱动原始报错文案。

### 元数据加载性能优化

- **异步分片加载**：大库不再一次性拖慢界面；查询成功后自动注册表名到补全目录。
- 分页版对象加载替代跳过大库的问题；`limit huge meta cache` 控制缓存上限。
- Progressive per-schema metadata loading：大库元数据逐 schema 渐进式加载，不阻塞 UI。

### AppImage 体积优化

- Linux AppImage 通过去除调试符号、按构建平台裁剪 SQLite 原生库、xz 压缩等，体积从 ~116 MB 降至 **~103 MB**（减少约 11%）。

---

## ✨ 优化与修复

### 编辑器 / 补全增强

- **子串匹配**：编辑器补全从前缀匹配改为子串匹配，长表名更容易被命中。
- 断开连接时仍可正常使用编辑器补全（基于本地缓存）。
- 支持编辑器注释切换 `Ctrl+/`。
- 修复 ALTER TABLE / CREATE INDEX 补全：目标表正确登记到限定符上下文，DML 列补全恢复正常。
- Compose `derivedStateOf` 无限重组死循环已修复（补全列表派生逻辑）。
- X11 下 `Ctrl+/` 泄漏 `KEY_TYPED '/'` 导致多余输入已修复。

### 结果区改进

- 查询结果上限 500 行，避免超大结果集拖慢渲染。
- 结果区窄列缩短、小缓冲留白，表格更紧凑。
- PAGE_SIZE 从 1000 调到 2000，首屏补全更多表名。
- COPY 选中行 → INSERT 语句改为**完整列 + 完整行值**（与复制本行一致），控制字符正确转义。
- 右键菜单改为级联结构：复制选中行 / 复制本行 → 二级 TSV / CSV / INSERT。
- TEXT / JSON 等不可编辑单元格现在可以通过 Ctrl+双击正常弹出只读编辑对话框。
- SQL INSERT 转义完善：完整处理 `\n`, `\r`, `\t` 及单引号。

### 连接与展示优化

- 连接编辑弹窗禁止点击外部遮罩关闭，防止误触丢失已填写的数据。
- 连接编辑弹窗高度上限 480dp → 540dp，适配所有表单字段无需滚动。
- 时间类型按连接配置的 display timezone 渲染（支持多时区展示）。
- JDBC 内置驱动启动时预加载，首次连接等待从 ~3s 降到可感知消失。
- 顶栏「状态 + 连接/断开」合并为一个切换按钮，空间更紧凑。
- 编辑器字体设置（`EditorSettings`）同步应用于 DDL 查看器等弹窗组件。
- DDL 查看器弹窗支持自由文本选择（便于复制 EXPLAIN 等多行文本）。

### 其他修复

- PG `57014` 错误码统一归一化处理。
- CI 自动化：tag 触发 Linux/Windows 打包与 GitHub Release 发布。

---

## 📦 安装与升级

| 格式 | 说明 |
|------|------|
| **AppImage**（推荐 Linux） | `db-k-1.0.5-x86_64.AppImage`，glibc ≥ 2.28 |
| **Deb** | `gradle packageDeb` → `db-k_1.0.5_*.deb` |
| **MSI**（Windows） | `gradle packageMsi` → `db-k-1.0.5.msi` |
| **免安装目录** | `gradle createDistributable` |

升级无需迁移操作：本地数据（连接 / 控制台 / 历史 / 主题 / 工作区）自动兼容。

---

## 📊 校验

- `gradle test`：测试通过（方言 / 存储迁移 / 外部文件 / 复制导出 / i18n 一致性等）。
- `gradle smokeJdbc` 通过（SQLite + H2 + cancel + 元数据 + 编辑器工具 + 外部驱动加载）。
- `gradle smokeRedis` / `gradle smokeEs`：连得上才跑，连不上 SKIP。

---

*Tag: [v1.0.5](https://github.com/your-org/db-k/releases/tag/v1.0.5) | SHA: fe8c0c2*
