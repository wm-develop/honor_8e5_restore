# 荣耀 8E5 假回锁恢复教程：通过 ADB 恢复原始 GPT、ABL 和 init_boot

> 本教程根据一次真实恢复过程整理，目标是：**撤销假回锁相关修改、去除 root，并尽量保留用户数据。**
>
> 示例设备为 `HNAAP / AAP-AN00`，原系统为 `11.0.0.110(SP5C00E110R104P1)`，后续官方小补丁更新为 `SP7C00E110R104P1`。
>
> **不要照搬其中的磁盘偏移、分区容量和哈希到其他机型。**这些值只适用于本文所示设备及这两份备份。写错磁盘或偏移可能造成无法启动或数据损坏。

## 一、开始前必须理解的事项

### 1. 两份备份的作用不能弄反

本文使用：

| 备份目录 | 状态 | 用途 |
| --- | --- | --- |
| `ublbackup_20261029_233101` | 假回锁前的干净备份 | **恢复来源** |
| `ublbackup_20261030_131440` | 假回锁后的备份 | **核对当前状态，不用于恢复** |

判断依据是实际文件比较，而不是只看目录时间：

- 干净备份中的 `gpt_sda.raw` 哈希为 `f1ebe397...`；
- 假回锁后的 GPT 哈希为 `9b2d2213...`；
- 当前手机必须先与第二份备份一致，才能使用本文流程。

### 2. 本流程会恢复什么？

- `sda` 主、备 GPT；
- `abl_a`；
- `init_boot_a`。

本例中，`abl_b` 和 `init_boot_b` 本来就没有修改，因此不需要刷写。

### 3. 本流程不会做什么？

- 不格式化 userdata；
- 不恢复出厂设置；
- 不执行官方回锁；
- 不修改 B 槽镜像；
- 不主动扩大 F2FS 文件系统。

恢复 GPT 后，原 `efisp` 占用的空间会在**分区表层面**重新归入 userdata，但 F2FS 不会仅因此自动扩容。

### 4. 数据备份仍然必要

`ublbackup` 只包含 ABL、init_boot 和 GPT，**不包含照片、应用和个人文件**。

原始磁盘写入始终存在风险。开始前应当：

- 将重要个人数据另外备份到电脑或外部存储；
- 将两份 `ublbackup` 也保存在手机之外；
- 使用稳定 USB 连接并保持足够电量；
- 暂停系统更新和其他分区工具；
- 每一步核对输出后再继续。

## 二、准备 ADB root 环境

本例使用 SukiSU。

### 1. 如果提示 `su: inaccessible or not found`

这说明当前 ADB shell 找不到 `su`，不一定是授权被拒绝。

可以在 SukiSU 设置中开启 **ADB Root**，然后执行：

```powershell
adb reconnect
adb shell id -u
```

如果返回：

```text
0
```

说明 ADB shell 已是 root。后续命令**不再需要 `su -c`**。

恢复完成并重启后，`id -u` 应重新变成 `2000`。不需要使用时，建议关闭 ADB Root。

### 2. 核对设备、槽位和磁盘

执行：

```powershell
adb shell "id -u; getprop ro.product.device; getprop ro.build.display.id; getprop ro.boot.slot_suffix; uname -r"
adb shell "readlink -f /dev/block/by-name/userdata; readlink -f /dev/block/by-name/efisp; blockdev --getsize64 /dev/block/sda; cat /sys/class/block/sda/queue/logical_block_size"
```

本例预期：

```text
0
HNAAP
AAP-AN00 11.0.0.110(SP5C00E110R104P1)
_a
6.12.58-android16-6-g3a7ac1674240-abogki541502914-4k
/dev/block/sda65
/dev/block/sda66
509884760064
4096
```

如果磁盘、槽位、版本或布局不同，不要直接继续。

## 三、准备恢复工具

应用已经生成过工具时，可以直接复用：

```powershell
adb shell "mkdir -p /data/local/tmp/hnubl-restore/tools && cp /data/data/net.hawyn.honor8e5ubl/files/run/gpttool /data/local/tmp/hnubl-restore/tools/gpttool && cp /data/data/net.hawyn.honor8e5ubl/files/run/partition_worker /data/local/tmp/hnubl-restore/tools/partition_worker && chmod 700 /data/local/tmp/hnubl-restore/tools/gpttool /data/local/tmp/hnubl-restore/tools/partition_worker && /data/local/tmp/hnubl-restore/tools/gpttool --self-test"
```

