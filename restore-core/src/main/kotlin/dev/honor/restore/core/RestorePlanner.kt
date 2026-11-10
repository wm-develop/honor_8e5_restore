package dev.honor.restore.core

import java.io.File
import java.security.MessageDigest

object RestorePlanner {
    private val bases = listOf("abl", "init_boot")
    private val slots = listOf("a", "b")

    fun plan(backups: List<BackupSet>, device: DeviceSnapshot?, historyMode: OtaHistoryMode = OtaHistoryMode.UNKNOWN): RestorePlan {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val operations = mutableListOf<ImageRestoreOperation>()
        if (backups.isEmpty()) blockers += "尚未导入备份"
        backups.groupBy { it.id }.values.forEach { group -> if (group.size > 1) blockers += "备份标识重复：${group.first().displayName}" }
        val normal = backups.filterIsInstance<NormalBackup>().sortedBy { it.timestamp }
        val ota = backups.filterIsInstance<OtaBackup>().sortedBy { it.timestamp }
        if (ota.zipWithNext().any { (left, right) -> left.timestamp >= right.timestamp }) {
            blockers += "OTA 备份时间顺序不唯一，无法建立恢复链"
        }
        if (ota.zipWithNext().any { (left, right) -> left.targetSlot != right.currentSlot }) {
            blockers += "OTA 备份的槽位顺序不连续，不能按大版本/小补丁历史建立恢复计划"
        }
        val cleanNormal = normal.filter { backup ->
            backup.records.keys.filter { it.startsWith("gpt_") && it.endsWith(".raw") }.any { name ->
                runCatching { GptSnapshot.parse(backup.file(name)) }.getOrNull()
                    ?.let { it.named("userdata").isNotEmpty() && it.named("efisp").isEmpty() } == true
            }
        }
        val clean = cleanNormal.lastOrNull()
        val selected = linkedMapOf<String, SourceCandidate>()
        val latest = ota.lastOrNull()
        val currentSlot = device?.runningSlot ?: latest?.targetSlot
        val otherSlot = if (currentSlot == "a") "b" else "a"
        val currentSlotOnly = historyMode == OtaHistoryMode.MULTIPLE && ota.size == 1 &&
            latest?.packageType == OtaPackageType.MAJOR
        val targetSlots = if (currentSlotOnly && currentSlot != null) listOf(currentSlot) else slots

        fun select(target: String, backup: BackupSet, sourceSlot: String, reason: String) {
            val base = target.substringBeforeLast('_')
            val record = backup.records["${base}_$sourceSlot.img"]
            if (record == null) {
                blockers += "${backup.displayName} 缺少 ${base}_$sourceSlot.img"
                return
            }
            val file = backup.file(record.name)
            if (!file.isFile) {
                blockers += "${backup.displayName} 的 ${record.name} 不存在"
                return
            }
            if (base == "init_boot") {
                val result = BootImageVerifier.inspectBoot(file)
                if (!result.signatureValid || !result.contentValid) {
                    blockers += "$target 的候选 init_boot 未通过 AVB 原始内容或签名验证：${result.detail}"
                    return
                }
                if (device != null && device.initBootChainKeys.isNotEmpty() &&
                    device.initBootChainKeys[target.substringAfterLast('_')] != result.publicKeySha256) {
                    blockers += "$target 的 init_boot 内嵌公钥与设备 vbmeta 链式公钥不一致"
                    return
                }
                if (backup is OtaBackup) {
                    warnings += "$target 的 init_boot 已通过 AVB 原始内容和签名校验，但其版本与当前系统或目标槽的适用性仍需你确认"
                }
                if (sourceSlot != target.substringAfterLast('_')) {
                    val stockSha1 = sha1(file)
                    val evidence = device?.partitions?.get(target)?.sha256 == record.sha256 ||
                        crossSlotStockEvidence(backups, source = backup, target = target, stockSha1 = stockSha1, stockSha256 = record.sha256) ||
                        device?.partitions?.get(target)?.stockImageSha1 == stockSha1
                    if (!evidence) {
                        blockers += "$target 没有证据证明该槽修补前的官方 init_boot 与来源槽镜像相同，禁止跨槽复用"
                        return
                    }
                    notes += "$target 跨槽复用依据：原版 stock_image.sha1、当前镜像或后续备份证明其与所选干净镜像相同"
                }
            } else {
                val knownPatched = record.sha256 == BUILTIN_ABL_SHA256 ||
                    (file.length() > 642136L && Hashes.sha256Prefix(file, 642136L) == BUILTIN_ABL_SHA256)
                if (knownPatched) {
                    blockers += "$target 的候选 ABL 是已识别的 hnubl 内置修补版，禁止作为干净镜像"
                    return
                }
                val structure = AblRollbackInspector.inspect(file)
                if (structure is AblRollbackInspector.Result.Invalid) {
                    blockers += "$target 的候选 ABL 结构无效：${structure.reason}"
                    return
                }
                if (sourceSlot != target.substringAfterLast('_')) {
                    warnings += "$target 跨槽复用同一 ABL：已排除已知内置修补版并检查结构，但未独立验证厂商签名信任链；版本兼容及小补丁未更新 ABL 由你确认并承担风险"
                } else {
                    warnings += "$target 的 ABL 已排除已知内置修补版并检查结构，但官方版本、签名信任链及与当前系统的兼容性仍需你确认并承担风险"
                }
            }
            selected[target] = SourceCandidate(target, backup.id, record.name, record.sha256, record.bytes, reason)
        }

        fun selectFromNormal(target: String) {
            val source = clean
            if (source == null) {
                blockers += "缺少 GPT 不含 efisp 的假回锁前普通备份"
                return
            }
            select(target, source, target.substringAfterLast('_'), "假回锁前普通备份的对应槽镜像；文件、AVB/结构与版本适用性分别核对")
        }

        fun selectFromOta(target: String, source: OtaBackup, reason: String) =
            select(target, source, source.targetSlot, reason)

        if (historyMode == OtaHistoryMode.NONE) {
            if (ota.isNotEmpty()) blockers += "你选择了假回锁后没有 OTA，但导入了 OTA 备份；请核对实际次数"
            for (base in bases) for (slot in slots) selectFromNormal("${base}_$slot")
            if (clean == null && backups.isNotEmpty()) blockers += "缺少 GPT 不含 efisp 的假回锁前普通备份"
        } else if (latest == null) {
            if (backups.isNotEmpty()) blockers += "尚未导入实际保锁 OTA 备份并选择其更新类型"
        } else {
            if (ota.any { it.packageType == OtaPackageType.UNSET }) {
                blockers += "每次导入 OTA 备份后，都必须确认它是大版本还是小补丁"
            }
            if (device != null && device.runningSlot != latest.targetSlot) {
                blockers += "当前运行槽位为 ${device.runningSlot}，已导入中时间最晚的 OTA 备份目标槽为 ${latest.targetSlot}；可能缺少后续 OTA 备份或发生过槽位回退"
            }
            if (historyMode == OtaHistoryMode.ONE && ota.size != 1) {
                blockers += "你选择了仅一次 OTA，却导入了多套 OTA 备份；请重新按实际历史导入"
            }
            when (latest.packageType) {
                OtaPackageType.UNSET -> Unit
                OtaPackageType.MAJOR -> when (historyMode) {
                    OtaHistoryMode.ONE -> {
                        for (base in bases) {
                            selectFromOta("${base}_${latest.targetSlot}", latest,
                                "一次大版本 OTA 的目标槽镜像；另一槽使用假回锁前对应槽备份")
                            selectFromNormal("${base}_${latest.currentSlot}")
                        }
                        warnings += "一次大版本：新目标槽采用该次镜像，另一槽采用假回锁前镜像；两份镜像的版本和适用性需你确认"
                    }
                    OtaHistoryMode.MULTIPLE -> {
                        val previous = ota.dropLast(1).lastOrNull()
                        for (base in bases) {
                            selectFromOta("${base}_${latest.targetSlot}", latest,
                                "最后一次大版本 OTA 的目标槽镜像")
                            if (previous?.packageType == OtaPackageType.MAJOR) {
                                selectFromOta("${base}_${previous.targetSlot}", previous,
                                    "倒数第二次为大版本，使用其目标槽镜像恢复对应槽")
                            } else if (!currentSlotOnly && currentSlot != null) {
                                selectFromOta("${base}_$otherSlot", latest,
                                    "小补丁在前、大版本在后；以最后一次大版本镜像为双槽候选")
                            }
                        }
                        if (previous?.packageType == OtaPackageType.MAJOR) {
                            notes += "两次都是大版本：分别采用两次备份中的目标槽镜像，未把整份备份一概视为干净"
                        } else if (previous?.packageType == OtaPackageType.PATCH) {
                            warnings += "小补丁在前、大版本在后：双槽均考虑最后一次大版本镜像；跨槽和版本适用性仍需逐项通过检查"
                        }
                    }
                    else -> blockers += "请选择假回锁后的实际 OTA 次数"
                }
                OtaPackageType.PATCH -> when (historyMode) {
                    OtaHistoryMode.ONE -> {
                        for (base in bases) for (slot in slots) selectFromNormal("${base}_$slot")
                        warnings += "仅一次小补丁：按你的确认，该补丁未更新 ABL/init_boot，双槽使用假回锁前镜像；App 无法独立核实补丁内容，错误判断可能导致无法启动"
                    }
                    OtaHistoryMode.MULTIPLE -> {
                        val major = ota.lastOrNull { it.packageType == OtaPackageType.MAJOR }
                        if (major == null) {
                            blockers += "最近连续多次都是小补丁，尚未导入此前的大版本备份；请继续按倒数顺序导入，直到找到大版本"
                        } else {
                            for (base in bases) for (slot in targetSlots) selectFromOta("${base}_$slot", major,
                                "最近为小补丁，采用此前大版本目标槽镜像作为双槽候选")
                            warnings += "最近为小补丁：你确认从 ${major.displayName} 对应的大版本之后，所有小补丁均未更新 ABL/init_boot。App 无法独立核实，版本判断错误可能导致当前槽或另一槽无法启动"
                        }
                    }
                    else -> blockers += "请选择假回锁后的实际 OTA 次数"
                }
            }
        }
        if (currentSlotOnly) {
            warnings += "只有最近一次大版本备份：仅恢复当前 $currentSlot 槽，另一 $otherSlot 槽不处理。删除共享 efisp 后，另一槽可能无法启动，不能再作为可靠回退槽"
            warnings += "下一次系统更新不能直接使用不含 ABL/init_boot 的小增量包；必须使用包含它们的官方全量包，或先修复另一槽"
            notes += "仅当前槽属于风险承担方案；未验证所传 OTA 是否为实际最近一次，由用户确认"
        }
        val historyMismatch = when {
            device?.otaDirectoryCount == null -> null
            historyMode == OtaHistoryMode.NONE && device.otaDirectoryCount > 0 ->
                "你选择了没有 OTA，但 Download/hnubl 中有 ${device.otaDirectoryCount} 套 OTA 备份目录。目录可能包含旧操作，请确认你的选择真实并对此负责"
            historyMode == OtaHistoryMode.ONE && device.otaDirectoryCount != 1 ->
                "你选择了仅一次 OTA，但 Download/hnubl 中有 ${device.otaDirectoryCount} 套 OTA 备份目录。可能存在其他 OTA，或备份被删除/移动/重复；请确认你的选择真实并对此负责"
            historyMode == OtaHistoryMode.MULTIPLE && device.otaDirectoryCount < 2 ->
                "你选择了多次 OTA，但 Download/hnubl 中只有 ${device.otaDirectoryCount} 套 OTA 备份目录。备份可能不完整，请确认你的选择真实并对此负责"
            else -> null
        }
        if (historyMismatch != null) warnings += historyMismatch
        val strategy = if (currentSlotOnly) RestoreStrategy.CURRENT_SLOT_ONLY else RestoreStrategy.FULL
        for (base in bases) for (slot in targetSlots) {
            val target = "${base}_$slot"
            val candidate = selected[target] ?: continue
            val sourceBackup = backups.singleOrNull { it.id == candidate.backupId }
            if (sourceBackup == null) {
                blockers += "$target 的恢复来源记录无效"
                continue
            }
            val partition = device?.partitions?.get(target)
            if (device != null && partition == null) {
                blockers += "设备缺少必需分区 $target"
                continue
            }
            if (partition != null && partition.bytes != candidate.bytes) {
                blockers += "$target 恢复镜像与分区容量不一致（${candidate.bytes} / ${partition.bytes}）"
                continue
            }
            if (partition?.sha256 == candidate.sha256) {
                notes += "$target 已经与所选且通过当前检查的恢复镜像一致，无需重复写入"
                continue
            }
            if (base == "abl" && partition != null) {
                val knownCurrent = partition.prefixHashes[642136L] == BUILTIN_ABL_SHA256 ||
                    ota.any { source ->
                        val write = source.write(target)
                        write != null && (partition.sha256 == write.sha256 || partition.prefixHashes[write.bytes] == write.sha256)
                    } || backups.any { it.records["$target.img"]?.sha256 == partition.sha256 }
                if (!knownCurrent) blockers += "$target 当前内容不属于已识别的 hnubl 修改、候选镜像或已导入备份，已停止自动恢复"
            } else if (partition != null) {
                val known = backups.any { it.records["$target.img"]?.sha256 == partition.sha256 }
                if (!known) warnings += "$target 当前哈希不在备份中；执行前会重新读取并核对，不会把当前镜像当作恢复来源"
            }
            operations += ImageRestoreOperation(target, candidate.backupId, candidate.sourceName, candidate.sha256,
                candidate.bytes, partition?.sha256.orEmpty(), partition?.identity.orEmpty(), partition?.bytes ?: candidate.bytes, candidate.reason)
        }
        restorePreAbl(ota, backups, device, operations, blockers, notes, if (currentSlotOnly) otherSlot else null)
        var gptOperation: GptRestoreOperation? = null
        if (device == null) {
            blockers += "尚未读取真实设备状态；当前结果只能用于离线审查"
            notes += "设备扫描后将用真实 vbmeta 链式公钥复核 init_boot；离线通过不等于已允许写入"
        } else {
            if (device.incompleteRestore) blockers += "上次恢复中断或结果未复核，必须先完成人工核验并清除中断标记"
            if (device.otaMarker == "1") {
                warnings += "检测到历史 freeotaok=1 标记。它只表示上次保锁 OTA 已完成，不能证明当前仍在等待重启；若系统已正常使用，可继续，执行前请确认没有待重启更新"
            }
            if (device.initBootChainKeys.isEmpty()) blockers += "没有取得设备 vbmeta 的 init_boot 链式公钥，不能完成镜像校验"
            if (device.f2fsBytes <= 0) blockers += "F2FS 容量检查未通过"
            val currentGpt = device.gpt
            if (currentGpt == null) {
                blockers += "当前 GPT 未通过结构校验"
            } else if (currentGpt.named("efisp").isNotEmpty()) {
                val currentUserdataBytes = currentGpt.named("userdata").singleOrNull()?.let { (it.endLba - it.startLba + 1) * currentGpt.logicalSectorBytes }
                if (currentUserdataBytes == null || device.f2fsBytes > currentUserdataBytes) blockers += "F2FS 超出当前 userdata 分区边界"
                val cleanGpt = findCleanGpt(normal, currentGpt, blockers)
                if (cleanGpt != null) {
                    val (backup, name, snapshot) = cleanGpt
                    val restoredBytes = snapshot.named("userdata").single().let { (it.endLba - it.startLba + 1) * snapshot.logicalSectorBytes }
                    if (device.f2fsBytes > restoredBytes) blockers += "F2FS 无法容纳于恢复后的 userdata"
                    gptOperation = GptRestoreOperation(device.gptDisk, backup.id, name, currentGpt.sha256, snapshot.sha256,
                        "仅删除 efisp 并恢复原始 userdata 末端；保留当前各分区类型、GUID 与 A/B 槽属性")
                }
            } else {
                notes += "当前 GPT 已不含 efisp，无需修改分区表"
            }
        }
        notes += "恢复过程不格式化 userdata、不主动扩大 F2FS、不切换槽位、不自动重启"
        notes += "大版本/小补丁由用户声明；App 无法仅凭 hnubl 备份独立核实官方补丁清单，相关版本假设属于风险承担范围"
        notes += "不执行官方回锁，不修改 OEMINFO/DeviceInfo；Fastboot 的 PHONE Unlocked 需要单独核验"
        return RestorePlan(
            status = if (blockers.isEmpty()) RestorePlanStatus.READY else RestorePlanStatus.BLOCKED,
            strategy = strategy,
            historyMismatch = historyMismatch,
            images = operations,
            gpt = gptOperation,
            blockers = blockers.distinct(),
            warnings = warnings.distinct(),
            notes = notes.distinct(),
        )
    }

