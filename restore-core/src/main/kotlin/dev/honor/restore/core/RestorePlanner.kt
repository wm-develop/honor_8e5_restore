package dev.honor.restore.core

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
        val allOta = backups.filterIsInstance<OtaBackup>().sortedBy { it.timestamp }
        val ota = allOta.takeLast(2)
        if (allOta.size > ota.size) notes += "更早的 ${allOta.size - ota.size} 套 OTA 备份不参与恢复；双槽只使用最近两次备份"
        if (ota.zipWithNext().any { (left, right) -> left.timestamp >= right.timestamp }) {
            blockers += "OTA 备份时间顺序不唯一，无法建立恢复链"
        }
        val cleanNormal = normal.filter { backup ->
            backup.records.keys.filter { it.startsWith("gpt_") && it.endsWith(".raw") }.any { name ->
                runCatching { GptSnapshot.parse(backup.file(name)) }.getOrNull()
                    ?.let { it.named("userdata").isNotEmpty() && it.named("efisp").isEmpty() } == true
            }
        }
        val selected = linkedMapOf<String, SourceCandidate>()
        val sourceGaps = mutableSetOf<String>()
        if (ota.isEmpty()) {
            cleanNormal.lastOrNull()?.let { source ->
                for (base in bases) for (slot in slots) select(target = "${base}_$slot", backup = source,
                    recordName = "${base}_$slot.img", reason = "无保锁 OTA 记录，使用 GPT 不含 efisp 的干净普通备份", selected = selected,
                    blockers = blockers, optional = false)
            }
            if (cleanNormal.isEmpty() && backups.isNotEmpty()) blockers += "缺少 GPT 不含 efisp 的假回锁前普通备份"
        } else {
            selectOtaSources(cleanNormal, ota, selected, blockers, notes, sourceGaps, historyMode == OtaHistoryMode.ONE)
        }
        if (historyMode == OtaHistoryMode.NONE && ota.isNotEmpty()) {
            blockers += "你选择了假回锁后没有 OTA，但导入了 OTA 备份；请核对实际次数"
        }
        if (historyMode == OtaHistoryMode.ONE && ota.size > 1) {
            blockers += "你选择了仅一次 OTA，却导入了多套 OTA 备份；请只保留最近一次，或改选多次"
        }
        if (device != null && ota.isNotEmpty()) {
            val latest = ota.last()
            if (device.runningSlot != latest.targetSlot) {
                blockers += "当前运行槽位为 ${device.runningSlot}，已导入中时间最晚的 OTA 备份目标槽为 ${latest.targetSlot}；可能缺少后续 OTA 备份或发生过槽位回退"
            }
        }
        val currentSlot = device?.runningSlot ?: ota.lastOrNull()?.targetSlot
        val otherSlot = when (currentSlot) {
            "a" -> "b"
            "b" -> "a"
            else -> null
        }
        val currentSlotOnly = currentSlot != null && otherSlot != null && ota.isNotEmpty() && historyMode != OtaHistoryMode.ONE &&
            (historyMode != OtaHistoryMode.MULTIPLE || ota.size < 2) && bases.all { base -> selected.containsKey("${base}_$currentSlot") }
        if (currentSlotOnly) {
            blockers.removeAll { message -> sourceGaps.any { target -> message.startsWith("$target ") || message.contains("$target ") } }
            if (historyMode == OtaHistoryMode.MULTIPLE) {
                warnings += "假回锁后执行过多次 OTA，但缺少倒数第二次备份：只能恢复当前 $currentSlot 槽，另一 $otherSlot 槽留待后续官方 OTA 修复"
            } else {
                warnings += "尚未确认假回锁后的 OTA 次数：当前先按仅恢复 $currentSlot 槽处理；如果实际只有一次 OTA，请选择“1 次”以使用干净备份恢复另一槽"
            }
            warnings += "仅恢复当前 $currentSlot 槽：另一 $otherSlot 槽不处理。删除共享 efisp 后，另一槽可能无法启动，不能再作为可靠回退槽。"
            warnings += "下一次系统更新不能直接使用不含 ABL/init_boot 的小增量包；必须使用包含它们的官方全量包，或先修复另一槽。只有官方 OTA 写入、切槽和重启全部成功，另一槽才算修复。"
            warnings += "如果当前槽第一次启动失败，可能无法依靠另一槽自救。请先导出个人数据和恢复前备份，接受这个风险后再继续。"
            notes += "普通恢复模式仅处理当前槽；未验证所传 OTA 是否为实际最近一次，由用户确认"
        }
        if (ota.isNotEmpty()) {
            when (historyMode) {
                OtaHistoryMode.ONE -> notes += "仅一次 OTA：目标槽使用最近备份，另一槽使用假回锁前的干净备份，可进行双槽恢复"
                OtaHistoryMode.MULTIPLE -> if (ota.size >= 2) notes += "多次 OTA：使用最近两次备份分别恢复双槽，更早备份不参与"
                OtaHistoryMode.UNKNOWN -> warnings += "请选择假回锁后的实际 OTA 次数；App 不会根据 A/B 交替猜测总次数"
                OtaHistoryMode.NONE -> Unit
            }
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
        val imageSlots = if (currentSlotOnly) listOf(currentSlot) else slots
        for (base in bases) for (slot in imageSlots) {
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
                notes += "$target 已经与候选原厂镜像一致，无需重复写入"
                continue
            }
            if (base == "abl" && partition != null) {
                val knownModified = partition.prefixHashes[642136L] == BUILTIN_ABL_SHA256 || ota.any { source ->
                    val write = source.write(target)
                    write != null && (partition.sha256 == write.sha256 || partition.prefixHashes[write.bytes] == write.sha256)
                }
                if (!knownModified) blockers += "$target 当前内容不属于已识别的 hnubl 修改或候选原厂镜像，已停止自动恢复"
            } else if (partition != null) {
                val known = backups.any { it.records[target]?.sha256 == partition.sha256 }
                if (!known) warnings += "$target 当前哈希不在备份中；若是 OTA 后重新修补的 KernelSU/SukiSU 镜像，这是预期情况，请人工核对"
            }
            operations += ImageRestoreOperation(target, candidate.backupId, candidate.sourceName, candidate.sha256,
                candidate.bytes, partition?.sha256.orEmpty(), partition?.identity.orEmpty(), partition?.bytes ?: candidate.bytes, candidate.reason)
        }
        restorePreAbl(ota, backups, device, operations, blockers, notes, if (currentSlotOnly) otherSlot else null)
        var gptOperation: GptRestoreOperation? = null
        if (device == null) {
            blockers += "尚未读取真实设备状态；当前结果只能用于离线审查"
        } else {
            if (device.incompleteRestore) {
                blockers += "上次恢复中断或结果未复核，必须先完成人工核验并清除中断标记"
            }
            if (device.otaMarker == "1") {
                warnings += "检测到历史 freeotaok=1 标记。它只表示上次保锁 OTA 已完成，不能证明当前仍在等待重启；若系统已正常使用，可继续，执行前请确认没有待重启更新"
            }
            if (device.f2fsBytes <= 0) blockers += "F2FS 容量检查未通过"
            val currentGpt = device.gpt
            if (currentGpt == null) {
                blockers += "当前 GPT 未通过结构校验"
            } else if (currentGpt.named("efisp").isNotEmpty()) {
                val currentUserdataBytes = currentGpt.named("userdata").singleOrNull()?.let { (it.endLba - it.startLba + 1) * currentGpt.logicalSectorBytes }
                if (currentUserdataBytes == null || device.f2fsBytes > currentUserdataBytes) blockers += "F2FS 超出当前 userdata 分区边界"
                val clean = findCleanGpt(normal, currentGpt, blockers)
                if (clean != null) {
                    val (backup, name, snapshot) = clean
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
        notes += "执行前暂停系统更新和其他分区工具；旧备份没有完整版本指纹，需人工核对当前系统镜像"
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

    private fun selectOtaSources(
        normal: List<NormalBackup>, ota: List<OtaBackup>, selected: MutableMap<String, SourceCandidate>,
        blockers: MutableList<String>, notes: MutableList<String>, sourceGaps: MutableSet<String>, allowOriginalWithoutOta: Boolean,
    ) {
        val latest = ota.last()
        for (base in bases) {
            val targetName = "${base}_${latest.targetSlot}.img"
            val targetRecord = latest.records[targetName]
            if (targetRecord == null) {
                blockers += "最近 OTA 备份缺少目标槽镜像 $targetName"
                continue
            }
            notes += "${latest.displayName}：运行时槽位 ${latest.currentSlot}，保锁写入目标 ${latest.targetSlot}，目标槽镜像用于恢复候选"
            for (slot in slots) {
                val target = "${base}_$slot"
                val recordName = "$target.img"
                val currentRecord = latest.records[recordName]
                when {
                    currentRecord?.sha256 == targetRecord.sha256 -> select(target, latest, targetRecord.name,
                        "与最近 OTA 目标槽的原厂候选哈希一致，可复用同一镜像", selected, blockers, false)
                    slot == latest.targetSlot -> select(target, latest, targetRecord.name,
                        "最近 OTA 目标槽在保锁 ABL 覆盖前的备份", selected, blockers, false)
                    else -> selectOlderSlotSource(normal, ota.dropLast(1), latest, base, slot, selected, blockers, sourceGaps, allowOriginalWithoutOta)
                }
            }
        }
    }

    private fun selectOlderSlotSource(
        normal: List<NormalBackup>, previousOta: List<OtaBackup>, latest: OtaBackup, base: String, slot: String,
        selected: MutableMap<String, SourceCandidate>, blockers: MutableList<String>, sourceGaps: MutableSet<String>,
        allowOriginalWithoutOta: Boolean,
    ) {
        val target = "${base}_$slot"
        val recordName = "$target.img"
        val currentRecord = latest.records[recordName]
        fun gap(message: String) {
            blockers += message
            sourceGaps += target
        }
        val previous = previousOta.lastOrNull { it.targetSlot == slot }
        if (previous != null) {
            val previousRecord = previous.records[recordName]
            val previousWrite = previous.write(target)
            when {
                previousRecord == null -> gap("$target 缺少前一次 OTA 的原厂候选")
                base == "init_boot" -> select(target, previous, previousRecord.name,
                    "该槽前一次作为 OTA 目标时的 init_boot 备份；当前槽镜像可能随后被 Root 修补", selected, blockers, false)
                currentRecord?.sha256 == previousWrite?.sha256 -> select(target, previous, previousRecord.name,
                    "当前槽仍是前一次保锁写入内容，恢复该次 OTA 覆盖前镜像", selected, blockers, false)
                currentRecord?.sha256 == previousRecord.sha256 -> select(target, previous, previousRecord.name,
                    "当前槽与前一次 OTA 覆盖前镜像一致", selected, blockers, false)
                else -> gap("$target 在两次 OTA 之间发生了未被记录解释的 ABL 变化；请提供当前版本官方镜像")
            }
            return
        }
        if (allowOriginalWithoutOta) {
            normal.lastOrNull()?.let { original ->
                select(target, original, recordName, "该槽未经历此次 OTA，使用假回锁前的干净备份", selected, blockers, false)
                return
            }
        }
        val original = normal.lastOrNull { it.records[recordName]?.sha256 == currentRecord?.sha256 }
        if (original != null) {
            select(target, original, recordName, "当前槽镜像与已校验的普通备份一致，且没有更早 OTA 覆盖记录", selected, blockers, false)
        } else {
            gap("$target 不能仅由最近 OTA 的当前槽备份证明为原厂镜像（${latest.displayName}）；请补充早期 OTA 或当前版本官方镜像")
        }
    }

    private fun restorePreAbl(
        ota: List<OtaBackup>, backups: List<BackupSet>, device: DeviceSnapshot?,
        operations: MutableList<ImageRestoreOperation>, blockers: MutableList<String>, notes: MutableList<String>,
        excludedSlot: String?,
    ) {
        val targets = ota.flatMap { source -> source.writes.map { it.target } }
            .filter { it != "efisp" && !it.startsWith("abl_") && !it.startsWith("init_boot_") }.distinct()
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

    private fun select(
        target: String, backup: BackupSet, recordName: String, reason: String,
        selected: MutableMap<String, SourceCandidate>, blockers: MutableList<String>, optional: Boolean,
    ) {
        val record = backup.records[recordName]
        if (record == null) {
            if (!optional) blockers += "${backup.displayName} 缺少 $recordName"
            return
        }
        selected[target] = SourceCandidate(target, backup.id, recordName, record.sha256, record.bytes, reason)
    }
}
