package dev.honor.restore

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupImportInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: BackupImportManager get() = BackupImportManager(context)

    @Before
    fun setUp() {
        File(context.filesDir, "imports").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "imports").deleteRecursively()
    }

    @Test
    fun validTreeIsCopiedVerifiedAndRemoved() {
        val imported = manager.importTree(tree("valid"))
        assertEquals(1, imported.records.size)
        assertEquals(1, manager.loadAll().size)
        manager.remove(imported)
        assertTrue(manager.loadAll().isEmpty())
    }

    @Test
    fun shortAndLongStreamsAreRejectedWithoutPublishingCopies() {
        listOf("short", "long").forEach { scenario ->
            val failure = runCatching { manager.importTree(tree(scenario)) }.exceptionOrNull()
            assertNotNull("$scenario must fail", failure)
            assertTrue(manager.loadAll().isEmpty())
            assertNoPartialImports()
        }
    }

    @Test
    fun unsafeNamesAndDeclaredSizesAreRejectedBeforePublishing() {
        listOf("badname", "empty", "oversize").forEach { scenario ->
            val failure = runCatching { manager.importTree(tree(scenario)) }.exceptionOrNull()
            assertNotNull("$scenario must fail", failure)
            assertTrue(manager.loadAll().isEmpty())
            assertNoPartialImports()
        }
    }

    @Test
    fun incompleteManifestIsRejectedAfterActualFileVerification() {
        val failure = runCatching { manager.importTree(tree("partial")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(manager.loadAll().isEmpty())
        assertNoPartialImports()
    }

    private fun assertNoPartialImports() {
        val root = File(context.filesDir, "imports")
        val remaining = root.listFiles().orEmpty().filter { it.name.startsWith('.') }
        assertTrue("残留未发布导入：$remaining", remaining.isEmpty())
    }

    private fun tree(scenario: String): Uri = Uri.parse("content://dev.honor.restore.audit.documents/tree/$scenario")
}