    private fun crossSlotStockEvidence(backups: List<BackupSet>, source: BackupSet, target: String, stockSha1: String, stockSha256: String): Boolean =
        backups.filterIsInstance<OtaBackup>().filter { it.timestamp >= source.timestamp }.any { backup ->
            val record = backup.records["$target.img"] ?: return@any false
            runCatching {
                val result = BootImageVerifier.inspectBoot(backup.file(record.name))
                result.stockImageSha1 == stockSha1 || (record.sha256 == stockSha256 && result.signatureValid && result.contentValid)
            }.getOrDefault(false)
        }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun restorePreAbl(
        ota: List<OtaBackup>, backups: List<BackupSet>, device: DeviceSnapshot?,
        operations: MutableList<ImageRestoreOperation>, blockers: MutableList<String>, notes: MutableList<String>,
        excludedSlot: String?,
    ) {
        val targets = ota.flatMap { source -> source.writes.map { it.target } }
            .filter(OtaWritePolicy::isPreAblTarget).distinct()
        for (target in targets) {
            if (excludedSlot != null && target.endsWith("_$excludedSlot")) {
                notes += "$target 位于不处理的另一槽，本次不写入"
                continue
            }
            val source = ota.last { it.write(target) != null }
            val write = source.write(target)!!
            val record = source.records["$target.img"]
            if (record == null) {
                blockers += "$target 曾被保锁 OTA 对拷，但对应覆盖前公开备份缺失"
                continue
            }
            val partition = device?.partitions?.get(target)
            if (device != null && partition == null) {
                blockers += "设备缺少 OTA 曾修改的前级分区 $target"
                continue
            }
            if (partition != null && partition.sha256 != write.sha256 && partition.sha256 != record.sha256) {
                blockers += "$target 当前哈希与 OTA 写入结果及覆盖前备份均不一致"
                continue
            }
            if (partition?.sha256 == record.sha256) {
                notes += "$target 已与 OTA 覆盖前镜像一致，无需写入"
                continue
            }
            operations += ImageRestoreOperation(target, source.id, record.name, record.sha256, record.bytes,
                partition?.sha256.orEmpty(), partition?.identity.orEmpty(), partition?.bytes ?: record.bytes,
                "撤销 ${source.displayName} 中记录的前级引导分区对拷")
        }
    }

    private fun findCleanGpt(
        normal: List<NormalBackup>, current: GptSnapshot, blockers: MutableList<String>,
    ): Triple<NormalBackup, String, GptSnapshot>? {
        var lastError = "没有可用的干净 GPT 备份"
        normal.asReversed().forEach { backup ->
            backup.records.keys.filter { it.startsWith("gpt_") && it.endsWith(".raw") }.sorted().forEach { name ->
                runCatching { GptSnapshot.parse(backup.file(name)) }.getOrNull()?.let { snapshot ->
                    if (snapshot.diskGuid == current.diskGuid && snapshot.diskBytes == current.diskBytes && snapshot.named("efisp").isEmpty()) {
                        try {
                            val target = GptSnapshot.buildRestoreTarget(snapshot, current)
                            return Triple(backup, name, target)
                        } catch (error: Exception) {
                            lastError = error.message ?: lastError
                        }
                    }
                }
            }
        }
        blockers += lastError
        return null
    }
}
