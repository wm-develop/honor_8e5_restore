package dev.honor.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.honor.restore.core.OtaHistoryMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun FlowDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
fun HistorySelectionDialog(required: Boolean, importedCount: Int, onSelect: (OtaHistoryMode) -> Unit, onDismiss: () -> Unit) {
    FlowDialog("假回锁后实际做过几次保锁 OTA？", if (required) ({}) else onDismiss) {
        Text("请按真实情况选择。次数决定了需要哪些备份；选错可能导致恢复镜像与当前系统不匹配。",
            fontSize = 13.sp, lineHeight = 21.sp)
        if (importedCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text("选择后会重新开始分步导入，并删除 App 内已有的 $importedCount 套导入副本；不会删除或修改原备份文件。",
                fontSize = 15.sp, lineHeight = 23.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        listOf(
            OtaHistoryMode.NONE to "0 次 · 只导入干净备份",
            OtaHistoryMode.ONE to "1 次 · 再导入最后一次 OTA 备份",
            OtaHistoryMode.MULTIPLE to "多次 · 继续确认倒数第二次备份",
        ).forEach { (mode, label) ->
            Button(onClick = { onSelect(mode) }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text(label) }
            Spacer(Modifier.height(8.dp))
        }
        if (!required) {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消，保留当前流程") }
        }
    }
}

@Composable
fun PreviousOtaDialog(ordinal: Int, onAvailable: () -> Unit, onMissing: () -> Unit, onDismiss: () -> Unit) {
    var riskConfirmation by remember { mutableStateOf(false) }
    if (!riskConfirmation) {
        FlowDialog("是否有倒数第 $ordinal 次保锁 OTA 的备份？", onDismiss) {
            Text("这里指紧接已导入最后一次之前的那一次，不是任意一份旧备份。导入后还要选择大版本或小补丁；连续小补丁会继续要求更早的历史。",
                fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAvailable, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("有，导入倒数第 $ordinal 次备份") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { riskConfirmation = true }, modifier = Modifier.fillMaxWidth()) { Text("没有，了解仅恢复当前槽的风险") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("返回检查") }
        }
    } else {
        FlowDialog("确认仅恢复当前槽的风险", { riskConfirmation = false }) {
            Text("没有倒数第二次备份时，另一槽不会恢复。删除共享 efisp 后，另一槽可能无法启动，不能再作为可靠回退槽。",
                fontSize = 15.sp, lineHeight = 23.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("如果连当前槽也没有通过校验的大版本镜像，App 会阻止恢复，不能仅恢复当前槽。否则下一次不能直接依赖不含 ABL/init_boot 的小增量 OTA 修复另一槽；必须使用官方全量包并成功切槽、重启，或先单独修复另一槽。当前槽首次启动失败时，可能无法依靠另一槽自救。",
                fontSize = 15.sp, lineHeight = 23.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onMissing, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("我已理解并接受风险，仅恢复当前槽") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { riskConfirmation = false }, modifier = Modifier.fillMaxWidth()) { Text("返回，重新选择是否有备份") }
        }
    }
}

@Composable
fun OtaTypeDialog(backup: dev.honor.restore.core.OtaBackup, onSelect: (dev.honor.restore.core.OtaPackageType) -> Unit, onDismiss: () -> Unit) {
    var patchConfirmation by remember(backup.id) { mutableStateOf(false) }
    if (!patchConfirmation) {
        FlowDialog("这次保锁 OTA 是大版本还是小补丁？", onDismiss) {
            Text(backup.displayName, fontSize = 13.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("备份时运行槽位 ${backup.currentSlot.uppercase()}，保锁写入目标为 ${backup.targetSlot.uppercase()} 槽。请按系统更新前后的版本变化选择，不要根据镜像是否带 Root 来判断。",
                fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(8.dp))
            Text("例如 110 → 117 是大版本，117 → 117 patch1 是小补丁。App 无法仅凭备份独立核实官方补丁清单。",
                fontSize = 15.sp, lineHeight = 23.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSelect(dev.honor.restore.core.OtaPackageType.MAJOR) }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("这是大版本 OTA") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { patchConfirmation = true }, modifier = Modifier.fillMaxWidth()) { Text("这是小补丁 OTA") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("返回，暂不确认") }
        }
    } else {
        FlowDialog("确认小补丁的版本假设", { patchConfirmation = false }) {
            Text("请确认这次小补丁没有更新 ABL 或 init_boot。App 没有对应的官方补丁包，无法独立核实这一点。",
                fontSize = 15.sp, lineHeight = 23.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("如果判断错误，采用此前大版本或假回锁前的镜像可能与当前系统不匹配，导致当前槽或另一槽无法启动。镜像自身的签名、结构和跨槽证据仍会另外检查，不能用本确认替代。",
                fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSelect(dev.honor.restore.core.OtaPackageType.PATCH) }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认是小补丁且未更新上述分区，我承担此风险") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { patchConfirmation = false }, modifier = Modifier.fillMaxWidth()) { Text("返回重新选择") }
        }
    }
}

@Composable
fun ScanConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FlowDialog("确认只读设备检查", onDismiss) {
        Text("App 将通过 Root 读取槽位、ABL、init_boot、双槽 vbmeta、GPT、F2FS 状态和 OTA 备份目录数量，用于生成恢复计划及验证镜像。此操作不会写入分区、切槽或重启。",
            fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认开始只读检查") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun RemoveBackupDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FlowDialog("确认移除导入副本", onDismiss) {
        Text(message, fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(10.dp))
        Text("只删除 App 内副本，不会删除 Download 中的原备份。移除后可能需要重新按顺序导入。",
            fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认移除") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun HistoryMismatchDialog(message: String, directories: List<String>, onConfirm: () -> Unit, onChangeHistory: () -> Unit) {
    FlowDialog("OTA 次数与目录不一致，必须确认", {}) {
        Text(message, fontSize = 15.sp, lineHeight = 23.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("实际识别到 ${directories.size} 套目录。目录可能被删除、移动或重复，因此只能辅助核对，不能证明真实次数。",
            fontSize = 13.sp, lineHeight = 21.sp)
        if (directories.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(directories.joinToString("\n"), fontSize = 11.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary()) { Text("我确认次数选择真实，并对目录差异负责") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onChangeHistory, modifier = Modifier.fillMaxWidth()) { Text("重新选择次数并重新导入") }
    }
}

@Composable
fun IncompleteRestoreDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FlowDialog("上次恢复需要人工复核", onDismiss) {
        Text("检测到上次恢复中断，或写入完成后没有通过最终扫描。继续前，必须确认当前系统能够启动，并已核对分区状态和恢复前备份。",
            fontSize = 15.sp, lineHeight = 23.sp, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("如果尚未验证启动状态，或不确定哪些分区已经改变，请返回并保留标记。人工复核只会归档标记并重新扫描，不会修复分区，也不代表恢复已经成功。",
            fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary()) { Text("我已完成只读核验，归档标记并重新扫描") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("返回，保留中断标记") }
    }
}
