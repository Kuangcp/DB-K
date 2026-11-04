# 配置云同步（git 仓库）设计

日期：2026-11-04
状态：设计待确认（用户已定：系统 git / 整条连接加密 / 控制台 SQL 明文 / 冲突按实体取舍 / 口令记住）
关联：`AGENTS.md`「持久化分层」、`db/ProfileTransfer.kt`、`db/PasswordVault.kt`、api-x `app/settings/DataDirSync.kt`

## 1. 背景与动机

用户在多台设备（家里/公司/笔记本）使用 db-k，希望数据源配置、控制台 SQL、常用设置能跟着走，
换机不用重配。选用 git 仓库作为传输与版本介质：自带历史、可离线、可自建、可多设备合并。

api-x 已有半成品：`<dataDir>/data/{collection,env}` + 顶栏 Push/Pull（`DataDirSync.kt`）。
它只导出到目录、合并只增不删、不同步设置、不调 git。本次设计把它升级为**可复用的同步引擎**，
db-k 先落地，api-x 之后按同一约定接入。

## 2. 目标

1. db-k 的「连接档案（含文件夹）+ 控制台（元数据 + SQL 正文）+ 工作区 + 三类设置」可在一台设备
   推送、另一台拉取，命令级无冲突时全自动。
2. 连接的关键字段（host/port/database/user/password）**加密后才进仓库**；控制台 SQL 明文进仓库
   （可读历史、可 diff 合并）。
3. 口令只在设备本机保存，仓库里没有口令；首次输入后记住，重启无需重输。
4. 冲突按实体整份取舍，界面列出冲突项、显示两侧摘要，用户点选。
5. 同步引擎与具体应用解耦：同一套 `sync/` 代码可被 api-x 复用（adapter 只换实现）。

## 3. 非目标

- 不做自动定时同步、不做后台常驻监听（只在用户点按钮 / 退出时动作）。
- 不做多分支、不做 history 回滚 UI（回滚交给 `git` 命令行，文档里给命令）。
- 不做增量/二进制打包格式（一实体一文件，交给 git 存）。
- 不迁 `sql_history`、元数据缓存、窗口几何、树展开、光标、UI 缩放、主题明暗选择、Redis 视图偏好。
- 不做服务端，不内置 OAuth/账号体系。

## 4. 反目标（技术上能跑也不算成功）

- 拉取会静默丢本地未推送的改动。
- 每次启动/每次同步都提示输口令（用户明确要求记住）。
- 未改动的连接每次同步都产生新提交（密文 IV 随机 → 必须按明文比对去抖）。
- 口令、明文密码出现在日志 / 提交信息 / 冲突摘要里。
- 同步失败把本地库写成半状态（要么整体应用，要么不动）。

## 5. 约束

- 工具链/分层：遵循 `AGENTS.md`。新叶层 `sync/` 只依赖 JDK + kotlinx-serialization + tinylog；
  **不得** import compose / coroutines / db / app。
- `jdbc`/`redis`/`es` 等后端层不得被卷入同步（同步只碰 `app.db` 与 `<dataDir>` 文件）。
- 所有用户可见文案走 `i18n`（`Str` key + zh/en 双语），状态模型只存 key + 参数。
- 新增持久化按「持久化分层」判据：同步配置（远端 URL、本地目录、口令）属应用级偏好 →
  `<dataDir>/sync.properties`（600），不落 SQLite。
- 传输依赖系统 `git`（`ProcessBuilder`），不引入 JGit；启动/使用前探测 `git --version`。
- `debugHome` 重定向必须继续生效：仓库默认目录 = `<dataDir>/sync`。

## 6. 方案对比与选择

### 6.1 传输方式（已选 1）

