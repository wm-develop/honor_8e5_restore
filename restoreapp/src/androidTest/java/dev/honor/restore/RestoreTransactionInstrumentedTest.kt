package dev.honor.restore

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreTransactionInstrumentedTest {
    private lateinit var shell: SuShell
    private lateinit var stateDir: String
    private var fixturePid = 0

    @Before
    fun setUp() {
        shell = SuShell()
        check(shell.check().exitCode == 0) { "事务测试需要 Root，但不会访问生产状态目录" }
        stateDir = "/data/local/tmp/hnubl-restore-transaction-test-${UUID.randomUUID()}"
        check(shell.run("mkdir -p ${quote(stateDir)} && chmod 700 ${quote(stateDir)}").exitCode == 0)
    }

    @After
    fun tearDown() {
        if (fixturePid > 0) shell.run("kill -KILL $fixturePid 2>/dev/null || true")
        if (::stateDir.isInitialized) shell.run("rm -rf ${quote(stateDir)}")
    }

    @Test
    fun lockRejectsConcurrentOwnerAndRemovesStaleLock() {
        val first = RestoreTransaction(shell, stateDir, Process.myPid())
        val second = RestoreTransaction(shell, stateDir, 123456)
        first.acquire()
        assertNotNull(runCatching { second.acquire() }.exceptionOrNull())
        assertEquals(0, first.release().exitCode)
        second.acquire()
        assertEquals(0, second.release().exitCode)
        check(shell.run("mkdir -p ${quote(stateDir)}/LOCK && printf '999999' > ${quote(stateDir)}/LOCK/pid").exitCode == 0)
        first.acquire()
        assertEquals(0, first.release().exitCode)
    }

    @Test
    fun incompleteMarkerRequiresExplicitReviewBeforeAnotherTransaction() {
        val transaction = RestoreTransaction(shell, stateDir)
        assertFalse(RestoreTransaction.isIncomplete(shell, stateDir))
        transaction.mark("WRITE_STARTED|fixture")
        assertTrue(RestoreTransaction.isIncomplete(shell, stateDir))
        assertNotNull(runCatching { transaction.acquire() }.exceptionOrNull())
        transaction.acknowledge()
        assertFalse(RestoreTransaction.isIncomplete(shell, stateDir))
        assertEquals(0, shell.run("ls ${quote(stateDir)}/REVIEWED-*").exitCode)
        transaction.acquire()
        assertEquals(0, transaction.release().exitCode)
        transaction.mark("WRITES_VERIFIED_AWAITING_RESCAN")
        transaction.complete()
        assertFalse(RestoreTransaction.isIncomplete(shell, stateDir))
        assertEquals(0, shell.run("ls ${quote(stateDir)}/COMPLETED-*").exitCode)
    }

    @Test
    fun companionStopWaitsForFixtureProcessToExit() {
        createUninstallScript()
        val process = "$stateDir/net.hawyn.hnubl.module.ModuleMain"
        check(shell.run("ln -s /system/bin/sleep ${quote(process)}").exitCode == 0)
        startFixture(process)
        check(fixtureAlive()) { "正常测试进程未能启动" }
        val result = CompanionControl(shell, "$stateDir/uninstall.sh", stateDir,
            "dev.honor.restore.audit.no_updater", 10).stop()
        assertEquals(result.output, 0, result.exitCode)
        assertFalse(fixtureAlive())
    }

    @Test
    fun companionStopFailureIsReturnedWhenFixtureSurvivesTerm() {
        createUninstallScript()
        val process = "$stateDir/net.hawyn.hnubl.module.ModuleMain"
        write(process, "#!/system/bin/sh\ntrap '' TERM\nwhile true; do sleep 1; done\n", true)
        startFixture(process)
        check(fixtureAlive()) { "拒绝退出的测试进程未能启动" }
        val result = CompanionControl(shell, "$stateDir/uninstall.sh", stateDir,
            "dev.honor.restore.audit.no_updater", 2).stop()
        assertTrue("进程未退出时必须返回失败：$result", result.exitCode != 0)
        assertTrue(fixtureAlive())
    }

    private fun createUninstallScript() {
        write("$stateDir/uninstall.sh", "#!/system/bin/sh\nread -r pid < ${quote(stateDir)}/service.pid\nkill -TERM \"\$pid\" 2>/dev/null || true\n", true)
    }

    private fun startFixture(process: String) {
        val result = shell.run("${quote(process)} 30 >/dev/null 2>&1 & printf '%s' \"\$!\" > ${quote(stateDir)}/service.pid")
        check(result.exitCode == 0) { result.output }
        fixturePid = shell.run("cat ${quote(stateDir)}/service.pid").output.trim().toInt()
    }

    private fun fixtureAlive(): Boolean = shell.run(
        "if [ -r /proc/$fixturePid/cmdline ] && tr '\\000' ' ' < /proc/$fixturePid/cmdline | grep -q 'net.hawyn.hnubl.module.ModuleMain'; then exit 0; else exit 1; fi"
    ).exitCode == 0

    private fun write(path: String, content: String, executable: Boolean) {
        check(shell.run("printf '%s' ${quote(content)} > ${quote(path)}").exitCode == 0)
        if (executable) check(shell.run("chmod 700 ${quote(path)}").exitCode == 0)
    }

    private fun quote(value: String): String = SuShell.quote(value)
}
