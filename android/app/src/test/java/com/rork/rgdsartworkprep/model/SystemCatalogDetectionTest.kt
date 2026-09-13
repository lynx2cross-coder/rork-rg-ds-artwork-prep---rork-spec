package com.rork.rgdsartworkprep.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection rules for [SystemCatalog].
 *
 * `detect` takes the folder chain innermost-first, exactly as the scanner builds it, so
 * `ROMs/Nintendo/Wii/game.iso` is written here as `listOf("Wii", "Nintendo", "ROMs")`.
 */
class SystemCatalogDetectionTest {

    private fun detectKey(fileName: String, vararg folderChain: String): String? =
        SystemCatalog.detect(fileName, folderChain.toList())?.key

    // region existing aliases must keep resolving exactly as before

    @Test
    fun `unambiguous extension resolves without any folder context`() {
        assertEquals("snes", detectKey("Chrono Trigger.sfc"))
        assertEquals("nes", detectKey("Super Mario Bros.nes"))
        assertEquals("megadrive", detectKey("Sonic.md"))
        assertEquals("gba", detectKey("Metroid Fusion.gba"))
        assertEquals("n64", detectKey("Zelda.z64"))
    }

    @Test
    fun `known folder aliases resolve ambiguous containers`() {
        assertEquals("psx", detectKey("FF7.cue", "Final Fantasy VII", "PSX", "ROMs"))
        assertEquals("segacd", detectKey("game.chd", "segacd"))
        assertEquals("psp", detectKey("game.iso", "psp"))
        assertEquals("saturn", detectKey("game.cue", "Saturn"))
        assertEquals("dreamcast", detectKey("game.gdi", "dreamcast"))
    }

    @Test
    fun `innermost recognised folder wins over an outer one`() {
        assertEquals("gba", detectKey("Metroid.zip", "Game Boy Advance", "Nintendo", "ROMs"))
        assertEquals("saturn", detectKey("game.cue", "Saturn", "Sega", "ROMs"))
        assertEquals("snes", detectKey("game.zip", "SNES", "Nintendo - Nintendo Entertainment System"))
    }

    // endregion

    // region broad manufacturer aliases must no longer identify a system

    /**
     * The manufacturer folder itself still identifies nothing. `Wii` and `GameCube`
     * now resolve on their own names — which is the point of this change — so the
     * assertion that matters here is that the *outer* `Nintendo` folder contributes
     * nothing, and an unknown system inside it stays unknown.
     */
    @Test
    fun `outer Nintendo folder no longer makes an unknown system NES`() {
        assertNull(detectKey("game.zip", "Nintendo", "ROMs"))
        assertNull(detectKey("game.iso", "Virtual Console", "Nintendo", "ROMs"))
        assertNull(detectKey("game.iso", "64DD", "Nintendo", "ROMs"))
    }

    @Test
    fun `outer Sega folder no longer makes an unknown system Genesis`() {
        assertNull(detectKey("game.zip", "Model 2", "Sega", "ROMs"))
        assertNull(detectKey("game.zip", "Sega", "ROMs"))
    }

    @Test
    fun `outer Commodore folder no longer makes an unknown system C64`() {
        assertNull(detectKey("game.zip", "Amiga", "Commodore", "ROMs"))
        assertNull(detectKey("game.zip", "Commodore", "ROMs"))
    }

    @Test
    fun `an unambiguous extension still wins inside an unrecognised folder`() {
        assertEquals("megadrive", detectKey("Sonic.md", "Wii", "Nintendo"))
        assertEquals("nes", detectKey("Zelda.nes", "Amiga", "Commodore"))
        assertEquals("atari7800", detectKey("Choplifter.a78", "Unsorted"))
    }

    // endregion

    // region libretro / RetroArch folder names, added as a lower-priority tier

    @Test
    fun `libretro folder names resolve ambiguous containers`() {
        assertEquals("snes", detectKey("Mario.zip", "Nintendo - Super Nintendo Entertainment System"))
        assertEquals("nes", detectKey("game.zip", "Nintendo - Nintendo Entertainment System"))
        assertEquals("megadrive", detectKey("game.zip", "Sega - Mega Drive - Genesis"))
        assertEquals("mastersystem", detectKey("game.zip", "Sega - Master System - Mark III"))
        assertEquals("atari2600", detectKey("game.zip", "Atari - 2600"))
        assertEquals("c64", detectKey("game.zip", "Commodore - 64"))
        assertEquals("pcengine", detectKey("game.zip", "NEC - PC Engine - TurboGrafx 16"))
        assertEquals("ngpc", detectKey("game.zip", "SNK - Neo Geo Pocket Color"))
        assertEquals("psx", detectKey("game.cue", "Sony - PlayStation"))
        assertEquals("psx", detectKey("game.pbp", "Sony - PlayStation"))
        assertEquals("psp", detectKey("game.iso", "Sony - PlayStation Portable"))
        assertEquals("saturn", detectKey("game.chd", "Sega - Saturn"))
        assertEquals("ps2", detectKey("game.iso", "Sony - PlayStation 2"))
        assertEquals("gamecube", detectKey("game.iso", "Nintendo - GameCube"))
        assertEquals("wii", detectKey("game.iso", "Nintendo - Wii"))
    }

    @Test
    fun `every catalog libretro folder name is recognised as a folder`() {
        SystemCatalog.all.mapNotNull { it.libretroFolder }.forEach { folder ->
            assertNotNull("libretro folder not recognised: $folder", SystemCatalog.bySystemFolder(folder))
        }
    }

