# 元数据渐进式加载设计

## 问题

ClickHouse 等数据源含大量表（10000+）时，连库后的元数据缓存过程会阻塞 UI：
`loadAllMetaFromDb` 串行加载所有 schema 的所有对象后，才设置 `rt.schemas`，
在此之前树为空白，编辑器补全也无数据。

## 目标

- 连接后立即显示库列表（schema 节点），不等待对象加载
- 各 schema 的对象逐个渐进加载：树中每加载完一个 schema 立即出现表/视图节点
- 编辑器补全随加载进度逐步完善（已加载 schema 的对象立即可提示）
- 不并发请求同一 JDBC 连接（共享线程安全）
- CK 服务端不受过多并发影响（串行加载，无额外并发）

## 设计

### 1. `ConnectionsState.kt` — 两阶段加载

#### 阶段 1：库列表先行

原有 `loadAllMetaFromDb`（全量阻塞）拆为两个方法：

```kotlin
/** 只取库列表，不取任何对象。调用方负责在 IO 调度器。 */
private fun loadAllSchemas(rt: ConnRuntime): List<SchemaMeta>

/** 逐个 schema 加载对象并更新 UI。串行，共享同一 JDBC 连接。 */
private suspend fun progressiveLoadObjects(rt: ConnRuntime, schemas: List<SchemaMeta>): String?
```

`loadMetadata` 改为：

```
1. 尝试磁盘缓存命中 → applyMetadata 立即展示（同现行为）→ 后台渐进刷新
2. 缓存未命中：
   a. IO: loadNamespaces() → rt.schemas = schemas（树立即渲染）
   b. IO: 对每个 schema 串行 loadObjects：
        rt.objectsLoading[key] = true → yield() → loadObjects → rt.objects[key] = ...
        → rt.objectsLoading[key] = false → yield()
   c. 全部完成后：metaCache.save(...)
   d. rt.schemasLoading = false
```

`backgroundRefresh`（缓存过期后的静默刷新）同样改为渐进式。

#### 串行 + yield 机制

- `LiveConnection` 内部已用单线程 `ExecutorService` 串行化所有 JDBC 操作（`submitAndGet`），天然无竞争
- 每个 schema 加载前后各一次 `yield()`，让 Compose 的 snapshot 观察协程有机会在该间隙渲染 loading 占位 / 新对象
- 所有 IO 操作经 `withContext(Dispatchers.IO)`，不阻塞 Main 线程

### 预标记防重复加载

`progressiveLoadObjects` 在进入加载循环前预将所有 schema 的 `objectsLoading[key]` 置 `true`
（含尚未轮到的 schema），防止用户在渐进加载过程中展开某个 schema 触发 `ensureSchemaObjects`
重复加载同一批对象。`ensureSchemaObjects` 碰到 `objectsLoading == true` 会直接返回。

#### 2. `ClickHouseDialect.kt` — lazyObjectGroups

启用懒加载组能力以减少单次 `loadObjects` 的数据量。connect 时只取计数 + 核心组（表/视图名）；展开具体组时才拉正文。

```kotlin
override val lazyObjectGroups: Boolean get() = true

override fun loadObjectCounts(conn: Connection, schema: SchemaMeta): Map<ObjectKind, Int> {
    // SELECT engine, count() FROM system.tables WHERE database = ? GROUP BY engine
    // 只返回 VIEW / MATERIALIZEDVIEW → views 计数；其他 → tables 计数
}

override fun loadCoreObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
    // SELECT name, engine FROM system.tables WHERE database = ? AND is_temporary = 0
    // 等价于当前 loadObjects（已足够轻量，只取名和引擎）
}

override fun loadObjectsForKind(conn: Connection, schema: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> {
    // SELECT name, engine FROM system.tables WHERE database = ? AND is_temporary = 0
    //   AND engine ... （按 kind 过滤 VIEW 或非 VIEW）
}
```

#### 3. 不动的层

| 层 | 原因 |
|---|---|
| `DbTreeModels.kt` | 已有 `objects == null && objectsLoading` 的占位渲染逻辑 |
| `DataSourceSession.kt` | 接口不变 |
| `Main.kt` | `ensureConnectionReady` 已 fire-and-forget |
| `GenericDialect.kt` | 不引入 regress 风险 |

### 异常处理

- 单个 schema 加载失败：记日志，跳过该 schema，不打断其余 schema 加载
- 全部完成后如有失败 schema，`rt.statusMessage` 置警告
- 磁盘缓存写：只写已成功加载的 schema 的对象

## 受影响的文件

1. `app/state/ConnectionsState.kt` — 核心重构
2. `jdbc/ClickHouseDialect.kt` — 启用 `lazyObjectGroups`

## 提交顺序

1. ConnectionsState：加载管线重构（schema-first + progressive）
2. ClickHouseDialect：lazyObjectGroups 能力
3. 测试（单元测试验证渐进加载不会损坏既有缓存路径）