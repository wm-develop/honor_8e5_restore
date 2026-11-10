package dev.honor.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.OtaHistoryMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ImportGuidance(backups: List<BackupSet>, mode: OtaHistoryMode, previous: PreviousOtaChoice) {
    val cleanReady = RestoreFlow.hasClean(backups)
    val ota = backups.filterIsInstance<OtaBackup>().sortedBy { it.timestamp }
    Column {
        Text("每次只导入当前步骤要求的一个完整文件夹。系统文件选择器打开后，选中文件夹并确认，不要只选 img 文件。",
            fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(14.dp))
        RequirementLine("1", "假回锁前的干净备份", "通常是 ublbackup_日期_时间；分区表中不应包含 efisp。",
            cleanReady, "干净备份已导入并通过文件与分区表校验")
        if (mode != OtaHistoryMode.NONE) {
            Spacer(Modifier.height(12.dp))
            RequirementLine("2", "实际最后一次保锁 OTA 的备份", "先完成第 1 步，再导入 hnubl_ota_backup_日期_时间。由你确认它确实是最后一次。",
                cleanReady && ota.isNotEmpty(), if (ota.isEmpty()) "" else "已导入：${ota.last().displayName}")
        } else {
            Spacer(Modifier.height(12.dp))
            Text("已选择 0 次：不需要、也不能导入保锁 OTA 备份。", fontSize = 12.sp, lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.primary)
        }
        if (mode == OtaHistoryMode.MULTIPLE && ota.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            when (previous) {
                PreviousOtaChoice.UNASKED -> RequirementLine("3", "确认是否有倒数第二次保锁 OTA 的备份",
                    "有就继续导入；没有则只能恢复当前槽，必须先阅读并确认风险。", false, "")
                PreviousOtaChoice.AVAILABLE -> RequirementLine("3", "倒数第二次保锁 OTA 的备份",
                    "只能导入紧接最后一次之前的那次，App 会检查时间和槽位是否连续。", ota.size == 2,
                    if (ota.size == 2) "已导入：${ota.first().displayName}" else "")
                PreviousOtaChoice.MISSING -> Text("已确认没有倒数第二次备份：仅恢复当前槽。另一槽可能无法启动，后续小增量 OTA 不能直接作为修复手段。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.error)
            }
        }
        if (RestoreFlow.isComplete(backups, mode, previous)) {
            Spacer(Modifier.height(14.dp))
            Text("备份导入流程已完成，可以进行只读设备检查。", fontSize = 13.sp, lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RequirementLine(number: String, title: String, detail: String, complete: Boolean, completeText: String) {
    Column {
        Text("$number · $title${if (complete) "（已完成）" else ""}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (complete) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(detail, fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (complete && completeText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(completeText, fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

fun historyModeText(mode: OtaHistoryMode): String = when (mode) {
    OtaHistoryMode.UNKNOWN -> "尚未选择"
    OtaHistoryMode.NONE -> "0 次"
    OtaHistoryMode.ONE -> "1 次"
    OtaHistoryMode.MULTIPLE -> "多次"
}

fun backupRequestText(request: BackupRequest): String = when (request) {
    BackupRequest.CLEAN -> "导入假回锁前的干净备份"
    BackupRequest.LATEST_OTA -> "导入实际最后一次保锁 OTA 备份"
    BackupRequest.PREVIOUS_OTA -> "导入倒数第二次保锁 OTA 备份"
}

@Composable
fun normalBackupRoleText(backup: NormalBackup): String = remember(backup.id) {
    if (RestoreFlow.isClean(backup)) "本流程的假回锁前干净备份" else "不是可用于恢复的干净备份"
}

fun operationReasonText(reason: String): String = when {
    reason.contains("与最近 OTA 目标槽") -> "仅因同一备份内两个槽位文件的 SHA-256 相同而跨槽复用；未与当前官方系统独立验证"
    reason.contains("最近 OTA 目标槽在保锁 ABL 覆盖前") -> "取自所选 OTA 备份在保锁写入前的目标槽文件；按流程推断为候选原厂镜像，未独立验证官方签名"
    reason.contains("无保锁 OTA 记录") -> "来自假回锁前的干净备份"
    reason.contains("前一次作为 OTA 目标") || reason.contains("前一次保锁写入") -> "取自前一次保锁 OTA 备份的对应槽文件；是否适用于当前槽仍需你确认"
    reason.contains("没有更早 OTA 覆盖记录") -> "这份镜像已与普通备份和 OTA 记录交叉核对"
    reason.contains("前级引导分区对拷") -> "撤销这次保锁 OTA 对前级引导分区的修改"
    else -> reason
}
