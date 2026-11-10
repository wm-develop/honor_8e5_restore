package dev.honor.restore.core

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

object BootImageVerifier {
    data class Result(
        val signatureValid: Boolean,
        val contentValid: Boolean,
        val originalBytes: Long,
        val publicKeySha256: String,
        val stockImageSha1: String?,
        val detail: String,
    )

    private data class Vbmeta(
        val bytes: ByteArray,
        val signatureValid: Boolean,
        val publicKey: ByteArray,
        val publicKeySha256: String,
        val hashes: List<HashDescriptor>,
        val chains: Map<String, ByteArray>,
        val detail: String,
    )

    private data class HashDescriptor(
        val imageBytes: Long,
        val algorithm: String,
        val partition: String,
        val salt: ByteArray,
        val digest: ByteArray,
    )

    private class Reader(val data: ByteArray) {
        fun range(offset: Long, size: Long, end: Long = data.size.toLong()) {
            require(offset >= 0 && size >= 0 && end <= data.size && offset <= end && size <= end - offset) {
                "镜像字段越界或长度不完整"
            }
        }

        fun u32(offset: Long, littleEndian: Boolean = false): Long {
            range(offset, 4)
            return (0 until 4).fold(0L) { value, index ->
                val shift = if (littleEndian) index * 8 else (3 - index) * 8
                value or ((data[offset.toInt() + index].toLong() and 255) shl shift)
            }
        }

        fun u64(offset: Long): Long {
            val high = u32(offset)
            val low = u32(offset + 4)
            require(high <= Int.MAX_VALUE) { "镜像偏移超出支持范围" }
            return (high shl 32) or low
        }

        fun bytes(offset: Long, size: Long): ByteArray {
            range(offset, size)
            require(size <= Int.MAX_VALUE)
            return data.copyOfRange(offset.toInt(), (offset + size).toInt())
        }

        fun text(offset: Long, size: Long): String = bytes(offset, size).toString(Charsets.UTF_8).trimEnd(0.toChar())
    }

    fun inspectBoot(file: File): Result = runCatching {
        require(file.isFile && file.length() in 4096..32 * 1024 * 1024) { "init_boot 文件大小无效" }
        val image = file.readBytes()
        require(image.size >= 4096 + 64)
        val reader = Reader(image)
        val originalBytes = reader.u64((image.size - 52).toLong())
        val vbmetaOffset = reader.u64((image.size - 44).toLong())
        val vbmetaBytes = reader.u64((image.size - 36).toLong())
        require(reader.text((image.size - 64).toLong(), 4) == "AVBf") { "init_boot 缺少 AVB 页脚" }
        require(originalBytes > 0 && vbmetaOffset == originalBytes && vbmetaBytes > 0 &&
            originalBytes + vbmetaBytes <= image.size - 64L) { "init_boot AVB 页脚范围无效" }
        val vbmeta = parseVbmeta(reader.bytes(vbmetaOffset, vbmetaBytes))
        val descriptor = vbmeta.hashes.singleOrNull { it.partition == "init_boot" }
            ?: error("init_boot 内嵌签名缺少唯一 init_boot 摘要")
        require(originalBytes <= Int.MAX_VALUE && descriptor.imageBytes <= image.size) { "签名原始大小超出镜像范围" }
        val sizeConsistent = descriptor.imageBytes == originalBytes
        val digest = MessageDigest.getInstance(digestName(descriptor.algorithm))
        digest.update(descriptor.salt)
        digest.update(image, 0, descriptor.imageBytes.toInt())
        val contentValid = sizeConsistent && MessageDigest.isEqual(digest.digest(), descriptor.digest)
        var stockFailure = ""
        val stockSha1 = runCatching { readStockSha1(image, originalBytes) }
            .onFailure { stockFailure = it.message ?: it.javaClass.simpleName }.getOrNull()
        Result(vbmeta.signatureValid, contentValid, originalBytes, vbmeta.publicKeySha256, stockSha1,
            (if (vbmeta.signatureValid && contentValid) "AVB 原始内容、签名及页脚均匹配" else "AVB 签名或原始内容不匹配；${vbmeta.detail}") +
                (if (stockFailure.isNotEmpty()) "；ramdisk：$stockFailure" else ""))
    }.getOrElse {
        Result(false, false, 0, "", null, "init_boot 校验失败：${it.message ?: it.javaClass.simpleName}")
    }

    fun chainKeys(file: File): Map<String, String> = runCatching {
        require(file.isFile && file.length() in 256..4 * 1024 * 1024) { "vbmeta 文件大小无效" }
        val vbmeta = parseVbmeta(file.readBytes())
        require(vbmeta.signatureValid) { "vbmeta 签名无效：${vbmeta.detail}" }
        vbmeta.chains.mapValues { (_, key) -> sha256(key) }
    }.getOrElse { emptyMap() }