    @Test
    fun `libretro folder names never displace an existing alias`() {
        // Every libretro name must map to the system that declared it, which is only true
        // if no libretro name overwrote a hand-maintained alias belonging to another system.
        SystemCatalog.all.forEach { system ->
            val folder = system.libretroFolder ?: return@forEach
            assertEquals(
                "libretro folder $folder resolved to the wrong system",
                system.key,
                SystemCatalog.bySystemFolder(folder)?.key,
            )
        }
    }

    @Test
    fun `the four test-library systems resolve from their own folder names`() {
        assertEquals("ps2", detectKey("game.iso", "PS2"))
        assertEquals("ps2", detectKey("game.chd", "PlayStation 2"))
        assertEquals("gamecube", detectKey("game.iso", "GameCube"))
        assertEquals("gamecube", detectKey("game.rvz", "gamecube"))
        assertEquals("wii", detectKey("game.iso", "Wii"))
        assertEquals("wii", detectKey("game.rvz", "wii"))
        assertEquals("saturn", detectKey("game.cue", "Saturn"))
    }

    /**
     * Dolphin writes `rvz` for both GameCube and Wii, so deciding it by extension
     * would send half of such a library to the wrong platform. It must stay ambiguous
     * and be resolved by the folder, exactly as `iso` is.
     */
    @Test
    fun `a shared Dolphin container is never resolved by its extension alone`() {
        assertNull(detectKey("game.rvz"))
        assertNull(detectKey("game.rvz", "Unsorted"))
    }

    /** Systems genuinely still outside the catalog must stay unidentified. */
    @Test
    fun `systems not in the catalog stay unidentified`() {
        assertNull(detectKey("game.iso", "Sony - PlayStation 3"))
        assertNull(detectKey("game.iso", "Nintendo - Wii U"))
        assertNull(detectKey("game.iso", "Microsoft - Xbox"))
    }

    // endregion

    // region catalog integrity

    @Test
    fun `requested cartridge systems are enabled for scraping`() {
        val expected = setOf(
            "mastersystem", "gamegear", "sega32x", "pcengine", "atari2600", "atari7800",
            "atari5200", "lynx", "jaguar", "wonderswan", "wonderswancolor", "colecovision",
            "intellivision", "vb", "ngp", "ngpc", "c64", "n3ds",
        )
        val notEnabled = expected.filter { key -> SystemCatalog.byKey(key)?.scrapingEnabled != true }
        assertTrue("still disabled: $notEnabled", notEnabled.isEmpty())
    }

    @Test
    fun `previously enabled systems remain enabled`() {
        val previously = setOf(
            "nes", "snes", "gb", "gbc", "gba", "n64", "nds", "megadrive", "dreamcast", "psx", "psp",
        )
        val regressed = previously.filter { key -> SystemCatalog.byKey(key)?.scrapingEnabled != true }
        assertTrue("regressed to disabled: $regressed", regressed.isEmpty())
    }

    /** The four systems present in the test library must actually be scraped. */
    @Test
    fun `the test library systems are enabled for scraping`() {
        val expected = setOf("saturn", "ps2", "gamecube", "wii")
        val notEnabled = expected.filter { key -> SystemCatalog.byKey(key)?.scrapingEnabled != true }
        assertTrue("still disabled: $notEnabled", notEnabled.isEmpty())
    }

    /**
     * A system can only be scraped if it can also be addressed. Every enabled
     * disc-based system is resolved by folder, so a missing libretro folder would
     * mean artwork lookups that can never find anything.
     */
    @Test
    fun `newly enabled systems can address the thumbnail archive`() {
        listOf("saturn", "ps2", "gamecube", "wii").forEach { key ->
            val system = SystemCatalog.byKey(key)
            assertNotNull("missing from the catalog: $key", system)
            assertNotNull("no libretro folder: $key", system?.libretroFolder)
            assertTrue("not marked disc-based: $key", system?.discBased == true)
        }
    }

    @Test
    fun `every scrapable system has the provider mappings it needs`() {
        SystemCatalog.scrapable.forEach { system ->
            assertTrue("missing ScreenScraper id: ${system.key}", system.screenScraperId > 0)
        }
    }

    @Test
    fun `no two systems claim the same folder alias`() {
        // Within one system duplicates are fine and expected: `sega-cd` and `segacd`
        // normalize to the same string on purpose. Across systems they would mean one
        // system silently shadowing another.
        val owners = mutableMapOf<String, String>()
        val clashes = mutableListOf<String>()
        SystemCatalog.all.forEach { system ->
            system.folderAliases
                .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
                .distinct()
                .forEach { normalized ->
                    val previous = owners.put(normalized, system.key)
                    if (previous != null && previous != system.key) {
                        clashes += "$normalized: $previous vs ${system.key}"
                    }
                }
        }
        assertTrue("aliases claimed by two systems: $clashes", clashes.isEmpty())
    }

    // endregion

    // region disc-set collapsing must be untouched by this change

    @Test
    fun `cue sheet hides its bin payload and tracks`() {
        val playable = SystemCatalog.playableFileNames(
            listOf("Game.cue", "Game.bin", "Game (Track 02).bin", "Game.sub"),
        )
        assertEquals(setOf("Game.cue"), playable)
    }

    @Test
    fun `playlist hides its individual discs`() {
        val playable = SystemCatalog.playableFileNames(
            listOf("Game.m3u", "Game (Disc 1).chd", "Game (Disc 2).chd"),
        )
        assertEquals(setOf("Game.m3u"), playable)
    }

    // endregion
}
