package dev.honor.restore.core

object RestoreGuidance {
    fun forBlocker(message: String): String = when {
        message == "尚未导入备份" ->
            "先导入两类文件夹：①假回锁前用 hnubl 备份页生成的 ublbackup_…；②最近一次保锁 OTA 生成的 hnubl_ota_backup_…。请选择整个文件夹，不要只选里面的 img 文件。"
        message.startsWith("备份标识重复") ->
            "同一份备份被重复导入了。请移除一个导入副本，再继续。"
        message.startsWith("OTA 备份时间顺序") ->
            "有两份 OTA 备份的时间完全相同，App 无法判断先后顺序。请保留原始目录和清单，不要修改备份；必要时重新从 hnubl 导出。"
        message.contains("缺少 GPT 不含 efisp") || message.contains("没有可用的干净 GPT") ->
            "还缺“假回锁前”的干净备份。请选择 ublbackup_… 文件夹；如果有多份、记不清哪份正确，可以全部导入，App 只会采用分区表中没有 efisp 的那份。"
        message.startsWith("当前运行槽位") -> {
            val actual = message.substringAfter("当前运行槽位为 ", "").substringBefore('，')
            val expected = message.substringAfter("已导入中时间最晚的 OTA 备份目标槽为 ", "").substringBefore('；')
            "手机当前从 $actual 槽启动，但已导入中时间最晚的 OTA 备份对应 $expected 槽。如果这之后还做过保锁 OTA，请导入你确认的实际最后一次备份；如果没有后续更新，可能发生过槽位回退，请不要恢复。"
        }
        message.endsWith("的恢复来源记录无效") || message.contains("缺少 ${recordNameSuffix(message)}") ->
            "备份目录不完整或清单不匹配。请重新导入完整文件夹，不要只复制 img 文件，也不要修改 backup.json、ota-complete.json。"
        message.startsWith("设备缺少必需分区") || message.startsWith("设备缺少 OTA 曾修改的前级分区") ->
            "设备的分区结构与备份不一致，当前情况不在安全恢复范围内。请不要继续刷写，先导出日志核对。"
        message.contains("恢复镜像与分区容量不一致") ->
            "这份镜像的大小与设备分区不一致，可能选错了系统版本或设备。请不要强行刷入，改用当前系统对应的官方镜像。"
        message.contains("当前内容不属于已识别的 hnubl 修改") -> {
            val target = message.substringBefore(' ')
            "$target 不是 App 能确认的 hnubl 修改版本。若刚做过 OTA，请导入最新保锁 OTA 备份后重新扫描；若手动刷过其他 ABL，请改用当前系统官方镜像，不要继续自动恢复。"
        }
        message.startsWith("尚未读取真实设备状态") ->
            "备份导入完成后，点击上方“读取设备与分区信息”。App 会先只读检查，不会写入分区。"
        message.contains("保锁 OTA 尚待官方重启收尾") ->
            "上一次系统更新还没有完成重启收尾。请先回到系统更新完成官方重启，或取消这次更新，再打开恢复助手。"
        message.contains("中断标记") ->
            "上次恢复没有完成全部核验。请先确认当前系统可启动，并核对分区与恢复前备份；然后在设备诊断区进行人工复核。清除标记只解除执行阻止，不会修复分区，也不代表恢复已经成功。"
        message.contains("F2FS") || message.contains("userdata 分区边界") ->
            "数据分区的文件系统或容量检查未通过。请不要格式化或继续恢复，先保留个人数据和诊断日志。"
        message.contains("当前 GPT 未通过结构校验") || message.contains("恢复范围以外的差异") ||
            message.contains("GPT 容器身份不一致") || message.contains("GPT 保护 MBR 存在差异") ||
            message.contains("GPT 表头存在") || message.contains("GPT 第") || message.contains("efisp 与 userdata 不相邻") ||
            message.contains("干净 GPT 与当前磁盘身份") || message.contains("干净备份的原始末端") ->
            "假回锁产生的 efisp 和 userdata 末端差异是正常的，恢复工具本来就会处理。此次阻止是因为还发现了其他差异。请确认干净备份来自同一设备、同一磁盘且确实在假回锁前生成；不要盲目换 GPT 刷写。具体差异：$message"
        message.startsWith("最近 OTA 备份缺少目标槽镜像") || message.contains("覆盖前公开备份缺失") ->
            "最近一次保锁 OTA 备份不完整。请重新导入完整的 hnubl_ota_backup_… 文件夹，包含其中的 json、img 和写入日志，不能只导入部分文件。"
        message.contains("缺少前一次 OTA 的原厂候选") || message.contains("请补充早期 OTA") ->
            "最近备份无法为所有槽位提供可确认的恢复镜像。若之后还做过 OTA，请导入最近那次；否则可以补倒数第二次 OTA 或当前系统官方镜像。中间备份不全时，也可采用仅恢复当前槽的方案，但另一槽可能暂时无法启动。"
        message.contains("未被记录解释的 ABL 变化") || message.contains("当前哈希与 OTA 写入结果") ->
            "两次备份之间，启动镜像还发生过其他变化。请先导入对应那次操作的备份或当前系统官方镜像；App 不会用旧镜像猜测恢复。"
        message.contains("缺少 ${recordNameSuffix(message)}") || message.endsWith("的原厂候选") ->
            "有一套备份缺少需要的镜像。请重新导入完整备份，或补齐对应系统版本的官方镜像。"
        else ->
            "App 还不能安全确定恢复方案。请检查是否已导入假回锁前备份和最近一次保锁 OTA 备份；如果最近备份不能覆盖当前槽，再补对应早期 OTA 或当前系统官方镜像。\n详细原因：$message"
    }

    fun forWarning(message: String): String = when {
        message.contains("freeotaok") ->
            "freeotaok=1 只是上次保锁 OTA 留下的完成标记，不代表现在仍在等待重启。如果系统已经正常使用，可以继续；执行前会再确认没有待重启更新。"
        message.contains("当前哈希不在备份中") -> {
            val target = message.substringBefore(' ')
            "$target 可能是 OTA 后重新修补的 KernelSU/SukiSU 镜像，这很常见。请确认恢复来源是当前系统对应的官方 init_boot；不确定就先不要执行。"
        }
        else -> "请人工核对这一项后再继续：$message"
    }

    private fun recordNameSuffix(message: String): String = message.substringAfterLast("缺少 ", "").ifBlank { "不可能匹配的记录" }
}