必须看到：

```text
SELF_TEST_RESULT=PASS
RESULT=success
```

如果这些文件不存在，应使用与当前 hnubl 版本匹配的原生工具，不要随意替换来源不明的二进制。

## 四、核对当前 GPT 和 F2FS

### 1. 验证并导出当前 GPT

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool verify /dev/block/sda"
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool export /dev/block/sda > /data/local/tmp/hnubl-restore/current-sda.raw"
adb shell "sha256sum /data/local/tmp/hnubl-restore/current-sda.raw; wc -c < /data/local/tmp/hnubl-restore/current-sda.raw"
```

必须满足：

- `GPT_VALID=1`
- `DISK_READ_MODE=direct`
- `RESULT=success`
- 文件大小为 `37376`
- 当前哈希为：

```text
9b2d221315abe406b8e72587cb95ce8668c3f0afc23b073dad280c61fed8b3a2
```

这证明当前 GPT 与假回锁后的第二份备份一致。若不同，停止操作。

### 2. 确认 `/data` 的解密映射

```powershell
adb shell "grep ' /data ' /proc/1/mountinfo"
```

本例为：

```text
/dev/block/dm-88
```

实际设备可能不同，应以输出为准。

### 3. 检查 F2FS

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/partition_worker inspect-f2fs /data /dev/block/dm-88"
```

本例关键结果：

```text
DEVICE_BYTES=490811162624
FS_TYPE=f2fs
FS_BYTES=490811162624
FS_CP_CRC_VALID=1
FS_MATCH=1
RESULT=success
```

这表示：

- 当前 F2FS 与当前 userdata 容量一致；
- 超级块和 checkpoint 校验正常；
- 恢复到更大的原始 userdata 后，不会截断当前文件系统。

## 五、推送并校验干净备份

执行：

```powershell
adb shell "mkdir -p /data/local/tmp/hnubl-restore/original-backup"
adb push "path/to/ublbackup_20261029_233101" /data/local/tmp/hnubl-restore/original-backup
```

本例推送后的目录为：

```text
/data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101
```

校验所有备份文件：

```powershell
adb shell "cd /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101 && sha256sum -c SHA256SUMS > /data/local/tmp/hnubl-restore/original-backup-check.log 2>&1 && echo BACKUP_HASHES_OK || { echo BACKUP_HASH_CHECK_FAILED; cat /data/local/tmp/hnubl-restore/original-backup-check.log; }"
```

必须得到：

```text
BACKUP_HASHES_OK
```

再核对干净 GPT：

```powershell
adb shell "sha256sum /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101/gpt_sda.raw"
```

必须为：

```text
f1ebe39743f7299d69705c950b093fefd761d156b8901453f0f565b926963bff
```

## 六、核对恢复源和当前启动镜像

### 1. 核对 GPT 区域和干净镜像

```powershell
adb shell "cd /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101 && sha256sum gpt_sda_primary_header.bin gpt_sda_primary_entries.bin gpt_sda_backup_header.bin gpt_sda_backup_entries.bin init_boot_a.img abl_a.img"
```

必须与下表一致：

| 文件 | SHA-256 前缀 |
| --- | --- |
| `gpt_sda_primary_header.bin` | `84e9a862...` |
| `gpt_sda_primary_entries.bin` | `8ea30ce0...` |
| `gpt_sda_backup_header.bin` | `05c42ee0...` |
| `gpt_sda_backup_entries.bin` | `8ea30ce0...` |
| `init_boot_a.img` | `6a72d74f...` |
| `abl_a.img` | `180fadd5...` |

### 2. 分区节点变化时使用 by-name

备份中的节点可能是 `/dev/block/sde23`，当前却可能变成 `/dev/block/sdf23`。这是磁盘枚举变化，不代表分区错误。

因此后续统一使用：

```text
/dev/block/by-name/abl_a
/dev/block/by-name/init_boot_a
```

不要照搬旧节点名。

### 3. 当前 init_boot 哈希不同怎么办？

如果当前 `init_boot_a` 与第二份备份不同，先确认是否在备份后重新修补过它。

