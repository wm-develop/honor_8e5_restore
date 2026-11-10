package dev.honor.restore

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.honor.restore.core.DeviceSnapshot
import dev.honor.restore.core.GptRestoreOperation
import dev.honor.restore.core.Hashes
import dev.honor.restore.core.ImageRestoreOperation
import dev.honor.restore.core.OtaHistoryMode
import dev.honor.restore.core.RestorePlan
import dev.honor.restore.core.RestorePlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ExecutionBackupInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun validBackupRoundTrip() {
        val fixture = fixture()
        try {
            val destination = Uri.parse("content://dev.honor.restore.audit/valid")
            val hash = ExecutionBackup.export(context, destination, fixture.first, fixture.second)
            val bytes = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
            assertEquals(hash, Hashes.sha256(bytes.inputStream()))
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            assertEquals("current-abl", entries.getValue("abl_a.img").toString(Charsets.UTF_8))
            assertEquals("current-gpt", entries.getValue("current-gpt.raw").toString(Charsets.UTF_8))
            assertTrue(entries.getValue("restore-plan.txt").toString(Charsets.UTF_8).contains("WRITE|abl_a|clean|abl_a.img"))
        } finally {
            fixture.first.directory.deleteRecursively()
        }
    }

    @Test
    fun corruptedReadbackMustStopBeforePartitionWrites() {
        listOf("corrupt", "flip", "truncate", "second").forEach { scenario ->
            val fixture = fixture()
            try {
                val destination = Uri.parse("content://dev.honor.restore.audit/$scenario")
                assertNotNull(context.packageManager.resolveContentProvider("dev.honor.restore.audit", 0))
                val failure = runCatching { ExecutionBackup.export(context, destination, fixture.first, fixture.second) }.exceptionOrNull()
                assertNotNull("$scenario：损坏的恢复前备份不能通过执行前校验", failure)
            } finally {
                fixture.first.directory.deleteRecursively()
            }
        }
    }

    private fun fixture(): Pair<ScannedDevice, RestorePlan> {
        RestoreRuntime.startHistory(OtaHistoryMode.NONE)
        val directory = File(context.cacheDir, "backup-audit-${UUID.randomUUID()}").apply { mkdirs() }
        val abl = File(directory, "abl_a.img").apply { writeText("current-abl") }
        val gpt = File(directory, "current-gpt.raw").apply { writeText("current-gpt") }
        val snapshot = DeviceSnapshot("a", "audit", "audit", emptyMap(), "/dev/block/sda", null, 1, "")
        val scanned = ScannedDevice(snapshot, mapOf("abl_a" to abl), gpt, directory)
        val operation = ImageRestoreOperation("abl_a", "clean", "abl_a.img", "1".repeat(64), 11,
            Hashes.sha256(abl), "abl_a|link|node|1:1|11|0|512", 11, "audit")
        val gptOperation = GptRestoreOperation("/dev/block/sda", "clean", "gpt.raw", "2".repeat(64), "3".repeat(64), "audit")
        return scanned to RestorePlan(RestorePlanStatus.READY, images = listOf(operation), gpt = gptOperation,
            blockers = emptyList(), warnings = emptyList(), notes = emptyList())
    }
}
