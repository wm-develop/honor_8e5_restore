package dev.honor.restore

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

class AuditDocumentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ))
        if (uri.path?.endsWith("/children") == true) {
            val scenario = uri.path?.substringAfter("/tree/")?.substringBefore('/') ?: return cursor
            documents(scenario).forEach { document ->
                cursor.addRow(arrayOf<Any>("$scenario|${document.name}", document.name, document.size, DocumentsContract.Document.MIME_TYPE_DIR.let { "application/octet-stream" }))
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = DocumentsContract.getDocumentId(uri)
        val scenario = id.substringBefore('|')
        val name = id.substringAfter('|')
        val document = documents(scenario).firstOrNull { it.name == name } ?: throw FileNotFoundException(id)
        val file = File(context!!.cacheDir, "audit-document-${scenario.hashCode()}-${name.hashCode()}")
        file.writeBytes(document.data)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private data class Document(val name: String, val size: Long, val data: ByteArray)

    private fun documents(scenario: String): List<Document> {
        if (scenario == "badname") return listOf(Document("../escape.img", 3, byteArrayOf(1, 2, 3)))
        if (scenario == "empty") return listOf(Document("empty.img", 0, ByteArray(0)))
        if (scenario == "oversize") return listOf(Document("huge.img", 128L * 1024 * 1024 + 1, byteArrayOf(1)))
        val payload = byteArrayOf(1, 2, 3)
        val manifest = JSONObject()
            .put("format", "bootforge-slot-backup-v2.2")
            .put("status", if (scenario == "partial") "partial" else "complete")
            .put("partition_writes", false)
            .put("running_slot", "a")
            .put("requested_at_millis", 1L)
            .put("files", JSONArray().put(JSONObject()
                .put("name", "abl_a.img").put("bytes", 3L).put("sha256", hash(payload))))
        val manifestBytes = manifest.toString().toByteArray()
        val sums = "${hash(payload)} abl_a.img\n${hash(manifestBytes)} backup.json\n".toByteArray()
        val files = listOf(
            Document("abl_a.img", 3, payload),
            Document("backup.json", manifestBytes.size.toLong(), manifestBytes),
            Document("SHA256SUMS", sums.size.toLong(), sums),
        )
        return when (scenario) {
            "short" -> files.map { if (it.name == "abl_a.img") it.copy(size = 8) else it }
            "long" -> files.map { if (it.name == "abl_a.img") it.copy(size = 2) else it }
            else -> files
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
