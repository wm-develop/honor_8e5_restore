package dev.honor.restore

class CompanionControl(
    private val shell: SuShell,
    private val scriptPath: String = "/data/adb/modules/hnubl_ota/uninstall.sh",
    private val stateDir: String = "/data/adb/hnubl",
    private val updaterPackage: String = "com.hihonor.ouc",
    private val attempts: Int = 50,
) {
    fun stop(): ShellResult {
        val script = """
            if [ -f ${quote(scriptPath)} ]; then sh ${quote(scriptPath)} || exit ${'$'}?; fi
            i=0
            while [ "${'$'}i" -lt $attempts ]; do
                alive=0
                if [ -r ${quote(stateDir)}/service.pid ]; then
                    read -r pid < ${quote(stateDir)}/service.pid
                    case "${'$'}pid" in *[!0-9]*|'') ;; *)
                        if [ -r "/proc/${'$'}pid/cmdline" ] && tr '\000' ' ' < "/proc/${'$'}pid/cmdline" | grep -q 'net.hawyn.hnubl.module.ModuleMain'; then
                            alive=1
                        fi
                    ;; esac
                fi
                if [ "${'$'}alive" -eq 0 ] && ! pidof ${quote(updaterPackage)} >/dev/null 2>&1; then
                    exit 0
                fi
                sleep 0.2
                i=$((i + 1))
            done
            exit 1
        """.trimIndent()
        return shell.run(script, attempts + 15L)
    }

    private fun quote(value: String): String = SuShell.quote(value)
}
