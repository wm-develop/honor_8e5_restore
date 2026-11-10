package dev.honor.restore

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import dev.honor.restore.core.OtaHistoryMode
import dev.honor.restore.core.OtaPackageType
import dev.honor.restore.core.RestorePlanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val imports by lazy { BackupImportManager(this) }
    private var showConfirmation by mutableStateOf(false)
    private var showHistoryDialog by mutableStateOf(true)
    private var showPreviousDialog by mutableStateOf(false)
    private var showScanDialog by mutableStateOf(false)
    private var showIncompleteDialog by mutableStateOf(false)
    private var pendingRemove by mutableStateOf<BackupSet?>(null)
    private var pendingImport: BackupRequest? = null
    private var pendingOtaType by mutableStateOf<OtaBackup?>(null)

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val expected = pendingImport
        pendingImport = null
        if (uri != null && expected != null) importBackup(uri, expected)
        else if (uri != null) RestoreRuntime.state.value = RestoreRuntime.state.value.copy(
            error = "导入会话已失效，请从当前步骤重新选择备份")
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) RestoreService.execute(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiuixTheme {
                val state = RestoreRuntime.state.value
                RestoreScreen(
                    state = state,
                    actions = RestoreActions(
                        onRequestBackup = ::requestBackup,
                        onChooseHistory = { if (!state.busy) showHistoryDialog = true },
                        onPreviousChoice = { if (!state.busy) showPreviousDialog = true },
                        onChooseOtaType = { if (!state.busy) pendingOtaType = it },
                        onScan = { if (!state.busy) showScanDialog = true },
                        onAcknowledgeIncomplete = { if (!state.busy) showIncompleteDialog = true },
                        onRemove = { if (!state.busy) pendingRemove = it },
                        onExecute = {
                            if (RestoreRuntime.canReview(state) && state.plan?.status == RestorePlanStatus.READY) {
                                showConfirmation = true
                            }
                        },
                    ),
                    showConfirmation = showConfirmation,
                    onDismissConfirmation = { showConfirmation = false },
                    onConfirmExecution = {
                        showConfirmation = false
                        ensureNotifications()
                        val name = "hnubl-restore-before-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
                        backupLauncher.launch(name)
                    },
                )
                if (showHistoryDialog || state.historyMode == OtaHistoryMode.UNKNOWN) {
                    HistorySelectionDialog(state.historyMode == OtaHistoryMode.UNKNOWN, state.backups.size,
                        onSelect = ::startHistory, onDismiss = { showHistoryDialog = false })
                }
                if (showPreviousDialog) {
                    PreviousOtaDialog(
                        ordinal = RestoreFlow.nextOtaOrdinal(state.backups),
                        onAvailable = {
                            showPreviousDialog = false
                            pendingImport = BackupRequest.PREVIOUS_OTA
                            importLauncher.launch(null)
                        },
                        onMissing = {
                            showPreviousDialog = false
                            RestoreRuntime.setPreviousOtaChoice(PreviousOtaChoice.MISSING)
                            RestoreRuntime.appendLog("已确认不再提供更多历史备份，将按当前可用镜像审查风险承担方案")
                        },
                        onDismiss = { showPreviousDialog = false },
                    )
                }
                pendingOtaType?.let { backup ->
                    OtaTypeDialog(backup, onSelect = { type -> selectOtaType(backup, type) }, onDismiss = { pendingOtaType = null })
                }
                if (showScanDialog) {
                    ScanConfirmationDialog(
                        onConfirm = {
                            showScanDialog = false
                            ensureNotifications()
                            RestoreService.scan(this)
                        },
                        onDismiss = { showScanDialog = false },
                    )
                }
                if (showIncompleteDialog) {
                    IncompleteRestoreDialog(
                        onConfirm = {
                            showIncompleteDialog = false
                            ensureNotifications()
                            RestoreService.acknowledgeIncomplete(this)
                        },
                        onDismiss = { showIncompleteDialog = false },
                    )
                }
                pendingRemove?.let { backup ->
                    RemoveBackupDialog(removeMessage(backup),
                        onConfirm = {
                            pendingRemove = null
                            removeBackup(backup)
                        },
                        onDismiss = { pendingRemove = null })
                }
                val mismatch = state.plan?.historyMismatch
                if (!state.busy && !showHistoryDialog && pendingOtaType == null && mismatch != null && state.confirmedHistoryMismatch != mismatch) {
                    HistoryMismatchDialog(mismatch, state.device?.otaDirectoryNames.orEmpty(),
                        onConfirm = {
                            RestoreRuntime.confirmHistoryMismatch(mismatch)
                            RestoreRuntime.appendLog("已确认 OTA 次数与目录差异，请继续逐项审查恢复计划")
                        },
                        onChangeHistory = { showHistoryDialog = true })
                }
            }
        }
        if (RestoreRuntime.state.value.backups.isEmpty()) {
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) { imports.loadAll() }
                if (RestoreRuntime.state.value.historyMode == OtaHistoryMode.UNKNOWN && !RestoreRuntime.state.value.busy) {
                    RestoreRuntime.setBackups(loaded)
                }
            }
        }
    }

    private fun startHistory(mode: OtaHistoryMode) {
        if (RestoreRuntime.state.value.busy) return
        lifecycleScope.launch {
            RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = true, error = "", stage = "正在重置 App 内导入副本")
            try {
                withContext(Dispatchers.IO) { imports.loadAll().forEach(imports::remove) }
                RestoreRuntime.startHistory(mode)
                showHistoryDialog = false
                showPreviousDialog = false
                showConfirmation = false
                pendingRemove = null
                pendingImport = null
                pendingOtaType = null
            } catch (error: Throwable) {
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(error = error.message ?: error.javaClass.simpleName)
            } finally {
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = false)
            }
        }
    }

    private fun requestBackup(request: BackupRequest) {
        if (RestoreRuntime.state.value.busy) return
        val state = RestoreRuntime.state.value
        if (RestoreFlow.nextRequest(state.backups, state.historyMode, state.previousOtaChoice) != request) return
        pendingImport = request
        importLauncher.launch(null)
    }

    private fun importBackup(uri: Uri, expected: BackupRequest) {
        if (RestoreRuntime.state.value.busy) {
            RestoreRuntime.state.value = RestoreRuntime.state.value.copy(error = "另一项任务正在进行，本次文件选择未导入")
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        lifecycleScope.launch {
            RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = true, error = "", stage = "正在导入并校验当前步骤的备份")
            var imported: BackupSet? = null
            var accepted = false
            try {
                val current = RestoreRuntime.state.value
                val validStep = if (expected == BackupRequest.PREVIOUS_OTA) {
                    RestoreFlow.needsPreviousChoice(current.backups, current.historyMode, current.previousOtaChoice) ||
                        RestoreFlow.nextRequest(current.backups, current.historyMode, current.previousOtaChoice) == expected
                } else {
                    RestoreFlow.nextRequest(current.backups, current.historyMode, current.previousOtaChoice) == expected
                }
                check(validStep) { "导入步骤已经变化，请返回当前步骤重新选择" }
                val candidate = withContext(Dispatchers.IO) { imports.importTree(uri) }
                imported = candidate
                when (expected) {
                    BackupRequest.CLEAN -> require(candidate is NormalBackup && RestoreFlow.isClean(candidate)) {
                        "当前步骤只接受假回锁前的干净普通备份：需要 backup.json，且 GPT 不含 efisp"
                    }
                    BackupRequest.LATEST_OTA -> require(candidate is OtaBackup) {
                        "当前步骤只接受你确认的实际最后一次保锁 OTA 备份"
                    }
                    BackupRequest.PREVIOUS_OTA -> {
                        require(candidate is OtaBackup) { "当前步骤只接受按倒序排列的前一次保锁 OTA 备份" }
                        val latest = current.backups.filterIsInstance<OtaBackup>().maxByOrNull { it.timestamp }
                            ?: error("请先导入最后一次保锁 OTA 备份")
                        require(RestoreFlow.validPrevious(latest, candidate)) {
                            "这份备份不能紧接在所选备份之前：它必须更早，且目标槽应等于后一次备份的运行槽"
                        }
                    }
                }
                accepted = true
                val all = withContext(Dispatchers.IO) { imports.loadAll() }
                RestoreRuntime.setBackups(all)
                if (candidate is OtaBackup) {
                    pendingOtaType = all.filterIsInstance<OtaBackup>().singleOrNull { it.id == candidate.id } ?: candidate
                    RestoreRuntime.state.value = RestoreRuntime.state.value.copy(stage = "备份已导入，请确认这次是大版本还是小补丁")
                } else {
                    RestoreRuntime.state.value = RestoreRuntime.state.value.copy(stage = "干净备份已就绪，请按下一步提示继续")
                }
            } catch (error: Throwable) {
                val cleanup = if (accepted) null else imported?.let {
                    runCatching { withContext(Dispatchers.IO) { imports.remove(it) } }.exceptionOrNull()
                }
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(
                    error = (error.message ?: error.javaClass.simpleName) + (cleanup?.let { "；错误副本清理失败：${it.message}" }.orEmpty()))
            } finally {
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = false)
            }
        }
    }

    private fun selectOtaType(backup: OtaBackup, type: OtaPackageType) {
        if (RestoreRuntime.state.value.busy) return
        lifecycleScope.launch {
            RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = true, error = "", stage = "正在保存本次 OTA 类型与风险选择")
            try {
                withContext(Dispatchers.IO) { imports.setOtaType(backup, type) }
                val all = withContext(Dispatchers.IO) { imports.loadAll() }
                RestoreRuntime.setBackups(all)
                val current = RestoreRuntime.state.value
                if (current.historyMode == OtaHistoryMode.MULTIPLE && current.previousOtaChoice == PreviousOtaChoice.UNASKED &&
                    all.count { it is OtaBackup } >= 2) {
                    RestoreRuntime.setPreviousOtaChoice(PreviousOtaChoice.AVAILABLE)
                }
                pendingOtaType = null
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(stage = when {
                    RestoreFlow.isComplete(all, current.historyMode, RestoreRuntime.state.value.previousOtaChoice) ->
                        "备份与更新类型已确认，请进行只读设备检查"
                    RestoreFlow.nextRequest(all, current.historyMode, RestoreRuntime.state.value.previousOtaChoice) == BackupRequest.PREVIOUS_OTA ->
                        "最近历史仍是小补丁，请继续导入倒数第 ${RestoreFlow.nextOtaOrdinal(all)} 次，直到找到大版本"
                    RestoreFlow.needsPreviousChoice(all, current.historyMode, RestoreRuntime.state.value.previousOtaChoice) ->
                        "请确认是否有倒数第二次保锁 OTA 备份"
                    else -> "请按当前步骤继续"
                })
            } catch (error: Throwable) {
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(error = error.message ?: error.javaClass.simpleName)
            } finally {
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = false)
            }
        }
    }

    private fun removeMessage(backup: BackupSet): String {
        val state = RestoreRuntime.state.value
        val ota = state.backups.filterIsInstance<OtaBackup>()
        val latest = backup is OtaBackup && backup.timestamp == ota.maxOfOrNull { it.timestamp }
        return if (latest && state.historyMode == OtaHistoryMode.MULTIPLE && ota.size > 1) {
            "这是最后一次 OTA 的导入副本。移除它会同时移除已导入的 OTA 历史，之后需要按顺序重新导入。"
        } else {
            "确认移除 ${backup.displayName} 的 App 内导入副本？如需更改更新类型，可移除后重新导入。"
        }
    }

    private fun removeBackup(backup: BackupSet) {
        if (RestoreRuntime.state.value.busy) return
        lifecycleScope.launch {
            RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = true, error = "")
            try {
                withContext(Dispatchers.IO) {
                    val current = RestoreRuntime.state.value.backups
                    val ota = current.filterIsInstance<OtaBackup>()
                    val removeLatestChain = backup is OtaBackup && RestoreRuntime.state.value.historyMode == OtaHistoryMode.MULTIPLE &&
                        backup.timestamp == ota.maxOfOrNull { it.timestamp }
                    (if (removeLatestChain) ota else listOf(backup)).forEach(imports::remove)
                    RestoreRuntime.setBackups(imports.loadAll())
                }
                pendingOtaType = null
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(stage = "导入副本已移除，请按当前步骤继续")
            } catch (error: Throwable) {
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(error = error.message ?: error.javaClass.simpleName)
            } finally {
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = false)
            }
        }
    }

    private fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71)
        }
    }
}
