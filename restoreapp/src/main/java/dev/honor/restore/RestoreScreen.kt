package dev.honor.restore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.RestoreGuidance
import dev.honor.restore.core.RestorePlanStatus
import dev.honor.restore.core.RestoreStrategy
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class RestoreActions(
    val onRequestBackup: (BackupRequest) -> Unit,
    val onChooseHistory: () -> Unit,
    val onPreviousChoice: () -> Unit,
    val onChooseOtaType: (OtaBackup) -> Unit,
    val onShowLogs: () -> Unit,
    val onExportLogs: () -> Unit,
    val onScan: () -> Unit,
    val onAcknowledgeIncomplete: () -> Unit,
    val onRemove: (BackupSet) -> Unit,
    val onExecute: () -> Unit,
)

@Composable
fun RestoreScreen(
    state: RestoreUiState,
    actions: RestoreActions,
    showConfirmation: Boolean,
    onDismissConfirmation: () -> Unit,
    onConfirmExecution: () -> Unit,
) {
    val flowComplete = RestoreFlow.isComplete(state.backups, state.historyMode, state.previousOtaChoice)
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text("hnubl 恢复助手", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("按顺序选择备份，撤销假回锁和 Root 修改，保留当前系统版本、应用与个人数据。",
                        fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item {
                Panel("1 · 确认假回锁后的实际 OTA 次数") {
                    Text("已选择：${historyModeText(state.historyMode)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("次数由你确认；App 不会仅根据 A/B 槽交替猜测总次数。", fontSize = 12.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = actions.onChooseHistory, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("重新选择次数并重新开始导入")
                    }
                }
            }
            item {
                Panel("2 · 按顺序导入备份") {
                    ImportGuidance(state.backups, state.historyMode, state.previousOtaChoice)
                    Spacer(Modifier.height(14.dp))
                    RestoreFlow.nextRequest(state.backups, state.historyMode, state.previousOtaChoice)?.let { request ->
                        Button(onClick = { actions.onRequestBackup(request) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(backupRequestText(request, RestoreFlow.nextOtaOrdinal(state.backups)))
                        }
                    }
                    RestoreFlow.untypedOta(state.backups)?.let { backup ->
                        Button(onClick = { actions.onChooseOtaType(backup) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text("确认本次 OTA 是大版本还是小补丁")
                        }
                    }
                    if (RestoreFlow.needsPreviousChoice(state.backups, state.historyMode, state.previousOtaChoice) ||
                        RestoreFlow.canStopAddingHistory(state.backups, state.previousOtaChoice)) {
                        Button(onClick = actions.onPreviousChoice, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(if (RestoreFlow.needsPreviousChoice(state.backups, state.historyMode, state.previousOtaChoice))
                                "确认是否有倒数第二次保锁 OTA 备份" else "没有更早的大版本备份？检查可选方案")
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) item { MessageCard("当前步骤未完成", listOf(state.error), error = true) }
            state.backups.forEach { backup ->
                item(key = backup.id) { BackupCard(backup, state.backups, !state.busy) { actions.onRemove(backup) } }
            }
            item {
                Panel("3 · 只读设备诊断") {
                    Button(onClick = actions.onScan, enabled = !state.busy && flowComplete, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(when {
                            !flowComplete -> "完成分步导入后才能检查设备"
                            state.device == null -> "读取设备与分区信息"
                            else -> "重新读取并核对"
                        })
                    }
                    state.device?.let { device ->
                        Spacer(Modifier.height(12.dp))
                        KeyValue("当前槽位", device.runningSlot.uppercase())
                        KeyValue("系统版本", device.displayId.ifBlank { "未读取" })
                        KeyValue("GPT 磁盘", device.gptDisk)
                        KeyValue("F2FS", "${device.f2fsBytes} 字节")
                        KeyValue("OTA 备份目录", device.otaDirectoryCount?.let { "$it 套（仅辅助核对）" } ?: "未读取")
                        KeyValue("Fastboot 锁态", "未自动检测，不据系统属性推断")
                        if (device.incompleteRestore) {
                            Spacer(Modifier.height(12.dp))
                            Text("检测到上次恢复中断或结果未复核。清除标记前必须人工确认启动和分区状态。",
                                fontSize = 14.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = actions.onAcknowledgeIncomplete, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text("人工复核中断状态")
                            }
                        }
                    }
                }
            }
            state.plan?.let { plan ->
                item {
                    Panel("4 · 恢复计划") {
                        Text(when {
                            plan.status != RestorePlanStatus.READY -> "现在还不能恢复"
                            plan.strategy == RestoreStrategy.CURRENT_SLOT_ONLY -> "仅恢复当前槽（高风险降级方案）"
                            plan.warnings.isNotEmpty() -> "候选计划已生成，必须确认风险"
                            else -> "可以进入恢复确认"
                        }, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = if (plan.status != RestorePlanStatus.READY || plan.warnings.isNotEmpty()) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
                        if (plan.images.isEmpty() && plan.gpt == null) {
                            Spacer(Modifier.height(8.dp))
                            Text("完成备份导入和只读检查后，这里会列出准备恢复的内容。", fontSize = 12.sp, lineHeight = 20.sp)
                        }
                        plan.images.forEach { operation ->
                            Spacer(Modifier.height(12.dp))
                            Text("${operation.target} ← ${operation.sourceName}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("来源备份：${state.backups.firstOrNull { it.id == operation.sourceBackupId }?.displayName ?: operation.sourceBackupId}",
                                fontSize = 12.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.primary)
                            Text(operationReasonText(operation.reason), fontSize = 12.sp, lineHeight = 19.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(operation.sourceSha256.take(16) + "…", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.primary)
                        }
                        plan.gpt?.let { gpt ->
                            Spacer(Modifier.height(12.dp))
                            Text("GPT ← ${gpt.sourceName}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(gpt.reason, fontSize = 12.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                if (plan.strategy == RestoreStrategy.CURRENT_SLOT_ONLY && plan.warnings.isNotEmpty()) item {
                    MessageCard("仅恢复当前槽的重要风险", plan.warnings.distinct(), error = true)
                }
                if (plan.strategy != RestoreStrategy.CURRENT_SLOT_ONLY && plan.warnings.isNotEmpty()) item {
                    MessageCard("必须由你确认并承担的风险", plan.warnings.map(RestoreGuidance::forWarning).distinct(), error = true)
                }
                plan.historyMismatch?.let { mismatch ->
                    item { MessageCard("OTA 次数与目录不一致", listOf(mismatch), error = true) }
                }
                if (plan.blockers.isNotEmpty()) item {
                    MessageCard("接下来怎么做", plan.blockers.map(RestoreGuidance::forBlocker).distinct(), error = true)
                }
                item { MessageCard("恢复边界", plan.notes, error = false) }
            }
            item {
                Panel("5 · 执行与重启") {
                    Text("执行前会重新扫描并导出当前状态 ZIP。先恢复启动镜像并回读，最后恢复 GPT；不会自动重启、切槽、清数据或官方回锁。",
                        fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(8.dp))
                    Text("写入过程中断电、强制重启或强制关闭 App 可能造成设备无法启动。请保持电量和连接稳定，不要中断。",
                        fontSize = 14.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onExecute,
                        enabled = !state.busy && flowComplete && RestoreRuntime.canReview(state) &&
                            state.plan?.status == RestorePlanStatus.READY && BuildConfig.ALLOW_PARTITION_WRITES &&
                            (state.plan.images.isNotEmpty() || state.plan.gpt != null),
                        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(if (BuildConfig.ALLOW_PARTITION_WRITES) "审查确认并选择恢复前备份位置" else "Debug 构建仅支持只读诊断")
                    }
                    if (state.completed) {
                        Spacer(Modifier.height(10.dp))
                        Text("分区恢复已完成。请确认系统可启动后再手动重启；Fastboot 若仍显示 PHONE Unlocked，不要执行官方回锁。",
                            fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Panel("任务日志") {
                    Text(state.stage, fontSize = 13.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.onShowLogs, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("查看完整日志") }
                        Button(onClick = actions.onExportLogs, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("导出诊断日志") }
                    }
                    if (state.backupSha256.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("恢复前备份 SHA-256\n${state.backupSha256}", fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (state.logs.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer {
                            Text(state.logs.takeLast(12).joinToString("\n"), fontSize = 11.sp, lineHeight = 18.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
    }
    if (showConfirmation) ConfirmationDialog(state, onDismissConfirmation, onConfirmExecution)
}

@Composable
private fun BackupCard(backup: BackupSet, backups: List<BackupSet>, enabled: Boolean, onRemove: () -> Unit) {
    val orderedOta = backups.filterIsInstance<OtaBackup>().sortedByDescending { it.timestamp }
    val ordinal = orderedOta.indexOfFirst { it.id == backup.id } + 1
    Panel(when (backup) {
        is NormalBackup -> "干净备份 · ${backup.displayName}"
        is OtaBackup -> "${if (ordinal == 1) "最后一次" else "倒数第 $ordinal 次"}保锁 OTA 备份 · ${backup.displayName}"
    }) {
        when (backup) {
            is NormalBackup -> Column {
                Text(normalBackupRoleText(backup), fontSize = 13.sp, lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
                Text("备份时运行槽位 ${backup.runningSlot.uppercase()} · ${backup.records.size} 个已验证文件",
                    fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            is OtaBackup -> Column {
                Text("这次升级在 ${backup.currentSlot.uppercase()} → ${backup.targetSlot.uppercase()} 槽之间进行。",
                    fontSize = 13.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary)
                Text(otaPackageTypeText(backup.packageType), fontSize = 14.sp, lineHeight = 22.sp,
                    fontWeight = if (backup.packageType == dev.honor.restore.core.OtaPackageType.MAJOR) FontWeight.SemiBold else FontWeight.Bold,
                    color = if (backup.packageType == dev.honor.restore.core.OtaPackageType.MAJOR)
                        MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error)
                Text("hnubl 实际写入：${backup.writes.joinToString { it.target }}", fontSize = 12.sp, lineHeight = 20.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRemove, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("移除这份 App 内副本（不影响原文件）")
        }
    }
}

@Composable
private fun ConfirmationDialog(state: RestoreUiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var imagesConfirmed by remember { mutableStateOf(false) }
    var updateConfirmed by remember { mutableStateOf(false) }
    var riskConfirmed by remember { mutableStateOf(false) }
    var fallbackConfirmed by remember { mutableStateOf(false) }
    var warningsConfirmed by remember { mutableStateOf(false) }
    var historyMismatchConfirmed by remember { mutableStateOf(false) }
    val plan = state.plan
    val currentSlotOnly = plan?.strategy == RestoreStrategy.CURRENT_SLOT_ONLY
    val warnings = plan?.warnings?.map(RestoreGuidance::forWarning)?.distinct().orEmpty()
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (currentSlotOnly) "确认仅恢复当前槽" else if (warnings.any { it.contains("小补丁") || it.contains("跨槽") })
                    "确认风险承担恢复" else "确认恢复计划", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("将写入 ${plan?.images?.size ?: 0} 个分区镜像${if (plan?.gpt != null) "，并恢复 GPT 删除 efisp" else ""}。执行前仍会重新扫描，任何差异都会停止。",
                    fontSize = 13.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text("写入过程中断电、强制重启或强制关闭 App 可能造成分区部分写入，导致设备无法启动。请保证电量和连接稳定，不要中断操作。",
                    fontSize = 14.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                if (currentSlotOnly) {
                    Spacer(Modifier.height(8.dp))
                    Text("另一槽不会被恢复。删除共享 efisp 后，它可能无法启动，后续必须使用包含 ABL/init_boot 的官方全量包或先单独修复。",
                        fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.error)
                }
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("本次必须核对的风险与警告", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.error)
                    warnings.forEach {
                        Spacer(Modifier.height(6.dp))
                        Text("· $it", fontSize = 14.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    ConfirmLine("我已逐项阅读并核对以上警告，确认恢复来源适用于当前系统", warningsConfirmed) { warningsConfirmed = it }
                }
                Spacer(Modifier.height(14.dp))
                ConfirmLine("我已核对恢复来源对应当前系统，并另行备份个人数据", imagesConfirmed) { imagesConfirmed = it }
                ConfirmLine("我确认当前没有已安装完成、等待重启的系统更新", updateConfirmed) { updateConfirmed = it }
                ConfirmLine("我理解 Fastboot unlocked 可能保留，不会尝试官方回锁或写 OEMINFO", riskConfirmed) { riskConfirmed = it }
                if (currentSlotOnly) {
                    ConfirmLine("我接受另一槽可能暂时无法启动，不会把下一次小增量 OTA 当作修复手段", fallbackConfirmed) { fallbackConfirmed = it }
                }
                plan?.historyMismatch?.let {
                    ConfirmLine("我再次确认 OTA 次数选择真实，并对目录差异负责", historyMismatchConfirmed) { historyMismatchConfirmed = it }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onConfirm, enabled = imagesConfirmed && updateConfirmed && riskConfirmed &&
                    (!currentSlotOnly || fallbackConfirmed) && (warnings.isEmpty() || warningsConfirmed) &&
                    (plan?.historyMismatch == null || historyMismatchConfirmed),
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("选择恢复前备份位置并开始") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("返回检查") }
            }
        }
    }
}

@Composable
private fun ConfirmLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.height(8.dp))
        Text(text, modifier = Modifier.padding(start = 10.dp), fontSize = 14.sp, lineHeight = 22.sp,
            color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun MessageCard(title: String, messages: List<String>, error: Boolean) {
    if (messages.isEmpty()) return
    Panel(title) {
        messages.forEach {
            Text("· $it", fontSize = if (error) 14.sp else 12.sp, lineHeight = if (error) 22.sp else 20.sp,
                color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = if (error) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, modifier = Modifier.padding(start = 12.dp), fontSize = 12.sp, lineHeight = 19.sp)
    }
}
