package dev.honor.restore

import android.content.Context
import dev.honor.restore.core.BootImageVerifier
import dev.honor.restore.core.DevicePartition
import dev.honor.restore.core.DeviceSnapshot
import dev.honor.restore.core.GptSnapshot
import dev.honor.restore.core.Hashes
import java.io.File

data class ScannedDevice(
    val snapshot: DeviceSnapshot,
    val images: Map<String, File>,
    val gptFile: File,
    val directory: File,
)

class DeviceScanner(private val context: Context, private val shell: SuShell, private val directory: File) {
    private lateinit var worker: File
    private lateinit var gpttool: File
    private lateinit var busybox: File

    fun scan(extraTargets: Set<String>): ScannedDevice {
        check(directory.isDirectory || directory.mkdirs()) { "设备扫描目录创建失败" }
        stage()
        val names = linkedSetOf("abl_a", "abl_b", "init_boot_a", "init_boot_b", "vbmeta_a", "vbmeta_b", "efisp", "userdata")
        names += extraTargets.filter { it != "efisp" && !it.startsWith("abl_") && !it.startsWith("init_boot_") }
        val identities = names.mapNotNull { name -> identity(name, required = name != "efisp") }.associateBy { it.substringBefore('|') }
        val majorMinors = identities.values.map { it.split('|')[3] }
        require(majorMinors.distinct().size == majorMinors.size) { "多个分区名指向同一块设备，已停止扫描" }
        val properties = properties()
        val images = mutableMapOf<String, File>()
        val partitions = mutableMapOf<String, DevicePartition>()
        identities.filterKeys { it != "userdata" }.forEach { (name, identity) ->
            val fields = identity.split('|')
            val bytes = fields[4].toLong()
            require(bytes in 1..128L * 1024 * 1024) { "$name 超过 128 MiB 读取范围" }
            val file = File(directory, "$name.img")
            val command = "${quote(worker.absolutePath)} read-range ${quote(fields[1])} 0 $bytes"
            val result = shell.capture(command, file)
            check(result.exitCode == 0 && file.length() == bytes) { "$name 直接读取失败：${result.output}" }
            require(identity(name, true) == identity) { "$name 的身份在读取期间发生变化" }
            images[name] = file
            val prefixHashes = if (name.startsWith("abl_") && bytes > 642136L) {
                mapOf(642136L to Hashes.sha256Prefix(file, 642136L))
            } else emptyMap()
            val stockSha1 = if (name.startsWith("init_boot_")) BootImageVerifier.inspectBoot(file).stockImageSha1 else null
            partitions[name] = DevicePartition(name, identity, bytes, Hashes.sha256(file), prefixHashes, stockSha1)
        }
        val initBootChainKeys = listOf("a", "b").mapNotNull { slot ->
            BootImageVerifier.chainKeys(images.getValue("vbmeta_$slot"))["init_boot"]?.let { slot to it }
        }.toMap()
        check(initBootChainKeys.size == 2) { "双槽 vbmeta 的 init_boot 链式公钥读取或验签失败" }
        val userdata = identities.getValue("userdata").split('|')
        val disk = diskFor(userdata[2])
        identities["efisp"]?.let { require(diskFor(it.split('|')[2]) == disk) { "userdata 与 efisp 不在同一物理磁盘" } }
        val verify = shell.run("${quote(gpttool.absolutePath)} verify ${quote(disk)}")
        check(verify.exitCode == 0 && verify.output.lineSequence().any { it == "GPT_VALID=1" }) { "当前 GPT 校验失败：${verify.output}" }
        val gptFile = File(directory, "current-gpt.raw")
        val exported = shell.capture("${quote(gpttool.absolutePath)} export ${quote(disk)}", gptFile)
        check(exported.exitCode == 0 && gptFile.isFile) { "当前 GPT 导出失败：${exported.output}" }
        val gpt = GptSnapshot.parse(gptFile)
        val partitionIndex = shell.run("cat /sys/class/block/${userdata[2].substringAfterLast('/')}/partition").output.trim()
        check(partitionIndex.toIntOrNull() != null) { "userdata 分区编号无效" }
        val inspected = shell.run("${quote(gpttool.absolutePath)} inspect ${quote(disk)} $partitionIndex")
        check(inspected.exitCode == 0 && inspected.output.lineSequence().any { it == "RESULT=success" }) { "userdata/efisp 几何检查失败：${inspected.output}" }
        val dataSource = dataSource()
        val f2fs = shell.run("${quote(worker.absolutePath)} inspect-f2fs /data ${quote(dataSource)}")
        val values = f2fs.output.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        check(f2fs.exitCode == 0 && values["FS_TYPE"] == "f2fs" && values["FS_CP_CRC_VALID"] == "1" && values["FS_MATCH"] == "1") {
            "F2FS 与挂载映射检查失败：${f2fs.output}"
        }
        val otaDirectories = otaDirectories()
        val snapshot = DeviceSnapshot(properties.getValue("SLOT"), properties.getValue("DISPLAY"), properties.getValue("FINGERPRINT"),
            partitions, disk, gpt, values.getValue("FS_BYTES").toLong(), properties["OTA_MARKER"].orEmpty(),
            otaDirectories.first, otaDirectories.second, RestoreTransaction.isIncomplete(shell), initBootChainKeys)
        return ScannedDevice(snapshot, images, gptFile, directory)
    }

