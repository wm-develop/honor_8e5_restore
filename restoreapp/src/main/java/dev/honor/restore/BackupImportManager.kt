package dev.honor.restore

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.honor.restore.core.BackupSet
import dev.honor.restore.core.BackupVerifier
import dev.honor.restore.core.NormalBackup
import dev.honor.restore.core.OtaBackup
import java.io.File
import java.util.UUID

class BackupImportManager(private val context: Context) {
    private val root = File(context.filesDir, "imports")
    private val safeName = Regex("[A-Za-z0-9_.-]+")

    fun importTree(tree: Uri): BackupSet {
        require(tree.scheme == "content") { "备份目录 URI 无效" }
        val documents = children(tree)
        require(documents.isNotEmpty()) { "所选目录为空或不可读取" }
        val displayName = DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').ifBlank { "导入备份" }
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".$id.partial")
        val destination = File(root, id)
        var committed = false
        try {
            check(staging.mkdirs()) { "无法创建备份导入目录" }
            var total = 0L
            documents.filter { !it.directory }.forEach { document ->
                require(safeName.matches(document.name)) { "备份包含不支持的文件名：${document.name}" }
                require(document.size in 1..128L * 1024 * 1024) { "备份文件为空或超过 128 MiB：${document.name}" }
                total = Math.addExact(total, document.size)
                require(total <= 1024L * 1024 * 1024) { "单次备份导入超过 1 GiB" }
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取 ${document.name}" }
                    val output = File(staging, document.name)
                    output.outputStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                copied = Math.addExact(copied, count.toLong())
                                require(copied <= document.size) { "${document.name} 读取长度超过清单" }
                                stream.write(buffer, 0, count)
                            }
                        }
                        require(copied == document.size) { "${document.name} 读取不完整" }
                        stream.fd.sync()
                    }
                }
            }
            BackupVerifier.verify(staging)
            require(destination.mkdir()) { "无法发布导入备份" }
            staging.listFiles().orEmpty().forEach { source ->
                val target = File(destination, source.name)
                require(source.renameTo(target)) { "无法固定备份文件 ${source.name}" }
            }
            staging.delete()
            val verified = BackupVerifier.verify(destination)
            File(destination, ".import-name").writeText(displayName)
            committed = true
            return when (verified) {
                is NormalBackup -> verified.copy(displayName = displayName)
                is OtaBackup -> verified.copy(displayName = displayName)
            }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            if (!committed && destination.exists()) destination.deleteRecursively()
        }
    }

    fun loadAll(): List<BackupSet> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { directory ->
                runCatching {
                    when (val backup = BackupVerifier.verify(directory)) {
                        is NormalBackup -> backup.copy(displayName = File(directory, ".import-name").takeIf { it.isFile }?.readText() ?: backup.displayName)
                        is OtaBackup -> backup.copy(displayName = File(directory, ".import-name").takeIf { it.isFile }?.readText() ?: backup.displayName)
                    }
                }.getOrNull()
            }
            .sortedBy { it.timestamp }
    }

    fun remove(backup: BackupSet) {
        val directory = backup.directory.canonicalFile
        require(directory.parentFile == root.canonicalFile && Regex("[a-f0-9-]{36}").matches(directory.name)) { "导入目录身份无效" }
        check(directory.deleteRecursively()) { "无法删除导入备份" }
    }

    private data class Child(val id: String, val name: String, val size: Long, val directory: Boolean)

    private fun children(tree: Uri): List<Child> {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val result = mutableListOf<Child>()
        context.contentResolver.query(uri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ), null, null, null).use { cursor ->
            requireNotNull(cursor) { "系统文件提供器无法列出备份目录" }
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val size = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                val directory = cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                result += Child(cursor.getString(0), name, size, directory)
            }
        }
        return result
    }
}
