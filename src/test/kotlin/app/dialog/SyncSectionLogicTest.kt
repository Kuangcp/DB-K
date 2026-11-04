package app.dialog

import app.state.SyncState
import i18n.Str
import sync.SyncFailure
import sync.SyncOutcome
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncSectionLogicTest {

    private val dataDir: Path = Path.of("/home/u/.local/share/db-k")

    @Test
    fun `local dir inside data dir is shown as relative name`() {
        assertEquals("sync", relativeLocalDirLabel("sync", dataDir))
        assertEquals("repos/main", relativeLocalDirLabel("/home/u/.local/share/db-k/repos/main", dataDir))
    }

    @Test
    fun `local dir outside data dir is shown absolute`() {
        assertEquals("/data/other", relativeLocalDirLabel("/data/other", dataDir))
    }

    @Test
    fun `no result yet yields null status`() {
        assertNull(syncStatusText(SyncState()))
    }

    @Test
    fun `status maps failures to error keys`() {
        val state = SyncState()
        state.lastResult = SyncOutcome(ok = false, failure = SyncFailure.GIT_MISSING)
        assertEquals(SyncStatusText(Str.SyncErrorGitMissing), syncStatusText(state))

        state.lastResult = SyncOutcome(ok = false, failure = SyncFailure.PUSH_REJECTED)
        assertEquals(SyncStatusText(Str.SyncErrorPushRejected), syncStatusText(state))

        state.lastResult = SyncOutcome(ok = false, failure = SyncFailure.WRONG_PASSPHRASE)
        assertEquals(SyncStatusText(Str.SyncErrorWrongPassphrase), syncStatusText(state))

        state.lastResult = SyncOutcome(ok = false, failure = SyncFailure.EMPTY_LOCAL_GUARD)
        assertEquals(SyncStatusText(Str.SyncErrorEmptyLocalGuard), syncStatusText(state))

        state.lastResult = SyncOutcome(ok = false, failure = SyncFailure.GIT_FAILED, detail = "boom")
        assertEquals(SyncStatusText(Str.SyncErrorGitFailed, listOf("boom")), syncStatusText(state))
    }

    @Test
    fun `success and conflicts map to their own keys`() {
        val state = SyncState()
        state.lastResult = SyncOutcome(ok = true)
        assertEquals(SyncStatusText(Str.SyncResultOk), syncStatusText(state))

        state.lastResult = SyncOutcome(ok = false, detail = "conflicts")
        assertEquals(SyncStatusText(Str.SyncResultConflicts), syncStatusText(state))
    }

    @Test
    fun `time is formatted in the given zone`() {
        // 2026-11-04T07:30:00Z
        val millis = 1793777400000L
        assertEquals("2026-11-04 07:30", formatSyncTime(millis, ZoneId.of("UTC")))
        assertEquals("2026-11-04 15:30", formatSyncTime(millis, ZoneId.of("Asia/Shanghai")))
    }
}
