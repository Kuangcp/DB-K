# Progressive Metadata Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make metadata loading progressive: show schemas immediately, then load objects per-schema one at a time so the tree and completion are usable during loading.

**Architecture:** Split `loadAllMetaFromDb` into two phases: (1) `loadNamespaces()` → set `rt.schemas` immediately, (2) sequentially load each schema's objects with `yield()` between so Compose re-renders incrementally. Enable `lazyObjectGroups` for ClickHouse to reduce per-schema load time.

**Tech Stack:** Kotlin, Compose snapshot state, JDBC (single-thread executor in LiveConnection), ClickHouse JDBC driver.

**Spec:** `docs/superpowers/specs/2026-10-08-progressive-metadata-loading.md`

## Global Constraints

- All JDBC calls must stay sequential on the same LiveConnection (single-thread executor; no concurrent use of `java.sql.Connection`).
- Compose snapshot state (`mutableStateOf`, `mutableStateMapOf`) is thread-safe; write from any dispatcher, read on composition thread.
- `ensureSchemaObjects` must not duplicate work already in progress by `progressiveLoadObjects`.
- Existing cache-first path (cache hit → immediate apply) must remain unchanged.
- Existing `namespaceAsFilter` (Redis) path must remain unchanged.
- `backgroundRefresh` must not flash loading states since cached data is already applied.

## Review Focus

- Schema loads successfully but one of its schemas' object loading fails → that schema shows error placeholder, others still render; overall connection status remains CONNECTED.
- `ensureSchemaObjects` called by tree expansion during progressive loading → returns early because `objectsLoading[key]` is pre-marked.
- Cache-miss path: schemas appear in tree immediately after `loadNamespaces`, with loading placeholders for each schema until its objects load.
- Stale-cache path: `backgroundRefresh` silently updates objects without toggling loading states (uses `fetchFreshMetadata` → applies on success only).
- Each `yield()` point must actually let Compose observe snapshot changes (verified by tree rendering progressively).

---

### Task 1: ClickHouseDialect lazyObjectGroups

**Files:**
- Modify: `src/main/kotlin/jdbc/ClickHouseDialect.kt`

**Interfaces:**
- Consumes: `DbDialect.lazyObjectGroups`, `DbDialect.loadObjectCounts`, `DbDialect.loadCoreObjects`, `DbDialect.loadObjectsForKind`
- Produces: ClickHouse now supports `lazyObjectGroups`, which `ConnectionsState.progressiveLoadObjects` will use to fetch only counts + core objects first

**Details:**

ClickHouse's current `loadObjects` queries `SELECT name, engine FROM system.tables WHERE database = ? AND is_temporary = 0`. Enable `lazyObjectGroups` so that on progressive load, only counts + core (table/view names) are fetched initially; individual group expansion (e.g. only views) uses `loadObjectsForKind`.

- [ ] **Step 1: Add `override val lazyObjectGroups = true` to `ClickHouseDialect`**

Add property override in the class body:

```kotlin
override val lazyObjectGroups: Boolean get() = true
```

- [ ] **Step 2: Override `loadObjectCounts` with GROUP BY query**

```kotlin
override fun loadObjectCounts(conn: Connection, schema: SchemaMeta): Map<ObjectKind, Int> {
    val catalog = schema.catalog ?: return emptyMap()
    val counts = mutableMapOf<ObjectKind, Int>()
    conn.prepareStatement(
        "SELECT engine, count() AS cnt FROM system.tables " +
            "WHERE database = ? AND is_temporary = 0 GROUP BY engine"
    ).use { ps ->
        ps.setString(1, catalog)
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val engine = rs.getString(1)?.uppercase().orEmpty()
                val cnt = rs.getInt(2)
                val kind = if (engine in viewEngines) ObjectKind.VIEW else ObjectKind.TABLE
                counts[kind] = (counts[kind] ?: 0) + cnt
            }
        }
    }
    return counts
}
```

- [ ] **Step 3: Override `loadCoreObjects` — same as current `loadObjects`**

```kotlin
override fun loadCoreObjects(conn: Connection, schema: SchemaMeta): SchemaObjects =
    loadObjects(conn, schema)
```