    private fun stage() {
        val native = File(context.applicationInfo.nativeLibraryDir)
        fun install(name: String, destination: String): File {
            val source = File(native, name)
            check(source.isFile && source.length() > 0) { "缺少原生工具 $name" }
            val target = File(directory, destination)
            source.copyTo(target, overwrite = true)
            check(target.setExecutable(true, true) && target.length() == source.length()) { "原生工具准备失败：$name" }
            return target
        }
        worker = install("librestore_partition_worker.so", "partition_worker")
        gpttool = install("librestore_gpttool.so", "gpttool")
        busybox = install("librestore_busybox.so", "busybox")
        val selfTest = shell.run("${quote(gpttool.absolutePath)} --self-test")
        check(selfTest.exitCode == 0 && selfTest.output.contains("SELF_TEST_RESULT=PASS")) { "GPT 工具自检失败：${selfTest.output}" }
    }

    private fun properties(): Map<String, String> {
        val script = """
            printf 'SLOT='; getprop ro.boot.slot_suffix
            printf 'SLOT_ALT='; getprop ro.boot.slot
            printf 'CMDLINE_SLOT='
            for arg in $(${quote(busybox.absolutePath)} cat /proc/cmdline); do
                case "${'$'}arg" in androidboot.slot_suffix=*) printf '%s' "${'$'}{arg#*=}";; esac
            done
            printf '\nBOOTCONFIG_SLOT='
            if [ -r /proc/bootconfig ]; then
                ${quote(busybox.absolutePath)} awk '${'$'}1 == "androidboot.slot_suffix" {gsub(/\"/, "", ${'$'}3); printf "%s", ${'$'}3}' /proc/bootconfig
            fi
            printf '\nDISPLAY='; getprop ro.build.display.id
            printf 'FINGERPRINT='; getprop ro.build.fingerprint
            printf 'OTA_MARKER='; if [ -f /data/adb/hnubl/freeotaok ]; then cat /data/adb/hnubl/freeotaok; fi
        """.trimIndent()
        val result = shell.run(script)
        check(result.exitCode == 0) { "设备属性读取失败：${result.output}" }
        val values = result.output.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        return values + mapOf("SLOT" to dev.honor.restore.core.SlotEvidence.normalize(values))
    }

    fun currentIdentity(name: String): String = checkNotNull(identity(name, true))