| 方案 | 优点 | 代价 |
|---|---|---|
| **1. 调系统 git（选中）** | 免包体；ssh key / credential helper / 2FA 直接复用；git 合并语义原样可用 | 需机器有 git；需要探测与失败提示 |
| 2. 内置 JGit | 无外部依赖，https+token 应用内配 | 包体 +12MB；ssh 需 mina-sshd；错误面自己兜 |
| 3. 只维护工作树，git 交用户 | 零依赖零凭证，兼容 Syncthing | 每次手动敲命令；冲突检测仍要自己做 |

选 1 的理由：目标用户是开发者，git + ssh key 已在环境里；`sql_history`/大库不进仓库，仓库很小，
不需要 JGit 的“可控性”换 12MB 和 ssh 复杂度。

### 6.2 加密粒度（已选 B）

| 方案 | 仓库内容 | 冲突/历史 |
|---|---|---|
| A 只加密 password 字段 | 其余明文 | git 可读可三路合并 |
| **B 整条连接条目加密（选中）** | `.enc` 二进制 blob | 连接冲突只能整份取舍；`git log/diff` 对连接不可读 |
| C 整仓加密（git-crypt 式） | 全密文 | 连 SQL 历史也丢失，密钥管理更复杂 |

选 B：用户要求「IP/端口/库名/用户名/密码都要加密」。控制台 SQL 保持明文（用户明确），
SQL 的文本三路合并因此仍然可用。

### 6.3 合并语义（已选 A）

| 方案 | 做法 | 风险 |
|---|---|---|
| **A 仓库工作树为合并面（选中）** | 拉取前先把本地状态导出成一次本地提交 → `fetch` + `merge` → 冲突取舍 → 导入合并结果 | 依赖“先提交本地快照”，需要首次接入守卫 |
| B 按实体时间戳 LWW | 不走 git merge，比较 updatedAt 取新 | 丢失 git 语义；删除/并发覆盖静默丢数据 |
| C 只读远端 | 拉取时远端全量覆盖本地 | 本地未推送改动必丢 |

选 A：git 的三路合并天然正确处理「一边删、一边没改」，删除语义不用自造墓碑表；
冲突只在真正两边都改时出现，正好对上用户「冲突就整份取舍」的预期。

## 7. 仓库布局

一个仓库可同时服务多个应用，按应用分命名空间；每个应用内部再按实体分文件（冲突粒度 = 实体）。

```
<repo>/
  README.md                      # 由应用生成：说明这是 db-k/api-x 同步仓库，勿手改 .enc
  db-k/
    salt.bin                     # 16B 随机盐（口令派生用，首次 push 时生成）
    secret.check                 # AES-GCM 加密的固定串，用于校验口令是否正确
    meta.json                    # { "formatVersion": 1, "app": "db-k" }（向前兼容用）
    folders/<folderId>.json
    connections/<connId>.enc     # 整条连接（含 password 明文）密文
    workspaces/<workspaceId>.json  # 名称 + 成员 consoleId 列表
    consoles/<consoleId>.meta.json # name/connectionId/target/sortOrder/updatedAt
    consoles/<consoleId>.sql       # 正文，明文
    settings/live-templates.json
    settings/keymap.json           # properties → JSON（key 是命令 id，值是键位串）
    settings/themes.json
  api-x/                         # 后续接入，同套约定
    ...
```

规则：

- **id 即文件名**，现有 24 位 UUID 稳定；重命名不换 id。
- 文件只由应用写；`README.md` 明确「`*.enc` 是密文，手工编辑会损坏」。
- 写盘统一「临时文件 + ATOMIC_MOVE」（复用 `ConsoleFiles.write` 的做法）。
- JSON 一律 `prettyPrint = true`、`encodeDefaults = false`、字段顺序由 DTO 固定 → diff 稳定。
- 集合型文件（folders/workspaces/consoles/settings）**不用单一大文件**，避免整份文件冲突。
- 仓库根 ` .gitattributes`：`db-k/**/*.enc -text`（二进制，禁换行归一化）；`db-k/**/*.sql text eol=lf`。
  git 调用统一带 `-c core.quotepath=false`，避免中文文件名被转义。

