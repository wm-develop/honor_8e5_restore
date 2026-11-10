package dev.honor.restore.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class BackupVerifierTest {
    private val root = File("..").canonicalFile

    @Test
    fun verifiesRealNormalAndOtaBackups() {
        val normalDirectory = File(root, "ublbackup_20261029_233101")
        val firstDirectory = File(root, "hnubl_ota_backup_20261108_084414")
        val secondDirectory = File(root, "hnubl_ota_backup_20261108_093831")
        assumeTrue(normalDirectory.isDirectory && firstDirectory.isDirectory && secondDirectory.isDirectory)

        val normal = BackupVerifier.verify(normalDirectory) as NormalBackup
        val first = BackupVerifier.verify(firstDirectory) as OtaBackup
        val second = BackupVerifier.verify(secondDirectory) as OtaBackup

        assertEquals("a", normal.runningSlot)
        assertEquals("b", first.currentSlot)
        assertEquals("a", first.targetSlot)
        assertEquals("a", second.currentSlot)
        assertEquals("b", second.targetSlot)
        assertEquals(listOf("abl_a", "efisp"), first.writes.map { it.target })
        assertEquals(listOf("abl_b", "efisp"), second.writes.map { it.target })
        assertEquals(second.records.getValue("abl_a.img").sha256, second.records.getValue("abl_b.img").sha256)
        assertEquals(second.records.getValue("init_boot_a.img").sha256, second.records.getValue("init_boot_b.img").sha256)
    }

    @Test
    fun rejectsUnexpectedDirectory() {
        val directory = File(root, "restore-core/build/no-such-backup")
        val error = runCatching { BackupVerifier.verify(directory) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun acceptsMinimalWellFormedManifests() = fixture { directory ->
        writeNormal(directory)
        assertEquals("a", (BackupVerifier.verify(directory) as NormalBackup).runningSlot)
        deleteContents(directory)
        writeOta(directory)
        val ota = BackupVerifier.verify(directory) as OtaBackup
        assertEquals("b", ota.targetSlot)
        assertEquals(listOf("abl_b"), ota.writes.map { it.target })
    }

    @Test
    fun rejectsChangedImageBytesAndWrongDeclaredLength() = fixture { directory ->
        writeNormal(directory)
        File(directory, "abl_a.img").writeBytes(byteArrayOf(1, 2, 4))
        expectReject(directory)
        deleteContents(directory)
        writeNormal(directory) { manifest ->
            manifest.put("files", JSONArray().put(record("abl_a.img", 4, Hashes.sha256(ByteArray(0).inputStream()))))
        }
        expectReject(directory)
    }

    @Test
    fun rejectsNormalManifestIntegrityFailures() = fixture { directory ->
        writeNormal(directory)
        File(directory, "SHA256SUMS").appendText("0".repeat(64) + " extra.img\n")
        expectReject(directory)
        deleteContents(directory)
        writeNormal(directory)
        File(directory, "SHA256SUMS").delete()
        expectReject(directory)
        deleteContents(directory)
        writeNormal(directory) { it.put("status", "partial") }
        expectReject(directory)
        deleteContents(directory)
        writeNormal(directory) { it.put("partition_writes", true) }
        expectReject(directory)
    }

    @Test
    fun rejectsDuplicateAndTraversalRecords() = fixture { directory ->
        val hash = Hashes.sha256(byteArrayOf(1, 2, 3).inputStream())
        writeNormal(directory) { manifest ->
            manifest.put("files", JSONArray().put(record("abl_a.img", 3, hash)).put(record("abl_a.img", 3, hash)))
        }
        expectReject(directory)
        deleteContents(directory)
        writeNormal(directory) { manifest ->
            manifest.put("files", JSONArray().put(record("../abl_a.img", 3, hash)))
        }
        expectReject(directory)
    }

    @Test
    fun rejectsIncompleteOtaJournalAndSourceFreezeFlag() = fixture { directory ->
        writeOta(directory, journal = "WRITE_STARTED=abl_b\n")
        expectReject(directory)
        deleteContents(directory)
        writeOta(directory) { it.put("ramSourcesFrozen", false) }
        expectReject(directory)
        deleteContents(directory)
        writeOta(directory) { it.put("status", "running") }
        expectReject(directory)
        deleteContents(directory)
        writeOta(directory) { it.put("operation", "INCREMENTAL") }
        expectReject(directory)
    }

    @Test
    fun rejectsPreparedIdentityMismatchAndDuplicateWrites() = fixture { directory ->
        writeOta(directory, preparedMutator = { it.put("clickedAtEpochMillis", 99L) })
        expectReject(directory)
        deleteContents(directory)
        writeOta(directory) { manifest ->
            val write = manifest.getJSONArray("writes").getJSONObject(0)
            manifest.put("writes", JSONArray().put(write).put(write))
        }
        expectReject(directory)
    }

    @Test
    fun rejectsInvalidOtaSlotAndChangedPayload() = fixture { directory ->
        writeOta(directory) { it.put("currentSlot", "c") }
        expectReject(directory)
        deleteContents(directory)
        writeOta(directory)
        File(directory, "abl_b.img").writeBytes(byteArrayOf(9, 9, 9))
        expectReject(directory)
    }

    private fun writeNormal(directory: File, mutate: (JSONObject) -> Unit = {}) {
        directory.mkdirs()
        val payload = byteArrayOf(1, 2, 3)
        File(directory, "abl_a.img").writeBytes(payload)
        val manifest = JSONObject()
            .put("format", "bootforge-slot-backup-v2.2")
            .put("status", "complete")
            .put("partition_writes", false)
            .put("running_slot", "a")
            .put("requested_at_millis", 1L)
            .put("files", JSONArray().put(record("abl_a.img", payload.size.toLong(), Hashes.sha256(payload.inputStream()))))
        mutate(manifest)
        File(directory, "backup.json").writeText(manifest.toString())
        val lines = (manifest.getJSONArray("files").objects().map { it.getString("name") } + "backup.json").map { name ->
            val hash = if (name == "abl_a.img" || name == "backup.json") Hashes.sha256(File(directory, name)) else "0".repeat(64)
            "$hash $name"
        }
        File(directory, "SHA256SUMS").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun writeOta(
        directory: File,
        journal: String = "WRITE_STARTED=abl_b\nWRITE_VERIFIED=abl_b\n",
        preparedMutator: (JSONObject) -> Unit = {},
        mutate: (JSONObject) -> Unit = {},
    ) {
        directory.mkdirs()
        File(directory, "abl_a.img").writeBytes(byteArrayOf(1, 1, 1))
        File(directory, "abl_b.img").writeBytes(byteArrayOf(2, 2, 2))
        val prepared = JSONObject()
            .put("status", "backups_complete").put("lastPhase", "backups_complete")
            .put("clickedAtEpochMillis", 1L).put("currentSlot", "a").put("request", "request")
        preparedMutator(prepared)
        File(directory, "ota-backup.json").writeText(prepared.toString())
        val records = JSONArray()
        listOf("abl_a.img", "abl_b.img", "ota-backup.json").forEach { name ->
            val file = File(directory, name)
            records.put(record(name, file.length(), Hashes.sha256(file)))
        }
        val write = JSONObject().put("target", "abl_b").put("source", "abl_b.img")
            .put("bytes", 3L).put("sha256", "f".repeat(64))
        val complete = JSONObject()
            .put("format", "hnubl-ota-v1").put("status", "complete").put("lastPhase", "complete")
            .put("operation", "FULL").put("ramSourcesFrozen", true).put("currentSlot", "a")
            .put("clickedAtEpochMillis", 1L).put("request", "request").put("backups", records)
            .put("writes", JSONArray().put(write)).put("snapshots", JSONArray())
        mutate(complete)
        File(directory, "ota-complete.json").writeText(complete.toString())
        File(directory, "ota-write-journal.log").writeText(journal)
    }

    private fun record(name: String, bytes: Long, hash: String) = JSONObject()
        .put("name", name).put("bytes", bytes).put("sha256", hash)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun expectReject(directory: File) {
        val error = runCatching { BackupVerifier.verify(directory) }.exceptionOrNull()
        assertNotNull("损坏或不完整的备份必须被拒绝：$directory", error)
    }

    private fun deleteContents(directory: File) {
        directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    private fun fixture(block: (File) -> Unit) {
        val directory = File("build/backup-verifier-tests", UUID.randomUUID().toString())
        try {
            directory.mkdirs()
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
