package com.rork.rgdsartworkprep.ui.screens

import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.SystemCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPlatformsTest {

    private data class Cover(val name: String, val system: GameSystem?)

    private fun system(key: String): GameSystem = SystemCatalog.all.first { it.key == key }

    private val covers = listOf(
        Cover("Pokemon Emerald", system("gba")),
        Cover("Metroid Fusion", system("gba")),
        Cover("Minish Cap", system("gba")),
        Cover("Kid Icarus", system("n3ds")),
        Cover("Mario Kart DS", system("nds")),
        Cover("Ocarina", system("n64")),
        Cover("Unknown", null),
    )

    @Test
    fun `3DS and DS lead, then the rest by count, and undetected games come last`() {
        val chips = ArtworkPlatforms.chips(covers) { it.system }
        assertEquals(listOf("n3ds", "nds", "gba", "n64", ArtworkPlatforms.OTHER_KEY), chips.map { it.key })
        assertEquals(listOf("3DS", "NDS", "GBA", "N64", "Other"), chips.map { it.label })
        assertEquals(listOf(1, 1, 3, 1, 1), chips.map { it.count })
    }

    @Test
    fun `only platforms with covers get a chip`() {
        val chips = ArtworkPlatforms.chips(covers.filter { it.system?.key == "gba" }) { it.system }
        assertEquals(listOf("gba"), chips.map { it.key })
    }

    @Test
    fun `filtering keeps one platform and null keeps everything`() {
        assertEquals(listOf("Kid Icarus"), ArtworkPlatforms.filter(covers, "n3ds") { it.system }.map { it.name })
        assertEquals(listOf("Unknown"), ArtworkPlatforms.filter(covers, ArtworkPlatforms.OTHER_KEY) { it.system }.map { it.name })
        assertEquals(covers, ArtworkPlatforms.filter(covers, null) { it.system })
    }

    @Test
    fun `a platform with no covers left falls back to all`() {
        val chips = ArtworkPlatforms.chips(covers) { it.system }
        assertEquals("nds", ArtworkPlatforms.effectiveSelection("nds", chips))
        assertNull(ArtworkPlatforms.effectiveSelection("psx", chips))
        assertNull(ArtworkPlatforms.effectiveSelection(null, chips))
    }
}