连接条目明文（加密前的 JSON；字段与 `ProfileTransfer.ConnectionDto` 同构但**不复用**该类——同步 DTO
需要独立的 `formatVersion`/`updatedAt` 演进，且不能和导出/导入文件格式互相绑架）：

```json
{
  "formatVersion": 1,
  "id": "8f2c...",
  "name": "prod-pg",
  "dbType": "POSTGRES",
  "folderId": "a1b2...",
  "host": "10.0.3.7",
  "port": 5432,
  "database": "orders",
  "user": "app",
  "password": "…",
  "extraParams": "",
  "color": "#5586E4",
  "sortOrder": 3,
  "keySeparator": ":",
  "displayTz": "",
  "queryTimeoutSeconds": 20,
  "updatedAt": 1762300000000
}
```

## 8. 加密与口令

- 盐：`db-k/salt.bin`（16B 随机）。仓库已有则读取；没有（首台设备首次 push）则生成并写入。
- 密钥：`PBKDF2WithHmacSHA256(passphrase, salt, 200_000, 256)`；实现沿用
  `ProfileTransfer.deriveKey` 的参数，抽到 `sync/SecretBox.kt`。
- 单条加密：AES-256-GCM，12B 随机 IV + 128bit tag，
  文件体 = `dbk-sync:v1:<base64(iv || ciphertext)>`（文本文件，便于肉眼确认是密文）。
- 口令校验：`db-k/secret.check` = 加密后的固定串 `db-k-sync-v1`；拉取/解密前先解它，
  失败即「口令不正确」，**在写任何本地数据之前**终止。
- 口令缓存：`<dataDir>/sync.properties` 的 `passphrase`（POSIX 下 chmod 600，Windows 忽略）；
  首次输入时提示、成功后保存，之后不再问。设置窗口提供「清除已记住的口令」。
- 换口令：设置窗口入口「更换同步口令」→ 旧口令校验 → 用新口令重加密全部 `.enc` + `secret.check`
  （盐可复用）→ 产生一次提交，其它设备拉取后需输入新口令（旧口令解密失败 → 明确提示）。
- **去抖（关键）**：GCM 随机 IV 会让相同明文产生不同密文。导出时先解密仓库中已有的
  `<id>.enc`，与本次要写的明文 JSON 做字符串比较，**相同则不写**；不同才写。保证
  「没改连接 → 无新提交」。
- 明文密码只在内存与 `.enc` 明文结构里存在：导入本地库时仍走
  `PasswordVault.encrypt(secret.key, …)`，本机 SQLite 里依旧是机器本地密文。
- 日志脱敏：同步相关日志只记录文件路径、实体 id、数量、git 退出码；不打印口令、密码、
  连接明文 JSON。

## 9. 同步算法

### 9.1 组件

```
sync/                        ← 纯叶层（无 compose/coroutines/db 依赖）
  SecretBox.kt               口令派生 + AES-GCM 加解密 + 口令校验文件
  GitRepo.kt                 系统 git 封装（run/探测/分支/init/clone/fetch/merge/commit/push/冲突查询）
  SyncPaths.kt               仓库路径约定（app 命名空间、实体文件路径 ↔ id 双向）
  SyncModels.kt              DTO：FolderSync/ConnectionSync/WorkspaceSync/ConsoleMetaSync/ConflictItem/SyncResult
  SyncAdapter.kt             接口：exportLocal() / importRemote(plan) / listLocalIds()
  SyncEngine.kt              导出→提交→合并→冲突取舍→导入 的流程编排（纯同步、可单测）
app/sync/
  DbKSyncAdapter.kt          读 SQLite + <dataDir> 文件 → SyncModels；把合并结果写回（单事务）
  SyncPrefs.kt               <dataDir>/sync.properties 读写（远端 URL/本地目录/分支/口令/开关）
  SyncService.kt             单线程执行器 + 运行中状态 + 与 UI 状态桥接（IO 调度）
app/state/
  SyncState.kt               可观察状态：configured/running/lastResult/conflicts
app/dialog/
  SyncSection.kt             设置窗口「云同步」分区
  SyncConflictDialog.kt      冲突取舍弹窗
```