    fun publicKeySha256(file: File): String = inspectBoot(file).publicKeySha256

    private fun parseVbmeta(data: ByteArray): Vbmeta {
        require(data.size >= 256)
        val reader = Reader(data)
        require(reader.text(0, 4) == "AVB0") { "缺少 AVB0 头" }
        require(reader.u32(4) == 1L) { "不支持的 AVB 主版本" }
        val authBytes = reader.u64(12)
        val auxiliaryBytes = reader.u64(20)
        val algorithm = reader.u32(28)
        val hashOffset = reader.u64(32)
        val hashBytes = reader.u64(40)
        val signatureOffset = reader.u64(48)
        val signatureBytes = reader.u64(56)
        val keyOffset = reader.u64(64)
        val keyBytes = reader.u64(72)
        val descriptorsOffset = reader.u64(96)
        val descriptorsBytes = reader.u64(104)
        require(authBytes <= data.size && auxiliaryBytes <= data.size - 256 - authBytes) { "AVB 块大小无效" }
        val auxiliaryBase = 256L + authBytes
        require(hashOffset + hashBytes <= authBytes && signatureOffset + signatureBytes <= authBytes) { "AVB 认证字段越界" }
        require(keyOffset + keyBytes <= auxiliaryBytes) { "AVB 公钥字段越界" }
        reader.range(auxiliaryBase + descriptorsOffset, descriptorsBytes, auxiliaryBase + auxiliaryBytes)
        val publicKey = reader.bytes(auxiliaryBase + keyOffset, keyBytes)
        val signedData = data.copyOf(256) + reader.bytes(auxiliaryBase, auxiliaryBytes)
        val digestAlgorithm = when (algorithm) {
            1L, 2L, 3L, 4L -> "SHA-256"
            5L, 6L, 7L, 8L -> "SHA-512"
            else -> "NONE"
        }
        require(digestAlgorithm != "NONE") { "不支持的 AVB 签名算法 $algorithm" }
        val expectedHash = MessageDigest.getInstance(digestAlgorithm).digest(signedData)
        val actualHash = reader.bytes(256 + hashOffset, hashBytes)
        require(MessageDigest.isEqual(expectedHash, actualHash)) { "AVB 签名数据摘要不匹配" }
        val bits = Reader(publicKey).u32(0)
        require(bits in 2048..8192 && bits % 1024 == 0L && publicKey.size.toLong() == 8 + bits / 4 && signatureBytes == bits / 8) { "AVB 公钥格式无效" }
        val modulus = BigInteger(1, publicKey.copyOfRange(8, 8 + bits.toInt() / 8))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        val signatureAlgorithm = when (algorithm) {
            1L, 2L -> "SHA256withRSA"
            5L, 6L -> "SHA512withRSA"
            else -> null
        }
        var signatureFailure = ""
        val signatureValid = signatureAlgorithm != null && runCatching {
            val verifier = Signature.getInstance(signatureAlgorithm)
            verifier.initVerify(key)
            verifier.update(signedData)
            verifier.verify(reader.bytes(256 + signatureOffset, signatureBytes))
        }.onFailure { signatureFailure = it.message ?: it.javaClass.simpleName }.getOrDefault(false)
        val hashes = mutableListOf<HashDescriptor>()
        val chains = linkedMapOf<String, ByteArray>()
        var cursor = auxiliaryBase + descriptorsOffset
        val end = cursor + descriptorsBytes
        while (cursor < end) {
            reader.range(cursor, 16, end)
            val tag = reader.u64(cursor)
            val size = reader.u64(cursor + 8)
            cursor += 16
            reader.range(cursor, size, end)
            when (tag) {
                2L -> {
                    require(size >= 132) { "AVB Hash 描述符不完整" }
                    val imageBytes = reader.u64(cursor)
                    val algorithmName = reader.text(cursor + 8, 32)
                    val nameBytes = reader.u32(cursor + 40)
                    val saltBytes = reader.u32(cursor + 44)
                    val digestBytes = reader.u32(cursor + 48)
                    require(nameBytes in 1..128 && saltBytes <= 64 && digestBytes in 32..64 &&
                        116 + nameBytes + saltBytes + digestBytes <= size) { "AVB Hash 描述符字段无效" }
                    val name = reader.text(cursor + 116, nameBytes)
                    val salt = reader.bytes(cursor + 116 + nameBytes, saltBytes)
                    val digest = reader.bytes(cursor + 116 + nameBytes + saltBytes, digestBytes)
                    hashes += HashDescriptor(imageBytes, algorithmName, name, salt, digest)
                }
                4L -> {
                    require(size >= 76) { "AVB 链式描述符不完整" }
                    val nameBytes = reader.u32(cursor + 4)
                    val keySize = reader.u32(cursor + 8)
                    require(nameBytes in 1..128 && keySize > 0 && 76 + nameBytes + keySize <= size) {
                        "AVB 链式描述符字段无效"
                    }
                    val name = reader.text(cursor + 76, nameBytes)
                    val key = reader.bytes(cursor + 76 + nameBytes, keySize)
                    require(chains.put(name, key) == null) { "AVB 链式分区重复：$name" }
                }
            }
            cursor += size
        }
        require(cursor == end) { "AVB 描述符尾部不完整" }
        return Vbmeta(data, signatureValid, publicKey, sha256(publicKey), hashes, chains,
            if (signatureValid) "签名有效" else "算法=$algorithm，密钥=$bits 位，验签失败${if (signatureFailure.isNotEmpty()) "：$signatureFailure" else "（不支持或返回不匹配）"}")
    }

