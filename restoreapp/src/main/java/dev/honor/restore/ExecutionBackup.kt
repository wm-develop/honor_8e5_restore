package dev.honor.restore

import android.content.Context
import android.net.Uri
import dev.honor.restore.core.Hashes
import dev.honor.restore.core.ImageRecord
import dev.honor.restore.core.RestorePlan
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ExecutionBackup {
    fun export(context: Context, destination: Uri, scanned: ScannedDevice, plan: RestorePlan): String {
        val resolver = context.contentResolver
        val expected = linkedMapOf<String, ImageRecord>()
        val archiveDigest = MessageDigest.getInstance("SHA-256")
        resolver.openFileDescriptor(destination, "rwt").use { descriptor ->
            requireNotNull(descriptor) { "无法创建恢复前备份" }
            val fileOutput = java.io.FileOutputStream(descriptor.fileDescriptor)
            val output = DigestOutputStream(fileOutput, archiveDigest)
            val zip = ZipOutputStream(output)
            try {
                fun expect(name: String, record: ImageRecord) {
                    require(expected.put(name, record) == null) { "恢复前备份条目重复：$name" }
                }
                fun add(name: String, file: File) {
                    require(file.isFile) { "恢复前备份文件缺失：$name" }
                    expect(name, ImageRecord(name, file.length(), Hashes.sha256(file)))
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                scanned.images.toSortedMap().forEach { (name, file) -> add("$name.img", file) }
                add("current-gpt.raw", scanned.gptFile)
                val manifest = buildString {
                    appendLine("format=hnubl-restore-backup-v1")
                    appendLine("strategy=${plan.strategy}")
                    appendLine("historyMode=${RestoreRuntime.state.value.historyMode}")
                    appendLine("slot=${scanned.snapshot.runningSlot}")
                    appendLine("display=${scanned.snapshot.displayId}")
                    appendLine("fingerprint=${scanned.snapshot.fingerprint}")
                    plan.images.forEach {
                        appendLine("WRITE|${it.target}|${it.sourceBackupId}|${it.sourceName}|${it.sourceSha256}|${it.beforeSha256}")
                    }
                    plan.gpt?.let { appendLine("GPT|${it.disk}|${it.sourceBackupId}|${it.sourceName}|${it.beforeSha256}|${it.afterSha256}") }
                }.toByteArray()
                expect("restore-plan.txt", ImageRecord("restore-plan.txt", manifest.size.toLong(), Hashes.sha256(manifest.inputStream())))
                zip.putNextEntry(ZipEntry("restore-plan.txt"))
                zip.write(manifest)
                zip.closeEntry()
                zip.finish()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        }
        val expectedArchiveHash = archiveDigest.digest().toHex()
        val actualArchiveHash = resolver.openInputStream(destination).use { input ->
            requireNotNull(input) { "恢复前备份回读失败" }
            Hashes.sha256(input)
        }
        require(actualArchiveHash == expectedArchiveHash) { "恢复前备份回读字节与写入结果不一致" }
        resolver.openInputStream(destination).use { input ->
            requireNotNull(input) { "恢复前备份结构回读失败" }
            ZipInputStream(input).use { zip ->
                val seen = mutableSetOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val expectedEntry = expected[entry.name] ?: error("恢复前备份包含未知条目：${entry.name}")
                    require(!entry.isDirectory && seen.add(entry.name)) { "恢复前备份条目重复或目录异常：${entry.name}" }
                    val actualEntry = readEntry(zip, entry.name)
                    require(actualEntry.bytes == expectedEntry.bytes && actualEntry.sha256 == expectedEntry.sha256) {
                        "恢复前备份条目长度或 SHA-256 不一致：${entry.name}"
                    }
                    zip.closeEntry()
                }
                require(seen == expected.keys) { "恢复前备份条目集合不完整" }
            }
        }
        return expectedArchiveHash
    }

    private fun readEntry(input: InputStream, name: String): ImageRecord {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) {
                bytes = Math.addExact(bytes, count.toLong())
                digest.update(buffer, 0, count)
            }
        }
        return ImageRecord(name, bytes, digest.digest().toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
