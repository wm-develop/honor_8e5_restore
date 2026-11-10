package dev.honor.restore.core

object SlotEvidence {
    fun normalize(values: Map<String, String>): String {
        val slots = listOf("SLOT", "SLOT_ALT", "CMDLINE_SLOT", "BOOTCONFIG_SLOT").mapNotNull { key ->
            values[key]?.trim()?.takeIf(String::isNotEmpty)?.removePrefix("_")
        }
        require(slots.isNotEmpty() && slots.all { it == "a" || it == "b" } && slots.distinct().size == 1) {
            "当前运行槽位的多个来源缺失或不一致"
        }
        return slots.first()
    }
}
