package dev.honor.restore.core

import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AblRollbackInspectorTest {
    @Test
    fun realRestoreCandidatesHaveReadableStructure() {
        val files = listOf(
            File("..", "hnubl_ota_backup_20261108_093831/abl_b.img"),
            File("..", "ublbackup_20261029_233101/abl_a.img"),
            File("..", "app/src/main/assets/images/abl.img"),
        )
        assumeTrue(files.all { it.isFile })
        files.forEach { file ->
            val result = AblRollbackInspector.inspect(file)
            println("${file.name}: ${result.javaClass.simpleName}: $result")
            assertFalse("$file must have a readable ABL structure", result is AblRollbackInspector.Result.Invalid)
        }
        val builtin = files.last()
        val padded = File("build/prefix-abl-fixture.img")
        padded.parentFile.mkdirs()
        padded.writeBytes(builtin.readBytes() + ByteArray(4 * 1024 * 1024 - builtin.length().toInt()))
        try {
            org.junit.Assert.assertEquals(BUILTIN_ABL_SHA256, Hashes.sha256Prefix(padded, 642136L))
        } finally {
            padded.delete()
        }
    }

    @Test
    fun capturedDeviceAblCountersDoNotExceedRestoreCandidates() {
        val pairs = listOf(
            File("..", "restoreapp/build/audit-current/abl_a.img") to File("..", "hnubl_ota_backup_20261108_084414/abl_a.img"),
            File("..", "restoreapp/build/audit-current/abl_b.img") to File("..", "hnubl_ota_backup_20261108_093831/abl_b.img"),
        )
        assumeTrue(pairs.all { it.first.isFile && it.second.isFile })
        pairs.forEach { (currentFile, candidateFile) ->
            val current = AblRollbackInspector.inspect(currentFile)
            val candidate = AblRollbackInspector.inspect(candidateFile)
            assertFalse("$currentFile: ${(current as? AblRollbackInspector.Result.Invalid)?.reason}",
                current is AblRollbackInspector.Result.Invalid)
            assertFalse("$candidateFile: ${(candidate as? AblRollbackInspector.Result.Invalid)?.reason}",
                candidate is AblRollbackInspector.Result.Invalid)
            println("CURRENT=${currentFile.name}: $current\nCANDIDATE=${candidateFile.name}: $candidate")
            if (current is AblRollbackInspector.Result.Parsed && candidate is AblRollbackInspector.Result.Parsed) {
                val candidateValues = candidate.counters.associate { it.authority to it.value }
                current.counters.forEach { counter ->
                    val candidateValue = candidateValues[counter.authority]
                    assertFalse("${counter.authority}: candidate $candidateValue < current ${counter.value}",
                        candidateValue == null || candidateValue < counter.value)
                }
            }
        }
    }
}
