package dev.honor.restore

import android.content.Context
import android.net.Uri
import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.Hashes
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.RestorePlan
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DiagnosticExporter {
    fun export(context: Context, destination: Uri): String {
        DiagnosticLog.init(context)
        val entries = linkedMapOf(
            "diagnostics.txt" to DiagnosticLog.text().toByteArray(),
            "summary.txt" to summary().toByteArray(),
        )
        DiagnosticLog.recentFiles().forEachIndexed { index, file ->
            require(file.isFile && file.length() <= 4 * 1024 * 1024) { "历史日志文件无效：${file.name}" }
            entries["history-${index + 1}-${file.name}"] = file.readBytes()
        }
        return writeVerifiedZip(context, destination, entries)
    }

    private fun summary(): String = buildString {
        val state = RestoreRuntime.state.value
        appendLine("format=hnubl-restore-diagnostics-v1")
        appendLine("package=${BuildConfig.APPLICATION_ID}")
        appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("historyMode=${state.historyMode}")
        appendLine("previousOtaChoice=${state.previousOtaChoice}")
        appendLine("stage=${state.stage}")
        appendLine("error=${state.error}")
        appendLine("completed=${state.completed}")
        appendLine("backupSha256=${state.backupSha256}")
        appendLine("backupCount=${state.backups.size}")
        state.backups.forEach { backup -> appendBackup(backup) }
        state.device?.let { device ->
            appendLine("device.slot=${device.runningSlot}")
            appendLine("device.display=${device.displayId}")
            appendLine("device.fingerprint=${device.fingerprint}")
            appendLine("device.gptDisk=${device.gptDisk}")
            appendLine("device.gptSha256=${device.gpt?.sha256}")
            appendLine("device.f2fsBytes=${device.f2fsBytes}")
            appendLine("device.otaDirectories=${device.otaDirectoryNames}")
            appendLine("device.initBootChainKeys=${device.initBootChainKeys}")
            device.partitions.values.sortedBy { it.name }.forEach { partition ->
                appendLine("device.partition=${partition.name}|${partition.identity}|${partition.sha256}|${partition.stockImageSha1}")
            }
        }
        state.plan?.let { plan -> appendPlan(plan) }
        appendLine("uiLogs=${state.logs.joinToString(" | ")}")
    }

    private fun StringBuilder.appendBackup(backup: BackupSet) {
        when (backup) {
            is NormalBackup -> appendLine("backup.normal=${backup.displayName}|slot=${backup.runningSlot}|records=${backup.records.size}")
            is OtaBackup -> {
                appendLine("backup.ota=${backup.displayName}|slot=${backup.currentSlot}|target=${backup.targetSlot}|type=${backup.packageType}|timestamp=${backup.timestamp}")
                backup.writes.forEach { appendLine("backup.write=${it.target}|${it.source}|${it.bytes}|${it.sha256}") }
            }
        }
    }

    private fun StringBuilder.appendPlan(plan: RestorePlan) {
        appendLine("plan.status=${plan.status}")
        appendLine("plan.strategy=${plan.strategy}")
        appendLine("plan.historyMismatch=${plan.historyMismatch}")
        plan.images.forEach { image ->
            appendLine("plan.image=${image.target}|${image.sourceBackupId}|${image.sourceName}|${image.sourceSha256}|${image.beforeSha256}|${image.targetIdentity}|${image.reason}")
        }
        plan.gpt?.let { appendLine("plan.gpt=${it.disk}|${it.sourceBackupId}|${it.sourceName}|${it.beforeSha256}|${it.afterSha256}") }
        plan.blockers.forEach { appendLine("plan.blocker=$it") }
        plan.warnings.forEach { appendLine("plan.warning=$it") }
        plan.notes.forEach { appendLine("plan.note=$it") }
    }

    private fun writeVerifiedZip(context: Context, destination: Uri, entries: Map<String, ByteArray>): String {
        val resolver = context.contentResolver
        val archiveDigest = MessageDigest.getInstance("SHA-256")
        resolver.openFileDescriptor(destination, "rwt").use { descriptor ->
            requireNotNull(descriptor) { "无法创建诊断日志 ZIP" }
            val fileOutput = java.io.FileOutputStream(descriptor.fileDescriptor)
            val output = DigestOutputStream(fileOutput, archiveDigest)
            val zip = ZipOutputStream(output)
            try {
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.finish()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        }
        val expectedHash = archiveDigest.digest().toHex()
        val actualHash = resolver.openInputStream(destination).use { input ->
            requireNotNull(input) { "诊断 ZIP 回读失败" }
            Hashes.sha256(input)
        }
        require(actualHash == expectedHash) { "诊断 ZIP 回读字节与写入结果不一致" }
        resolver.openInputStream(destination).use { input ->
            requireNotNull(input) { "诊断 ZIP 结构回读失败" }
            ZipInputStream(input).use { zip ->
                val seen = mutableSetOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val expected = entries[entry.name] ?: error("诊断 ZIP 包含未知条目：${entry.name}")
                    require(!entry.isDirectory && seen.add(entry.name)) { "诊断 ZIP 条目重复或目录异常" }
                    val actual = readEntry(zip)
                    require(actual.size == expected.size && Hashes.sha256(actual.inputStream()) == Hashes.sha256(expected.inputStream())) {
                        "诊断 ZIP 条目校验失败：${entry.name}"
                    }
                    zip.closeEntry()
                }
                require(seen == entries.keys) { "诊断 ZIP 条目不完整" }
            }
        }
        return expectedHash
    }

    private fun readEntry(input: InputStream): ByteArray = input.readBytes()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