(`loadCoreObjects` is identical to `loadObjects` because ClickHouse's `loadObjects` is already minimal — just name + engine.)

- [ ] **Step 4: Override `loadObjectsForKind` — filter by engine type**

```kotlin
override fun loadObjectsForKind(conn: Connection, schema: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> {
    val catalog = schema.catalog ?: return emptyList()
    val wantViews = kind == ObjectKind.VIEW
    conn.prepareStatement(
        "SELECT name FROM system.tables " +
            "WHERE database = ? AND is_temporary = 0 ORDER BY name"
    ).use { ps ->
        ps.setString(1, catalog)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<DbObjectMeta>()
            while (rs.next()) {
                val name = rs.getString(1) ?: continue
                out += DbObjectMeta(name, kind)
            }
            return out
        }
    }
}
```

- [ ] **Step 5: Compile check**

Run: `gradle compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/jdbc/ClickHouseDialect.kt
git commit -m "feat(ck): enable lazyObjectGroups for ClickHouse dialect"
```

---

### Task 2: ConnectionsState progressive loading

**Files:**
- Modify: `src/main/kotlin/app/state/ConnectionsState.kt`
- Test: `src/test/kotlin/app/SqlEditingTest.kt` (no changes needed — existing tests pass)
- Verify: `gradle test`, `gradle smokeJdbc`

**Interfaces:**
- Consumes: `ConnRuntime.schemas` / `objects` / `objectsLoading` snapshot maps, `MetaCache.load`/`save`, `DataSourceSession.loadNamespaces`/`loadObjects`/`loadObjectCounts`/`loadCoreObjects`
- Produces: `loadMetadata()` sets schemas before objects are loaded; progressive per-schema object loading via `progressiveLoadObjects()`

- [ ] **Step 1: Extract `loadAllSchemas` helper**

Add a private method that calls `rt.live.loadNamespaces()` and returns the list or null on failure:

```kotlin
/**
 * Phase 1: 只取库列表（不取任何对象）。失败返回 null（调用方负责设 ERROR 状态）。
 */
private fun loadAllSchemas(rt: ConnRuntime): List<SchemaMeta>? =
    runCatching {
        rt.live.loadNamespaces()
    }.onFailure { t ->
        Logger.error(t, "load schemas failed {}", rt.profile.name)
    }.getOrNull()
```

- [ ] **Step 2: Add `progressiveLoadObjects` method**

```kotlin
/**
 * Phase 2: 逐个 schema 渐进加载对象（串行，共享同一 JDBC 连接）。
 * 进入前必须先设置 [rt.schemas]（树可见 schema 节点）。
 * 预标记所有 schema 的 objectsLoading = true 防止 ensureSchemaObjects 重复加载。
 * @return 警告消息（有失败 schema 时），null 表示全部成功。
 */
private suspend fun progressiveLoadObjects(
    rt: ConnRuntime,
    schemas: List<SchemaMeta>,
): String? {
    val lazy = rt.live.capabilities.lazyObjectGroups
    val errors = mutableListOf<String>()
    val reasons = mutableListOf<String>()

    // 预标记所有 schema 防止 ensureSchemaObjects 重复加载
    schemas.forEach { rt.objectsLoading[it.key] = true }
    kotlinx.coroutines.yield()

    for (schema in schemas) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (lazy) {
                    val counts = rt.live.loadObjectCounts(schema)
                    val core = rt.live.loadCoreObjects(schema)
                    SchemaObjects(objects = core.objects, counts = counts)
                } else {
                    rt.live.loadObjects(schema)
                }
            }
        }
        result.onSuccess { rt.objects[schema.key] = it }
            .onFailure { t ->
                Logger.error(t, "load objects failed {} {}", rt.profile.name, schema.displayName)
                errors += schema.displayName
                reasons += friendlyMessage(t)
            }
        rt.objectsLoading[schema.key] = false
        kotlinx.coroutines.yield()
    }

    if (errors.isEmpty()) return null
    return I18n.t(Str.ObjectsLoadFailed,
        errors.joinToString(I18n.t(Str.CommonListSeparator)).take(40),
        reasons.first().take(160))
}
```

- [ ] **Step 3: Restructure `loadMetadata`**

Replace the current `loadMetadata` body with:

```kotlin
private suspend fun loadMetadata(rt: ConnRuntime) {
    rt.schemasLoading = true
    rt.statusMessage = null

    // namespaceAsFilter (Redis) path — unchanged
    if (rt.live.capabilities.namespaceAsFilter) {
        val fetched = fetchFreshMetadata(rt)
        rt.schemasLoading = false
        fetched.onFailure { t ->
            Logger.error(t, "load metadata failed {}", rt.profile.name)
            rt.status = ConnUiStatus.ERROR
            rt.statusMessage = friendlyMessage(t)
        }
        return
    }

    // 1) Try cache hit first (full data available)
    val cached = withContext(Dispatchers.IO) { metaCache.load(rt.profile) }
    if (cached != null) {
        applyMetadata(rt, cached.schemas, cached.objects)
        rt.schemasLoading = false
        if (cached.stale) {
            Logger.info("meta cache stale for {}; silent background refresh", rt.profile.name)
            backgroundRefresh(rt)
        }
        return
    }

    // 2) Cache miss: load schemas first, show immediately
    val schemas = withContext(Dispatchers.IO) { loadAllSchemas(rt) }
    if (schemas == null) {
        rt.status = ConnUiStatus.ERROR
        rt.statusMessage = friendlySqlError(RuntimeException("Failed to load schemas"))
        rt.schemasLoading = false
        return
    }
    rt.schemas = schemas

    // 3) Progressive per-schema objects
    val warn = progressiveLoadObjects(rt, schemas)
    rt.schemasLoading = false
    if (warn != null) rt.statusMessage = warn

    // 4) Save to disk cache
    if (!rt.live.capabilities.namespaceAsFilter) {
        withContext(Dispatchers.IO) { metaCache.save(rt.profile, schemas, rt.objects.toMap()) }
    }
}
```

- [ ] **Step 4: Restructure `fetchFreshMetadata` for progressive loading**

`fetchFreshMetadata` is called by `refreshMetadata` (right-click refresh). It should also use the progressive approach. Replace with:

```kotlin
/**
 * 查库全量元数据（schema-first + progressive per-schema objects）并回写缓存。
 * 供 [refreshMetadata] 使用。
 */
private suspend fun fetchFreshMetadata(rt: ConnRuntime): Result<Unit> {
    val schemas = withContext(Dispatchers.IO) { loadAllSchemas(rt) }
    if (schemas == null) {
        return Result.failure(RuntimeException("Failed to load schemas"))
    }
    rt.schemas = schemas
    val warn = progressiveLoadObjects(rt, schemas)
    if (warn != null) rt.statusMessage = warn
    if (!rt.live.capabilities.namespaceAsFilter) {
        withContext(Dispatchers.IO) {
            metaCache.save(rt.profile, schemas, rt.objects.toMap())
        }
    }
    return Result.success(Unit)
}
```

- [ ] **Step 5: Restructure `backgroundRefresh` — atomic, no flickering**

`backgroundRefresh` is called when cached data is stale. Cached data is already applied, so it must NOT flash loading states.
It collects all objects synchronously first, then applies atomically. Add a `collectAllObjects` helper + restructure `backgroundRefresh`:

```kotlin
/**
 * 同步获取全部 schema 的对象（调用方负责切 IO）。返回 (objects, 警告)。
 * 供 [backgroundRefresh] 静默更新用——收集完毕后整体替换，不闪 loading 态。
 */
private fun collectAllObjects(
    rt: ConnRuntime,
    schemas: List<SchemaMeta>,
): Pair<Map<String, SchemaObjects>, String?> {
    val objects = linkedMapOf<String, SchemaObjects>()
    val lazy = rt.live.capabilities.lazyObjectGroups
    val failed = mutableListOf<String>()
    val reasons = mutableListOf<String>()
    schemas.forEach { s ->
        runCatching {
            if (lazy) {
                val counts = rt.live.loadObjectCounts(s)
                val core = rt.live.loadCoreObjects(s)
                SchemaObjects(objects = core.objects, counts = counts)
            } else {
                rt.live.loadObjects(s)
            }
        }
            .onSuccess { objects[s.key] = it }
            .onFailure { t ->
                Logger.error(t, "load objects failed {} {}", rt.profile.name, s.displayName)
                failed += s.displayName
                reasons += friendlyMessage(t)
            }
    }
    val warn = if (failed.isEmpty()) null
    else I18n.t(Str.ObjectsLoadFailed,
        failed.joinToString(I18n.t(Str.CommonListSeparator)).take(40),
        reasons.first().take(160))
    return objects to warn
}
```

```kotlin
private suspend fun backgroundRefresh(rt: ConnRuntime) {
    if (rt.backgroundRefreshing) return
    rt.backgroundRefreshing = true
    try {
        val schemas = withContext(Dispatchers.IO) { loadAllSchemas(rt) } ?: return
        val (objects, warn) = withContext(Dispatchers.IO) {
            collectAllObjects(rt, schemas)
        }
        applyMetadata(rt, schemas, objects)
        if (warn != null) rt.statusMessage = warn
        withContext(Dispatchers.IO) { metaCache.save(rt.profile, schemas, objects) }
    } catch (t: Throwable) {
        Logger.error(t, "background meta refresh failed {}", rt.profile.name)
    } finally {
        rt.backgroundRefreshing = false
    }
}
```

- [ ] **Step 6: Remove `loadAllMetaFromDb`**

Delete the old `loadAllMetaFromDb` method (it is replaced by `loadAllSchemas` + `progressiveLoadObjects` + integration in callers).

- [ ] **Step 7: Compile check + test**

Run: `gradle compileKotlin` — must succeed
Run: `gradle test` — all tests must pass

Fix any compilation or test failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/app/state/ConnectionsState.kt
git commit -m "feat: progressive per-schema metadata loading"
```

---

### Task 3: Verification

- [ ] **Step 1: Full build and test**

Run: `gradle compileKotlin` — BUILD SUCCESSFUL
Run: `gradle test` — BUILD SUCCESSFUL
Run: `gradle smokeJdbc` — BUILD SUCCESSFUL (all PASS lines present)

- [ ] **Step 2: Commit**

```bash
git add -A && git commit -m "chore: verify progressive metadata loading"
```