    private fun identity(name: String, required: Boolean): String? {
        require(Regex("[a-z0-9_]+").matches(name))
        val script = """
            found=
            link=
            for base in /dev/block/by-name /dev/block/bootdevice/by-name; do
                candidate=$(${quote(busybox.absolutePath)} readlink -f "${'$'}base/$name") || continue
                [ -b "${'$'}candidate" ] || continue
                case "${'$'}candidate" in /dev/block/*) :;; *) exit 21;; esac
                [ -z "${'$'}found" ] || [ "${'$'}found" = "${'$'}candidate" ] || exit 22
                found=${'$'}candidate
                [ -n "${'$'}link" ] || link=${'$'}base/$name
            done
            [ -n "${'$'}found" ] || exit 23
            node=${'$'}{found##*/}
            sys=$(${quote(busybox.absolutePath)} readlink -f "/sys/class/block/${'$'}node") || exit 24
            [ -f "${'$'}sys/partition" ] || exit 25
            partname=$(${quote(busybox.absolutePath)} awk -F= '${'$'}1 == "PARTNAME" {print ${'$'}2}' "${'$'}sys/uevent") || exit 26
            [ "${'$'}partname" = "$name" ] || exit 27
            mm=$(${quote(busybox.absolutePath)} cat "${'$'}sys/dev") || exit 28
            bytes=$(${quote(busybox.absolutePath)} blockdev --getsize64 "${'$'}link") || exit 29
            start=$(${quote(busybox.absolutePath)} cat "${'$'}sys/start") || exit 30
            sector=$(${quote(busybox.absolutePath)} cat "${'$'}{sys%/*}/queue/logical_block_size") || exit 31
            printf '$name|%s|%s|%s|%s|%s|%s\n' "${'$'}link" "${'$'}found" "${'$'}mm" "${'$'}bytes" "${'$'}start" "${'$'}sector"
        """.trimIndent()
        val result = shell.run(script)
        if (!required && result.exitCode != 0) return null
        check(result.exitCode == 0) { "$name 分区身份读取失败：${result.output}" }
        val identity = result.output.lineSequence().singleOrNull { it.startsWith("$name|") }
        checkNotNull(identity) { "$name 分区身份输出无效：${result.output}" }
        return identity
    }

    private fun diskFor(device: String): String {
        val node = device.substringAfterLast('/')
        val result = shell.run("${quote(busybox.absolutePath)} readlink -f /sys/class/block/$node")
        check(result.exitCode == 0) { "磁盘身份读取失败：${result.output}" }
        val path = result.output.trim()
        val parent = path.substringBeforeLast('/').substringAfterLast('/')
        require(Regex("sd[a-z]+").matches(parent)) { "不支持的物理磁盘：$device" }
        return "/dev/block/$parent"
    }

    private fun dataSource(): String {
        val command = "${quote(busybox.absolutePath)} awk '${'$'}5 == \"/data\" {for (i = 6; i <= NF; i++) if (${'$'}i == \"-\") print ${'$'}(i + 2)}' /proc/1/mountinfo"
        val result = shell.run(command)
        val sources = result.output.lineSequence().filter { it.startsWith("/dev/block/") }.distinct().toList()
        check(result.exitCode == 0 && sources.size == 1) { "/data 解密映射无法唯一识别：${result.output}" }
        return sources.single()
    }

    private fun otaDirectories(): Pair<Int?, List<String>> {
        val script = """
            if [ -d /storage/emulated/0/Download/hnubl ]; then
                for d in /storage/emulated/0/Download/hnubl/hnubl_ota_backup_*; do
                    [ -d "${'$'}d" ] || continue
                    printf '%s\n' "${'$'}{d##*/}"
                done
            fi
        """.trimIndent()
        val result = shell.run(script)
        if (result.exitCode != 0) return null to emptyList()
        val names = result.output.lineSequence().map { it.trim() }
            .filter { Regex("hnubl_ota_backup_[0-9]{8}_[0-9]{6}").matches(it) }.distinct().sorted().toList()
        return names.size to names
    }

    private fun quote(value: String): String = SuShell.quote(value)
}
