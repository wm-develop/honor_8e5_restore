package dev.honor.restore

import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.GptSnapshot
import dev.honor.restore.core.Hashes
import dev.honor.restore.core.ImageRestoreOperation
import dev.honor.restore.core.RestorePlan
import java.io.File
import java.util.UUID

class RestoreExecutor(
    private val shell: SuShell,
    private val scanner: DeviceScanner,
    private val backups: List<BackupSet>,
    private val plan: RestorePlan,
    private val scanned: ScannedDevice,
    private val log: (String) -> Unit,
    private val transaction: RestoreTransaction = RestoreTransaction(shell),
    private val companionControl: CompanionControl = CompanionControl(shell),
) {
    private val ram = "/dev/hnubl-restore-${UUID.randomUUID()}"
    private var started = false

    fun execute() {
        check(plan.images.isNotEmpty() || plan.gpt != null) { "设备已经符合恢复计划，无需写入" }
        check(shell.run("mkdir $ram && chmod 700 $ram && [ \"$(stat -f -c %T $ram)\" = tmpfs ]").exitCode == 0) { "RAM 恢复工作区创建失败" }
        var acquired = false
        try {
            transaction.acquire()
            acquired = true
            stopCompanion()
            pinSources()
            plan.images.forEach(::writeImage)
            plan.gpt?.let(::restoreGpt)
            cleanupRam()
            transaction.mark("WRITES_VERIFIED_AWAITING_RESCAN")
            log("全部分区写入与回读已经完成，等待最终重新扫描；尚未执行重启")
        } catch (error: Throwable) {
            log("恢复已停止：${error.message ?: error.javaClass.simpleName}")
            log(if (started) "已有写入开始，禁止盲目重试或重启；中断标记已持久化，RAM 证据保留在 $ram" else "尚未开始分区写入；RAM 证据保留在 $ram")
            throw error
        } finally {
            if (acquired) {
                val released = transaction.release()
                if (released.exitCode != 0) log("事务锁清理失败，请重新启动 App 后再核验：${released.output}")
            }
        }
    }

    private fun pinSources() {
        plan.images.forEach { operation ->
            val source = sourceFile(operation)
            check(Hashes.sha256(source) == operation.sourceSha256 && source.length() == operation.sourceBytes) { "${operation.target} 的恢复源在固定前发生变化" }
            if (operation.target.startsWith("init_boot_")) {
                val result = dev.honor.restore.core.BootImageVerifier.inspectBoot(source)
                check(result.signatureValid && result.contentValid) { "${operation.target} 的 init_boot AVB 校验失败：${result.detail}" }
                check(result.publicKeySha256 == scanned.snapshot.initBootChainKeys[operation.target.substringAfterLast('_')]) {
                    "${operation.target} 的 init_boot 公钥与设备链式公钥不一致"
                }
            }
            checkAblRollback(operation, source)
            pin(source, "${operation.target}.img", operation.sourceSha256)
        }
        plan.gpt?.let { operation ->
            val before = scanned.gptFile
            check(Hashes.sha256(before) == operation.beforeSha256) { "GPT 当前快照与计划不一致" }
            pin(before, "gpt-before.raw", operation.beforeSha256)
            val backup = backups.singleOrNull { it.id == operation.sourceBackupId } ?: error("GPT 恢复来源已失效")
            val clean = GptSnapshot.parse(backup.file(operation.sourceName))
            val target = GptSnapshot.buildRestoreTarget(clean, GptSnapshot.parse(before))
            check(target.sha256 == operation.afterSha256) { "GPT 恢复目标与已审查计划不一致" }
            val after = File(scanned.directory, "gpt-after.raw").apply { writeBytes(target.raw) }
            pin(after, "gpt-after.raw", operation.afterSha256)
        }
        val manifest = File(scanned.directory, "restore-commit.plan").apply {
            writeText(buildString {
                plan.images.forEach { appendLine("WRITE|${it.target}|${it.target}.img|${it.sourceSha256}|${it.beforeSha256}|${it.targetIdentity}") }
                plan.gpt?.let { appendLine("GPT|${it.disk}|gpt-before.raw|gpt-after.raw|${it.beforeSha256}|${it.afterSha256}") }
            })
        }
        pin(manifest, "restore-commit.plan", Hashes.sha256(manifest))
    }

    private fun pin(source: File, name: String, expectedHash: String) {
        require(Regex("[A-Za-z0-9_.-]+").matches(name))
        val result = shell.run("cp ${quote(source.absolutePath)} $ram/$name && " +
            "[ \"$(sha256sum $ram/$name)\" = \"$expectedHash  $ram/$name\" ] && chmod 400 $ram/$name")
        check(result.exitCode == 0) { "固定恢复源 $name 失败：${result.output}" }
    }

    private fun writeImage(operation: ImageRestoreOperation) {
        check(dev.honor.restore.core.OtaWritePolicy.isRestoreImageTarget(operation.target)) { "${operation.target} 不在允许恢复的写入范围内" }
        val fields = operation.targetIdentity.split('|')
        check(fields.size == 7 && fields[0] == operation.target) { "${operation.target} 目标身份记录无效" }
        check(operation.sourceBytes == operation.targetBytes && operation.targetBytes == fields[4].toLong()) { "${operation.target} 只接受完整分区镜像" }
        val byName = fields[1]
        log("正在恢复 ${operation.target} · 来源 ${operation.sourceName}")
        check(scanner.currentIdentity(operation.target) == operation.targetIdentity) { "${operation.target} 的身份已变化" }
        check(fullHash(byName) == operation.beforeSha256) { "${operation.target} 当前内容与扫描时不一致" }
        started = true
        transaction.mark("WRITE_STARTED|${operation.target}|${operation.sourceSha256}")
        val rw = shell.run("blockdev --setrw ${quote(byName)} && [ \"$(blockdev --getro ${quote(byName)})\" = 0 ]")
        check(rw.exitCode == 0) { "${operation.target} 无法进入可写状态：${rw.output}" }
        check(scanner.currentIdentity(operation.target) == operation.targetIdentity) { "${operation.target} 设置可写后身份变化" }
        val worker = File(scanned.directory, "partition_worker")
        val result = shell.run("${quote(worker.absolutePath)} write-range ${quote(byName)} 0 ${operation.targetBytes} $ram/${operation.target}.img --execute", 3600)
        check(result.exitCode == 0 && result.output.lineSequence().any { it == "READBACK=match" } &&
            result.output.lineSequence().any { it == "RESULT=success" }) { "${operation.target} 写入或回读失败：${result.output}" }
        check(fullHash(byName) == operation.sourceSha256) { "${operation.target} 完整分区哈希与恢复源不一致" }
        check(scanner.currentIdentity(operation.target) == operation.targetIdentity) { "${operation.target} 写入后身份变化" }
        log("${operation.target} 已恢复并完成完整回读")
    }

    private fun restoreGpt(operation: dev.honor.restore.core.GptRestoreOperation) {
        log("正在恢复 GPT：删除 efisp 并归还相邻 userdata 空间")
        val gpttool = File(scanned.directory, "gpttool")
        val current = File(scanned.directory, "prewrite-gpt.raw")
        val exportedBefore = shell.capture("${quote(gpttool.absolutePath)} export ${quote(operation.disk)}", current)
        check(exportedBefore.exitCode == 0 && Hashes.sha256(current) == operation.beforeSha256) { "当前 GPT 已在执行期间发生变化：${exportedBefore.output}" }
        started = true
        transaction.mark("GPT_STARTED|${operation.disk}|${operation.afterSha256}")
        val result = shell.run("${quote(gpttool.absolutePath)} apply-restore ${quote(operation.disk)} $ram/gpt-before.raw $ram/gpt-after.raw --execute", 3600)
        check(result.exitCode == 0 && result.output.lineSequence().any { it == "READBACK=match" } &&
            result.output.lineSequence().any { it == "RESULT=success" }) { "GPT 恢复失败：${result.output}" }
        val verify = shell.run("${quote(gpttool.absolutePath)} verify ${quote(operation.disk)}")
        check(verify.exitCode == 0 && verify.output.contains("GPT_VALID=1")) { "恢复后的 GPT 校验失败：${verify.output}" }
        val readback = File(scanned.directory, "restored-gpt.raw")
        val exported = shell.capture("${quote(gpttool.absolutePath)} export ${quote(operation.disk)}", readback)
        check(exported.exitCode == 0 && Hashes.sha256(readback) == operation.afterSha256) { "恢复后的 GPT 完整哈希不一致：${exported.output}" }
        check(GptSnapshot.parse(readback).named("efisp").isEmpty()) { "恢复后仍存在 efisp" }
        log("GPT 主备表恢复及完整快照回读通过；F2FS 未扩大、userdata 未格式化")
    }

    private fun stopCompanion() {
        val result = companionControl.stop()
        check(result.exitCode == 0) { "OTA 配套服务未完全停止，已阻止分区写入：${result.output}" }
        log("OTA 配套服务已停止并确认退出；未删除其他 Root 模块或 /data/adb 内容")
    }

    private fun cleanupRam() {
        val result = shell.run("rm -f $ram/* && rmdir $ram")
        check(result.exitCode == 0) { "恢复已完成，但 RAM 工作区清理失败：${result.output}" }
    }

    private fun fullHash(byName: String): String {
        val result = shell.run("sha256sum ${quote(byName)}", 600)
        check(result.exitCode == 0) { "分区哈希读取失败：${result.output}" }
        return result.output.trim().substringBefore(' ')
    }

    private fun checkAblRollback(operation: ImageRestoreOperation, source: File) {
        if (!operation.target.startsWith("abl_")) return
        val currentFile = scanned.images[operation.target] ?: error("缺少 ${operation.target} 当前镜像，无法检查防回滚")
        val current = dev.honor.restore.core.AblRollbackInspector.inspect(currentFile)
        val candidate = dev.honor.restore.core.AblRollbackInspector.inspect(source)
        check(current !is dev.honor.restore.core.AblRollbackInspector.Result.Invalid) {
            "${operation.target} 当前 ABL 防回滚结构无法解析：${(current as? dev.honor.restore.core.AblRollbackInspector.Result.Invalid)?.reason.orEmpty()}"
        }
        check(candidate !is dev.honor.restore.core.AblRollbackInspector.Result.Invalid) {
            "${operation.target} 恢复源防回滚结构无法解析：${(candidate as? dev.honor.restore.core.AblRollbackInspector.Result.Invalid)?.reason.orEmpty()}"
        }
        if (current is dev.honor.restore.core.AblRollbackInspector.Result.Parsed &&
            candidate is dev.honor.restore.core.AblRollbackInspector.Result.NoMetadata) {
            error("${operation.target} 恢复源缺少当前 ABL 已声明的防回滚元数据")
        }
        if (current is dev.honor.restore.core.AblRollbackInspector.Result.Parsed &&
            candidate is dev.honor.restore.core.AblRollbackInspector.Result.Parsed) {
            val candidateValues = candidate.counters.associate { it.authority to it.value }
            current.counters.forEach { counter ->
                val candidateValue = candidateValues[counter.authority]
                check(candidateValue != null && candidateValue >= counter.value) {
                    "${operation.target} 恢复源的 ${counter.authority} 防回滚计数低于当前值，已停止"
                }
            }
            log("${operation.target} 的镜像防回滚声明值检查通过；未读取设备熔丝或 RPMB")
        } else {
            log("${operation.target} 未形成可比较的完整防回滚计数；仅验证镜像结构，不宣称设备熔丝允许降级")
        }
    }

    private fun sourceFile(operation: ImageRestoreOperation): File {
        val backup = backups.singleOrNull { it.id == operation.sourceBackupId } ?: error("${operation.target} 的恢复来源已失效")
        return backup.file(operation.sourceName)
    }

    private fun quote(value: String): String = SuShell.quote(value)
}
