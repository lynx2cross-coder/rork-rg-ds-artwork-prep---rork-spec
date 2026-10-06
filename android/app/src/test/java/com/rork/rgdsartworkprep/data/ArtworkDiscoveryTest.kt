package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.model.SystemCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk's cover accounting, per folder. These are the cases that leave a cover on an
 * SD card invisible on the Artwork screen even though the location itself scans fine.
 */
class ArtworkDiscoveryTest {

    private fun folder(
        covers: Map<String, String>,
        roms: Set<String>,
        others: List<String> = emptyList(),
        ignored: Set<String> = emptySet(),
    ) = ArtworkDiscovery.folder(
        folderPath = "3DS",
        coversByBase = covers,
        romBaseNames = roms,
        otherFileNames = others,
        knownRomExtensions = SystemCatalog.knownRomExtensions,
        isIgnored = { it.lowercase() in ignored },
    )

    @Test
    fun `a cover named exactly like its game is matched`() {
        val report = folder(mapOf("kid icarus" to "Imgs/Kid Icarus.png"), roms = setOf("kid icarus"))
        assertEquals(1, report.coverFiles)
        assertEquals(1, report.matchedCovers)
        assertEquals(0, report.unmatchedCovers)
    }

    @Test
    fun `a cover whose name differs from the game is reported with its path`() {
        val report = folder(
            covers = mapOf("kid icarus uprising (usa)" to "Imgs/Kid Icarus Uprising (USA).png"),
            roms = setOf("kid icarus"),
        )
        assertEquals(1, report.unmatchedCovers)
        assertEquals(listOf("3DS/Imgs/Kid Icarus Uprising (USA).png"), report.unmatchedSamples)
    }

    @Test
    fun `game files of a type the walk does not read are counted by extension`() {
        val report = folder(
            covers = emptyMap(),
            roms = emptySet(),
            others = listOf("Kid Icarus.cci", "Zelda.cci", "Homebrew.3dsx", "gamelist.xml", "readme.txt"),
        )
        assertEquals(mapOf("cci" to 2, "3dsx" to 1), report.unreadByExtension)
        assertEquals(listOf("cci" to 2, "3dsx" to 1), report.topUnread())
    }

    @Test
    fun `ignored and hidden files are never reported as unread`() {
        val report = folder(
            covers = emptyMap(),
            roms = emptySet(),
            others = listOf("boot9.bin.cci", ".hidden.cci"),
            ignored = setOf("boot9.bin.cci"),
        )
        assertEquals(0, report.unreadTotal)
    }

    @Test
    fun `reports from several folders add up and keep only a few samples`() {
        val one = folder(mapOf("a" to "Imgs/a.png", "b" to "Imgs/b.png"), roms = emptySet())
        val two = folder(mapOf("c" to "Imgs/c.png", "d" to "Imgs/d.png"), roms = setOf("d"))
        val sum = one + two.copy(nestedCoverFiles = 4, nestedCoverFolders = listOf("3DS/media/box2dfront"))
        assertEquals(4, sum.coverFiles)
        assertEquals(1, sum.matchedCovers)
        assertEquals(3, sum.unmatchedCovers)
        assertEquals(ArtworkDiscovery.MAX_SAMPLES, sum.unmatchedSamples.size)
        assertEquals(4, sum.nestedCoverFiles)
        assertTrue("3DS/media/box2dfront" in sum.nestedCoverFolders)
    }
}
