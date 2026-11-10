package dev.honor.restore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RestorePlannerTest {
    private val root = File("..").canonicalFile
    private val normalName = "ublbackup_20261029_233101"
    private val majorName = "hnubl_ota_backup_20261108_084414"
    private val patchName = "hnubl_ota_backup_20261108_093831"

    @Test
    fun actualMajorThenPatchUsesVerifiedMajorImageForBothSlots() {
        val (backups, device) = actualHistory()
        val major = backups.filterIsInstance<OtaBackup>().first { it.packageType == OtaPackageType.MAJOR }

        val plan = RestorePlanner.plan(backups, device, OtaHistoryMode.MULTIPLE)

        assertEquals(plan.blockers.joinToString("\n"), RestorePlanStatus.READY, plan.status)
        assertEquals(listOf("abl_a", "abl_b", "init_boot_a", "init_boot_b"), plan.images.map { it.target })
        assertTrue(plan.images.all { it.sourceBackupId == major.id })
        assertTrue(plan.notes.any { it.contains("stock_image.sha1") })
        assertTrue(plan.warnings.any { it.contains("所有小补丁均未更新") })
        assertTrue(plan.images.none { it.target == "efisp" })
        assertEquals(normalName, plan.gpt?.sourceBackupId)
        assertTrue(plan.blockers.none { it.contains("未通过 AVB") })
    }

    @Test
    fun noOtaUsesCleanPerSlotImages() {
        val normal = BackupVerifier.verify(File(root, normalName))
        val plan = RestorePlanner.plan(listOf(normal), null, OtaHistoryMode.NONE)
        assertEquals(4, plan.images.size)
        assertTrue(plan.images.all { it.sourceBackupId == normal.id })
        assertTrue(plan.images.all { it.sourceName.endsWith("${it.target}.img") })
    }

    @Test
    fun onePatchUsesOriginalBackupButDoesNotWriteRootedPatchImages() {
        val (backups, device) = actualHistory()
        val selected = backups.filter { it is NormalBackup || (it as? OtaBackup)?.packageType == OtaPackageType.PATCH }

        val plan = RestorePlanner.plan(selected, device, OtaHistoryMode.ONE)

        assertEquals(plan.blockers.joinToString("\n"), RestorePlanStatus.READY, plan.status)
        assertEquals(4, plan.images.size)
        assertTrue(plan.images.all { it.sourceBackupId == normalName })
        assertTrue(plan.warnings.any { it.contains("无法独立核实补丁内容") })
    }

    @Test
    fun oneMajorUsesNewTargetAndOriginalOtherSlot() {
        val (backups, device) = actualHistory()
        val selected = backups.filter { it is NormalBackup || (it as? OtaBackup)?.packageType == OtaPackageType.MAJOR }
        val major = selected.filterIsInstance<OtaBackup>().single()
        val write = major.write("abl_a")!!
        val currentAbl = device.partitions.getValue("abl_a").copy(sha256 = write.sha256, prefixHashes = mapOf(write.bytes to write.sha256))
        val currentDevice = device.copy(runningSlot = "a", partitions = device.partitions + ("abl_a" to currentAbl))

        val plan = RestorePlanner.plan(selected, currentDevice, OtaHistoryMode.ONE)

        assertEquals(plan.blockers.joinToString("\n"), RestorePlanStatus.READY, plan.status)
        assertTrue(plan.images.any { it.target == "init_boot_a" && it.sourceBackupId == majorName })
        assertTrue(plan.images.any { it.target == "init_boot_b" && it.sourceBackupId == normalName })
        assertTrue(plan.images.none { it.sourceName == "init_boot_b.img" && it.sourceBackupId == majorName })
    }

    @Test
    fun alreadyCleanTargetDoesNotRequireRootEvidenceAgain() {
        val (backups, device) = actualHistory()
        val major = backups.filterIsInstance<OtaBackup>().first { it.packageType == OtaPackageType.MAJOR }
        val clean = major.records.getValue("init_boot_a.img")
        val restored = device.partitions.getValue("init_boot_b").copy(sha256 = clean.sha256, stockImageSha1 = null)
        val current = device.copy(partitions = device.partitions + ("init_boot_b" to restored))

        val plan = RestorePlanner.plan(backups, current, OtaHistoryMode.MULTIPLE)

        assertEquals(plan.blockers.joinToString("\n"), RestorePlanStatus.READY, plan.status)
        assertTrue(plan.images.none { it.target == "init_boot_b" })
    }

    @Test
    fun completedRestoreLeavesNoOperations() {
        val (backups, device) = actualHistory()
        val major = backups.filterIsInstance<OtaBackup>().first { it.packageType == OtaPackageType.MAJOR }
        val restored = device.partitions.mapValues { (target, partition) ->
            val source = if (target.startsWith("abl_")) "abl_a.img" else "init_boot_a.img"
            partition.copy(sha256 = major.records.getValue(source).sha256)
        }
        val cleanGpt = GptSnapshot.parse(File(root, "$normalName/gpt_sda.raw"))
        val current = device.copy(partitions = restored, gpt = cleanGpt)

        val plan = RestorePlanner.plan(backups, current, OtaHistoryMode.MULTIPLE)

        assertEquals(plan.blockers.joinToString("\n"), RestorePlanStatus.READY, plan.status)
        assertTrue(plan.images.isEmpty())
        assertTrue(plan.gpt == null)
    }

    @Test
    fun missingCrossSlotEvidenceBlocksReuse() {
        val (backups, device) = actualHistory()
        val patch = backups.filterIsInstance<OtaBackup>().last().copy(records = backups.filterIsInstance<OtaBackup>().last().records - "init_boot_b.img")
        val selected = backups.filter { it !is OtaBackup || it.packageType == OtaPackageType.MAJOR } + patch
        val current = device.copy(partitions = device.partitions + ("init_boot_b" to device.partitions.getValue("init_boot_b").copy(stockImageSha1 = null)))

        val plan = RestorePlanner.plan(selected, current, OtaHistoryMode.MULTIPLE)

        assertTrue(plan.blockers.any { it.contains("init_boot_b 没有证据证明") })
        assertTrue(plan.images.none { it.target == "init_boot_b" })
    }

    @Test
    fun twoMajorBackupsUseTheirOwnTargetSlots() {
        val first = syntheticOta("first", 1, "b")
        val second = syntheticOta("second", 2, "a")

        val plan = RestorePlanner.plan(listOf(first, second), null, OtaHistoryMode.MULTIPLE)

        assertEquals("first", plan.images.single { it.target == "init_boot_a" }.sourceBackupId)
        assertEquals("second", plan.images.single { it.target == "init_boot_b" }.sourceBackupId)
        assertTrue(plan.blockers.none { it.contains("跨槽") || it.contains("AVB") })
    }

    @Test
    fun wrongTargetSlotChainKeyIsBlocked() {
        val (backups, device) = actualHistory()
        val valid = device.initBootChainKeys.getValue("a")
        val changed = device.copy(initBootChainKeys = mapOf("a" to valid, "b" to "0".repeat(64)))

        val plan = RestorePlanner.plan(backups, changed, OtaHistoryMode.MULTIPLE)

        assertTrue(plan.blockers.any { it.startsWith("init_boot_b") && it.contains("内嵌公钥与设备") })
        assertEquals(RestorePlanStatus.BLOCKED, plan.status)
    }

    @Test
    fun consecutivePatchesContinueToEarlierMajor() {
        val major = BackupVerifier.verify(File(root, majorName)) as OtaBackup
        val middle = (BackupVerifier.verify(File(root, patchName)) as OtaBackup).copy(packageType = OtaPackageType.PATCH)
        val latest = middle.copy(id = "latest-patch", timestamp = middle.timestamp + 1, currentSlot = "b")
        val normal = BackupVerifier.verify(File(root, normalName))
        val backups = listOf(normal, major.copy(packageType = OtaPackageType.MAJOR), middle, latest)

        val plan = RestorePlanner.plan(backups, null, OtaHistoryMode.MULTIPLE)

        assertEquals(4, plan.images.size)
        assertTrue(plan.images.all { it.sourceBackupId == major.id })
        assertTrue(plan.blockers.none { it.contains("尚未导入此前的大版本") })
        assertTrue(plan.warnings.any { it.contains("所有小补丁均未更新") })
    }

    @Test
    fun patchesWithoutMajorDoNotInventCleanSource() {
        val patch = syntheticOta("patch", 1, "b", OtaPackageType.PATCH)
        val plan = RestorePlanner.plan(listOf(patch), null, OtaHistoryMode.MULTIPLE)
        assertTrue(plan.images.isEmpty())
        assertTrue(plan.blockers.any { it.contains("尚未导入此前的大版本") })
    }

    @Test
    fun latestRootedImageCannotBeSelectedEvenWhenDeclaredMajor() {
        val patch = (BackupVerifier.verify(File(root, patchName)) as OtaBackup).copy(packageType = OtaPackageType.MAJOR)
        val plan = RestorePlanner.plan(listOf(patch), null, OtaHistoryMode.MULTIPLE)
        assertTrue(plan.blockers.any { it.contains("未通过 AVB") })
        assertTrue(plan.images.none { it.target.startsWith("init_boot_") })
    }

    @Test
    fun onlyLatestMajorOffersCurrentSlotFallback() {
        val major = syntheticOta("major", 1, "b")
        val plan = RestorePlanner.plan(listOf(major), null, OtaHistoryMode.MULTIPLE)
        assertEquals(RestoreStrategy.CURRENT_SLOT_ONLY, plan.strategy)
        assertTrue(plan.images.all { it.target.endsWith("_a") })
        assertTrue(plan.warnings.any { it.contains("另一槽可能无法启动") })
    }

    @Test
    fun unknownCurrentAblAndWrongCapacityRemainBlocked() {
        val (_, device) = actualHistory()
        val major = syntheticOta("major", 1, "b")
        val wrongCapacity = major.copy(records = major.records + ("abl_a.img" to major.records.getValue("abl_a.img").copy(bytes = 123)))
        val plan = RestorePlanner.plan(listOf(wrongCapacity), device.copy(runningSlot = "a"), OtaHistoryMode.MULTIPLE)
        assertTrue(plan.blockers.any { it.contains("abl_a 恢复镜像与分区容量不一致") })

        val changed = device.copy(runningSlot = "a", partitions = device.partitions + ("abl_a" to device.partitions.getValue("abl_a").copy(sha256 = "9".repeat(64))))
        val blocked = RestorePlanner.plan(listOf(major), changed, OtaHistoryMode.MULTIPLE)
        assertTrue(blocked.blockers.any { it.contains("abl_a 当前内容不属于已识别") })
    }

    @Test
    fun slotMismatchDuplicateTimesAndIncompleteStateAreRejected() {
        val (backups, device) = actualHistory()
        assertTrue(RestorePlanner.plan(backups, device.copy(runningSlot = "a"), OtaHistoryMode.MULTIPLE)
            .blockers.any { it.contains("当前运行槽位为 a") })
        assertTrue(RestorePlanner.plan(backups, device.copy(incompleteRestore = true), OtaHistoryMode.MULTIPLE)
            .blockers.any { it.contains("中断标记") })
        val first = syntheticOta("same", 1, "b")
        val second = syntheticOta("other", 1, "a")
        assertTrue(RestorePlanner.plan(listOf(first, second), null, OtaHistoryMode.MULTIPLE)
            .blockers.any { it.contains("时间顺序不唯一") })
        assertTrue(RestorePlanner.plan(listOf(first, first), null, OtaHistoryMode.MULTIPLE)
            .blockers.any { it.contains("备份标识重复") })
    }

    @Test
    fun directoryMismatchNeedsExplicitReview() {
        val (backups, device) = actualHistory()
        val selected = backups.filter { it is NormalBackup || (it as? OtaBackup)?.packageType == OtaPackageType.PATCH }
        val plan = RestorePlanner.plan(selected, device.copy(otaDirectoryCount = 3), OtaHistoryMode.ONE)
        assertTrue(plan.historyMismatch?.contains("3 套") == true)
        assertTrue(plan.warnings.any { it.contains("选择真实并对此负责") })
    }

    private fun actualHistory(): Pair<List<BackupSet>, DeviceSnapshot> {
        val files = listOf(normalName, majorName, patchName)
        assumeTrue(files.all { File(root, it).isDirectory })
        val normal = BackupVerifier.verify(File(root, normalName))
        val major = (BackupVerifier.verify(File(root, majorName)) as OtaBackup).copy(packageType = OtaPackageType.MAJOR)
        val patch = (BackupVerifier.verify(File(root, patchName)) as OtaBackup).copy(packageType = OtaPackageType.PATCH)
        val partitions = mutableMapOf<String, DevicePartition>()
        for (base in listOf("abl", "init_boot")) for (slot in listOf("a", "b")) {
            val target = "${base}_$slot"
            val record = patch.records.getValue("$target.img")
            val stockSha1 = if (base == "init_boot") BootImageVerifier.inspectBoot(patch.file(record.name)).stockImageSha1 else null
            partitions[target] = DevicePartition(target,
                "$target|/dev/block/by-name/$target|/dev/block/test|259:1|${record.bytes}|0|4096",
                record.bytes, record.sha256, stockImageSha1 = stockSha1)
        }
        val publicKey = BootImageVerifier.inspectBoot(major.file("init_boot_a.img")).publicKeySha256
        val device = DeviceSnapshot("b", "current-build", "current-fingerprint", partitions,
            "/dev/block/sda", GptSnapshot.parse(File(root, "ublbackup_20261030_131440/gpt_sda.raw")),
            490811162624, "0", 2, listOf(majorName, patchName),
            initBootChainKeys = mapOf("a" to publicKey, "b" to publicKey))
        return listOf(normal, major, patch) to device
    }

    private fun syntheticOta(id: String, timestamp: Long, currentSlot: String, type: OtaPackageType = OtaPackageType.MAJOR): OtaBackup {
        val directory = File(root, normalName)
        assumeTrue(directory.isDirectory)
        val records = BackupVerifier.verify(directory).records.filterKeys { name ->
            name in setOf("abl_a.img", "abl_b.img", "init_boot_a.img", "init_boot_b.img")
        }
        val target = if (currentSlot == "a") "b" else "a"
        return OtaBackup(id, directory, id, timestamp, records, currentSlot,
            listOf(OtaWrite("abl_$target", "builtin-abl.img", 642136, BUILTIN_ABL_SHA256),
                OtaWrite("efisp", "generated-efisp.img", 1, "f".repeat(64))), emptyMap(), type)
    }
}
