package dev.honor.restore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

class AblRollbackInspectorFuzzTest {
    @Test
    fun thousandsOfMalformedImagesAreBoundedAndRejected() {
        val random = Random(0x8e5)
        repeat(5000) { index ->
            val bytes = ByteArray(random.nextInt(16 * 1024 + 1))
            random.nextBytes(bytes)
            if (bytes.size >= 8 && index % 3 == 0) {
                bytes[0] = 0x7f
                bytes[1] = 69
                bytes[2] = 76
                bytes[3] = 70
            } else if (bytes.size >= 8 && index % 3 == 1) {
                bytes[0] = 77
                bytes[1] = 90
            }
            if (bytes.size >= 8 && index % 5 == 0) {
                val versions = longArrayOf(3, 5, 6, 7, 8, 0xffffffffL)
                val version = versions[index % versions.size]
                for (byte in 0 until 4) bytes[4 + byte] = (version ushr (8 * byte)).toByte()
            }
            val result = AblRollbackInspector.inspect(bytes)
            assertTrue("畸形输入必须作为无效镜像处理：index=$index, result=$result",
                result is AblRollbackInspector.Result.Invalid)
        }
    }

    @Test
    fun truncatedRealAblNeverBecomesValid() {
        val file = File("..", "app/src/main/assets/images/abl.img")
        assumeTrue(file.isFile)
        val bytes = file.readBytes()
        val lengths = sortedSetOf<Int>().apply {
            addAll(listOf(0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 32, 39, 40, 55, 56, 63, 64, 127, 128, 255, 256, 511, 512, 1023, 4095))
            var at = 997
            while (at < bytes.size) {
                add(at)
                at += 997
            }
            add(bytes.size - 1)
        }
        lengths.forEach { length ->
            val result = AblRollbackInspector.inspect(bytes.copyOf(length))
            assertTrue("截断镜像不能通过结构检查：$length, $result", result is AblRollbackInspector.Result.Invalid)
        }
    }

    @Test
    fun oversizedFileIsRejectedBeforeReadingWholeFile() {
        val file = File("build/abl-oversize-fuzz.img")
        RandomAccessFile(file, "rw").use { it.setLength(AblRollbackInspector.MAX_IMAGE_BYTES + 1L) }
        try {
            val result = AblRollbackInspector.inspect(file)
            assertEquals(AblRollbackInspector.Result.Invalid::class, result::class)
        } finally {
            file.delete()
        }
    }
}
