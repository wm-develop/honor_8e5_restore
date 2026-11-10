package dev.honor.restore

import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.BackupVerifier
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.OtaHistoryMode
import dev.honor.restore.core.OtaPackageType
import dev.honor.restore.core.OtaWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RestoreFlowTest {
    private lateinit var clean: NormalBackup
    private lateinit var latest: OtaBackup
    private lateinit var previous: OtaBackup

    @Before
    fun setUp() {
        val directory = File("../ublbackup_20261029_233101").canonicalFile
        assumeTrue(directory.isDirectory)
        clean = BackupVerifier.verify(directory) as NormalBackup
        latest = ota("latest", 20, "a")
        previous = ota("previous", 10, "b")
    }

    @Test
    fun zeroOtaOnlyRequestsCleanBackup() {
        assertEquals(BackupRequest.CLEAN, RestoreFlow.nextRequest(emptyList(), OtaHistoryMode.NONE, PreviousOtaChoice.UNASKED))
        assertFalse(RestoreFlow.isComplete(emptyList(), OtaHistoryMode.NONE, PreviousOtaChoice.UNASKED))
        val backups = listOf<BackupSet>(clean)
        assertNull(RestoreFlow.nextRequest(backups, OtaHistoryMode.NONE, PreviousOtaChoice.UNASKED))
        assertTrue(RestoreFlow.isComplete(backups, OtaHistoryMode.NONE, PreviousOtaChoice.UNASKED))
        assertFalse(RestoreFlow.isComplete(backups + latest, OtaHistoryMode.NONE, PreviousOtaChoice.UNASKED))
    }

    @Test
    fun oneOtaRequiresTypeAndCannotContainExtraOta() {
        assertEquals(BackupRequest.CLEAN, RestoreFlow.nextRequest(listOf(latest), OtaHistoryMode.ONE, PreviousOtaChoice.UNASKED))
        assertEquals(BackupRequest.LATEST_OTA, RestoreFlow.nextRequest(listOf(clean), OtaHistoryMode.ONE, PreviousOtaChoice.UNASKED))
        assertTrue(RestoreFlow.isComplete(listOf(clean, latest), OtaHistoryMode.ONE, PreviousOtaChoice.UNASKED))
        assertFalse(RestoreFlow.isComplete(listOf(clean, latest.copy(packageType = OtaPackageType.UNSET)), OtaHistoryMode.ONE, PreviousOtaChoice.UNASKED))
        assertFalse(RestoreFlow.isComplete(listOf(clean, latest, previous), OtaHistoryMode.ONE, PreviousOtaChoice.UNASKED))
    }

    @Test
    fun multipleRequiresExplicitPreviousChoiceAndSupportsOlderHistory() {
        val beforeChoice = listOf<BackupSet>(clean, latest)
        assertTrue(RestoreFlow.needsPreviousChoice(beforeChoice, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.UNASKED))
        assertFalse(RestoreFlow.isComplete(beforeChoice, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.UNASKED))
        assertTrue(RestoreFlow.isComplete(beforeChoice, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.MISSING))
        assertNull(RestoreFlow.nextRequest(beforeChoice, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.AVAILABLE))
        val complete = listOf(clean, previous, latest)
        assertTrue(RestoreFlow.isComplete(complete, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.AVAILABLE))
        assertTrue(RestoreFlow.isComplete(complete, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.MISSING))
        assertTrue(RestoreFlow.isComplete(complete + ota("older", 5, "a"), OtaHistoryMode.MULTIPLE, PreviousOtaChoice.AVAILABLE))
    }

    @Test
    fun consecutivePatchesRequestOlderBackupsUntilMajorAppears() {
        val smallLatest = latest.copy(packageType = OtaPackageType.PATCH)
        val smallPrevious = previous.copy(packageType = OtaPackageType.PATCH)
        val backups = listOf(clean, smallPrevious, smallLatest)
        assertEquals(BackupRequest.PREVIOUS_OTA,
            RestoreFlow.nextRequest(backups, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.AVAILABLE))
        assertFalse(RestoreFlow.isComplete(backups, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.AVAILABLE))
        val withMajor = backups + ota("major", 5, "a")
        assertNull(RestoreFlow.nextRequest(withMajor, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.AVAILABLE))
        assertTrue(RestoreFlow.isComplete(withMajor, OtaHistoryMode.MULTIPLE, PreviousOtaChoice.AVAILABLE))
    }

    @Test
    fun previousMustBeImmediatelyCompatibleWithLatest() {
        assertTrue(RestoreFlow.validPrevious(latest, previous))
        assertFalse(RestoreFlow.validPrevious(latest, latest))
        assertFalse(RestoreFlow.validPrevious(latest, ota("later", 30, "b")))
        assertFalse(RestoreFlow.validPrevious(latest, ota("wrong-slot", 10, "a")))
    }

    @Test
    fun fakeLockNormalBackupIsNotClean() {
        val directory = File("../ublbackup_20261030_131440").canonicalFile
        assumeTrue(directory.isDirectory)
        val modified = BackupVerifier.verify(directory) as NormalBackup
        assertFalse(RestoreFlow.isClean(modified))
        assertEquals(BackupRequest.CLEAN, RestoreFlow.nextRequest(listOf(modified), OtaHistoryMode.NONE, PreviousOtaChoice.UNASKED))
    }

    private fun ota(id: String, timestamp: Long, currentSlot: String): OtaBackup {
        val target = if (currentSlot == "a") "b" else "a"
        return OtaBackup(id, File(id), id, timestamp, emptyMap(), currentSlot,
            listOf(OtaWrite("abl_$target", "builtin-abl.img", 1, "f".repeat(64))), emptyMap(), OtaPackageType.MAJOR)
    }
}