`SyncAdapter` 的最小接口（保持应用无关）：

```kotlin
interface SyncAdapter {
    /** 本地全量同步态（已按 sync 语义过滤：不含 external 控制台、caret、closed 等）。 */
    fun exportSnapshot(): SyncSnapshot
    /** 拉取后把合并结果写回本地；必须在单事务内完成，失败整体回滚。 */
    fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds)
}
```

`SyncEngine` 不感知 db-k：它只搬 `SyncSnapshot`（内存对象）与 `Map<relativePath, ByteArray>`。
加密在引擎层做（`connections/<id>.enc` 由引擎序列化+加密），adapter 只给明文结构，
**adapter 拿不到口令、也碰不到 git**。

### 9.2 动作一：推送（push）

```
1. 检查已配置（远端 URL、本地目录、口令已记住）→ 否则提示去设置
2. ConsoleState.flushAllSync()        // 编辑器 buffer 落盘（AGENTS 约定）
3. adapter.exportSnapshot()           // 读本地全量同步态
4. engine.writeTree(snapshot)         // 写/删工作树文件；.enc 按明文比对去抖
5. git add -A -- db-k && git commit   // 无变更则跳过 commit（不算失败）
6. git push origin <branch>
   - 被拒（non-fast-forward）→ 不改本地，提示「远端有新提交，请先拉取」
7. 记录 lastSyncAt / 变更计数，刷新状态
```

### 9.3 动作二：拉取（pull）

```
0. 口令校验（secret.check）→ 失败即止，不写任何本地数据
1. 若本地目录无 .git：有远端 URL → git clone 到该目录；否则 git init + 设 local user.name/email
2. 首次接入守卫：本地同步态为空（0 连接 0 控制台）而远端 db-k/ 有内容
   → 跳过第 3 步的“本地快照提交”（避免用空状态提交删除），直接进入导入
3. adapter.exportSnapshot() + writeTree + commit（本地快照先落地，保证不丢本地改动）
4. git fetch origin <branch>
5. git merge --no-edit origin/<branch>
   - 无冲突 → 跳到 7
6. 冲突取舍：
   - 列出 `git status --porcelain` 中的 UU/AA/DU/UD 路径（只认 db-k/ 下）
   - 每个冲突项拿 :2:（本地/ours）与 :3:（远端/theirs）两份内容
     · .sql → 文本差异摘要（两侧行数 + 差异块前几行）
     · .enc → 解密后摘要（name/host:port/db/user，password 显示为 ****）
     · 其它 JSON → 解析后摘要（如 folders/<id>.json → 文件夹名）
   - 用户在弹窗逐项选「本地 / 远端」；取消 = 整次拉取放弃（reset --merge 回滚到第 4 步前）
   - 选择结果写回工作树 + git add + git commit（解决冲突的合并提交）
7. adapter.applySnapshot(mergedTree, removed)   // 单事务 + 文件写入 + 设置重载
8. 可选（默认开）：git push（把合并结果共享出去）
9. 刷新 UI（树、控制台列表、设置内存态），记录 lastSyncAt
```

失败与恢复：任何一步失败 → 保留工作树现状、记录错误摘要；重新点拉取即可继续（步骤幂等）。
若工作树中途处于 merge 状态，下次拉取前先 `git merge --abort` 并提示。

**无共同祖先**：用户在本地 `git init` 后又填了已有历史的远端 URL → merge 会报 unrelated histories；
此时用 `git merge --allow-unrelated-histories`，冲突照常进取舍弹窗。若本地仓库还没有任何提交
（纯新空仓库），直接 `fetch` + `reset --hard origin/<branch>`，不做合并。

