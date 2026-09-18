# db-k v1.0.2 发布公告

> **Release 标题建议**：`db-k v1.0.2`
>
> 本次从 `v1.0.1` 起累计 32 个提交、97 个文件（+8375 / −915）。
> 重点：**Elasticsearch 后端**、**Redis 浏览/查看体验全面增强**、**行级写操作**、
> **多格式导出**、**编辑器原生撤销**，以及一批内存/稳定性修复。

1.0.2 把「多协议数据工具」再往前推一步：新增 Elasticsearch 数据源；Redis 从
「能连、能查」升级为「层级浏览 + 双击即看 + 专用结果视图」；关系型结果继续补强
写入与导出能力。

---

## ✨ 新特性

### Redis 浏览 / 查看体验（本版重点）
- **Key 层级目录**：数据源可配置 key 分隔符（默认 `:`），`a:b:c` 会渲染成可逐级展开的
  目录树，叶子显示最后一段；留空则退回平铺。分隔符随连接档案持久化并可导出/导入。
- **双击直接查看**：双击 / 右键 Redis key 直接按类型执行 `GET/HGETALL/LRANGE/…` 并把
  结果渲染到结果区（不再只是往编辑器插命令）。
- **专用 Redis 结果视图**（与关系型网格分开）：
  - string → 标量；hash → 字段/值两列；list/set → 成员列表；zset → member/score。
  - 任意原生命令 → 等宽文本回退。
  - 字段列宽可拖拽；字段 / 值 / 成员 / 标量均可**双击弹出详情窗**（大段文本、JSON 高亮/树）。
  - 顶部展示 `key · type · TTL`；工具栏保留刷新 / 导出 / 取消。

### 新数据源：Elasticsearch（N6）
- 基于 `java.net.http.HttpClient` + JSON DSL 实现，无需 JDBC 驱动。
- 索引 / 别名作为对象树节点；控制台写 DSL 查询，编辑器提供**上下文补全**（键 / 查询类型 / 字段名）。
- 受限账号下索引/别名列出做权限宽容降级，缺权限不再整棵失败。

### 行级写操作（N3）
- 结果网格支持**新增行**与**标记删除行**（主键定位），与既有单元格编辑共用提交事务；
  提交前预览、影响行数校验、撤销与退出守卫保持一致。

### 多格式导出（N8）
- 在 CSV 基础上新增 **JSON / SQL INSERT / Excel** 导出。
- Excel 用 Apache POI 流式写出；全量流式导出走方言游标，避免大结果集内存峰值。

### 编辑器增强
- 迁移到 Compose `TextFieldState`：**每个控制台独立原生撤销栈**，切控制台不再丢 undo。
- 补全浮窗：宽度按候选内容自适应（不再截断长表名）、支持点击浮窗外区域收起、去掉空闲自动关闭。
- `Ctrl+双击` 稳定进入单元格编辑；编辑弹窗改用自适应尺寸的 `DialogWindow`。
- 修复长 JSON 卡死与切换控制台正文串台两个历史坑。

### 左侧树搜索
- 支持**搜索 / 高亮 / 跳转**：命中节点及其祖先保留并强制展开，Enter/↑↓ 在命中项间跳转。
- 支持**数据源级范围**（右键连接「在此数据源中搜索」），未连接数据源可搜本地缓存。

### UI / 其它
- 主窗口改为**无边框自定义标题栏**（最小化 / 最大化 / 还原 / 关闭 + 拖拽移动）。
- 连接编辑弹窗按内容自适应高度，底部元素不再被隐性截断。
- Windows 控制台中文乱码修复；非 Linux 平台跳过 glibc 内存调优。

---

## 🐛 修复
- JDBC 连接空闲被踢后**用前自动重连**（读操作自动重试）。
- 大字段内存治理：单格截断 + 结果字符预算 + 网格只渲染前缀预览，避免大文本拖垮渲染。
- 图片查看器显式释放 Skia 原生内存；glibc arena 原生内存治理，反复查看不再只涨不降。
- 拖拽选区自动滚过后保持起始锚点。
- `smokeJdbc` 自检结束清理临时目录，不再堆爆 `/tmp`。

