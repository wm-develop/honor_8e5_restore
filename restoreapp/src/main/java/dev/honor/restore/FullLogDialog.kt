package dev.honor.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun FullLogDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("完整诊断日志", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("包含备份校验、设备状态、恢复来源、计划及错误详情，不包含分区镜像原始字节。",
                    fontSize = 12.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text("日志还包含系统版本、分区哈希和备份目录名，分享导出文件前请自行检查。",
                    fontSize = 14.sp, lineHeight = 22.sp, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(DiagnosticLog.text(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭日志") }
            }
        }
    }
}
