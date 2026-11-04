package app.dialog

import i18n.I18n
import i18n.Lang
import i18n.Str
import sync.ConflictChoice
import sync.ConflictItem
import sync.ConflictPreview
import sync.SyncEntityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncConflictLogicTest {

    private fun item(
        path: String,
        kind: SyncEntityKind,
        local: ConflictPreview,
        remote: ConflictPreview,
        id: String? = "c1",
    ) = ConflictItem(
        path = path,
        kind = kind,
        id = id,
        local = local,
        remote = remote,
        localBytes = ByteArray(0),
        remoteBytes = ByteArray(0),
    )

    private val connection = item(
        "db-k/connections/c1.enc",
        SyncEntityKind.CONNECTION,
        ConflictPreview.Connection("prod", "POSTGRES", "10.0.0.1", 5432, "orders", "app"),
        ConflictPreview.Connection("prod", "POSTGRES", "10.0.0.2", 5432, "orders", "app"),
    )

    @Test
    fun `label uses entity kind and remote name`() {
        assertEquals(
            I18n.t(Lang.ZH, Str.SyncKindConnection) + " · prod",
            conflictLabel(connection, Lang.ZH),
        )
        assertEquals(
            I18n.t(Lang.EN, Str.SyncKindConnection) + " · prod",
            conflictLabel(connection, Lang.EN),
        )
    }

    @Test
    fun `connection preview shows target summary and never password`() {
        val lines = conflictPreviewLines(connection.local, Lang.ZH)
        assertEquals(listOf("POSTGRES · 10.0.0.1:5432", "orders", "app"), lines)
        assertFalse(lines.joinToString(" ").contains("password", ignoreCase = true))
    }

    @Test
    fun `sql preview shows line count and head lines`() {
        val sql = ConflictPreview.Sql(lineCount = 7, head = listOf("select 1;", "select 2;"))
        val lines = conflictPreviewLines(sql, Lang.ZH)
        assertEquals(I18n.t(Lang.ZH, Str.SyncConflictSqlLines, 7), lines.first())
        assertEquals(listOf("select 1;", "select 2;"), lines.drop(1))
    }

    @Test
    fun `empty side is reported as deleted and opaque side as no preview`() {
        assertEquals(
            listOf(I18n.t(Lang.ZH, Str.SyncConflictDeleted)),
            conflictPreviewLines(ConflictPreview.Unknown(0), Lang.ZH),
        )
        assertEquals(
            listOf(I18n.t(Lang.ZH, Str.SyncConflictNoPreview)),
            conflictPreviewLines(ConflictPreview.Unknown(128), Lang.ZH),
        )
    }

    @Test
    fun `all chosen only when every path has a choice`() {
        val second = item("db-k/folders/f1.json", SyncEntityKind.FOLDER, ConflictPreview.Named("prod"), ConflictPreview.Named("prod"))
        val items = listOf(connection, second)
        assertFalse(allChosen(items, emptyMap()))
        assertFalse(allChosen(items, mapOf(connection.path to ConflictChoice.LOCAL)))
        assertTrue(
            allChosen(
                items,
                mapOf(connection.path to ConflictChoice.LOCAL, second.path to ConflictChoice.REMOTE),
            ),
        )
        assertFalse(allChosen(emptyList(), emptyMap()))
    }
}
