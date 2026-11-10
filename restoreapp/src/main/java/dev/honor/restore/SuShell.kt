package dev.honor.restore

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ShellResult(val exitCode: Int, val output: String)

class SuShell {
    private var validatedSu: String? = null

    @Synchronized
    fun check(): ShellResult {
        validatedSu = null
        val diagnostics = mutableListOf<String>()
        for (candidate in candidates.distinct()) {
            val result = runCatching { execute(candidate, "id -u", null, 15).first }.getOrElse {
                ShellResult(1, it.message ?: it.javaClass.simpleName)
            }
            if (result.exitCode == 0 && result.output.trim() == "0") {
                validatedSu = candidate
                return ShellResult(0, "ROOT_UID=0\nSU_PATH=$candidate")
            }
            diagnostics += "$candidate: ${result.output.trim().ifEmpty { "退出码 ${result.exitCode}" }}"
        }
        return ShellResult(1, (listOf("ROOT_UID=unknown", "未获得 Root 权限，请在 KernelSU/SukiSU 中授权") + diagnostics).joinToString("\n"))
    }

    @Synchronized
    fun run(command: String, timeoutSeconds: Long = 120): ShellResult {
        val su = validatedSu ?: check().let { result ->
            check(result.exitCode == 0) { result.output }
            checkNotNull(validatedSu)
        }
        return execute(su, command, null, timeoutSeconds).first
    }

    @Synchronized
    fun capture(command: String, destination: File, timeoutSeconds: Long = 600): ShellResult {
        val su = validatedSu ?: check().let { result ->
            check(result.exitCode == 0) { result.output }
            checkNotNull(validatedSu)
        }
        val (result, error) = execute(su, command, destination, timeoutSeconds)
        return ShellResult(result.exitCode, (result.output + error).ifBlank { result.output })
    }

    private fun execute(su: String, command: String, destination: File?, timeoutSeconds: Long): Pair<ShellResult, String> {
        require(timeoutSeconds > 0)
        require(!command.contains('\u0000'))
        val process = ProcessBuilder(su, "-c", command).start()
        process.outputStream.close()
        val stdout = if (destination == null) ByteArrayOutputStream() else null
        val stderr = ByteArrayOutputStream()
        val failure = AtomicReference<Exception>()
        val out = pump(process.inputStream, stdout ?: FileOutputStream(destination), failure, process)
        val err = pump(process.errorStream, stderr, failure, process)
        val ended = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!ended) {
            process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close()
            out.join(2000); err.join(2000)
            return ShellResult(124, "命令执行超时") to "命令执行超时"
        }
        out.join(5000); err.join(5000)
        if (out.isAlive || err.isAlive) {
            process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close()
            return ShellResult(125, "命令输出未在期限内关闭") to "命令输出未在期限内关闭"
        }
        failure.get()?.let { return ShellResult(1, it.message ?: "输出读取失败") to (it.message ?: "输出读取失败") }
        val code = process.exitValue()
        val outText = stdout?.toString(Charsets.UTF_8.name()).orEmpty()
        val errText = stderr.toString(Charsets.UTF_8.name())
        return ShellResult(code, outText + errText) to errText
    }

    private fun pump(input: InputStream, output: OutputStream, failure: AtomicReference<Exception>, process: Process): Thread =
        Thread({
            try {
                input.use { source ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            total += count
                            if (output is ByteArrayOutputStream && total > 8 * 1024 * 1024) throw IllegalStateException("文本输出超过 8 MiB")
                            output.write(buffer, 0, count)
                        }
                    }
                    if (output is FileOutputStream) output.fd.sync() else output.flush()
                }
            } catch (error: Exception) {
                failure.compareAndSet(null, error)
                process.destroyForcibly()
            } finally {
                runCatching { if (output is FileOutputStream) output.close() }
            }
        }, "restore-shell-output").apply { isDaemon = true; start() }

    companion object {
        private val candidates = listOf("su", "/data/adb/ksu/bin/su", "/system/xbin/su", "/system/bin/su", "/sbin/su",
            "/su/bin/su", "/debug_ramdisk/su", "/data/adb/magisk/su", "/data/adb/magisk/bin/su", "/data/adb/ap/bin/su")
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
