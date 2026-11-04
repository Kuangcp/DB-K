package sync

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPathsTest {

    private val tree: Path = Path.of("/tmp/repo")
    private val root: Path = SyncPaths.appRoot(tree, "db-k")

    @Test
    fun `entity paths roundtrip through parse`() {
        val cases = listOf(
            SyncPaths.folderFile(root, "f1") to SyncRef.Folder("f1"),
            SyncPaths.connectionFile(root, "c1") to SyncRef.Connection("c1"),
            SyncPaths.workspaceFile(root, "w1") to SyncRef.Workspace("w1"),
            SyncPaths.consoleMetaFile(root, "s1") to SyncRef.ConsoleMeta("s1"),
            SyncPaths.consoleBodyFile(root, "s1") to SyncRef.ConsoleBody("s1"),
            SyncPaths.settingFile(root, "themes.json") to SyncRef.Setting("themes.json"),
        )
        for ((file, expected) in cases) assertEquals(expected, SyncPaths.parse(root, file), file.toString())
    }

    @Test
    fun `infra files are not entities`() {
        listOf(SyncPaths.saltFile(root), SyncPaths.checkFile(root), SyncPaths.metaFile(root))
            .forEach { assertNull(SyncPaths.parse(root, it)) }
        assertNull(SyncPaths.parse(root, root.resolve(SyncPaths.README_FILE)))
        assertNull(SyncPaths.parse(root, root.resolve("unknown.json")))
        assertNull(SyncPaths.parse(root, root.resolve("settings/other.json")))
        assertNull(SyncPaths.parse(root, tree.resolve("api-x/collections/x.json")))
        assertNull(SyncPaths.parse(root, tree.resolve("db-k/folders/deep/x.json")))
    }

    @Test
    fun `app roots are isolated`() {
        assertTrue(SyncPaths.appRoot(tree, "db-k") != SyncPaths.appRoot(tree, "api-x"))
        assertEquals(tree.resolve("db-k"), SyncPaths.appRoot(tree, "db-k"))
    }

    @Test
    fun `entity ids with dots survive roundtrip`() {
        val id = "abc.def"
        assertEquals(SyncRef.ConsoleMeta(id), SyncPaths.parse(root, SyncPaths.consoleMetaFile(root, id)))
        assertEquals(SyncRef.Connection(id), SyncPaths.parse(root, SyncPaths.connectionFile(root, id)))
    }
}
