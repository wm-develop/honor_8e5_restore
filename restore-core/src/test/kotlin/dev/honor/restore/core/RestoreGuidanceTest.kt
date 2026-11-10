package dev.honor.restore.core

import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuidanceTest {
    @Test
    fun emptyStateExplainsBothFolderTypesAndWholeFolderSelection() {
        val text = RestoreGuidance.forBlocker("尚未导入备份")
        assertTrue(text.contains("ublbackup_"))
        assertTrue(text.contains("hnubl_ota_backup_"))
        assertTrue(text.contains("整个文件夹"))
        assertTrue(text.contains("不要只选里面的 img 文件"))
    }

    @Test
    fun missingDevicePointsToReadOnlyScan() {
        val text = RestoreGuidance.forBlocker("尚未读取真实设备状态；当前结果只能用于离线审查")
        assertTrue(text.contains("读取设备与分区信息"))
        assertTrue(text.contains("不会写入分区"))
    }

    @Test
    fun missingVersionEvidenceNamesNextAction() {
        val text = RestoreGuidance.forBlocker("abl_b 不能仅由最近 OTA 的当前槽备份证明为原厂镜像（latest）；请补充早期 OTA 或当前版本官方镜像")
        assertTrue(text.contains("最近那次"))
        assertTrue(text.contains("仅恢复当前槽"))
    }

    @Test
    fun gptGuidanceAllowsExpectedFakeLockChangesAndShowsActualDifference() {
        val text = RestoreGuidance.forBlocker("GPT 第 66 项存在恢复范围以外的差异")
        assertTrue(text.contains("差异是正常的"))
        assertTrue(text.contains("具体差异：GPT 第 66 项"))
    }

    @Test
    fun incompleteRestoreGuidanceDoesNotClaimRepairSucceeded() {
        val text = RestoreGuidance.forBlocker("上次恢复中断或结果未复核，必须先完成人工核验并清除中断标记")
        assertTrue(text.contains("确认当前系统可启动"))
        assertTrue(text.contains("不会修复分区"))
        assertTrue(text.contains("不代表恢复已经成功"))
    }

    @Test
    fun rootPatchedInitBootWarningIsUnderstandable() {
        val text = RestoreGuidance.forWarning("init_boot_b 当前哈希不在备份中；若是 OTA 后重新修补的 KernelSU/SukiSU 镜像，这是预期情况，请人工核对")
        assertTrue(text.contains("很常见"))
        assertTrue(text.contains("官方 init_boot"))
    }
}