本例中，用户更新过 SukiSU 并重新修补刷入，因此该差异有明确解释，不影响使用更早的干净备份恢复。**如果无法解释差异，应先停止并查明原因。**

## 七、精确恢复 GPT

> 以下偏移只适用于本例的 `sda` 和原始备份。
>
> 每次 `write-range` 都必须返回 `READBACK=match` 和 `RESULT=success`。任何一步失败都停止，不要盲目重试或重启。

### 1. 写入前再次核对当前 GPT

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool export /dev/block/sda > /data/local/tmp/hnubl-restore/prewrite-current-sda.raw && sha256sum /data/local/tmp/hnubl-restore/prewrite-current-sda.raw"
adb shell "blockdev --getro /dev/block/sda"
```

必须：

- 当前 GPT 哈希仍为 `9b2d2213...`；
- `getro` 返回 `0`。

### 2. 先恢复备 GPT

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/partition_worker write-range /dev/block/sda 509882662912 12288 /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101/gpt_sda_backup_entries.bin --execute && /data/local/tmp/hnubl-restore/tools/partition_worker write-range /dev/block/sda 509884755968 4096 /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101/gpt_sda_backup_header.bin --execute"
```

这一步只恢复备 GPT，主 GPT 尚未变化。**此时不要运行整体 GPT 验证，也不要重启。**

### 3. 再恢复主 GPT

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/partition_worker write-range /dev/block/sda 8192 12288 /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101/gpt_sda_primary_entries.bin --execute && /data/local/tmp/hnubl-restore/tools/partition_worker write-range /dev/block/sda 4096 4096 /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101/gpt_sda_primary_header.bin --execute"
```

### 4. 验证整份 GPT

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool verify /dev/block/sda"
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool export /dev/block/sda > /data/local/tmp/hnubl-restore/restored-sda.raw && sha256sum /data/local/tmp/hnubl-restore/restored-sda.raw"
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool inspect /dev/block/sda 65"
```

必须满足：

```text
GPT_VALID=1
RESULT=success
```

最终哈希必须为：

```text
f1ebe39743f7299d69705c950b093fefd761d156b8901453f0f565b926963bff
```

并且：

```text
UD_END=124483071
UD_BYTES=490882465792
EFI_COUNT=0
```

这证明：

- 主备 GPT 均与干净备份一致；
- `efisp` 已从分区表删除；
- 原来的 68 MiB 已在 GPT 层面重新归入 userdata。

**如果最终哈希不同，不要重启。**

## 八、恢复官方 init_boot 和 ABL

### 1. 检查目标只读标志

```powershell
adb shell "blockdev --getro /dev/block/by-name/init_boot_a; blockdev --getro /dev/block/by-name/abl_a"
```

本例均应返回 `0`。若不为 `0`，先停止并查明目标状态。

