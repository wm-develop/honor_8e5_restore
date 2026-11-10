package dev.honor.restore.core

import java.io.File

const val BUILTIN_ABL_SHA256 = "509e8f9655184f69c147c494f8cfabe795f2cacb4e5e6b7a1fb0a48ecc08376c"

data class ImageRecord(val name: String, val bytes: Long, val sha256: String)

sealed interface BackupSet {
    val id: String
    val directory: File
    val displayName: String
    val timestamp: Long
    val records: Map<String, ImageRecord>
    fun file(name: String): File = File(directory, name)
}

data class NormalBackup(
    override val id: String,
    override val directory: File,
    override val displayName: String,
    override val timestamp: Long,
    override val records: Map<String, ImageRecord>,
    val runningSlot: String,
) : BackupSet

data class OtaWrite(val target: String, val source: String, val bytes: Long, val sha256: String)

data class OtaBackup(
    override val id: String,
    override val directory: File,
    override val displayName: String,
    override val timestamp: Long,
    override val records: Map<String, ImageRecord>,
    val currentSlot: String,
    val writes: List<OtaWrite>,
    val snapshots: Map<String, String>,
) : BackupSet {
    val targetSlot: String get() = if (currentSlot == "a") "b" else "a"
    fun write(target: String): OtaWrite? = writes.singleOrNull { it.target == target }
}

data class DevicePartition(
    val name: String,
    val identity: String,
    val bytes: Long,
    val sha256: String,
    val prefixHashes: Map<Long, String> = emptyMap(),
)

data class DeviceSnapshot(
    val runningSlot: String,
    val displayId: String,
    val fingerprint: String,
    val partitions: Map<String, DevicePartition>,
    val gptDisk: String,
    val gpt: GptSnapshot?,
    val f2fsBytes: Long,
    val otaMarker: String,
    val otaDirectoryCount: Int? = null,
    val otaDirectoryNames: List<String> = emptyList(),
    val incompleteRestore: Boolean = false,
)

data class ImageRestoreOperation(
    val target: String,
    val sourceBackupId: String,
    val sourceName: String,
    val sourceSha256: String,
    val sourceBytes: Long,
    val beforeSha256: String,
    val targetIdentity: String,
    val targetBytes: Long,
    val reason: String,
)

data class GptRestoreOperation(
    val disk: String,
    val sourceBackupId: String,
    val sourceName: String,
    val beforeSha256: String,
    val afterSha256: String,
    val reason: String,
)

enum class RestorePlanStatus { READY, BLOCKED }
enum class RestoreStrategy { FULL, CURRENT_SLOT_ONLY }
enum class OtaHistoryMode { UNKNOWN, NONE, ONE, MULTIPLE }

data class RestorePlan(
    val status: RestorePlanStatus,
    val strategy: RestoreStrategy = RestoreStrategy.FULL,
    val historyMismatch: String? = null,
    val images: List<ImageRestoreOperation>,
    val gpt: GptRestoreOperation?,
    val blockers: List<String>,
    val warnings: List<String>,
    val notes: List<String>,
)

data class SourceCandidate(
    val target: String,
    val backupId: String,
    val sourceName: String,
    val sha256: String,
    val bytes: Long,
    val reason: String,
)
