package dev.honor.restore.core

object OtaWritePolicy {
    private val preAblBases = setOf(
        "xbl", "xbl_config", "uefi", "sbl1", "sbl2", "sbl3", "tz", "hyp", "devcfg", "aop", "aop_config",
        "rpm", "pmic", "qupfw", "uefisecapp", "storsec", "keymaster", "cmnlib", "cmnlib64", "featenabler",
        "shrm", "hyp_ac_config", "dcp_dtb", "dcp", "tz_ac_config", "soccp_dtb", "soccp", "soccp_debug",
        "soccp_dcd", "pdp_cdb", "pdp", "cpucp_dtb", "cpucp", "cpucpfw", "tz_qti_config", "sec",
        "xbl_ac_config", "imagefv", "soccp_config", "dpm", "tme", "tme_config", "tme_fw", "tme_seq_patch",
    )

    fun requireFullWrites(writes: List<OtaWrite>, currentSlot: String) {
        val targetSlot = if (currentSlot == "a") "b" else "a"
        require(writes.any { it.target == "abl_$targetSlot" }) { "完整保锁 OTA 缺少对槽 ABL 写入记录" }
        writes.forEach { write ->
            require(write.target == "efisp" || write.target == "abl_$targetSlot" || isPreAblTarget(write.target)) {
                "OTA 写入目标超出允许的恢复范围：${write.target}"
            }
        }
    }

    fun isPreAblTarget(target: String): Boolean =
        (target.endsWith("_a") || target.endsWith("_b")) && target.substringBeforeLast('_') in preAblBases

    fun isRestoreImageTarget(target: String): Boolean =
        target in setOf("abl_a", "abl_b", "init_boot_a", "init_boot_b") || isPreAblTarget(target)
}
