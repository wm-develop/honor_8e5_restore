package dev.honor.restore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RestorePlannerTest {
    private val root = File("..").canonicalFile

    @Test
    fun twoRealOtaBackupsProduceCurrentVersionRestorePlan() {
        val files = listOf(
            "ublbackup_20261029_233101",
            "ublbackup_20261030_131440",
            "hnubl_ota_backup_20261108_084414",
            "hnubl_ota_backup_20261108_093831",
        )
        assumeTrue(files.all { File(root, it).isDirectory })
        val backups = files.map { BackupVerifier.verify(File(root, it)) }
        val second = backups.filterIsInstance<OtaBackup>().last()
        val stockAbl = second.records.getValue("abl_b.img").sha256
        val stockInit = second.records.getValue("init_boot_b.img").sha256
        val modifiedGpt = GptSnapshot.parse(File(root, "ublbackup_20261030_131440/gpt_sda.raw"))
        val partitions = mutableMapOf<String, DevicePartition>()
        for (base in listOf("abl", "init_boot")) for (slot in listOf("a", "b")) {
            val target = "${base}_$slot"
            val hash = when (target) {
                "abl_a" -> stockAbl
                "abl_b" -> BUILTIN_ABL_SHA256
                "init_boot_a" -> stockInit
                else -> "1".repeat(64)
            }
            partitions[target] = DevicePartition(target, "$target|/dev/block/by-name/$target|/dev/block/test|259:1",
                if (base == "abl") 4194304 else 8388608, hash,
                if (target == "abl_b") mapOf(642136L to BUILTIN_ABL_SHA256) else emptyMap())
        }
        val device = DeviceSnapshot("b", "current-build", "current-fingerprint", partitions,
            "/dev/block/sda", modifiedGpt, 490811162624, "0")

        val plan = RestorePlanner.plan(backups, device, historyMode = OtaHistoryMode.MULTIPLE)

        assertEquals(plan.blockers.joinToString("\n"), RestorePlanStatus.READY, plan.status)
        assertEquals(listOf("abl_b", "init_boot_b"), plan.images.map { it.target })
        assertTrue(plan.images.all { it.sourceBackupId == second.id })
        assertTrue(plan.images.none { it.target == "efisp" })
        assertEquals("ublbackup_20261029_233101", plan.gpt?.sourceBackupId)
        assertEquals("gpt_sda.raw", plan.gpt?.sourceName)
        assertFalse(plan.images.any { it.target == "abl_a" })
        val markerPlan = RestorePlanner.plan(backups, device.copy(otaMarker = "1"))
        assertFalse(markerPlan.blockers.any { it.contains("尚待官方重启") })
        assertTrue(markerPlan.warnings.any { it.contains("freeotaok") })
    }

    @Test
    fun noOtaUsesCleanBackupRatherThanNewerModifiedBackup() {
        val files = listOf("ublbackup_20261029_233101", "ublbackup_20261030_131440")
        assumeTrue(files.all { File(root, it).isDirectory })
        val backups = files.map { BackupVerifier.verify(File(root, it)) }

        val plan = RestorePlanner.plan(backups, null)

        assertTrue(plan.images.isNotEmpty())
        assertTrue(plan.images.all { it.sourceBackupId == "ublbackup_20261029_233101" })
        assertTrue(plan.images.none { it.sourceBackupId == "ublbackup_20261030_131440" })
    }

    @Test
    fun missingOtherSlotEvidenceFallsBackButMissingCurrentPartitionsBlock() {
        val files = listOf("ublbackup_20261029_233101", "hnubl_ota_backup_20261108_084414")
        assumeTrue(files.all { File(root, it).isDirectory })
        val backups = files.map { BackupVerifier.verify(File(root, it)) }
        val modifiedGpt = GptSnapshot.parse(File(root, "ublbackup_20261030_131440/gpt_sda.raw"))
        val device = DeviceSnapshot("a", "build", "fingerprint", emptyMap(), "/dev/block/sda", modifiedGpt, 1, "0")

        val plan = RestorePlanner.plan(backups, device)

        assertEquals(RestorePlanStatus.BLOCKED, plan.status)
        assertEquals(RestoreStrategy.CURRENT_SLOT_ONLY, plan.strategy)
        assertTrue(plan.blockers.none { it.contains("abl_b") })
        assertTrue(plan.blockers.any { it.contains("abl_a") })
        assertTrue(plan.images.none { it.target == "abl_b" && it.sourceBackupId == "ublbackup_20261029_233101" })
    }

    @Test
    fun syntheticTwoOtaUsesLatestMatchingImagesWithoutHostFixtures() {
        val first = syntheticOta("first", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val second = syntheticOta("second", 2, "a", "5".repeat(64), "5".repeat(64), "6".repeat(64), "6".repeat(64))

        val plan = RestorePlanner.plan(listOf(first, second), null, historyMode = OtaHistoryMode.MULTIPLE)

        assertTrue(plan.images.isNotEmpty())
        assertTrue(plan.images.all { it.sourceBackupId == "second" })
        assertTrue(plan.blockers.none { it.contains("缺少") })
        assertEquals(RestoreStrategy.FULL, plan.strategy)
    }

    @Test
    fun fourOtaBackupsUseOnlyThirdAndFourthForDualSlotRestore() {
        val first = syntheticOta("first", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val second = syntheticOta("second", 2, "a", "5".repeat(64), "6".repeat(64), "7".repeat(64), "8".repeat(64))
        val third = syntheticOta("third", 3, "b", "9".repeat(64), "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val fourth = syntheticOta("fourth", 4, "a", BUILTIN_ABL_SHA256, "d".repeat(64), "e".repeat(64), "f".repeat(64))

        val plan = RestorePlanner.plan(listOf(first, second, third, fourth), null, historyMode = OtaHistoryMode.MULTIPLE)

        val sources = plan.images.associate { it.target to it.sourceBackupId }
        assertEquals("third", sources["abl_a"])
        assertEquals("fourth", sources["abl_b"])
        assertEquals("third", sources["init_boot_a"])
        assertEquals("fourth", sources["init_boot_b"])
        assertTrue(plan.images.none { it.sourceBackupId == "first" || it.sourceBackupId == "second" })
        assertTrue(plan.notes.any { it.contains("更早的 2 套") })
    }

    @Test
    fun normalModeUsesCurrentSlotAndRequiresLatestBackupConfirmation() {
        val first = syntheticOta("first", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val second = syntheticOta("second", 2, "a", "5".repeat(64), "5".repeat(64), "6".repeat(64), "6".repeat(64))

        val plan = RestorePlanner.plan(listOf(first, second), null)

        assertEquals(RestoreStrategy.CURRENT_SLOT_ONLY, plan.strategy)
        assertEquals(listOf("abl_b", "init_boot_b"), plan.images.map { it.target })
        assertTrue(plan.warnings.any { it.contains("不会根据 A/B 交替猜测总次数") })
    }

    @Test
    fun syntheticMissingLineageUsesCurrentSlotFallbackWithoutHostFixtures() {
        val first = syntheticOta("first", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))

        val plan = RestorePlanner.plan(listOf(first), null)

        assertEquals(RestoreStrategy.CURRENT_SLOT_ONLY, plan.strategy)
        assertEquals(listOf("abl_a", "init_boot_a"), plan.images.map { it.target })
        assertTrue(plan.blockers.none { it.contains("abl_b") })
        assertTrue(plan.warnings.any { it.contains("另一槽可能无法启动") })
        assertTrue(plan.warnings.any { it.contains("小增量包") })
    }

    @Test
    fun fallbackDoesNotLowerCurrentSlotRequirements() {
        val first = syntheticOta("first", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val incomplete = first.copy(records = first.records - "abl_a.img")

        val plan = RestorePlanner.plan(listOf(incomplete), null)

        assertEquals(RestoreStrategy.FULL, plan.strategy)
        assertTrue(plan.blockers.any { it.contains("最近 OTA 备份缺少目标槽镜像 abl_a.img") })
    }

    @Test
    fun oneOtaUsesCleanBackupForOtherSlot() {
        val normalFile = File(root, "ublbackup_20261029_233101")
        assumeTrue(normalFile.isDirectory)
        val normal = BackupVerifier.verify(normalFile)
        val only = syntheticOta("only", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))

        val plan = RestorePlanner.plan(listOf(normal, only), null, OtaHistoryMode.ONE)

        assertEquals(RestoreStrategy.FULL, plan.strategy)
        assertTrue(plan.images.any { it.target == "abl_b" && it.sourceBackupId == normal.id })
        assertTrue(plan.images.any { it.target == "init_boot_b" && it.sourceBackupId == normal.id })
    }

    @Test
    fun directoryCountMismatchIsReportedWithoutChangingHistory() {
        val only = syntheticOta("only", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val device = DeviceSnapshot("a", "build", "fingerprint", emptyMap(), "/dev/block/sda", null, 1, "0", 3)

        val plan = RestorePlanner.plan(listOf(only), device, OtaHistoryMode.ONE)

        assertTrue(plan.historyMismatch?.contains("3 套") == true)
        assertTrue(plan.warnings.any { it.contains("选择真实并对此负责") })
    }

    @Test
    fun wrongCapacityAndUnknownAblRemainBlocked() {
        val ota = syntheticOta("only", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val partitions = mutableMapOf<String, DevicePartition>()
        for (base in listOf("abl", "init_boot")) for (slot in listOf("a", "b")) {
            val target = "${base}_$slot"
            val bytes = if (target == "abl_a") 123L else if (base == "abl") 4194304L else 8388608L
            val hash = if (target == "abl_a") "9".repeat(64) else ota.records.getValue("$target.img").sha256
            partitions[target] = partition(target, bytes, hash)
        }
        val device = DeviceSnapshot("a", "build", "fingerprint", partitions, "/dev/block/sda", null, 1, "0")

        val plan = RestorePlanner.plan(listOf(ota), device, OtaHistoryMode.MULTIPLE)

        assertEquals(RestorePlanStatus.BLOCKED, plan.status)
        assertTrue(plan.blockers.any { it.contains("abl_a 恢复镜像与分区容量不一致") })
        val corrected = device.copy(partitions = partitions + ("abl_a" to partitions.getValue("abl_a").copy(
            identity = "abl_a|/dev/block/by-name/abl_a|/dev/block/test|1:1|4194304|0|512", bytes = 4194304)))
        val identityPlan = RestorePlanner.plan(listOf(ota), corrected, OtaHistoryMode.MULTIPLE)
        assertTrue(identityPlan.blockers.any { it.contains("abl_a 当前内容不属于已识别") })
    }

    @Test
    fun recognizedPatchedAblPrefixPassesIdentityCheck() {
        val ota = syntheticOta("only", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val current = partition("abl_a", 4194304, "9".repeat(64)).copy(prefixHashes = mapOf(642136L to BUILTIN_ABL_SHA256))
        val device = DeviceSnapshot("a", "build", "fingerprint", mapOf("abl_a" to current), "/dev/block/sda", null, 1, "0")

        val plan = RestorePlanner.plan(listOf(ota), device, OtaHistoryMode.MULTIPLE)

        assertTrue(plan.blockers.none { it.contains("abl_a 当前内容不属于已识别") })
    }

    @Test
    fun latestTargetMustMatchRunningSlot() {
        val ota = syntheticOta("only", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val device = DeviceSnapshot("b", "build", "fingerprint", emptyMap(), "/dev/block/sda", null, 1, "0")

        val plan = RestorePlanner.plan(listOf(ota), device, OtaHistoryMode.MULTIPLE)

        assertTrue(plan.blockers.any { it.contains("当前运行槽位为 b") && it.contains("目标槽为 a") })
    }

    @Test
    fun duplicateIdsAndTimestampsAreRejected() {
        val first = syntheticOta("first", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val sameId = first.copy()
        assertTrue(RestorePlanner.plan(listOf(first, sameId), null, OtaHistoryMode.MULTIPLE)
            .blockers.any { it.contains("备份标识重复") })
        val sameTime = syntheticOta("second", 1, "a", "5".repeat(64), "6".repeat(64), "7".repeat(64), "8".repeat(64))
        assertTrue(RestorePlanner.plan(listOf(first, sameTime), null, OtaHistoryMode.MULTIPLE)
            .blockers.any { it.contains("时间顺序不唯一") })
    }

    @Test
    fun declaredHistoryMustMatchImportedBackupKinds() {
        val first = syntheticOta("first", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val second = syntheticOta("second", 2, "a", "5".repeat(64), "6".repeat(64), "7".repeat(64), "8".repeat(64))
        assertTrue(RestorePlanner.plan(listOf(first), null, OtaHistoryMode.NONE)
            .blockers.any { it.contains("你选择了假回锁后没有 OTA") })
        assertTrue(RestorePlanner.plan(listOf(first, second), null, OtaHistoryMode.ONE)
            .blockers.any { it.contains("你选择了仅一次 OTA") })
    }

    @Test
    fun multipleWithOnlyLatestUsesExplicitCurrentSlotFallback() {
        val ota = syntheticOta("only", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val device = DeviceSnapshot("a", "build", "fingerprint", emptyMap(), "/dev/block/sda", null, 1, "0")

        val plan = RestorePlanner.plan(listOf(ota), device, OtaHistoryMode.MULTIPLE)

        assertEquals(RestoreStrategy.CURRENT_SLOT_ONLY, plan.strategy)
        assertTrue(plan.warnings.any { it.contains("缺少倒数第二次备份") })
        assertTrue(plan.warnings.any { it.contains("小增量包") })
        assertTrue(plan.images.none { it.target.endsWith("_b") })
    }

    @Test
    fun incompleteRestoreMarkerBlocksAnotherExecution() {
        val ota = syntheticOta("only", 1, "b", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val device = DeviceSnapshot("a", "build", "fingerprint", emptyMap(), "/dev/block/sda", null, 1, "0",
            incompleteRestore = true)

        val plan = RestorePlanner.plan(listOf(ota), device, OtaHistoryMode.MULTIPLE)

        assertEquals(RestorePlanStatus.BLOCKED, plan.status)
        assertTrue(plan.blockers.any { it.contains("中断标记") })
    }

    private fun partition(target: String, bytes: Long, hash: String): DevicePartition = DevicePartition(
        target, "$target|/dev/block/by-name/$target|/dev/block/test|1:1|$bytes|0|512", bytes, hash)

    private fun syntheticOta(
        id: String, timestamp: Long, currentSlot: String, ablA: String, ablB: String, initA: String, initB: String,
    ): OtaBackup {
        val records = linkedMapOf(
            "abl_a.img" to ImageRecord("abl_a.img", 4194304, ablA),
            "abl_b.img" to ImageRecord("abl_b.img", 4194304, ablB),
            "init_boot_a.img" to ImageRecord("init_boot_a.img", 8388608, initA),
            "init_boot_b.img" to ImageRecord("init_boot_b.img", 8388608, initB),
        )
        val targetSlot = if (currentSlot == "a") "b" else "a"
        return OtaBackup(id, File(id), id, timestamp, records, currentSlot,
            listOf(OtaWrite("abl_$targetSlot", "builtin-abl.img", 642136, BUILTIN_ABL_SHA256),
                OtaWrite("efisp", "generated-efisp.img", 1, "f".repeat(64))), emptyMap())
    }
}