### 9.4 删除语义

- 删除 = 文件消失。推送时 `writeTree` 会删掉本地已不存在的实体文件，产生 delete 提交。
- 一边删、一边没改 → git 合并保留删除（无需墓碑）。
- 一边删、一边改 → 冲突（DU/UD），进取舍弹窗。
- 删除连接：本地 FK 级联删控制台与 `.sql`（现有行为），导出时这些文件一并消失。

### 9.5 导入的应用顺序（adapter 内部）

1. 校验：所有 `.enc` 先解密（任一失败 → 整体放弃，提示口令/仓库损坏）。
2. 单事务写 SQLite，顺序：`folders` → `connections` → `workspaces` → `workspace_consoles` → `consoles`，
   每类「按 id upsert，再删掉不在合并结果里的 id」。
   **upsert 只写同步字段**：`consoles.caret_start/end`、`consoles.closed`、`consoles.external`
   保持本机现值（不因拉取被重置）；`file_path` 按 id 重算为 `<dataDir>/consoles/<id>.sql`。
3. 提交后写控制台 `.sql`（内容不同才写，走 `ConsoleFiles.write`）与 `settings/*` 文件。
4. 重载内存态：`TreeState.refresh()`、控制台列表重建（已打开的编辑器 buffer 从磁盘重读，
   因为导入是显式的用户动作，丢弃未推送 buffer 是预期行为——导出前已 `flushAllSync`）。
5. `LiveTemplatesStore` / `KeymapPrefs` / `ThemesStore` 重新 load；当前主题若被删 → 回退默认主题。

### 9.6 并发与刷新

- `SyncService` 内部单线程（`newSingleThreadExecutor`），UI 动作排队；运行中按钮禁用 + 进度文案。
- 同步期间不做增量热更新：结束后整体刷新（树/标签/设置），与现有 `refresh` 路径一致。

## 10. 实体映射表

| 本地来源 | 仓库文件 | 同步字段 |
|---|---|---|
| `folders` 行 | `db-k/folders/<id>.json` | id/name/parentId/sortOrder |
| `connections` 行 | `db-k/connections/<id>.enc`（整条加密） | 全部（含 password 明文） |
| `workspaces` 行 + `workspace_consoles` | `db-k/workspaces/<id>.json` | id/name/autoNamed/sortOrder/createdAt/lastActiveConsoleId + consoleIds |
| `consoles` 行（`external = 0`） | `db-k/consoles/<id>.meta.json` | id/connectionId/name/target/sortOrder/updatedAt |
| `consoles/<id>.sql` 文件 | `db-k/consoles/<id>.sql` | 正文 |
| `<dataDir>/live-templates.json` | `db-k/settings/live-templates.json` | 整份 |
| `<dataDir>/keymap.properties` | `db-k/settings/keymap.json` | 覆盖项（`{命令id: "CTRL+ENTER"}`） |
| `<dataDir>/themes.json` | `db-k/settings/themes.json` | 整份 |

**不同步**（设备本地）：`sql_history`、`column_cache`、`meta_cache`、`schema_migrations`、
`consoles.caret_start/end`、`consoles.closed`、`external = 1` 的控制台（路径是设备本地的）、
`<dataDir>/secret.key`、`window.properties`、`theme.properties`（明暗选择）、`tree-expand.properties`、
`ui-scale.properties`、`tabbar.properties`、`editor.properties`、`redis.properties`、`drivers/`、`logs/`、
`app.properties`（语言）。

**边界情况**：

- 控制台指向的连接不在合并结果里（连接被另一设备删了）→ 该控制台不导入，结果里报告「跳过 N 个孤儿控制台」。
- `external` 控制台完全不出现在快照里；本地的 external 控制台在导入时**保留不动**（不被删除）。
- 同名连接/控制台不冲突（id 为准），允许重名。
- 时间字段：`updatedAt` 随文件同步（内容变更时间），不是设备状态。