### 2. 恢复 init_boot_a

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/partition_worker write-range /dev/block/by-name/init_boot_a 0 8388608 /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101/init_boot_a.img --execute"
adb shell "sha256sum /dev/block/by-name/init_boot_a"
```

必须：

```text
READBACK=match
RESULT=success
```

哈希必须为：

```text
6a72d74fa3450ac60e06f604b3d6e1f10f5f72f85d4c0e9f7df63dbf4a53d52c
```

### 3. 恢复 abl_a

```powershell
adb shell "/data/local/tmp/hnubl-restore/tools/partition_worker write-range /dev/block/by-name/abl_a 0 4194304 /data/local/tmp/hnubl-restore/original-backup/ublbackup_20261029_233101/abl_a.img --execute"
adb shell "sha256sum /dev/block/by-name/abl_a"
```

必须：

```text
READBACK=match
RESULT=success
```

哈希必须为：

```text
180fadd525fe90c112a48f1518bed4f5662f87d6b4231ba25176634629394edf
```

## 九、最终检查并重启

执行：

```powershell
adb shell "getprop ro.boot.slot_suffix; sha256sum /dev/block/by-name/init_boot_a /dev/block/by-name/abl_a"
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool verify /dev/block/sda"
adb shell "/data/local/tmp/hnubl-restore/tools/gpttool export /dev/block/sda > /data/local/tmp/hnubl-restore/final-sda.raw && sha256sum /data/local/tmp/hnubl-restore/final-sda.raw"
adb shell sync
```

确认：

- 当前槽位符合预期；
- 两份启动镜像哈希正确；
- GPT 校验通过；
- 最终 GPT 哈希仍为 `f1ebe397...`。

全部满足后，才手动执行：

```powershell
adb reboot
```

不要执行官方回锁、格式化或 `blockdev --rereadpt`。

## 十、重启后如何确认？

执行：

```powershell
adb shell "id -u; getprop ro.boot.slot_suffix; getprop ro.build.display.id; getprop ro.boot.verifiedbootstate; getprop ro.boot.flash.locked"
adb shell "ls -l /dev/block/by-name/efisp 2>&1"
```

理想结果：

- `id -u=2000`；
- `verifiedbootstate=green`；
- `flash.locked=1`；
- `efisp` 显示 `No such file or directory`；
- 应用、照片和内部存储正常。

注意：如果后续安装了官方补丁，`ro.build.display.id` 发生变化是正常的。本例最终由 `SP5...` 更新为 `SP7...`，不表示恢复失败。

## 十一、可能遇到的问题

### 1. 重启后进入了另一个槽位

例如预期进入 A，实际却进入 B。

这通常意味着：

> 启动链先尝试了 A，但未成功，于是自动回退到仍可用的 B。

它不一定意味着硬砖，也不一定意味着用户数据丢失，但**不能据此宣称 A 已恢复成功**。

应当：

- 不要反复切槽；
- 不要先清数据；
- 保留能启动的槽位；
- 检查另一槽是否还有其他启动相关分区或厂商状态需要修复。

本例后来安装了一个官方小补丁，官方更新引擎完成写入和激活后成功进入 A。相比手工切槽，这种方式更可靠。

### 2. Fastboot 显示 `PHONE Unlocked`

这不必然表示：

- root 还在；
- 项目修改版 ABL 还在；
- Fastboot 已允许任意刷写；
- Android 的 locked/green 属性一定错误。

可以结合以下现象判断：

- 是否显示项目定制文字 `device freedom (github hawyn2018)`；
- `fastboot getvar` 是否仍被拒绝；
- Android 中 `verifiedbootstate` 和 `flash.locked` 的状态；
- 当前 ABL 的哈希是否已经核对。

荣耀可能还存在独立的 `rdmode`、Rescue 或厂商锁态通道。**不要仅凭这一行文字就执行官方回锁，回锁可能清除数据。**

如果仍要求 Fastboot 显示也完全恢复原厂状态，建议使用官方全量恢复或售后渠道，而不是继续盲目刷写。

### 3. `fastboot getvar` 返回 `Command not allowed`

这说明当前厂商 Fastboot 限制了该命令，并不等同于设备损坏。

可以先返回系统：

```powershell
fastboot reboot
```

如果命令也不能执行，按照屏幕提示断开 USB，并长按电源键和音量下键退出，不要选择清除数据。

### 4. `fastboot --set-active=a` 提示 `Device does not support slots`

这并不表示手机没有 A/B 槽，只表示当前 Fastboot 没有开放该命令。

不要继续尝试其他来源不明的切槽命令。优先让官方 OTA/更新引擎写入并激活另一槽。

### 5. 中途写入失败或最终哈希不一致

立即停止：

- 不要重启；
- 不要盲目重复写入；
- 不要继续刷 ABL/init_boot；
- 保留完整输出和当前快照；
- 确认失败发生在备 GPT、主 GPT 还是启动镜像阶段。

主、备 GPT 分步恢复时，中间状态不一致是可以预期的，但全部完成后必须与干净快照完全一致。

### 6. 恢复 GPT 后存储空间没有立刻变大

这是正常的。恢复 GPT 只改变分区边界，不会自动扩大 F2FS。

不要因为这个原因直接格式化 userdata。若需要扩大文件系统，应另外进行安全的 F2FS 扩容和校验。

## 十二、最终结论

这次恢复能够保留数据的关键，不是简单“删除 `efisp`”，而是：

1. 用假回锁后的备份确认当前状态；
2. 用干净备份精确恢复主、备 GPT；
3. 恢复对应版本的官方 init_boot 和 ABL；
4. 全部回读校验后才重启；
5. 出现槽位回退或 Fastboot 状态异常时，不盲目清数据或回锁；
6. 必要时让官方 OTA 完成剩余分区和槽位激活。

只要任何一步与预期不符，都应先停下来核对，而不是用下一次写入或重启碰运气。
