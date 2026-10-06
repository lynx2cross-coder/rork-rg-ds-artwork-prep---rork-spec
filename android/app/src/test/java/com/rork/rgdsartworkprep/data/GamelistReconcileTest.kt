package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.data.GamelistReconcile.Listed
import com.rork.rgdsartworkprep.data.GamelistReconcile.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gamelist.xml reconciliation: a `<game>` leaves the file only when its ROM is confirmed
 * gone from the gamelist's own folder, in its own location. Everything else stays.
 */
class GamelistReconcileTest {

    /** A fake storage tree: folder id -> children. A missing key is an unreadable folder. */
    private class Disk(private val folders: Map<String, List<Listed<String>>>) {
        val listed = mutableListOf<String>()
        fun list(id: String): List<Listed<String>>? {
            listed += id
            return folders[id]
        }
    }

    private fun file(name: String) = Listed(name, isDirectory = false, handle = "file:$name")
    private fun dir(name: String, id: String) = Listed(name, isDirectory = true, handle = id)

    private val threeDsXml = """
        <?xml version="1.0"?>
        <gameList>
        	<game>
        		<path>./Kid Icarus - Uprising (USA).cci</path>
        		<name>Kid Icarus: Uprising</name>
        		<favorite>true</favorite>
        	</game>
        	<game>
        		<path>./Super Mario 3D Land (USA).3ds</path>
        		<name>Super Mario 3D Land</name>
        		<playcount>12</playcount>
        	</game>
        	<game>
        		<path>./Zelda - A Link Between Worlds (USA).3ds</path>
        		<name>A Link Between Worlds</name>
        	</game>
        </gameList>
    """.trimIndent() + "\n"

    @Test
    fun `a deleted ROM's game entry is removed and every other entry stays byte for byte`() {
        val disk = Disk(
            mapOf(
                "sd:3DS" to listOf(
                    file("Kid Icarus - Uprising (USA).cci"),
                    file("Zelda - A Link Between Worlds (USA).3ds"),
                    file("gamelist.xml"),
                    dir("Imgs", "sd:3DS/Imgs"),
                ),
            ),
        )
        val pruned = GamelistReconcile.prune(threeDsXml, "sd:3DS", disk::list)

        assertEquals(listOf("./Super Mario 3D Land (USA).3ds"), pruned.removedPaths)
        val expected = threeDsXml.replace(
            "\t<game>\n\t\t<path>./Super Mario 3D Land (USA).3ds</path>\n" +
                "\t\t<name>Super Mario 3D Land</name>\n\t\t<playcount>12</playcount>\n\t</game>\n",
            "",
        )
        assertEquals(expected, pruned.xml)
        assertTrue("<favorite>true</favorite>" in pruned.xml)
        assertEquals(
            listOf("./Kid Icarus - Uprising (USA).cci", "./Zelda - A Link Between Worlds (USA).3ds"),
            GamelistReconcile.gamePaths(pruned.xml),
        )
    }

    @Test
    fun `nothing removed returns the very same document`() {
        val disk = Disk(
            mapOf(
                "sd:3DS" to listOf(
                    file("Kid Icarus - Uprising (USA).cci"),
                    file("Super Mario 3D Land (USA).3ds"),
                    file("Zelda - A Link Between Worlds (USA).3ds"),
                ),
            ),
        )
        val pruned = GamelistReconcile.prune(threeDsXml, "sd:3DS", disk::list)
        assertEquals(0, pruned.removedCount)
        assertSame(threeDsXml, pruned.xml)
    }

    @Test
    fun `an unreadable folder keeps every entry`() {
        val pruned = GamelistReconcile.prune(threeDsXml, "sd:3DS", Disk(emptyMap())::list)
        assertEquals(0, pruned.removedCount)
        assertSame(threeDsXml, pruned.xml)
    }

    @Test
    fun `each location's gamelist is checked only against its own folder`() {
        // Internal storage still has Mario; the SD card does not. Each gamelist is
        // resolved from its own root, so only the SD card's entry goes.
        val disk = Disk(
            mapOf(
                "primary:ROMS/3DS" to listOf(file("Super Mario 3D Land (USA).3ds")),
                "sd:3DS" to listOf(file("Kid Icarus - Uprising (USA).cci")),
            ),
        )
        val internalXml = "<gameList>\n\t<game>\n\t\t<path>./Super Mario 3D Land (USA).3ds</path>\n\t</game>\n</gameList>\n"

        val internal = GamelistReconcile.prune(internalXml, "primary:ROMS/3DS", disk::list)
        val sd = GamelistReconcile.prune(threeDsXml, "sd:3DS", disk::list)

        assertEquals(0, internal.removedCount)
        assertSame(internalXml, internal.xml)
        assertEquals(
            listOf("./Super Mario 3D Land (USA).3ds", "./Zelda - A Link Between Worlds (USA).3ds"),
            sd.removedPaths,
        )
        assertFalse(disk.listed.any { it.startsWith("primary:") && it != "primary:ROMS/3DS" })
    }

