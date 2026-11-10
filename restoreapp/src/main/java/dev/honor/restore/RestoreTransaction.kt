package dev.honor.restore

import android.os.Process

class RestoreTransaction(
    private val shell: SuShell,
    private val stateDir: String = DEFAULT_STATE_DIR,
    private val ownerPid: Int = Process.myPid(),
) {
    private val lock = "$stateDir/LOCK"

    fun acquire() {
        require(!isIncomplete(shell, stateDir)) { "检测到上次恢复中断或结果未复核；必须先完成人工核验并清除中断标记" }
        val script = """
            mkdir -p ${quote(stateDir)} && chmod 700 ${quote(stateDir)} || exit 40
            if [ -d ${quote(lock)} ]; then
                owner=
                [ -r ${quote(lock)}/pid ] && read -r owner < ${quote(lock)}/pid
                case "${'$'}owner" in *[!0-9]*|'') ;; *)
                    if [ -r "/proc/${'$'}owner/cmdline" ] && tr '\000' ' ' < "/proc/${'$'}owner/cmdline" | grep -q 'wm.honor8e5restore'; then
                        exit 41
                    fi
                ;; esac
                rm -f ${quote(lock)}/pid && rmdir ${quote(lock)} || exit 42
            fi
            mkdir ${quote(lock)} || exit 43
            printf '%s' '$ownerPid' > ${quote(lock)}/pid || { rmdir ${quote(lock)}; exit 44; }
            chmod 700 ${quote(lock)} ${quote(lock)}/pid
        """.trimIndent()
        check(shell.run(script).exitCode == 0) { "另一个恢复任务正在运行，或恢复事务锁状态异常" }
    }

    fun release(): ShellResult = shell.run("rm -f ${quote(lock)}/pid && rmdir ${quote(lock)}")

    fun mark(stage: String) {
        val temporary = "$stateDir/INCOMPLETE.tmp"
        val marker = "$stateDir/INCOMPLETE"
        val text = "${System.currentTimeMillis()}|$stage\n"
        val result = shell.run("mkdir -p ${quote(stateDir)} && chmod 700 ${quote(stateDir)} && " +
            "printf '%s' ${quote(text)} > ${quote(temporary)} && chmod 600 ${quote(temporary)} && " +
            "sync && mv -f ${quote(temporary)} ${quote(marker)} && sync")
        check(result.exitCode == 0) { "无法持久化恢复中断状态：${result.output}" }
    }

    fun complete() = archive("COMPLETED")

    fun acknowledge() = archive("REVIEWED")

    private fun archive(kind: String) {
        val marker = "$stateDir/INCOMPLETE"
        val destination = "$stateDir/$kind-${System.currentTimeMillis()}"
        val result = shell.run("if [ -f ${quote(marker)} ]; then mv ${quote(marker)} ${quote(destination)} && sync; fi")
        check(result.exitCode == 0) { "无法归档恢复状态：${result.output}" }
    }

    companion object {
        const val DEFAULT_STATE_DIR = "/data/adb/hnubl/restore-state"

        fun isIncomplete(shell: SuShell, stateDir: String = DEFAULT_STATE_DIR): Boolean {
            val result = shell.run("if [ -f ${quote(stateDir)}/INCOMPLETE ]; then printf '1'; else printf '0'; fi")
            check(result.exitCode == 0 && result.output.trim() in setOf("0", "1")) { "无法读取恢复中断状态：${result.output}" }
            return result.output.trim() == "1"
        }

        private fun quote(value: String): String = SuShell.quote(value)
    }
}
