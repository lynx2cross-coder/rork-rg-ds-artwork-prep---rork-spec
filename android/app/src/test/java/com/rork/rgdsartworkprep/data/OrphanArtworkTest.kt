package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.data.OrphanArtwork.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orphaned-artwork rules: a cover is only a candidate for removal when nothing in its
 * ROM folder still shares its name, and whole folders that look like another naming
 * scheme are left alone.
 */
class OrphanArtworkTest {

    private val images = setOf("jpg", "jpeg", "png")

    private fun file(name: String) = Item(name, isDirectory = false, handle = name)
    private fun dir(name: String) = Item(name, isDirectory = true, handle = name)

    private fun orphanNames(
        romFolder: List<Item<String>>,
        coverFolder: List<Item<String>>,
        hasGame: Boolean = true,
    ): List<String> = OrphanArtwork.orphans(romFolder, coverFolder, hasGame, images).map { it.name }

    @Test
    fun `the deleted 3DS game's cover is the only orphan`() {
        val romFolder = listOf(
            file("Kid Icarus - Uprising (USA).cci"),
            file("Zelda - A Link Between Worlds (USA).3ds"),
            file("gamelist.xml"),
            dir("Imgs"),
        )
        val covers = listOf(
            file("Kid Icarus - Uprising (USA).png"),
            file("Zelda - A Link Between Worlds (USA).jpg"),
            file("Super Mario 3D Land (USA).png"),
        )
        assertEquals(listOf("Super Mario 3D Land (USA).png"), orphanNames(romFolder, covers))
    }

    @Test
    fun `names compare case-insensitively`() {
        val romFolder = listOf(file("ZELDA.GBA"), file("metroid.gba"))
        val covers = listOf(file("zelda.PNG"), file("Metroid.jpg"), file("Gone.png"))
        assertEquals(listOf("Gone.png"), orphanNames(romFolder, covers))
    }

    @Test
    fun `a file the app cannot read as a game still keeps its cover`() {
        // `.cxi` is not detected; `Ignored.gba` stands for a file on the ignore list.
        val romFolder = listOf(file("Game A.3ds"), file("Homebrew.cxi"), file("Ignored.gba"))
        val covers = listOf(file("Game A.png"), file("Homebrew.png"), file("Ignored.png"), file("Deleted.png"))
        assertEquals(listOf("Deleted.png"), orphanNames(romFolder, covers))
    }

    @Test
    fun `folder-style games and multi-disc sets keep their covers`() {
        val romFolder = listOf(
            dir("Doom"),
            file("Final Fantasy VII (Disc 1).chd"),
            file("Final Fantasy VII (Disc 2).chd"),
            file("Tekken.chd"),
        )
        val covers = listOf(file("Doom.png"), file("Final Fantasy VII.png"), file("Tekken.png"), file("Old.png"))
        assertEquals(listOf("Old.png"), orphanNames(romFolder, covers))
    }

    @Test
    fun `suffixed cover names still belong to their game`() {
        val romFolder = listOf(file("Tetris.gb"), file("Pokemon Red.gb"))
        val covers = listOf(
            file("Tetris-image.png"),
            file("Pokemon Red (Box).jpg"),
            file("Tetris_thumb.png"),
            file("Removed Game-image.png"),
        )
        assertEquals(listOf("Removed Game-image.png"), orphanNames(romFolder, covers))
    }

    @Test
    fun `nothing is removed from a folder without games`() {
        val romFolder = listOf(file("readme.txt"), dir("Imgs"))
        val covers = listOf(file("Anything.png"))
        assertTrue(orphanNames(romFolder, covers, hasGame = false).isEmpty())
    }

    @Test
    fun `a cover folder where no cover matches any game is left alone`() {
        // Every cover unmatched means another naming scheme (e.g. by title, not file
        // name), never "every ROM was deleted while one remains".
        val romFolder = listOf(file("sm3dl.3ds"), file("kiu.cci"))
        val covers = listOf(file("Super Mario 3D Land.png"), file("Kid Icarus Uprising.png"))
        assertTrue(orphanNames(romFolder, covers).isEmpty())
    }

    @Test
    fun `only top-level jpg, jpeg and png files are candidates`() {
        val romFolder = listOf(file("Kept.gba"))
        val covers = listOf(
            file("Kept.png"),
            file("Gone.jpeg"),
            file("Gone.webp"),
            file("Gone.txt"),
            file(".hidden.png"),
            dir("box2dfront"),
            dir("Gone.png"),
        )
        assertEquals(listOf("Gone.jpeg"), orphanNames(romFolder, covers))
    }

    @Test
    fun `images beside the ROMs never act as owners`() {
        // A stray `Gone.png` next to the ROMs must not protect `Imgs/Gone.png`, and is
        // itself never a candidate: only the cover folder's contents are checked.
        val romFolder = listOf(file("Kept.gba"), file("Gone.png"))
        val covers = listOf(file("Kept.png"), file("Gone.png"))
        assertEquals(listOf("Gone.png"), orphanNames(romFolder, covers))
    }

    @Test
    fun `an empty cover folder yields nothing`() {
        assertTrue(orphanNames(listOf(file("Kept.gba")), emptyList()).isEmpty())
    }
}
