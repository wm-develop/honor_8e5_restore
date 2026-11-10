package dev.honor.restore

import dev.honor.restore.core.BackupVerifier
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.OtaHistoryMode
import dev.honor.restore.core.RestorePlan
import dev.honor.restore.core.RestorePlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RestoreRuntimeTest {
    private lateinit var backups: List<dev.honor.restore.core.BackupSet>
    private lateinit var ota: OtaBackup

    @Before
    fun setUp() {
        val directory = File("../ublbackup_20261029_233101").canonicalFile
        assumeTrue(directory.isDirectory)
        val clean = BackupVerifier.verify(directory)
        ota = OtaBackup("latest", File("latest"), "latest", 1, emptyMap(), "a", emptyList(), emptyMap())
        backups = listOf(clean, ota)
        RestoreRuntime.state.value = RestoreUiState()
    }

    @Test
    fun serviceGateRejectsUnansweredPreviousChoice() {
        RestoreRuntime.state.value = RestoreUiState(backups = backups, historyMode = OtaHistoryMode.MULTIPLE,
            previousOtaChoice = PreviousOtaChoice.UNASKED, plan = readyPlan())
        val error = runCatching { RestoreRuntime.requireReadyPlan() }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message?.contains("倒数第二次") == true)
        assertFalse(RestoreRuntime.canReview())
    }

    @Test
    fun missingPreviousMustBeExplicitlyConfirmed() {
        RestoreRuntime.state.value = RestoreUiState(backups = backups, historyMode = OtaHistoryMode.MULTIPLE,
            previousOtaChoice = PreviousOtaChoice.MISSING, plan = readyPlan())
        assertTrue(RestoreRuntime.canReview())
        assertEquals(RestorePlanStatus.READY, RestoreRuntime.requireReadyPlan().status)
    }

    @Test
    fun directoryMismatchNeedsSeparateAcknowledgement() {
        val mismatch = "目录数量与次数不一致"
        RestoreRuntime.state.value = RestoreUiState(backups = backups, historyMode = OtaHistoryMode.MULTIPLE,
            previousOtaChoice = PreviousOtaChoice.MISSING, plan = readyPlan(mismatch))
        assertFalse(RestoreRuntime.canReview())
        assertTrue(runCatching { RestoreRuntime.requireReadyPlan() }.exceptionOrNull()?.message?.contains("弹窗") == true)
        RestoreRuntime.confirmHistoryMismatch("不同内容")
        assertFalse(RestoreRuntime.canReview())
        RestoreRuntime.confirmHistoryMismatch(mismatch)
        assertTrue(RestoreRuntime.canReview())
        assertEquals(mismatch, RestoreRuntime.requireReadyPlan().historyMismatch)
    }

    @Test
    fun restartingHistoryClearsReviewAndImportState() {
        RestoreRuntime.state.value = RestoreUiState(backups = backups, historyMode = OtaHistoryMode.MULTIPLE,
            previousOtaChoice = PreviousOtaChoice.MISSING, confirmedHistoryMismatch = "old", plan = readyPlan("old"))
        RestoreRuntime.startHistory(OtaHistoryMode.NONE)
        val state = RestoreRuntime.state.value
        assertTrue(state.backups.isEmpty())
        assertTrue(state.device == null && state.plan == null && state.confirmedHistoryMismatch == null)
        assertEquals(PreviousOtaChoice.UNASKED, state.previousOtaChoice)
        assertFalse(RestoreRuntime.canReview())
    }

    private fun readyPlan(mismatch: String? = null) = RestorePlan(RestorePlanStatus.READY,
        historyMismatch = mismatch, images = emptyList(), gpt = null, blockers = emptyList(), warnings = emptyList(), notes = emptyList())
}
