package dev.honor.restore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DeviceEvidenceTest {
    @Test
    fun kotlinAndArm64NativeProduceIdenticalRestoreTargets() {
        val cleanFile = File("..", "ublbackup_20261029_233101/gpt_sda.raw")
        val currentFile = File("..", "restoreapp/build/deep-audit-current-gpt-clean.raw")
        val nativeFile = File("..", "restoreapp/build/deep-audit-native-target.raw")
        assumeTrue(cleanFile.isFile && currentFile.isFile && nativeFile.isFile)
        val clean = GptSnapshot.parse(cleanFile)
        val current = GptSnapshot.parse(currentFile)
        val kotlinTarget = GptSnapshot.buildRestoreTarget(clean, current)
        val nativeTarget = GptSnapshot.parse(nativeFile)
        assertEquals(1, current.named("efisp").size)
        assertTrue(kotlinTarget.named("efisp").isEmpty())
        assertEquals(clean.named("userdata").single().endLba, kotlinTarget.named("userdata").single().endLba)
        assertEquals(kotlinTarget.sha256, nativeTarget.sha256)
        assertTrue(kotlinTarget.raw.contentEquals(nativeTarget.raw))
    }
}
