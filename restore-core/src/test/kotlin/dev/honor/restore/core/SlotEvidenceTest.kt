package dev.honor.restore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotEvidenceTest {
    @Test
    fun multipleIdenticalSlotSourcesAreAccepted() {
        val slot = SlotEvidence.normalize(mapOf(
            "SLOT" to "_a",
            "SLOT_ALT" to "a",
            "CMDLINE_SLOT" to "_a",
            "BOOTCONFIG_SLOT" to "a",
        ))
        assertEquals("a", slot)
    }

    @Test
    fun conflictingSlotSourcesAreRejected() {
        val error = runCatching { SlotEvidence.normalize(mapOf("SLOT" to "_a", "CMDLINE_SLOT" to "_b")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
