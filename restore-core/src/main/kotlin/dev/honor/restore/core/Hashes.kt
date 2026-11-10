package dev.honor.restore.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashes {
    private val sha256Pattern = Regex("[a-f0-9]{64}")

    fun validSha256(value: String): Boolean = sha256Pattern.matches(value)

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256Prefix(file: File, bytes: Long): String {
        require(bytes in 1..file.length()) { "前缀哈希长度无效" }
        return file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var remaining = bytes
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                if (count > 0) {
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
            }
            require(remaining == 0L) { "前缀哈希读取不完整" }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
