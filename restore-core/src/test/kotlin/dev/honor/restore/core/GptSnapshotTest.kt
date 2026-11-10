package dev.honor.restore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GptSnapshotTest {
    @Test
    fun realCleanAndModifiedSnapshotsFormExactRestore() {
        val cleanFile = File("..", "ublbackup_20261029_233101/gpt_sda.raw")
        val modifiedFile = File("..", "ublbackup_20261030_131440/gpt_sda.raw")
        assumeTrue(cleanFile.isFile && modifiedFile.isFile)

        val clean = GptSnapshot.parse(cleanFile)
        val modified = GptSnapshot.parse(modifiedFile)
        GptSnapshot.requireRestorable(clean, modified)

        assertEquals("f1ebe39743f7299d69705c950b093fefd761d156b8901453f0f565b926963bff", clean.sha256)
        assertEquals("9b2d221315abe406b8e72587cb95ce8668c3f0afc23b073dad280c61fed8b3a2", modified.sha256)
        assertTrue(clean.named("efisp").isEmpty())
        assertEquals(1, modified.named("efisp").size)
        assertEquals(clean.named("userdata").single().endLba, modified.named("efisp").single().endLba)
        assertEquals(clean.sha256, GptSnapshot.buildRestoreTarget(clean, modified).sha256)
    }

    @Test
    fun slotAttributeChangesArePreservedInsteadOfRestoringOldSlotState() {
        val cleanFile = File("..", "ublbackup_20261029_233101/gpt_sda.raw")
        val modifiedFile = File("..", "ublbackup_20261030_131440/gpt_sda.raw")
        assumeTrue(cleanFile.isFile && modifiedFile.isFile)
        val clean = GptSnapshot.parse(cleanFile)
        val modified = GptSnapshot.parse(modifiedFile)
        val partition = modified.named("eng_system_a").single()
        val changedAttributes = partition.attributesPrimary xor 0x10L
        val raw = modified.raw.copyOf()
        val sector = modified.logicalSectorBytes
        val padded = ((modified.entryBytes + sector - 1) / sector * sector).toInt()
        val primaryAt = 512 + 2 * sector + (partition.number - 1) * modified.entrySize + 48
        val backupAt = primaryAt + padded + sector
        ByteBuffer.wrap(raw, primaryAt, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(changedAttributes)
        ByteBuffer.wrap(raw, backupAt, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(changedAttributes)
        raw[primaryAt - 48] = (raw[primaryAt - 48].toInt() xor 1).toByte()
        raw[backupAt - 48] = (raw[backupAt - 48].toInt() xor 1).toByte()
        recalculate(raw)
        val current = GptSnapshot.parse(raw)

        val target = GptSnapshot.buildRestoreTarget(clean, current)

        assertEquals(current.named("eng_system_a").single().typeGuid, target.named("eng_system_a").single().typeGuid)
        assertEquals(changedAttributes, target.named("eng_system_a").single().attributesPrimary)
        assertTrue(target.named("efisp").isEmpty())
        assertEquals(clean.named("userdata").single().endLba, target.named("userdata").single().endLba)
    }

    private fun recalculate(raw: ByteArray) {
        val sector = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val entryBytes = ByteBuffer.wrap(raw, 56, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
        val padded = (entryBytes + sector - 1) / sector * sector
        val primaryHeader = 512 + sector
        val primaryEntries = primaryHeader + sector
        val backupHeader = primaryEntries + padded
        val backupEntries = backupHeader + sector
        for ((header, entries) in listOf(primaryHeader to primaryEntries, backupHeader to backupEntries)) {
            val entriesCrc = CRC32().apply { update(raw, entries, entryBytes) }.value.toInt()
            ByteBuffer.wrap(raw, header + 88, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(entriesCrc)
            ByteBuffer.wrap(raw, header + 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
            val headerBytes = raw.copyOfRange(header, header + 92)
            val headerCrc = CRC32().apply { update(headerBytes) }.value.toInt()
            ByteBuffer.wrap(raw, header + 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerCrc)
        }
    }
}
