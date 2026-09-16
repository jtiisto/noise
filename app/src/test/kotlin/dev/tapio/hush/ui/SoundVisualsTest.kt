package dev.tapio.hush.ui

import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SoundVisualsTest {

    @Test
    fun `every sound in the catalog has an icon`() {
        SoundId.entries.forEach { id ->
            // The mapping is exhaustive by construction; this fails loudly if a
            // new catalog entry ever slips through with a placeholder.
            requireNotNull(id.icon) { "$id has no icon" }
        }
    }

    @Test
    fun `no two sounds share an icon`() {
        val byIcon = SoundId.entries.groupBy { it.icon.name }
        val shared = byIcon.filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<SoundId>>(), shared) {
            "icons must be distinct so tiles are told apart at a glance"
        }
    }
}
