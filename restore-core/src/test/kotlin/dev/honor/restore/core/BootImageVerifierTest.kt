package dev.honor.restore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BootImageVerifierTest {
    private val root = File("..").canonicalFile

    @Test
    fun fullOtaInitBootAHasValidAvbContentAndRootedImagesDoNot() {
        val clean = file("hnubl_ota_backup_20261108_084414/init_boot_a.img")
        val rooted = file("hnubl_ota_backup_20261108_093831/init_boot_b.img")
        assumeTrue(clean.isFile && rooted.isFile)

        val cleanResult = BootImageVerifier.inspectBoot(clean)
        val rootedResult = BootImageVerifier.inspectBoot(rooted)

        assertTrue(cleanResult.detail, cleanResult.signatureValid)
        assertTrue(cleanResult.detail, cleanResult.contentValid)
        assertEquals(2236416, cleanResult.originalBytes)
        assertNull(cleanResult.stockImageSha1)
        assertTrue(rootedResult.detail, rootedResult.signatureValid)
        assertFalse(rootedResult.contentValid)
        assertEquals("f716755a744d4c490c6009e041d7df7603eb9c16", rootedResult.stockImageSha1)
        assertEquals(cleanResult.publicKeySha256, rootedResult.publicKeySha256)
    }

    @Test
    fun latestBothSlotsPointToTheSameStockImage() {
        val a = file("hnubl_ota_backup_20261108_093831/init_boot_a.img")
        val b = file("hnubl_ota_backup_20261108_093831/init_boot_b.img")
        assumeTrue(a.isFile && b.isFile)

        val left = BootImageVerifier.inspectBoot(a)
        val right = BootImageVerifier.inspectBoot(b)

        assertFalse(left.contentValid)
        assertFalse(right.contentValid)
        assertEquals(left.stockImageSha1, right.stockImageSha1)
        assertNotNull(left.stockImageSha1)
    }

    @Test
    fun realDeviceVbmetaSignaturesProvideBothInitBootChainKeys() {
        val a = file("restoreapp/build/device-evidence/vbmeta_a.img")
        val b = file("restoreapp/build/device-evidence/vbmeta_b.img")
        val clean = file("hnubl_ota_backup_20261108_084414/init_boot_a.img")
        assumeTrue(a.isFile && b.isFile && clean.isFile)

        val keysA = BootImageVerifier.chainKeys(a)
        val keysB = BootImageVerifier.chainKeys(b)
        val expected = BootImageVerifier.inspectBoot(clean).publicKeySha256

        assertEquals(expected, keysA["init_boot"])
        assertEquals(expected, keysB["init_boot"])
    }

    @Test
    fun signatureAndContentTamperingNeverPasses() {
        val sourceFile = file("hnubl_ota_backup_20261108_084414/init_boot_a.img")
        assumeTrue(sourceFile.isFile)
        val source = sourceFile.readBytes()
        val directory = java.nio.file.Files.createTempDirectory("boot-image-tamper-").toFile()
        try {
            val original = 2236416
            val mutations = listOf(0, 4096, original, original + 256 + 32, original + 256 + 576, source.size - 64)
            mutations.forEach { offset ->
                val changed = source.copyOf()
                changed[offset] = (changed[offset].toInt() xor 1).toByte()
                val modified = File(directory, "tampered-$offset.img").apply { writeBytes(changed) }
                val result = BootImageVerifier.inspectBoot(modified)
                assertFalse("偏移 $offset 的篡改不得同时通过签名与内容检查：${result.detail}", result.signatureValid && result.contentValid)
            }
            val signedMetadata = source.copyOfRange(original, original + 2176)
            signedMetadata[256 + 32] = (signedMetadata[256 + 32].toInt() xor 1).toByte()
            assertTrue(BootImageVerifier.chainKeys(File(directory, "tampered-vbmeta.img").apply { writeBytes(signedMetadata) }).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun truncatedAndForeignImagesAreRejected() {
        val directory = java.nio.file.Files.createTempDirectory("boot-image-verifier-").toFile()
        try {
            val truncated = File(directory, "truncated.img").apply { writeBytes(ByteArray(128)) }
            val foreign = File(directory, "foreign.img").apply { writeBytes(ByteArray(8388608)) }
            val first = BootImageVerifier.inspectBoot(truncated)
            val second = BootImageVerifier.inspectBoot(foreign)
            assertFalse(first.signatureValid || first.contentValid)
            assertFalse(second.signatureValid || second.contentValid)
            assertNull(second.stockImageSha1)
            assertTrue(BootImageVerifier.chainKeys(foreign).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun file(relative: String): File = File(root, relative)
}
