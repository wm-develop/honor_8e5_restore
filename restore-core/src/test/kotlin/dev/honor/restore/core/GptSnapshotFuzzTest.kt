package dev.honor.restore.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

class GptSnapshotFuzzTest {
    @Test
    fun randomContainersAreBoundedAndDoNotCrashParser() {
        val random = Random(0x475054)
        var rejected = 0
        repeat(2000) { index ->
            val raw = ByteArray(random.nextInt(64 * 1024 + 1))
            random.nextBytes(raw)
            if (raw.size >= 64 && index % 2 == 0) raw.copyOfRange(0, 8).copyInto(raw, 0)
            if (raw.size >= 8 && index % 2 == 1) "HUBLGT22".toByteArray().copyInto(raw)
            val result = runCatching { GptSnapshot.parse(raw) }
            if (result.isFailure) {
                rejected++
            } else {
                val snapshot = result.getOrThrow()
                assertTrue(snapshot.raw.size >= 512 && snapshot.diskBytes > 0)
                assertTrue(snapshot.logicalSectorBytes == 512 || snapshot.logicalSectorBytes == 4096)
            }
        }
        assertTrue("绝大多数随机容器应被拒绝：$rejected", rejected > 1900)
    }

    @Test
    fun mutatedRealSnapshotsNeverCrashParsingOrTargetConstruction() {
        val cleanFile = File("..", "ublbackup_20261029_233101/gpt_sda.raw")
        val modifiedFile = File("..", "ublbackup_20261030_131440/gpt_sda.raw")
        assumeTrue(cleanFile.isFile && modifiedFile.isFile)
        val clean = GptSnapshot.parse(cleanFile)
        val modified = GptSnapshot.parse(modifiedFile).raw
        val random = Random(0x8e5)
        repeat(1000) { index ->
            val raw = modified.copyOf()
            val changes = 1 + index % 8
            repeat(changes) {
                val at = random.nextInt(raw.size)
                raw[at] = (raw[at].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
            val current = runCatching { GptSnapshot.parse(raw) }.getOrNull() ?: return@repeat
            val target = runCatching { GptSnapshot.buildRestoreTarget(clean, current) }.getOrNull() ?: return@repeat
            assertTrue(target.named("efisp").isEmpty())
            assertTrue(target.named("userdata").single().endLba == clean.named("userdata").single().endLba)
            assertTrue(target.diskGuid == current.diskGuid && target.diskBytes == current.diskBytes)
        }
    }
}
