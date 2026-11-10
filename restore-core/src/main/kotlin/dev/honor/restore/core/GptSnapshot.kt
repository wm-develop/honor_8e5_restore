package dev.honor.restore.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32

private const val CONTAINER_BYTES = 512
private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

data class GptEntry(
    val number: Int,
    val name: String,
    val typeGuid: String,
    val uniqueGuid: String,
    val startLba: Long,
    val endLba: Long,
    val attributesPrimary: Long,
    val attributesBackup: Long,
    val primaryBytes: ByteArray,
    val backupBytes: ByteArray,
)

data class GptSnapshot(
    val raw: ByteArray,
    val diskBytes: Long,
    val logicalSectorBytes: Int,
    val diskGuid: String,
    val entryCount: Int,
    val entrySize: Int,
    val firstUsable: Long,
    val lastUsable: Long,
    val entries: List<GptEntry>,
) {
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    val entryBytes: Long get() = entryCount.toLong() * entrySize
    fun named(name: String): List<GptEntry> = entries.filter { it.name == name }

    companion object {
        fun parse(file: File): GptSnapshot = parse(file.readBytes())

        fun parse(raw: ByteArray): GptSnapshot {
            require(raw.size >= CONTAINER_BYTES) { "GPT 快照过短" }
            require(raw.copyOfRange(0, 8).contentEquals("HUBLGT22".toByteArray())) { "GPT 快照格式无效" }
            val header = ByteBuffer.wrap(raw, 0, CONTAINER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val sector = header.getInt(8)
            require(header.getInt(12) == 1 && sector in setOf(512, 4096)) { "GPT 快照版本或扇区大小无效" }
            require(raw.copyOfRange(64, CONTAINER_BYTES).all { it == 0.toByte() }) { "GPT 快照保留字段无效" }
            val diskBytes = header.getLong(16)
            require(diskBytes > 0 && diskBytes % sector == 0L && diskBytes / sector >= 6) { "GPT 磁盘容量无效" }
            val entryBytes = header.getLong(56)
            require(entryBytes in 128..MAX_ENTRY_BYTES) { "GPT 条目表长度无效" }
            val padded = ((entryBytes + sector - 1) / sector) * sector
            require(raw.size.toLong() == CONTAINER_BYTES + 3L * sector + 2L * padded) { "GPT 快照长度与几何信息不符" }
            val mbrAt = CONTAINER_BYTES
            val primaryHeaderAt = mbrAt + sector
            val primaryEntriesAt = primaryHeaderAt + sector
            val backupHeaderAt = primaryEntriesAt + padded.toInt()
            val backupEntriesAt = backupHeaderAt + sector
            require(raw[510 + mbrAt] == 0x55.toByte() && raw[511 + mbrAt] == 0xaa.toByte()) { "GPT 保护 MBR 签名无效" }
            val primary = parseHeader(raw, primaryHeaderAt, sector)
            val backup = parseHeader(raw, backupHeaderAt, sector)
            val sectors = diskBytes / sector
            require(primary.currentLba == 1L && primary.backupLba == sectors - 1 &&
                backup.currentLba == sectors - 1 && backup.backupLba == 1L) { "GPT 主备表头位置无效" }
            require(primary.entriesLba == header.getLong(32) && backup.currentLba == header.getLong(40) &&
                backup.entriesLba == header.getLong(48) && primary.entryCount * primary.entrySize == entryBytes) { "GPT 容器与表头不一致" }
            require(primary.guid.contentEquals(backup.guid) && primary.firstUsable == backup.firstUsable &&
                primary.lastUsable == backup.lastUsable && primary.entryCount == backup.entryCount && primary.entrySize == backup.entrySize) { "GPT 主备几何信息不一致" }
            require(primary.firstUsable > 1 && primary.firstUsable <= primary.lastUsable && primary.lastUsable < sectors - 1) { "GPT 可用范围无效" }
            require(primary.entriesLba >= 2 && primary.entriesLba + padded / sector <= primary.firstUsable &&
                backup.entriesLba > primary.lastUsable && backup.entriesLba + padded / sector <= sectors - 1) { "GPT 元数据区域重叠" }
            require(crc(raw, primaryEntriesAt, entryBytes.toInt()) == primary.entriesCrc &&
                crc(raw, backupEntriesAt, entryBytes.toInt()) == backup.entriesCrc) { "GPT 条目表 CRC 不匹配" }
            val entries = mutableListOf<GptEntry>()
            val uniqueGuids = mutableSetOf<String>()
            for (index in 0 until primary.entryCount.toInt()) {
                val pAt = primaryEntriesAt + index * primary.entrySize
                val bAt = backupEntriesAt + index * primary.entrySize
                val p = raw.copyOfRange(pAt, pAt + primary.entrySize)
                val b = raw.copyOfRange(bAt, bAt + primary.entrySize)
                require(p.copyOfRange(0, 48).contentEquals(b.copyOfRange(0, 48)) &&
                    p.copyOfRange(56, p.size).contentEquals(b.copyOfRange(56, b.size))) { "GPT 主备条目存在属性以外差异" }
                val attributeDiff = readLong(p, 48) xor readLong(b, 48)
                require(attributeDiff and ((3L shl 50).inv()) == 0L) { "GPT 主备属性差异超出已识别范围" }
                if (p.copyOfRange(0, 16).all { it == 0.toByte() }) {
                    require(b.copyOfRange(0, 16).all { it == 0.toByte() }) { "GPT 主备空条目不一致" }
                    continue
                }
                val start = readLong(p, 32)
                val end = readLong(p, 40)
                require(start >= primary.firstUsable && end >= start && end <= primary.lastUsable) { "GPT 分区范围无效" }
                val unique = guid(p, 16)
                require(uniqueGuids.add(unique)) { "GPT 分区 GUID 重复" }
                entries += GptEntry(index + 1, partitionName(p), guid(p, 0), unique, start, end,
                    readLong(p, 48), readLong(b, 48), p, b)
            }
            entries.sortedBy { it.startLba }.zipWithNext().forEach { (left, right) ->
                require(left.endLba < right.startLba) { "GPT 分区范围重叠" }
            }
            return GptSnapshot(raw, diskBytes, sector, guid(primary.guid, 0), primary.entryCount.toInt(), primary.entrySize,
                primary.firstUsable, primary.lastUsable, entries)
        }

        fun requireRestorable(clean: GptSnapshot, current: GptSnapshot) {
            require(clean.diskBytes == current.diskBytes && clean.logicalSectorBytes == current.logicalSectorBytes &&
                clean.diskGuid == current.diskGuid && clean.entryCount == current.entryCount && clean.entrySize == current.entrySize &&
                clean.firstUsable == current.firstUsable && clean.lastUsable == current.lastUsable) { "干净 GPT 与当前磁盘身份或几何信息不一致" }
            require(clean.named("efisp").isEmpty()) { "干净 GPT 仍包含 efisp" }
            val efisp = current.named("efisp").singleOrNull() ?: error("当前 GPT 必须且只能包含一个 efisp")
            val currentUserdata = current.named("userdata").singleOrNull() ?: error("当前 GPT 的 userdata 不唯一")
            val cleanUserdata = clean.named("userdata").singleOrNull() ?: error("干净 GPT 的 userdata 不唯一")
            require(efisp.startLba == currentUserdata.endLba + 1) {
                "efisp 与 userdata 不相邻，不能形成精确恢复"
            }
            require(cleanUserdata.number == currentUserdata.number && cleanUserdata.startLba == currentUserdata.startLba &&
                cleanUserdata.typeGuid == currentUserdata.typeGuid && cleanUserdata.uniqueGuid == currentUserdata.uniqueGuid) { "userdata 身份发生变化" }
            require(clean.raw.copyOfRange(0, CONTAINER_BYTES).contentEquals(current.raw.copyOfRange(0, CONTAINER_BYTES))) { "GPT 容器身份不一致" }
            val sector = clean.logicalSectorBytes
            val padded = (((clean.entryBytes + sector - 1) / sector) * sector).toInt()
            val cleanPrimaryAt = CONTAINER_BYTES + 2 * sector
            val cleanBackupAt = cleanPrimaryAt + padded + sector
            val currentPrimaryAt = cleanPrimaryAt
            val currentBackupAt = cleanBackupAt
            require(clean.raw.copyOfRange(CONTAINER_BYTES, CONTAINER_BYTES + sector)
                .contentEquals(current.raw.copyOfRange(CONTAINER_BYTES, CONTAINER_BYTES + sector))) { "GPT 保护 MBR 存在差异" }
            val offsets = listOf(CONTAINER_BYTES + sector, cleanPrimaryAt + padded)
            offsets.forEach { at ->
                val left = clean.raw.copyOfRange(at, at + sector)
                val right = current.raw.copyOfRange(at, at + sector)
                intArrayOf(16, 88).forEach { crcAt -> left.fill(0, crcAt, crcAt + 4); right.fill(0, crcAt, crcAt + 4) }
                require(left.contentEquals(right)) { "GPT 表头存在恢复范围以外的差异" }
            }
            for (index in 0 until clean.entryCount) {
                val number = index + 1
                val atP = index * clean.entrySize
                val cleanP = clean.raw.copyOfRange(cleanPrimaryAt + atP, cleanPrimaryAt + atP + clean.entrySize)
                val cleanB = clean.raw.copyOfRange(cleanBackupAt + atP, cleanBackupAt + atP + clean.entrySize)
                val currentP = current.raw.copyOfRange(currentPrimaryAt + atP, currentPrimaryAt + atP + current.entrySize)
                val currentB = current.raw.copyOfRange(currentBackupAt + atP, currentBackupAt + atP + current.entrySize)
                when (number) {
                    efisp.number -> {
                        require(cleanP.all { it == 0.toByte() } && cleanB.all { it == 0.toByte() }) { "干净 GPT 的 efisp 条目非空" }
                    }
                    currentUserdata.number -> {
                        for (byteAt in 0 until clean.entrySize) {
                            if (byteAt in 40..47) continue
                            require(cleanP[byteAt] == currentP[byteAt] && cleanB[byteAt] == currentB[byteAt]) { "userdata 存在末端以外的差异" }
                        }
                    }
                    else -> if (!cleanP.contentEquals(currentP) || !cleanB.contentEquals(currentB)) {
                        val cleanEntry = clean.entries.firstOrNull { it.number == number }
                        val currentEntry = current.entries.firstOrNull { it.number == number }
                        val slotPartition = cleanEntry != null && currentEntry != null && cleanEntry.name == currentEntry.name &&
                            (cleanEntry.name.endsWith("_a") || cleanEntry.name.endsWith("_b"))
                        require(slotPartition) { "GPT 第 $number 项存在恢复范围以外的差异" }
                        for (byteAt in 0 until clean.entrySize) {
                            if (byteAt in 0..15 || byteAt in 48..55) continue
                            require(cleanP[byteAt] == currentP[byteAt] && cleanB[byteAt] == currentB[byteAt]) {
                                "GPT 第 $number 项存在允许变化的类型与槽属性以外的差异"
                            }
                        }
                    }
                }
            }
        }

        fun buildRestoreTarget(clean: GptSnapshot, current: GptSnapshot): GptSnapshot {
            requireRestorable(clean, current)
            val raw = current.raw.copyOf()
            val sector = current.logicalSectorBytes
            val padded = (((current.entryBytes + sector - 1) / sector) * sector).toInt()
            val primaryHeaderAt = CONTAINER_BYTES + sector
            val primaryEntriesAt = primaryHeaderAt + sector
            val backupHeaderAt = primaryEntriesAt + padded
            val backupEntriesAt = backupHeaderAt + sector
            val userdata = current.named("userdata").single()
            val cleanUserdata = clean.named("userdata").single()
            val efisp = current.named("efisp").single()
            for (headerAt in listOf(primaryHeaderAt, backupHeaderAt)) {
                val entriesAt = if (headerAt == primaryHeaderAt) primaryEntriesAt else backupEntriesAt
                writeLong(raw, entriesAt + (userdata.number - 1) * current.entrySize + 40, cleanUserdata.endLba)
                raw.fill(0, entriesAt + (efisp.number - 1) * current.entrySize,
                    entriesAt + efisp.number * current.entrySize)
                writeInt(raw, headerAt + 88, crc(raw, entriesAt, current.entryBytes.toInt()))
                val headerSize = ByteBuffer.wrap(raw, headerAt + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val header = raw.copyOfRange(headerAt, headerAt + headerSize)
                header.fill(0, 16, 20)
                writeInt(raw, headerAt + 16, crc(header, 0, header.size))
            }
            return parse(raw)
        }

        private data class Header(
            val currentLba: Long, val backupLba: Long, val firstUsable: Long, val lastUsable: Long,
            val guid: ByteArray, val entriesLba: Long, val entryCount: Long, val entrySize: Int, val entriesCrc: Long,
        )

        private fun parseHeader(raw: ByteArray, at: Int, sector: Int): Header {
            val h = ByteBuffer.wrap(raw, at, sector).order(ByteOrder.LITTLE_ENDIAN).slice().order(ByteOrder.LITTLE_ENDIAN)
            require(raw.copyOfRange(at, at + 8).contentEquals("EFI PART".toByteArray()) && h.getInt(8) == 0x00010000) { "GPT 表头签名或版本无效" }
            val size = h.getInt(12)
            require(size in 92..sector && h.getInt(20) == 0) { "GPT 表头长度或保留字段无效" }
            val stored = h.getInt(16).toLong() and 0xffffffffL
            val checked = raw.copyOfRange(at, at + size)
            checked.fill(0, 16, 20)
            require(crc(checked, 0, checked.size) == stored) { "GPT 表头 CRC 不匹配" }
            return Header(h.getLong(24), h.getLong(32), h.getLong(40), h.getLong(48), raw.copyOfRange(at + 56, at + 72),
                h.getLong(72), h.getInt(80).toLong() and 0xffffffffL, h.getInt(84), h.getInt(88).toLong() and 0xffffffffL)
        }

        private fun crc(raw: ByteArray, at: Int, length: Int): Long = CRC32().apply { update(raw, at, length) }.value
        private fun readLong(raw: ByteArray, at: Int): Long = ByteBuffer.wrap(raw, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
        private fun writeLong(raw: ByteArray, at: Int, value: Long) {
            ByteBuffer.wrap(raw, at, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
        }
        private fun writeInt(raw: ByteArray, at: Int, value: Long) {
            ByteBuffer.wrap(raw, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
        }
        private fun guid(raw: ByteArray, at: Int): String {
            val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
            return buildString {
                order.forEachIndexed { index, offset ->
                    if (index in setOf(4, 6, 8, 10)) append('-')
                    append("%02X".format(raw[at + offset]))
                }
            }.uppercase(Locale.ROOT)
        }
        private fun partitionName(raw: ByteArray): String {
            val text = raw.copyOfRange(56, 56 + 72).toString(Charsets.UTF_16LE)
            return text.substringBefore('\u0000')
        }
    }
}
