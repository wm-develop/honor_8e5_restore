package dev.honor.restore

import androidx.compose.runtime.mutableStateOf
import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.DeviceSnapshot
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.OtaHistoryMode
import dev.honor.restore.core.RestorePlan
import dev.honor.restore.core.RestorePlanner
import dev.honor.restore.core.RestorePlanStatus

data class RestoreUiState(
    val backups: List<BackupSet> = emptyList(),
    val historyMode: OtaHistoryMode = OtaHistoryMode.UNKNOWN,
    val previousOtaChoice: PreviousOtaChoice = PreviousOtaChoice.UNASKED,
    val confirmedHistoryMismatch: String? = null,
    val device: DeviceSnapshot? = null,
    val plan: RestorePlan? = null,
    val busy: Boolean = false,
    val stage: String = "请先确认假回锁后实际执行的保锁 OTA 次数",
    val error: String = "",
    val logs: List<String> = emptyList(),
    val completed: Boolean = false,
    val backupSha256: String = "",
)

object RestoreRuntime {
    val state = mutableStateOf(RestoreUiState())

    fun startHistory(mode: OtaHistoryMode) {
        DiagnosticLog.i("History", "选择假回锁后的 OTA 次数：$mode")
        state.value = RestoreUiState(historyMode = mode, stage = "请按顺序导入假回锁前的干净备份")
    }

    fun setBackups(backups: List<BackupSet>) {
        val current = state.value
        val otaCount = backups.count { it is OtaBackup }
        val previous = when {
            otaCount == 0 -> PreviousOtaChoice.UNASKED
            current.previousOtaChoice == PreviousOtaChoice.AVAILABLE && otaCount < 2 -> PreviousOtaChoice.UNASKED
            else -> current.previousOtaChoice
        }
        val plan = RestorePlanner.plan(backups, current.device, current.historyMode)
        backups.forEach(DiagnosticLog::backup)
        DiagnosticLog.plan(plan)
        state.value = current.copy(backups = backups, previousOtaChoice = previous,
            plan = plan, error = "", completed = false)
    }

    fun setDevice(scanned: ScannedDevice, backups: List<BackupSet>) {
        DiagnosticLog.device(scanned.snapshot)
        val plan = RestorePlanner.plan(backups, scanned.snapshot, state.value.historyMode)
        DiagnosticLog.plan(plan)
        state.value = state.value.copy(device = scanned.snapshot, plan = plan,
            confirmedHistoryMismatch = null, error = "", completed = false)
    }

    fun setPreviousOtaChoice(choice: PreviousOtaChoice) {
        val current = state.value
        val plan = RestorePlanner.plan(current.backups, current.device, current.historyMode)
        DiagnosticLog.i("History", "是否继续提供历史备份：$choice")
        DiagnosticLog.plan(plan)
        state.value = current.copy(previousOtaChoice = choice, plan = plan, error = "", completed = false)
    }

    fun confirmHistoryMismatch(message: String) {
        if (state.value.plan?.historyMismatch == message) {
            DiagnosticLog.w("History", "用户确认 OTA 次数与目录差异：$message")
            state.value = state.value.copy(confirmedHistoryMismatch = message, error = "")
        }
    }

    fun appendLog(message: String) {
        DiagnosticLog.i("Task", message)
        state.value = state.value.copy(logs = state.value.logs + message, stage = message)
    }

    fun canReview(current: RestoreUiState = state.value): Boolean =
        RestoreFlow.isComplete(current.backups, current.historyMode, current.previousOtaChoice) &&
            current.plan?.historyMismatch.let { it == null || current.confirmedHistoryMismatch == it }

    fun requireReadyPlan(): RestorePlan {
        check(RestoreFlow.isComplete(state.value.backups, state.value.historyMode, state.value.previousOtaChoice)) {
            "请先按顺序完成备份导入、更新类型选择及所需的 OTA 历史确认"
        }
        val plan = state.value.plan?.takeIf { it.status == RestorePlanStatus.READY }
            ?: error("当前恢复计划尚未满足执行条件")
        check(plan.historyMismatch == null || state.value.confirmedHistoryMismatch == plan.historyMismatch) {
            "OTA 次数与目录数量不一致，必须先在弹窗中确认"
        }
        return plan
    }
}
