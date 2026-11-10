package dev.honor.restore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.honor.restore.core.RestorePlanStatus
import dev.honor.restore.core.RestorePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class RestoreService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (RestoreRuntime.state.value.busy) return START_NOT_STICKY
        RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = true, error = "", completed = false)
        startForeground()
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hnubl:restore")
        scope.launch {
            try {
                wake.acquire(2 * 60 * 60 * 1000L)
                when (action) {
                    ACTION_SCAN -> scan()
                    ACTION_EXECUTE -> execute(intent.getStringExtra(EXTRA_BACKUP_URI))
                    ACTION_ACKNOWLEDGE_INCOMPLETE -> acknowledgeIncomplete()
                }
            } catch (error: Throwable) {
                Log.e("RestoreService", "Task failed: $action", error)
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(
                    error = error.message ?: error.javaClass.simpleName, stage = "任务已停止")
            } finally {
                if (wake.isHeld) wake.release()
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(busy = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun scan() {
        val root = withContext(Dispatchers.IO) { SuShell().check() }
        check(root.exitCode == 0) { root.output }
        stage("正在只读扫描设备、分区、GPT 与 F2FS")
        val shell = SuShell()
        check(shell.check().exitCode == 0) { "Root 授权在扫描前失效" }
        val targets = RestoreRuntime.state.value.backups.filterIsInstance<dev.honor.restore.core.OtaBackup>()
            .flatMap { it.writes.map { write -> write.target } }.toSet()
        val directory = File(filesDir, "sessions/${UUID.randomUUID()}")
        val scanned = withContext(Dispatchers.IO) { DeviceScanner(this@RestoreService, shell, directory).scan(targets) }
        RestoreRuntime.setDevice(scanned, RestoreRuntime.state.value.backups)
        val blockers = RestoreRuntime.state.value.plan?.blockers.orEmpty()
        stage(if (blockers.isEmpty()) "恢复计划已就绪，请逐项审查" else "设备扫描完成，请补齐恢复条件")
    }

    private suspend fun acknowledgeIncomplete() {
        stage("正在归档人工复核结果并重新扫描")
        val shell = SuShell()
        check(shell.check().exitCode == 0) { "人工复核时未获得 Root 权限" }
        withContext(Dispatchers.IO) { RestoreTransaction(shell).acknowledge() }
        scan()
    }

    private suspend fun execute(uriText: String?) {
        check(BuildConfig.ALLOW_PARTITION_WRITES) { "当前 Debug 构建只允许只读诊断，未开放分区写入" }
        val reviewed = RestoreRuntime.requireReadyPlan()
        val destination = Uri.parse(requireNotNull(uriText) { "尚未选择恢复前备份保存位置" })
        stage("正在重新扫描并核对已审查计划")
        val shell = SuShell()
        check(shell.check().exitCode == 0) { "执行前未获得 Root 权限" }
        val directory = File(filesDir, "sessions/${UUID.randomUUID()}")
        val scanner = DeviceScanner(this, shell, directory)
        val backups = RestoreRuntime.state.value.backups
        val targets = backups.filterIsInstance<dev.honor.restore.core.OtaBackup>()
            .flatMap { it.writes.map { write -> write.target } }.toSet()
        val fresh = withContext(Dispatchers.IO) { scanner.scan(targets) }
        val freshPlan = RestorePlanner.plan(backups, fresh.snapshot, RestoreRuntime.state.value.historyMode)
        check(freshPlan.status == RestorePlanStatus.READY) { "执行前状态变化：${freshPlan.blockers.joinToString("；")}" }
        check(freshPlan.strategy == reviewed.strategy && freshPlan.images == reviewed.images && freshPlan.gpt == reviewed.gpt &&
            freshPlan.historyMismatch == reviewed.historyMismatch) {
            "执行前恢复计划或 OTA 目录核对结果发生变化，请重新审查"
        }
        stage("正在导出并逐条校验当前状态备份；备份成功前不会写入分区")
        val backupHash = withContext(Dispatchers.IO) { ExecutionBackup.export(this@RestoreService, destination, fresh, freshPlan) }
        RestoreRuntime.state.value = RestoreRuntime.state.value.copy(backupSha256 = backupHash)
        stage("恢复前备份已通过字节与逐条内容校验，开始事务保护和固定恢复源")
        withContext(Dispatchers.IO) {
            RestoreExecutor(shell, scanner, backups, freshPlan, fresh, log = { message ->
                RestoreRuntime.state.value = RestoreRuntime.state.value.copy(stage = message, logs = RestoreRuntime.state.value.logs + message)
            }).execute()
        }
        stage("分区写入回读完成，正在重新扫描最终状态")
        val verificationDirectory = File(filesDir, "sessions/${UUID.randomUUID()}")
        val verified = withContext(Dispatchers.IO) { DeviceScanner(this@RestoreService, shell, verificationDirectory).scan(targets) }
        val verificationSnapshot = verified.snapshot.copy(incompleteRestore = false)
        val completionPlan = RestorePlanner.plan(backups, verificationSnapshot, RestoreRuntime.state.value.historyMode)
        check(completionPlan.status == RestorePlanStatus.READY) {
            "恢复后核验未通过，中断标记已保留：${completionPlan.blockers.joinToString("；")}"
        }
        check(completionPlan.images.isEmpty() && completionPlan.gpt == null) {
            "恢复后仍有未完成的镜像或 GPT 项目，中断标记已保留"
        }
        withContext(Dispatchers.IO) { RestoreTransaction(shell).complete() }
        RestoreRuntime.setDevice(verified.copy(snapshot = verificationSnapshot), backups)
        RestoreRuntime.state.value = RestoreRuntime.state.value.copy(
            confirmedHistoryMismatch = if (completionPlan.historyMismatch == reviewed.historyMismatch) reviewed.historyMismatch else null,
            completed = true, stage = "分区恢复与最终扫描均已完成，尚未重启；Fastboot 厂商锁态未验证")
    }

    private suspend fun stage(message: String) {
        RestoreRuntime.state.value = RestoreRuntime.state.value.copy(stage = message, logs = RestoreRuntime.state.value.logs + message)
    }

    private fun startForeground() {
        val channel = "restore_tasks"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "原厂恢复任务", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("hnubl 恢复助手")
            .setContentText("正在处理恢复任务，请勿重启设备")
            .setOngoing(true)
            .build()
        startForeground(71, notification)
    }

    companion object {
        private const val ACTION_SCAN = "dev.honor.restore.action.SCAN"
        private const val ACTION_EXECUTE = "dev.honor.restore.action.EXECUTE"
        private const val ACTION_ACKNOWLEDGE_INCOMPLETE = "dev.honor.restore.action.ACKNOWLEDGE_INCOMPLETE"
        private const val EXTRA_BACKUP_URI = "backup_uri"

        fun scan(context: Context) {
            context.startForegroundService(Intent(context, RestoreService::class.java).setAction(ACTION_SCAN))
        }

        fun execute(context: Context, backup: Uri) {
            context.startForegroundService(Intent(context, RestoreService::class.java).setAction(ACTION_EXECUTE)
                .putExtra(EXTRA_BACKUP_URI, backup.toString()))
        }

        fun acknowledgeIncomplete(context: Context) {
            context.startForegroundService(Intent(context, RestoreService::class.java).setAction(ACTION_ACKNOWLEDGE_INCOMPLETE))
        }
    }
}
