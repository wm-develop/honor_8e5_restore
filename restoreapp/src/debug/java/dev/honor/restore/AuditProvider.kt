package dev.honor.restore

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class AuditProvider : ContentProvider() {
    private val destination: File get() = File(context!!.filesDir, "backup-audit-output.zip")

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode == "rwt") {
            readCount = 0
            mutated = false
            destination.parentFile?.mkdirs()
            return ParcelFileDescriptor.open(destination,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        readCount++
        val path = uri.path.orEmpty()
        if (path.contains("second") && readCount >= 2) destination.writeBytes("corrupted-second-read".toByteArray())
        if (!mutated) {
            when {
                path.contains("corrupt") -> destination.writeBytes("corrupted-backup".toByteArray())
                path.contains("flip") -> {
                    val bytes = destination.readBytes()
                    if (bytes.isNotEmpty()) {
                        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
                        destination.writeBytes(bytes)
                    }
                }
                path.contains("truncate") -> destination.writeBytes(destination.readBytes().copyOf(destination.length().toInt() / 2))
            }
            mutated = true
        }
        return ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>("audit.zip", destination.length()))
        }

    override fun getType(uri: Uri): String = "application/zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        @Volatile private var readCount = 0
        @Volatile private var mutated = false
    }
}