## 11. UI 设计

### 11.1 设置窗口新分区「云同步」

位置：`SettingsDialog` 左侧导航新增第 4 项（通用设置 / 快捷键 / Live 模板 / 云同步）。
右侧内容（全部主题色，深浅色都要过一遍）：

```
远端仓库 URL        [ https://github.com/me/dbk-sync.git        ]  (TextField)
本地目录            [ ~/.local/share/db-k/sync                  ]  [选择…]
分支                [ main                                      ]  (TextField，默认 main)

同步口令            ● 已记住（本机）   [重新输入] [清除]
                    ○ 未设置            [输入口令]

[克隆并导入]  [拉取]  [推送]        状态：上次同步 2026-11-04 15:02 · 成功
开关：拉取后自动推送（默认开）   退出时自动推送（默认关）
提示：连接配置在仓库中为密文；控制台 SQL 为明文，请勿在 SQL 中写密码。
```

行为：

- 「拉取/推送」在未配置（URL 空、口令未设）时禁用并给一行说明。
- 「克隆并导入」仅在本地目录无 `.git` 时可用（首次接入）。
- 运行中：按钮禁用 + 行内进度文案（`t(Str.SyncRunning)`），不弹模态。
- 结果摘要行显示 `上次同步 <时间> · 拉取 X 个实体 / 推送 Y 个文件`，失败显示错误 key。
- 口令对话框复用现有单输入弹窗模式（`TextInputDialogs.kt` / `FramelessDialog`），
  **Enter 提交**（AGENTS 硬性要求），支持「记住口令」勾选（默认勾）。

### 11.2 冲突取舍弹窗（`SyncConflictDialog`）

- 独立自绘弹窗（`FramelessDialog`，显式尺寸，`ProvideUiScale`/`ProvideI18n`/`MaterialTheme` 自带）。
- 左侧冲突项列表（路径 + 实体类型徽章 + 人类可读名），右侧上下两块：`本地` / `远端` 摘要；
  底部 `保留本地` / `保留远端` / `应用到全部同类`（可选，首版可只做前两个）。
- 「确定」在**所有冲突项都已选**时才可用；「取消」= 放弃整次拉取（回滚到拉取前）。
- 摘要渲染只描述明文元数据（连接显示 host:port/db/user，密码固定 `****`；SQL 显示行数与差异片段，
  **不**把密码明文拼进摘要）。

### 11.3 i18n key（新增，zh/en 双语）

`SettingsSectionSync`、`SyncRemoteLabel`、`SyncLocalDirLabel`、`SyncBranchLabel`、
`SyncPassphraseLabel`、`SyncPassphraseSet`、`SyncPassphraseUnset`、`SyncPassphraseRemember`、
`SyncPassphraseWrong`、`SyncPassphraseClear`、`SyncPassphraseChange`、`SyncCloneAndImport`、
`SyncPull`、`SyncPush`、`SyncRunning`、`SyncLastAt`、`SyncNever`、`SyncAutoPushAfterPull`、
`SyncAutoPushOnExit`、`SyncNotConfigured`、`SyncHintCiphertext`、`SyncConflictTitle`、
`SyncConflictKeepLocal`、`SyncConflictKeepRemote`、`SyncConflictApplyAll`、
`SyncConflictChooseAll`、`SyncResultSummary`、`SyncErrorGitMissing`、`SyncErrorPushRejected`、
`SyncErrorOrphanConsoles`、`SyncErrorDecryptFailed`。

## 12. 安全

- 口令明文存 `sync.properties` 是用户明确要求的取舍；文件权限收紧为 600（非 POSIX 平台忽略），
  并在设置窗口标注「口令保存在本机配置文件中」。
