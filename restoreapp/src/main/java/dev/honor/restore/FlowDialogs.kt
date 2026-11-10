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
                fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.error)
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
fun PreviousOtaDialog(onAvailable: () -> Unit, onMissing: () -> Unit, onDismiss: () -> Unit) {
    var riskConfirmation by remember { mutableStateOf(false) }
    if (!riskConfirmation) {
        FlowDialog("是否有倒数第二次保锁 OTA 的备份？", onDismiss) {
            Text("这里指紧接“最后一次”之前的那一次，不是任意一份旧备份。有它才能按正常方案恢复双槽。",
                fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAvailable, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("有，导入倒数第二次备份") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { riskConfirmation = true }, modifier = Modifier.fillMaxWidth()) { Text("没有，了解仅恢复当前槽的风险") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("返回检查") }
        }
    } else {
        FlowDialog("确认仅恢复当前槽的风险", { riskConfirmation = false }) {
            Text("没有倒数第二次备份时，另一槽不会恢复。删除共享 efisp 后，另一槽可能无法启动，不能再作为可靠回退槽。",
                fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.error)
            Spacer(Modifier.height(10.dp))
            Text("下一次不能直接依赖不含 ABL/init_boot 的小增量 OTA 修复。必须使用包含它们的官方全量包并成功切槽、重启，或先单独修复另一槽。当前槽首次启动失败时，可能无法依靠另一槽自救。",
                fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onMissing, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("我已理解并接受风险，仅恢复当前槽") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { riskConfirmation = false }, modifier = Modifier.fillMaxWidth()) { Text("返回，重新选择是否有备份") }
        }
    }
}

@Composable
fun ScanConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FlowDialog("确认只读设备检查", onDismiss) {
        Text("App 将通过 Root 读取槽位、ABL、init_boot、GPT、F2FS 状态和 OTA 备份目录数量，用于生成恢复计划。此操作不会写入分区、切槽或重启。",
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
        Text(message, fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.error)
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
            fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.error)
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
