package tree

import db.ConnectionProfile
import db.DbType
import db.FolderRow
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.ObjectSearch
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 可控的运行时假实现：无 compose，直接回放预设状态。 */
private class FakeRuntime : ConnectionRuntimeView {
    val statuses = mutableMapOf<String, ConnUiStatus>()
    val messages = mutableMapOf<String, String>()
    val schemasByProfile = mutableMapOf<String, List<SchemaMeta>?>()
    val schemasLoading = mutableMapOf<String, Boolean>()
    val objectsBySchema = mutableMapOf<Pair<String, String>, SchemaObjects?>()
    val objectsLoading = mutableMapOf<String, Boolean>()
    val groupLoading = mutableMapOf<Triple<String, String, ObjectKind>, Boolean>()
    val flat = mutableMapOf<String, Boolean>()
    val activeNs = mutableMapOf<String, SchemaMeta>()
    val searches = mutableMapOf<String, ObjectSearch>()
    val hasMore = mutableMapOf<Pair<String, ObjectKind>, Boolean>()
    val groups = mutableMapOf<String, List<ObjectKind>>()

    override fun statusOf(profileId: String) = statuses[profileId] ?: ConnUiStatus.DISCONNECTED
    override fun statusMessageOf(profileId: String) = messages[profileId]
    override fun schemasOf(profileId: String) = schemasByProfile[profileId]
    override fun schemasLoadingOf(profileId: String) = schemasLoading[profileId] ?: false
    override fun objectsOf(profileId: String, schemaKey: String) = objectsBySchema[profileId to schemaKey]
    override fun objectsLoadingOf(profileId: String, schemaKey: String) = objectsLoading[profileId] ?: false
    override fun groupObjectsLoadingOf(profileId: String, schemaKey: String, kind: ObjectKind) =
        groupLoading[Triple(profileId, schemaKey, kind)] ?: false
    override fun flatNamespaceOf(profileId: String) = flat[profileId] ?: false
    override fun activeNamespaceOf(profileId: String) = activeNs[profileId]
    override fun objectSearchOf(profileId: String) = searches[profileId] ?: ObjectSearch()
    override fun objectsHasMoreOf(profileId: String, schemaKey: String, kind: ObjectKind) =
        hasMore[profileId to kind] ?: false
    override fun objectGroupsOf(profileId: String) = groups[profileId] ?: engine.model.SQL_OBJECT_KINDS
}

class DbTreeRowsTest {

    private fun conn(id: String, sortOrder: Int = 0, folderId: String? = null) = ConnectionProfile(
        id = id, name = "conn-$id", folderId = folderId, dbType = DbType.SQLITE, sortOrder = sortOrder,
    )

    private fun rows(
        folders: List<FolderRow> = emptyList(),
        connections: List<ConnectionProfile> = emptyList(),
        expandedFolderIds: Set<String> = emptySet(),
        expandedConnectionIds: Set<String> = emptySet(),
        expandedSchemaKeys: Set<String> = emptySet(),
        expandedGroupKeys: Set<String> = emptySet(),
        runtime: ConnectionRuntimeView = NoRuntime,
    ) = buildTreeRows(
        folders, connections, expandedFolderIds, expandedConnectionIds,
        expandedSchemaKeys, expandedGroupKeys, runtime,
    )

    @Test
    fun `empty input yields empty list`() {
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `root connections sorted by sortOrder`() {
        val out = rows(connections = listOf(conn("b", sortOrder = 1), conn("a", sortOrder = 0)))
        val ids = out.filter { it.kind == TreeRowKind.CONNECTION }.map { it.profile!!.id }
        assertEquals(listOf("a", "b"), ids)
        assertEquals(0, out.first().depth)
    }

    @Test
    fun `collapsed folder shows childCount without children`() {
        val out = rows(
            folders = listOf(FolderRow("f1", "My Folder", null, 0)),
            connections = listOf(conn("c1", folderId = "f1")),
        )
        assertEquals(1, out.size)
        val folder = out.single()
        assertEquals(TreeRowKind.FOLDER, folder.kind)
        assertEquals("My Folder", folder.name)
        assertEquals(1, folder.childCount)
        assertFalse(folder.expanded)
    }

    @Test
    fun `expanded folder lists children at depth one`() {
        val out = rows(
            folders = listOf(FolderRow("f1", "My Folder", null, 0)),
            connections = listOf(conn("c1", folderId = "f1")),
            expandedFolderIds = setOf("f1"),
        )
        assertEquals(2, out.size)
        assertEquals(TreeRowKind.FOLDER, out[0].kind)
        assertEquals(TreeRowKind.CONNECTION, out[1].kind)
        assertEquals(1, out[1].depth)
        assertEquals("c1", out[1].profile!!.id)
    }

    @Test
    fun `connection status placeholders`() {
        fun placeholderFor(status: ConnUiStatus, message: String? = null) = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = status
                if (message != null) messages["c1"] = message
            },
        )[1]