    private fun digestName(value: String): String = when (value) {
        "sha256" -> "SHA-256"
        "sha512" -> "SHA-512"
        else -> throw IllegalArgumentException("不支持的镜像摘要算法 $value")
    }

    private fun readStockSha1(image: ByteArray, originalBytes: Long): String? {
        val reader = Reader(image)
        require(reader.text(0, 8) == "ANDROID!") { "缺少 Android boot 头" }
        val headerBytes = reader.u32(20, true)
        val ramdiskBytes = reader.u32(12, true)
        require(headerBytes in 48..4096 && reader.u32(40, true) >= 4) { "Android boot 头无效" }
        val offset = (headerBytes + 4095) / 4096 * 4096
        require(ramdiskBytes >= 16 && offset + ramdiskBytes <= originalBytes) { "ramdisk 范围无效" }
        require(reader.u32(offset, true) == 0x184c2102L) { "ramdisk 不是支持的 LZ4 格式" }
        val packedBytes = reader.u32(offset + 4, true)
        require(packedBytes == ramdiskBytes - 8) { "LZ4 块长度无效" }
        val raw = lz4(reader.bytes(offset + 8, packedBytes))
        val cpio = Reader(raw)
        var cursor = 0L
        while (cpio.text(cursor, 6) in setOf("070701", "070702")) {
            val sizes = (0 until 13).map { cpio.text(cursor + 6 + it * 8, 8).toLong(16) }
            val fileBytes = sizes[6]
            val nameBytes = sizes[11]
            require(nameBytes in 1..4096 && fileBytes <= raw.size)
            val nameStart = cursor + 110
            val name = cpio.text(nameStart, nameBytes - 1)
            cursor = (nameStart + nameBytes + 3) and -4L
            cpio.range(cursor, fileBytes)
            if (name == "stock_image.sha1") {
                require(fileBytes == 40L)
                val hash = cpio.text(cursor, 40).lowercase()
                require(Regex("[a-f0-9]{40}").matches(hash)) { "stock_image.sha1 格式无效" }
                return hash
            }
            cursor = (cursor + fileBytes + 3) and -4L
            if (name == "TRAILER!!!") break
        }
        return null
    }

    private fun lz4(source: ByteArray): ByteArray {
        val reader = Reader(source)
        val output = ByteArrayOutput(64 * 1024 * 1024)
        var cursor = 0L
        while (cursor < source.size) {
            val token = source[cursor.toInt()].toInt() and 255
            cursor++
            var literals = token ushr 4
            if (literals == 15) cursor = extendedLength(source, cursor) { literals += it }
            reader.range(cursor, literals.toLong())
            output.append(source, cursor.toInt(), literals)
            cursor += literals
            if (cursor == source.size.toLong()) break
            val distance = (reader.u32(cursor, true) and 0xffff).toInt()
            cursor += 2
            require(distance in 1..output.size) { "LZ4 匹配距离无效" }
            var match = (token and 15) + 4
            if ((token and 15) == 15) cursor = extendedLength(source, cursor) { match += it }
            repeat(match) {
                output.append(output[output.size - distance])
            }
        }
        return output.bytes()
    }

    private fun extendedLength(source: ByteArray, start: Long, add: (Int) -> Unit): Long {
        var cursor = start
        while (true) {
            require(cursor < source.size) { "LZ4 扩展长度不完整" }
            val value = source[cursor.toInt()].toInt() and 255
            cursor++
            add(value)
            if (value != 255) return cursor
        }
    }

    private class ByteArrayOutput(private val maximum: Int) {
        private var data = ByteArray(4096)
        var size: Int = 0
            private set

        fun append(value: Byte) {
            ensure(1)
            data[size++] = value
        }

        fun append(source: ByteArray, offset: Int, count: Int) {
            ensure(count)
            source.copyInto(data, size, offset, offset + count)
            size += count
        }

        operator fun get(index: Int): Byte = data[index]

        fun bytes(): ByteArray = data.copyOf(size)

        private fun ensure(count: Int) {
            require(count >= 0 && size + count <= maximum) { "解压数据超过 64 MiB" }
            if (size + count > data.size) data = data.copyOf((data.size * 2).coerceAtLeast(size + count))
        }
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
