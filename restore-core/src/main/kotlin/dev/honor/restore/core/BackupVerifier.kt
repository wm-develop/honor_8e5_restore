package dev.honor.restore.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

object BackupVerifier {
    private val safeName = Regex("[A-Za-z0-9_.-]+")

    fun verify(directory: File): BackupSet {
        require(directory.isDirectory) { "备份目录不存在" }
        val ota = File(directory, "ota-complete.json")
        val normal = File(directory, "backup.json")
        require(ota.isFile.xor(normal.isFile)) { "目录必须且只能包含 ota-complete.json 或 backup.json 中的一种备份" }
        return if (ota.isFile) verifyOta(directory, ota) else verifyNormal(directory, normal)
    }

    private fun verifyNormal(directory: File, manifest: File): NormalBackup {
        val json = readJson(manifest)
        require(json.getString("format") == "bootforge-slot-backup-v2.2") { "不支持的普通备份格式" }
        require(json.getString("status") in setOf("complete", "complete_with_warnings")) { "普通备份未完成，不能作为自动恢复来源" }
        require(!json.optBoolean("partition_writes", true)) { "普通备份的写入标记异常" }
        val records = records(json.getJSONArray("files"))
        records.values.forEach { verifyFile(directory, it) }
        val sumsFile = File(directory, "SHA256SUMS")
        require(sumsFile.isFile) { "普通备份缺少 SHA256SUMS" }
        val sums = linkedMapOf<String, String>()
        sumsFile.readLines().filter(String::isNotBlank).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            require(parts.size == 2 && Hashes.validSha256(parts[0]) && safeName.matches(parts[1])) { "SHA256SUMS 记录无效" }
            require(sums.put(parts[1], parts[0]) == null) { "SHA256SUMS 存在重复文件" }
        }
        val expected = records.keys + manifest.name
        require(sums.keys == expected) { "SHA256SUMS 与备份清单文件集合不一致" }
        sums.forEach { (name, expectedHash) ->
            val file = checkedFile(directory, name)
            require(Hashes.sha256(file) == expectedHash) { "备份文件哈希不一致：$name" }
        }
        val slot = normalizeSlot(json.getString("running_slot"))
        return NormalBackup(directory.name, directory, directory.name, json.getLong("requested_at_millis"), records, slot)
    }

    private fun verifyOta(directory: File, manifest: File): OtaBackup {
        val json = readJson(manifest)
        require(json.getString("format") == "hnubl-ota-v1") { "不支持的 OTA 备份格式" }
        require(json.getString("status") == "complete" && json.getString("lastPhase") == "complete") { "OTA 任务未完成，不能作为自动恢复来源" }
        require(json.getString("operation") == "FULL") { "仅完整保锁 OTA 备份可用于恢复规划" }
        require(json.optBoolean("ramSourcesFrozen", false)) { "OTA 来源固定标记缺失" }
        require(json.getString("request").split(':').last() == "FAKE_LOCK") { "仅支持假回锁模式产生的 OTA 备份" }
        val slot = normalizeSlot(json.getString("currentSlot"))
        val records = records(json.getJSONArray("backups"))
        records.values.forEach { verifyFile(directory, it) }
        val activeRecord = records["slot_active_$slot"] ?: error("OTA 备份缺少当前槽标记")
        require(activeRecord.bytes == 1L && File(directory, activeRecord.name).readText() == slot) { "OTA 当前槽标记与清单不一致" }
        require(records["slot_active_${if (slot == "a") "b" else "a"}"] == null) { "OTA 同时包含冲突的槽位标记" }
        val writes = json.getJSONArray("writes").objects().map(::write)
        require(writes.isNotEmpty()) { "完整 OTA 没有写入记录" }
        require(writes.map { it.target }.distinct().size == writes.size) { "OTA 写入目标重复" }
        OtaWritePolicy.requireFullWrites(writes, slot)
        val journal = File(directory, "ota-write-journal.log")
        require(journal.isFile) { "OTA 写入日志缺失" }
        val expectedJournal = writes.flatMap { listOf("WRITE_STARTED=${it.target}", "WRITE_VERIFIED=${it.target}") }
        require(journal.readLines().filter(String::isNotBlank) == expectedJournal) { "OTA 写入日志与完成清单不一致" }
        val preparedRecord = records["ota-backup.json"] ?: error("OTA 准备清单缺失")
        val prepared = readJson(File(directory, preparedRecord.name))
        require(prepared.getString("status") == "backups_complete" && prepared.getString("lastPhase") == "backups_complete") { "OTA 准备清单未完整备份" }
        require(prepared.getLong("clickedAtEpochMillis") == json.getLong("clickedAtEpochMillis") &&
            normalizeSlot(prepared.getString("currentSlot")) == slot && prepared.getString("request") == json.getString("request")) { "OTA 准备与完成清单身份不一致" }
        require(prepared.getJSONArray("writes").objects().map(::write) == writes) { "OTA 准备清单与完成清单的写入记录不一致" }
        val snapshots = linkedMapOf<String, String>()
        json.getJSONArray("snapshots").objects().forEach { item ->
            val identity = item.getString("identity")
            val name = identity.substringBefore('|')
            val hash = item.getString("sha256")
            require(safeName.matches(name) && Hashes.validSha256(hash)) { "OTA 快照身份无效" }
            require(snapshots.put(name, "$identity|$hash") == null) { "OTA 快照目标重复" }
        }
        for (base in listOf("abl", "init_boot")) for (suffix in listOf("a", "b")) {
            val name = "${base}_$suffix"
            val record = records["$name.img"] ?: error("OTA 备份缺少 $name.img")
            val snapshot = snapshots[name] ?: error("OTA 快照缺少 $name")
            require(snapshot.endsWith("|${record.sha256}")) { "OTA 快照与公开备份哈希不一致：$name" }
        }
        val packageType = when (File(directory, ".ota-type").takeIf { it.length() <= 16 && it.isFile }?.readText()?.trim()) {
            null -> OtaPackageType.UNSET
            "major" -> OtaPackageType.MAJOR
            "patch" -> OtaPackageType.PATCH
            else -> throw IllegalArgumentException("OTA 类型标记无效，请重新导入并选择大版本或小补丁")
        }
        return OtaBackup(directory.name, directory, directory.name, json.getLong("clickedAtEpochMillis"), records, slot, writes, snapshots, packageType)
    }

    private fun write(item: JSONObject): OtaWrite {
        val target = item.getString("target")
        val source = item.getString("source")
        require(safeName.matches(target) && safeName.matches(source)) { "OTA 写入文件名无效" }
        val hash = item.getString("sha256")
        require(Hashes.validSha256(hash)) { "OTA 写入哈希无效" }
        return OtaWrite(target, source, positive(item, "bytes"), hash)
    }

    private fun records(array: JSONArray): Map<String, ImageRecord> {
        val result = linkedMapOf<String, ImageRecord>()
        array.objects().forEach { item ->
            val name = item.getString("name")
            require(safeName.matches(name)) { "备份文件名无效：$name" }
            val hash = item.getString("sha256")
            require(Hashes.validSha256(hash)) { "备份 SHA-256 无效：$name" }
            val record = ImageRecord(name, positive(item, "bytes"), hash)
            require(result.put(name, record) == null) { "备份清单存在重复文件：$name" }
        }
        return result
    }

    private fun verifyFile(directory: File, record: ImageRecord) {
        val file = checkedFile(directory, record.name)
        require(file.length() == record.bytes) { "备份文件长度不一致：${record.name}" }
        require(Hashes.sha256(file) == record.sha256) { "备份文件哈希不一致：${record.name}" }
    }

    private fun checkedFile(directory: File, name: String): File {
        require(safeName.matches(name)) { "备份文件名无效：$name" }
        val file = File(directory, name)
        require(file.canonicalFile.parentFile == directory.canonicalFile && file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "备份文件缺失、越界或不是普通文件：$name"
        }
        return file
    }

    private fun readJson(file: File): JSONObject {
        require(file.isFile && file.length() in 2..8 * 1024 * 1024) { "备份清单缺失或过大：${file.name}" }
        return JSONObject(file.readText())
    }

    private fun positive(json: JSONObject, key: String): Long = json.getLong(key).also { require(it > 0) { "$key 必须大于零" } }

    private fun normalizeSlot(value: String): String = when (value) {
        "a", "_a" -> "a"
        "b", "_b" -> "b"
        else -> throw IllegalArgumentException("备份槽位无效：$value")
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