- 仓库可能被推送到第三方托管：README 提示「仓库含控制台 SQL 明文」。
- 不把 `.enc` 明文解密结果写日志/提交信息；冲突摘要里密码恒为 `****`。
- git 提交信息只含 `db-k sync: <hostname> <ISO 时间> · <n> 个变更`，不含实体名。
- 若 `git` 缺失：设置分区显示明确提示（`SyncErrorGitMissing`），按钮禁用，不影响其它功能。

## 13. 测试计划

单元（不联网、无外部依赖）：

1. `SecretBoxTest`：派生密钥固定向量、加密/解密往返、错误口令解密失败、`secret.check` 校验。
2. `SyncPathsTest`：路径 ↔ id 双向映射、非 `db-k/` 路径不认。
3. `SyncTreeTest`（纯逻辑）：快照 → 工作树文件集合（含去抖：相同明文不重写 `.enc`）；
   工作树 + 快照 → 导入计划（新增/更新/删除 id 集合）。
4. `DbKSyncAdapterTest`（临时 SQLite，复用现有测试夹具）：导出→导入幂等；删除传播；
   external 控制台保留；孤儿控制台跳过并计数。

集成（需要系统 git，缺失则 `Assumptions.assumeTrue` 跳过）：

5. `GitRepoSyncTest`：临时目录建裸仓 → 设备 A `init/commit/push` → 设备 B `clone` →
   两边改不同实体 → 拉取自动合并、双方收敛；两边改同一连接 → 检测到冲突 →
   选「远端」后本地与该连接一致；一边删一边没改 → 删除传播。
6. 临时目录 `try/finally` 递归清理（沿用 `smokeJdbc` 约定）。

人工验收（按 AGENTS「UI 验证方式」，只给步骤，不代跑 GUI）：

- 前置：两台设备（或同一机器两个 `debugHome` 目录，由用户自行设置）。
- A 配好连接 → 设置→云同步 填 URL/口令 → 推送 → 期望：`git log` 出现一次提交、`db-k/connections/*.enc` 为密文。
- A 再推送一次（不改任何东西）→ 期望：无新提交。
- B 克隆并导入 → 输入口令 → 期望：树里出现同样连接、控制台 SQL 一致、密码可用（能连上）。
- A 与 B 各改一个不同控制台 → 双方拉取 → 期望：自动合并，无冲突弹窗，两边一致。
- A 与 B 改同一条连接 → 拉取 → 期望：冲突弹窗列出该连接（本地/远端摘要），选一侧后两侧一致。
- 深浅色 + 中英各切一遍，检查新分区与冲突弹窗无黑字沉底/占位符错位。
- 重启应用 → 期望：不再提示输口令，直接可拉取。