## ⚠️ 行为变更 / 注意
- Redis key 双击 / 右键「查看键」：由「插入命令到编辑器」改为**直接执行并渲染结果**。
- Redis 结果区不再使用关系型网格（编辑 / 转置 / 取更多仅对关系型结果生效）。

---

## 📦 安装与升级

- **AppImage**（单文件）：`db-k-1.0.2-x86_64.AppImage`，需 glibc ≥ 2.28（Debian 10+ / Ubuntu 20.04+ / RHEL 8+）。
- **Deb**：`gradle packageDeb` → `db-k_1.0.2_*.deb`。
- **MSI**（仅 Windows 构建）：`gradle packageMsi` → `db-k-1.0.2.msi`，升级 UUID 固定，可直接覆盖升级。
- **免安装目录**：`gradle createDistributable`。

升级无需迁移操作：本地数据（连接 / 控制台 / 历史）自动兼容；新增配置为独立 `*.properties`。
`connections` 表新增 `key_separator` 列（迁移 v9，默认 `:`），旧数据自动补齐。

> 外部驱动（SQL Server / Oracle）需自行下载 jar 放入数据目录 `drivers/` 后**重启**应用。

---

## 📊 变更统计
- 提交：**32**（`v1.0.1` → `v1.0.2`）
- 文件：**97**（+8375 / −915）
- 数据源：新增 **Elasticsearch**；**Redis** 浏览/查看体验增强

## 📝 完整变更
```
c6affe6 chore: bump version to 1.0.2
e39afca refine(redis): Redis 结果视图细节优化
7a77209 feat(redis): 独立 Redis 结果视图（按类型渲染 + TTL/复制/JSON 树）
324b486 feat(redis): 双击/右键 Redis key 直接执行查看命令并渲染结果
2c89f23 feat(redis): 按键分隔符把 Redis key 渲染成层级目录
c79c397 feat(tree): 树搜索支持数据源级范围与离线缓存搜索
b7d5135 fix(tree): 对象类型/数据源徽章文字不再只剩字头
136a050 feat(tree): 左侧树支持搜索 / 高亮 / 跳转
0d475d1 fix(editor): Ctrl+双击稳定进编辑；编辑弹窗改用 DialogWindow 并按内容自适应
5b45612 feat(editor): 编辑器迁移到 TextFieldState（每控制台原生撤销）并修两个坑
ce174ea fix(editor): 修复长 JSON 卡死与切换控制台正文串台
da80437 feat(editor): 补全浮窗支持点击浮窗外区域收起
b66783b fix(editor): 补全弹层宽度自适应不再截断长表名；去掉空闲自动关闭
bd55c4b fix(smoke): smokeJdbc 自检结束清理临时目录，不再堆爆 /tmp
b4170b2 feat(ui): 主窗口改为无边框自定义标题栏（最小化/最大化/关闭 + 可拖拽）
bc2cc13 fix(dialog): 连接编辑弹窗按内容自适应高度，底部元素不再被隐性截断
5dcc57b fix(build): run 任务改用 doFirst 追加 stdout/stderr 编码
4b2d263 fix: Windows 控制台中文乱码 / 非 Linux 跳过 glibc 内存调优
a4f4591 fix(jdbc): 连接空闲被踢后自动重连（用前校验）
d26cc45 fix(editor): 拖拽选区自动滚过后保持起始锚点
54d4117 fix(memory): glibc arena 原生内存治理 + 图片查看器缩略解码
4942ad5 fix(viewer): 图片预览显式释放 Skia 原生内存（base64 大图反复查看不再只涨不降）
3e3c447 fix(result): 大字段内存治理（单格截断 + 结果字符预算 + 网格预览）
caf63a8 feat(export): N8 JSON / SQL INSERT / Excel 导出（POI 流式 + 方言游标）
b9a0bad feat(es): 编辑器 ES DSL 上下文补全（键/查询类型/字段名）
df3b241 fix(es): 受限账号下索引/别名列出权限宽容降级
b1f15ca feat(es): N6 Elasticsearch 后端（HttpClient + JSON DSL）
b7efa6d feat(result): N3 行级写操作（增行 / 删行，主键定位）
a242ee8 rename
```

**Full Changelog**: https://gitee.com/gin9/db-k/compare/v1.0.1...v1.0.2
