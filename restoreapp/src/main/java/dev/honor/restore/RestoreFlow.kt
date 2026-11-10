package dev.honor.restore

import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.GptSnapshot
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.OtaHistoryMode
import dev.honor.restore.core.OtaPackageType

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
        val ota = backups.filterIsInstance<OtaBackup>().sortedBy { it.timestamp }
        if (mode == OtaHistoryMode.UNKNOWN || mode == OtaHistoryMode.NONE) return null
        if (ota.isEmpty()) return BackupRequest.LATEST_OTA
        if (ota.last().packageType == OtaPackageType.UNSET || mode == OtaHistoryMode.ONE) return null
        return when (previous) {
            PreviousOtaChoice.UNASKED, PreviousOtaChoice.MISSING -> null
            PreviousOtaChoice.AVAILABLE -> if (ota.last().packageType == OtaPackageType.PATCH &&
                ota.none { it.packageType == OtaPackageType.MAJOR }) BackupRequest.PREVIOUS_OTA else null
        }
    }

    fun needsPreviousChoice(backups: List<BackupSet>, mode: OtaHistoryMode, previous: PreviousOtaChoice): Boolean {
        if (mode != OtaHistoryMode.MULTIPLE || previous != PreviousOtaChoice.UNASKED || !hasClean(backups)) return false
        val latest = backups.filterIsInstance<OtaBackup>().maxByOrNull { it.timestamp } ?: return false
        return latest.packageType != OtaPackageType.UNSET
    }

    fun isComplete(backups: List<BackupSet>, mode: OtaHistoryMode, previous: PreviousOtaChoice): Boolean {
        if (!hasClean(backups)) return false
        val ota = backups.filterIsInstance<OtaBackup>().sortedBy { it.timestamp }
        if (mode == OtaHistoryMode.NONE) return ota.isEmpty()
        if (ota.isEmpty() || ota.any { it.packageType == OtaPackageType.UNSET }) return false
        return when (mode) {
            OtaHistoryMode.UNKNOWN -> false
            OtaHistoryMode.NONE -> ota.isEmpty()
            OtaHistoryMode.ONE -> ota.size == 1
            OtaHistoryMode.MULTIPLE -> when (previous) {
                PreviousOtaChoice.UNASKED -> false
                PreviousOtaChoice.MISSING -> true
                PreviousOtaChoice.AVAILABLE -> ota.size >= 2 &&
                    (ota.last().packageType == OtaPackageType.MAJOR || ota.any { it.packageType == OtaPackageType.MAJOR })
            }
        }
    }

    fun validPrevious(latest: OtaBackup, candidate: OtaBackup): Boolean =
        candidate.timestamp < latest.timestamp && candidate.targetSlot == latest.currentSlot

    fun nextOtaOrdinal(backups: List<BackupSet>): Int = backups.count { it is OtaBackup } + 1

    fun untypedOta(backups: List<BackupSet>): OtaBackup? = backups.filterIsInstance<OtaBackup>()
        .maxByOrNull { it.timestamp }?.takeIf { it.packageType == OtaPackageType.UNSET }

    fun canStopAddingHistory(backups: List<BackupSet>, previous: PreviousOtaChoice): Boolean =
        previous == PreviousOtaChoice.AVAILABLE && backups.filterIsInstance<OtaBackup>()
            .maxByOrNull { it.timestamp }?.packageType == OtaPackageType.PATCH &&
            backups.filterIsInstance<OtaBackup>().none { it.packageType == OtaPackageType.MAJOR }
}
