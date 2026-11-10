package dev.honor.restore

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.honor.restore.core.Hashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class DiagnosticExportInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun exportContainsDetailedEventsAndSummaryWithVerifiedBytes() {
        val marker = "DIAGNOSTIC_EVENT_${UUID.randomUUID()}"
        DiagnosticLog.init(context)
        DiagnosticLog.i("InstrumentedTest", marker)
        val destination = Uri.parse("content://dev.honor.restore.audit/diagnostic-export")
        val expectedHash = DiagnosticExporter.export(context, destination)
        val bytes = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
        assertEquals(expectedHash, Hashes.sha256(bytes.inputStream()))

        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertTrue(entries.getValue("diagnostics.txt").contains(marker))
        assertTrue(entries.getValue("summary.txt").contains("format=hnubl-restore-diagnostics-v1"))
        assertTrue(entries.getValue("summary.txt").contains("package="))
        assertTrue(entries.keys.any { it.startsWith("history-") })
    }
}