## 14. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 口令丢失 → 连接不可恢复 | 明文 SQL 仍可读；README 建议把口令存密码管理器；不提供“跳过口令”后门 |
| 误提交清空远端（本地库被删后推送） | 首次接入守卫 + 推送前比较「本地 0 实体 / 远端 N 实体」→ 要求二次确认 |
| `.enc` 每次重加密产生噪音提交 | 明文比对去抖（§8） |
| 单一大文件冲突（settings/*.json） | 接受整份取舍；后续可按条目拆分（folder/workspace 已按实体拆） |
| git 合并后 `.sql` 冲突标记被当正文导入 | 冲突路径统一进取舍弹窗，绝不让带 `<<<<<<<` 的内容进本地库（导入前扫描） |
| 中文路径/编码 | 全部 UTF-8 显式指定（`Files.readString/writeString` + git `-c core.quotepath=false`） |
| Windows 上权限位无效 | 口令文件尽力 chmod；文档注明 |
| debugHome 沙箱和正式数据混用 | 仓库默认在 `<dataDir>/sync`，随 debugHome 走；README 提示同步目录与数据目录同源 |

## 15. 对现有代码的改动点（builder 参考）

同步需要「按指定 id 写入」，现有仓库方法都是自己生成 id，因此新增：

| 位置 | 新增/改动 | 用途 |
|---|---|---|
| `db/ConnectionsRepository` | `upsertConnection(p: ConnectionProfile, id: String, updatedAt: Long?)` | 导入时保留远端 id |
| 同上 | `upsertFolder(id, name, parentId, sortOrder)` | 同上 |
| 同上 | `upsertConsole(record: ConsoleRecord)`（含 `filePath` 按 id 重算为 `<dataDir>/consoles/<id>.sql`） | 同上 |
| 同上 | `upsertWorkspace(record)` / `setWorkspaceConsoles(id, consoleIds)` | 同上 + 成员表 |
| 同上 | `listAllConsoles(): List<ConsoleRecord>` | 导出全量（现只有按连接查） |
| 同上 | `connectionUpdatedAt(id): Long`（或批量 `Map<String, Long>`） | `ConnectionProfile` 没有 `updatedAt` 字段，同步 DTO 直接查表取 |
| `app/settings/KeymapPrefs` | 暴露 `overrides(): Map<String,String>` / `saveOverrides(map)` | 与 `settings/keymap.json` 互转（现有 `Keymap.chordsOf` 已够用） |
| `db/ProfileTransfer` | `deriveKey` 抽到 `sync/SecretBox`，本类改为调用 | 避免两套 PBKDF2 参数漂移 |
| `app/core/Main.kt` | 设置保存后调用同步结果刷新；退出流程接入「退出时自动推送」 | 编排 |

事务边界：导入用 `conn.autoCommit = false` + 单次 commit（沿用 `importProfiles` 的写法）；
文件写入（`.sql`、`settings/*`）在 commit 之后，失败只记日志并提示「文件写入部分失败，请重试拉取」。

## 16. 分阶段落地

- **M1 引擎骨架**：`sync/` 叶层（SecretBox/GitRepo/SyncPaths/SyncModels/SyncEngine）+ `sync.properties`
  + 单元测试；不动 UI。
- **M2 db-k 适配**：`DbKSyncAdapter`（连接/文件夹/控制台 SQL）+ 推送/拉取（无冲突路径）+
  设置窗口「云同步」分区（URL/目录/口令/推送/拉取/结果）+ i18n。
- **M3 冲突取舍**：冲突检测、摘要生成、取舍弹窗、取消回滚、孤儿控制台报告 + git 集成测试。
- **M4 设置同步 + 换口令**：live-templates/keymap/themes 三件套、口令更换流程、退出时自动推送开关。
- **M5 api-x 接入**：按同一 `sync/` 约定实现 `ApiXSyncAdapter`（`collection/*.json` 复用现有
  Postman v2.1 导出；`env/environments.json` 的 secret 值走 `.enc` 约定），验证「一个仓库两应用」。
  届时再决定是否把 `sync/` 抽成共享模块（目前两项目各拷一份，接口先定型）。

## 17. 我做的决定（请检查）

1. **工作区（workspaces）也同步**：它是控制台分组，跨设备一致才有意义；`lastActiveConsoleId` 一并同步。
2. **外部文件控制台（`external = true`）不同步**：路径是设备本地的，同步内容会和用户自己的文件打架。
   如果希望「内容也跟着走」，改为在仓库里存内容副本、导入时新建普通控制台。
3. **自定义主题（themes.json）同步，明暗选择（theme.properties）不同步**：前者是资产，后者是设备偏好。
4. **编辑器字体/字号、UI 缩放、标签多行、树展开、Redis 视图偏好一律不同步**（设备/屏幕相关）。
5. **退出时自动推送默认关闭**，拉取后自动推送默认开启：避免退出时把半成品/含明文 SQL 的仓库推出去。
6. **`consoles.closed` 不同步**：关闭标签是会话状态，按设备各自记忆。
7. **`updatedAt` 同步**（内容变更时间），用于「最近改动」排序；不是设备状态。
8. **同步不做自动定时**，只在按钮/退出时动作；失败不重试，重新点即可（幂等）。
