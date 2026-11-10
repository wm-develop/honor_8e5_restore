package dev.honor.restore

import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.GptSnapshot
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.OtaHistoryMode

enum class PreviousOtaChoice { UNASKED, AVAILABLE, MISSING }
enum class BackupRequest { CLEAN, LATEST_OTA, PREVIOUS_OTA }

object RestoreFlow {
    fun isClean(backup: NormalBackup): Boolean = backup.records.keys
        .filter { it.startsWith("gpt_") && it.endsWith(".raw") }
        .any { name ->
            runCatching { GptSnapshot.parse(backup.file(name)) }.getOrNull()
                ?.let { it.named("userdata").size == 1 && it.named("efisp").isEmpty() } == true
        }

    fun hasClean(backups: List<BackupSet>): Boolean = backups.filterIsInstance<NormalBackup>().any(::isClean)

    fun nextRequest(backups: List<BackupSet>, mode: OtaHistoryMode, previous: PreviousOtaChoice): BackupRequest? {
        if (!hasClean(backups)) return BackupRequest.CLEAN
        val ota = backups.filterIsInstance<OtaBackup>()
        return when (mode) {
            OtaHistoryMode.UNKNOWN -> null
            OtaHistoryMode.NONE -> null
            OtaHistoryMode.ONE -> if (ota.isEmpty()) BackupRequest.LATEST_OTA else null
            OtaHistoryMode.MULTIPLE -> when {
                ota.isEmpty() -> BackupRequest.LATEST_OTA
                previous == PreviousOtaChoice.AVAILABLE && ota.size == 1 -> BackupRequest.PREVIOUS_OTA
                else -> null
            }
        }
    }

    fun needsPreviousChoice(backups: List<BackupSet>, mode: OtaHistoryMode, previous: PreviousOtaChoice): Boolean =
        mode == OtaHistoryMode.MULTIPLE && previous == PreviousOtaChoice.UNASKED &&
            hasClean(backups) && backups.any { it is OtaBackup }

    fun isComplete(backups: List<BackupSet>, mode: OtaHistoryMode, previous: PreviousOtaChoice): Boolean {
        if (!hasClean(backups)) return false
        val ota = backups.filterIsInstance<OtaBackup>()
        return when (mode) {
            OtaHistoryMode.UNKNOWN -> false
            OtaHistoryMode.NONE -> ota.isEmpty()
            OtaHistoryMode.ONE -> ota.size == 1
            OtaHistoryMode.MULTIPLE -> when (previous) {
                PreviousOtaChoice.UNASKED -> false
                PreviousOtaChoice.AVAILABLE -> ota.size == 2
                PreviousOtaChoice.MISSING -> ota.size == 1
            }
        }
    }

    fun validPrevious(latest: OtaBackup, candidate: OtaBackup): Boolean =
        candidate.timestamp < latest.timestamp && candidate.targetSlot == latest.currentSlot
}
