package dev.honor.restore

import android.content.Context
import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.DeviceSnapshot
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.RestorePlan
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object DiagnosticLog {
    private const val MAX_EVENTS = 6000
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val MAX_MESSAGE_BYTES = 64 * 1024
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTime = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private var directory: File? = null
    private var currentFile: File? = null
    private var initialized = false
    private var truncated = false

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val target = File(context.filesDir, "diagnostics").apply { mkdirs() }
            directory = target
            currentFile = File(target, "restore-${fileTime.format(Date())}-${UUID.randomUUID().toString().take(8)}.log")
            initialized = true
            target.listFiles().orEmpty().filter { it.name.startsWith("restore-") && it.name.endsWith(".log") }
                .sortedByDescending { it.lastModified() }.drop(4).forEach { it.delete() }
            writeLocked("I", "Log", "诊断日志初始化 · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
    }

    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String) = write("W", tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        write("E", tag, message + (error?.let { "\n${android.util.Log.getStackTraceString(it)}" }.orEmpty()))

    fun backup(backup: BackupSet) {
        when (backup) {
            is NormalBackup -> i("Backup", "普通备份 ${backup.displayName} · 运行槽 ${backup.runningSlot} · ${backup.records.size} 个已校验文件")
            is OtaBackup -> i("Backup", "OTA 备份 ${backup.displayName} · ${backup.currentSlot}→${backup.targetSlot} · 类型=${backup.packageType} · 写入=${backup.writes.joinToString { "${it.target}:${it.sha256.take(12)}" }}")
        }
    }

    fun device(device: DeviceSnapshot) {
        i("Device", "槽位=${device.runningSlot} · 系统=${device.displayId} · 指纹=${device.fingerprint}\n" +
            "GPT 磁盘=${device.gptDisk} · GPT SHA-256=${device.gpt?.sha256} · F2FS=${device.f2fsBytes}\n" +
            "OTA 目录=${device.otaDirectoryNames} · init_boot 链式公钥=${device.initBootChainKeys}")
        device.partitions.values.sortedBy { it.name }.forEach { partition ->
            i("Partition", "${partition.name} · ${partition.identity}\nSHA-256=${partition.sha256}" +
                (partition.stockImageSha1?.let { " · stock_image.sha1=$it" }.orEmpty()))
        }
    }

    fun plan(plan: RestorePlan) {
        i("Plan", "状态=${plan.status} · 策略=${plan.strategy} · 镜像写入=${plan.images.size} · GPT=${plan.gpt != null}")
        plan.images.forEach { image ->
            i("PlanImage", "${image.target} ← ${image.sourceBackupId}/${image.sourceName}\n" +
                "source=${image.sourceSha256}\nbefore=${image.beforeSha256}\ntarget=${image.targetIdentity}\n原因=${image.reason}")
        }
        plan.gpt?.let { i("PlanGpt", "${it.disk} ← ${it.sourceBackupId}/${it.sourceName}\n" +
            "before=${it.beforeSha256}\nafter=${it.afterSha256}\n${it.reason}") }
        plan.blockers.forEach { w("PlanBlocker", it) }
        plan.warnings.forEach { w("PlanWarning", it) }
        plan.notes.forEach { i("PlanNote", it) }
    }

    fun text(): String = synchronized(lock) {
        if (events.isEmpty()) "暂无诊断日志" else events.joinToString("\n")
    }

    fun recentFiles(): List<File> = synchronized(lock) {
        val current = currentFile
        val previous = directory?.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("restore-") && it.name.endsWith(".log") }
        (listOfNotNull(current) + previous.filter { it != current }).distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }.take(3)
    }

    private fun write(level: String, tag: String, message: String) {
        synchronized(lock) {
            writeLocked(level, tag, message)
        }
    }

    private fun writeLocked(level: String, tag: String, message: String) {
        val safeMessage = message.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_MESSAGE_BYTES) it.copyOf(MAX_MESSAGE_BYTES).toString(Charsets.UTF_8) else String(it, Charsets.UTF_8) }
        val line = "${time.format(Date())} [$level] $tag: $safeMessage"
        events.addLast(line)
        while (events.size > MAX_EVENTS) events.removeFirst()
        val file = currentFile ?: return
        runCatching {
            if (file.length() + line.toByteArray(Charsets.UTF_8).size + 1 <= MAX_FILE_BYTES) {
                FileOutputStream(file, true).use { output ->
                    output.write((line + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } else if (!truncated) {
                truncated = true
                FileOutputStream(file, true).use { output ->
                    output.write("${time.format(Date())} [W] Log: 当前日志文件已达到 2 MiB，后续完整事件仍保留在应用内及导出摘要中\n".toByteArray())
                    output.flush()
                }
            }
        }.onFailure { }
    }
}