    @Test
    fun `games in their own sub-folders resolve through those folders`() {
        val disk = Disk(
            mapOf(
                "psx" to listOf(dir("Final Fantasy VII", "psx/ff7"), dir("Gone", "psx/gone")),
                "psx/ff7" to listOf(file("ff7.m3u")),
                "psx/gone" to listOf(file("other.cue")),
            ),
        )
        assertEquals(Presence.Present, GamelistReconcile.presence("./Final Fantasy VII/ff7.m3u", "psx", disk::list))
        assertEquals(Presence.Missing, GamelistReconcile.presence("./Gone/game.cue", "psx", disk::list))
        assertEquals(Presence.Missing, GamelistReconcile.presence("./Deleted Folder/game.cue", "psx", disk::list))
    }

    @Test
    fun `names compare case-insensitively and a folder-style game counts as present`() {
        val disk = Disk(mapOf("root" to listOf(file("ZELDA.GBA"), dir("Doom.wad", "root/doom"))))
        assertEquals(Presence.Present, GamelistReconcile.presence("./zelda.gba", "root", disk::list))
        assertEquals(Presence.Present, GamelistReconcile.presence("Doom.wad", "root", disk::list))
    }

    @Test
    fun `paths that cannot be checked from this folder are always kept`() {
        val disk = Disk(mapOf("root" to listOf(file("a.gba"))))
        listOf(
            "/storage/emulated/0/ROMS/GBA/x.gba",
            "../GBA/x.gba",
            "~/ROMS/x.gba",
            "%ROMPATH%/x.gba",
            "content://x/y",
            "",
        ).forEach { path ->
            assertEquals(path, Presence.Unknown, GamelistReconcile.presence(path, "root", disk::list))
        }
        assertNull(GamelistReconcile.relativeSegments("../x"))
    }

    @Test
    fun `escaped and CDATA paths are read as the real file name`() {
        val xml = "<gameList>\n" +
            "\t<game><path>./Tom &amp; Jerry.gba</path></game>\n" +
            "\t<game><path><![CDATA[./Ren & Stimpy.gba]]></path></game>\n" +
            "\t<game><path>./Gone &amp; Away.gba</path></game>\n" +
            "</gameList>\n"
        val disk = Disk(mapOf("gba" to listOf(file("Tom & Jerry.gba"), file("Ren & Stimpy.gba"))))
        val pruned = GamelistReconcile.prune(xml, "gba", disk::list)
        assertEquals(listOf("./Gone & Away.gba"), pruned.removedPaths)
        assertTrue("Tom &amp; Jerry" in pruned.xml)
        assertTrue("Ren & Stimpy" in pruned.xml)
    }

    @Test
    fun `folder entries, game attributes and the gameList element itself are untouched`() {
        val xml = "<?xml version=\"1.0\"?>\n<gameList>\n" +
            "\t<folder><path>./Hacks</path><name>Hacks</name></folder>\n" +
            "\t<game id=\"42\" source=\"ScreenScraper\"><path>./gone.nes</path></game>\n" +
            "\t<game id=\"43\"><path>./here.nes</path></game>\n" +
            "</gameList>\n"
        val disk = Disk(mapOf("nes" to listOf(file("here.nes"))))
        val pruned = GamelistReconcile.prune(xml, "nes", disk::list)
        assertEquals(
            "<?xml version=\"1.0\"?>\n<gameList>\n" +
                "\t<folder><path>./Hacks</path><name>Hacks</name></folder>\n" +
                "\t<game id=\"43\"><path>./here.nes</path></game>\n" +
                "</gameList>\n",
            pruned.xml,
        )
    }

    @Test
    fun `each folder is listed once however many entries it has`() {
        val disk = Disk(mapOf("sd:3DS" to listOf(file("Kid Icarus - Uprising (USA).cci"))))
        GamelistReconcile.prune(threeDsXml, "sd:3DS", disk::list)
        assertEquals(listOf("sd:3DS"), disk.listed)
    }
}