        assertEquals(PlaceholderKind.LOADING, placeholderFor(ConnUiStatus.CONNECTING).placeholderKind)
        assertEquals("正在连接…", placeholderFor(ConnUiStatus.CONNECTING).name)

        assertEquals(PlaceholderKind.INFO, placeholderFor(ConnUiStatus.DISCONNECTED).placeholderKind)
        assertEquals("未连接", placeholderFor(ConnUiStatus.DISCONNECTED).name)

        val err = placeholderFor(ConnUiStatus.ERROR, "boom")
        assertEquals(PlaceholderKind.ERROR, err.placeholderKind)
        assertEquals("boom", err.name)
    }

    @Test
    fun `connected connection with schemas not yet loaded`() {
        val out = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = null
            },
        )
        assertEquals(2, out.size)
        assertEquals(PlaceholderKind.INFO, out[1].placeholderKind)
        assertEquals("尚未加载库列表", out[1].name)
    }

    @Test
    fun `connected connection loading schemas`() {
        val out = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = null
                schemasLoading["c1"] = true
            },
        )
        assertEquals(PlaceholderKind.LOADING, out[1].placeholderKind)
        assertEquals("正在加载库列表…", out[1].name)
    }

    @Test
    fun `connected connection with empty schemas`() {
        val out = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = emptyList()
            },
        )
        assertEquals(PlaceholderKind.INFO, out[1].placeholderKind)
        assertEquals("该连接下没有可见的库", out[1].name)
    }

    @Test
    fun `collapsed schema shows object total as childCount`() {
        val schema = SchemaMeta(null, "main")
        val out = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = listOf(schema)
                objectsBySchema["c1" to schema.key] = SchemaObjects.simple(listOf("users"), listOf("v"))
            },
        )
        assertEquals(2, out.size)
        val schemaRow = out[1]
        assertEquals(TreeRowKind.SCHEMA, schemaRow.kind)
        assertEquals("main", schemaRow.name)
        assertEquals(2, schemaRow.childCount)
        assertFalse(schemaRow.expanded)
    }

    @Test
    fun `expanded schema lists non-empty groups in canonical order`() {
        val schema = SchemaMeta(null, "main")
        val out = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            expandedSchemaKeys = setOf("s:c1:${schema.key}"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = listOf(schema)
                objectsBySchema["c1" to schema.key] = SchemaObjects.simple(listOf("users"), listOf("v"))
            },
        )
        // connection + schema + TABLES 组 + VIEWS 组
        assertEquals(4, out.size)
        assertEquals(ObjectKind.TABLE, out[2].groupKind)
        assertEquals(1, out[2].childCount)
        assertFalse(out[2].expanded)
        assertEquals(ObjectKind.VIEW, out[3].groupKind)
        assertEquals(1, out[3].childCount)
    }

    @Test
    fun `expanded group lists objects at depth plus two`() {
        val schema = SchemaMeta(null, "main")
        val key = schema.key
        val out = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            expandedSchemaKeys = setOf("s:c1:$key"),
            expandedGroupKeys = setOf("s:c1:$key:g:TABLE"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = listOf(schema)
                objectsBySchema["c1" to key] = SchemaObjects.simple(listOf("users"), listOf("v"))
            },
        )
        // connection + schema + TABLES 组 + users 对象 + VIEWS 组
        assertEquals(5, out.size)
        val objRow = out[3]
        assertEquals(TreeRowKind.DB_OBJECT, objRow.kind)
        assertEquals("users", objRow.name)
        assertEquals(3, objRow.depth)
        // 对象行带 schema/profile 上下文
        assertEquals("c1", objRow.profile!!.id)
        assertEquals("main", objRow.schema!!.displayName)
    }

    @Test
    fun `group row uses count when body not loaded`() {
        val schema = SchemaMeta(null, "main")
        val out = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            expandedSchemaKeys = setOf("s:c1:${schema.key}"),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = listOf(schema)
                objectsBySchema["c1" to schema.key] = SchemaObjects(
                    objects = mapOf(ObjectKind.TABLE to listOf(DbObjectMeta("users", ObjectKind.TABLE))),
                    counts = mapOf(ObjectKind.TABLE to 1, ObjectKind.ROUTINE to 1200),
                )
            },
        )
        // connection + schema + TABLES 组 + ROUTINES 组（正文未加载但计数已知）
        assertEquals(4, out.size)
        assertEquals(ObjectKind.TABLE, out[2].groupKind)
        assertEquals(1, out[2].childCount)
        assertEquals(ObjectKind.ROUTINE, out[3].groupKind)
        assertEquals(1200, out[3].childCount)
    }

    @Test
    fun `expanded unloaded group shows loading or not-loaded placeholder`() {
        val schema = SchemaMeta(null, "main")
        val key = schema.key
        fun runtime(loading: Boolean) = FakeRuntime().apply {
            statuses["c1"] = ConnUiStatus.CONNECTED
            schemasByProfile["c1"] = listOf(schema)
            objectsBySchema["c1" to key] = SchemaObjects(counts = mapOf(ObjectKind.ROUTINE to 1200))
            if (loading) groupLoading[Triple("c1", key, ObjectKind.ROUTINE)] = true
        }
        fun build(loading: Boolean) = rows(
            connections = listOf(conn("c1")),
            expandedConnectionIds = setOf("c1"),
            expandedSchemaKeys = setOf("s:c1:$key"),
            expandedGroupKeys = setOf("s:c1:$key:g:ROUTINE"),
            runtime = runtime(loading),
        )
        val loadingRow = build(true)[3]
        assertEquals(PlaceholderKind.LOADING, loadingRow.placeholderKind)
        assertEquals("正在加载函数与过程…", loadingRow.name)
        val idleRow = build(false)[3]
        assertEquals(PlaceholderKind.INFO, idleRow.placeholderKind)
        assertEquals("尚未加载", idleRow.name)
    }

    @Test
    fun `row key helpers are stable`() {
        assertEquals("c:c1", connectionRowKey("c1"))
        assertEquals("s:c1:${SchemaMeta(null, "main").key}", schemaRowKey("c1", SchemaMeta(null, "main")))
    }

    @Test
    fun `connection row carries status and schema count`() {
        val schema = SchemaMeta(null, "main")
        val out = rows(
            connections = listOf(conn("c1")),
            runtime = FakeRuntime().apply {
                statuses["c1"] = ConnUiStatus.CONNECTED
                schemasByProfile["c1"] = listOf(schema)
            },
        )
        val connRow = out.single()
        assertEquals(ConnUiStatus.CONNECTED, connRow.connStatus)
        assertEquals(1, connRow.childCount)
        assertNull(connRow.message)
    }

    @Test
    fun `flat namespace renders filter and keys without schema rows`() {
        val db0 = SchemaMeta(null, "db0")
        val runtime = FakeRuntime().apply {
            statuses["r1"] = ConnUiStatus.CONNECTED
            schemasByProfile["r1"] = listOf(db0, SchemaMeta(null, "db1"))
            flat["r1"] = true
            activeNs["r1"] = db0
            groups["r1"] = listOf(ObjectKind.KEY)
            searches["r1"] = ObjectSearch(pattern = "user:*", type = "hash")
            objectsBySchema["r1" to db0.key] = SchemaObjects(
                objects = mapOf(
                    ObjectKind.KEY to listOf(DbObjectMeta("user:1", ObjectKind.KEY, detail = "hash", ttlSeconds = 120)),
                ),
                counts = mapOf(ObjectKind.KEY to 42),
            )
            hasMore["r1" to ObjectKind.KEY] = true
        }
        val redis = ConnectionProfile(id = "r1", name = "redis", dbType = DbType.REDIS, database = "0")
        val out = rows(
            connections = listOf(redis),
            expandedConnectionIds = setOf("r1"),
            runtime = runtime,
        )
        assertEquals(0, out.count { it.kind == TreeRowKind.SCHEMA })
        val filter = out.single { it.kind == TreeRowKind.FILTER }.filter!!
        assertEquals(listOf("db0", "db1"), filter.dbOptions)
        assertEquals("db0", filter.db)
        assertEquals("user:*", filter.pattern)
        assertEquals("hash", filter.type)
        val group = out.single { it.kind == TreeRowKind.OBJECT_GROUP }
        assertEquals(ObjectKind.KEY, group.groupKind)
        assertEquals("键", group.name)
        // 有过滤时标题计数 = 已加载的匹配数（不是 DBSIZE）
        assertEquals(1, group.childCount)
        assertFalse(group.expandable)
        assertTrue(group.expanded)
        assertEquals("user:1", out.single { it.kind == TreeRowKind.DB_OBJECT }.name)
        assertEquals("继续扫描…", out.single { it.kind == TreeRowKind.LOAD_MORE }.name)
    }

    @Test
    fun `flat namespace uses authoritative total when unfiltered`() {
        val db0 = SchemaMeta(null, "db0")
        val runtime = FakeRuntime().apply {
            statuses["r1"] = ConnUiStatus.CONNECTED
            schemasByProfile["r1"] = listOf(db0)
            flat["r1"] = true
            activeNs["r1"] = db0
            groups["r1"] = listOf(ObjectKind.KEY)
            objectsBySchema["r1" to db0.key] = SchemaObjects(
                objects = mapOf(ObjectKind.KEY to listOf(DbObjectMeta("k1", ObjectKind.KEY, detail = "string"))),
                counts = mapOf(ObjectKind.KEY to 42),
            )
        }
        val redis = ConnectionProfile(id = "r1", name = "redis", dbType = DbType.REDIS)
        val out = rows(connections = listOf(redis), expandedConnectionIds = setOf("r1"), runtime = runtime)
        val group = out.single { it.kind == TreeRowKind.OBJECT_GROUP }
        assertEquals(42, group.childCount)
    }

    @Test
    fun `flat namespace shows no-match hint when filter empties result`() {
        val db0 = SchemaMeta(null, "db0")
        val runtime = FakeRuntime().apply {
            statuses["r1"] = ConnUiStatus.CONNECTED
            schemasByProfile["r1"] = listOf(db0)
            flat["r1"] = true
            activeNs["r1"] = db0
            groups["r1"] = listOf(ObjectKind.KEY)
            searches["r1"] = ObjectSearch(pattern = "nope:*")
            objectsBySchema["r1" to db0.key] = SchemaObjects(
                objects = mapOf(ObjectKind.KEY to emptyList()),
                counts = mapOf(ObjectKind.KEY to 42),
            )
        }
        val redis = ConnectionProfile(id = "r1", name = "redis", dbType = DbType.REDIS)
        val out = rows(connections = listOf(redis), expandedConnectionIds = setOf("r1"), runtime = runtime)
        assertEquals(0, out.count { it.kind == TreeRowKind.OBJECT_GROUP })
        assertTrue(out.any { it.kind == TreeRowKind.PLACEHOLDER && it.name == "无匹配的键" })
    }

    @Test
    fun `flat namespace without active db shows hint`() {
        val runtime = FakeRuntime().apply {
            statuses["r1"] = ConnUiStatus.CONNECTED
            schemasByProfile["r1"] = listOf(SchemaMeta(null, "db0"))
            flat["r1"] = true
        }
        val redis = ConnectionProfile(id = "r1", name = "redis", dbType = DbType.REDIS)
        val out = rows(connections = listOf(redis), expandedConnectionIds = setOf("r1"), runtime = runtime)
        assertEquals("尚未选择库", out.single { it.kind == TreeRowKind.PLACEHOLDER }.name)
    }
}
