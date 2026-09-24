package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.SystemCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file half of the library walk: which files become games, and on which system.
 *
 * Every game the app ever processes is created from this function's output — the walk
 * builds a ROM entry only for what it returns, and the checksum, the provider requests
 * and the artwork download all run against those entries. So a file this leaves out
 * cannot reach any of them, and "never reaches detection" is the strongest place to
 * prove the exclusion: detection is injected here and recorded.
 */
class ScanCandidatesTest {

    private val support = IgnoredFiles(listOf("boot11.bin", "boot9.bin", "seeddb.bin", "shared_font.bin"))

    /** A 3DS folder as found on the reporting device, plus the games beside it. */
    private val threeDsFolder = listOf(
        "boot11.bin",
        "boot9.bin",
        "seeddb.bin",
        "shared_font.bin",
        "Pokemon Y.3ds",
        "Zelda - A Link Between Worlds.3ds",
    )

    private fun select(
        files: List<String>,
        folders: List<String>,
        ignored: IgnoredFiles,
        detect: (String, List<String>) -> GameSystem? = SystemCatalog::detect,
    ) = ScanCandidates.select(files, { it }, folders, ignored, detect)

    // region the reported problem

    /** Before: the 3DS folder makes every `.bin` support file a 3DS "game". */
    @Test
    fun `without an ignore list the support files are detected as games`() {
        val accepted = select(threeDsFolder, listOf("3DS"), IgnoredFiles.NONE).map { it.item }
        assertTrue(accepted.containsAll(listOf("boot11.bin", "boot9.bin", "seeddb.bin", "shared_font.bin")))
    }

    @Test
    fun `ignored support files are excluded before scan results exist`() {
        val accepted = select(threeDsFolder, listOf("3DS"), support).map { it.item }
        assertEquals(listOf("Pokemon Y.3ds", "Zelda - A Link Between Worlds.3ds"), accepted)
    }

    // endregion

    // region never reaching detection

    @Test
    fun `an ignored file never reaches system detection`() {
        val detected = mutableListOf<String>()
        select(threeDsFolder, listOf("3DS"), support) { name, chain ->
            detected += name
            SystemCatalog.detect(name, chain)
        }
        assertEquals(listOf("Pokemon Y.3ds", "Zelda - A Link Between Worlds.3ds"), detected)
    }

    @Test
    fun `an ignored file in another case never reaches detection either`() {
        val detected = mutableListOf<String>()
        select(listOf("BOOT9.BIN", "game.3ds"), listOf("3DS"), support) { name, chain ->
            detected += name
            SystemCatalog.detect(name, chain)
        }
        assertEquals(listOf("game.3ds"), detected)
    }

    // endregion

    // region similar names and other bin files stay eligible

    @Test
    fun `similar but different names are still scanned`() {
        val files = listOf("boot11.bin", "boot11_backup.bin", "boot11.bin.bak", "boot110.bin")
        val accepted = select(files, listOf("3DS"), IgnoredFiles(listOf("boot11.bin"))).map { it.item }
        // `.bak` is not a ROM extension, so the walk never considered it: that is the
        // existing candidate filter, not the ignore list.
        assertEquals(listOf("boot11_backup.bin", "boot110.bin"), accepted)
    }

    /** Legitimate `.bin` disc games must continue through the normal pipeline. */
    @Test
    fun `ignoring a support bin leaves every bin game in a disc folder untouched`() {
        val psx = listOf("Crash Bandicoot.bin", "Spyro.bin", "boot9.bin")
        val accepted = select(psx, listOf("PSX"), support)
        assertEquals(listOf("Crash Bandicoot.bin", "Spyro.bin"), accepted.map { it.item })
        assertTrue(accepted.all { it.system?.key == "psx" })
    }

    // endregion

    // region disc sets

    /**
     * Ignoring a cue sheet must not free its bin. The sheet hides its payload before
     * the ignore list is applied, so the game disappears whole instead of reappearing
     * as a lone `.bin` on the next scan.
     */
    @Test
    fun `ignoring a cue sheet removes the game without exposing its bin`() {
        val files = listOf("Game.cue", "Game.bin", "Game (Track 02).bin")
        val accepted = select(files, listOf("PSX"), IgnoredFiles(listOf("Game.cue")))
        assertTrue("nothing reappears: ${accepted.map { it.item }}", accepted.isEmpty())
    }

    @Test
    fun `ignoring a playlist removes the game without exposing its discs`() {
        val files = listOf("Game.m3u", "Game (Disc 1).chd", "Game (Disc 2).chd")
        val accepted = select(files, listOf("PSX"), IgnoredFiles(listOf("Game.m3u")))
        assertTrue(accepted.isEmpty())
    }

    // endregion

    // region unchanged behaviour for files that are not ignored

    /**
     * With nothing ignored, the output is exactly what the walk produced inline before
     * the logic moved: same files, same order, same systems.
     */
    @Test
    fun `with nothing ignored the selection matches the previous inline rules`() {
        val folders = listOf("GBA", "Roms")
        val files = listOf(
            "Metroid Fusion.gba", ".hidden.gba", "cover.png", "readme.txt", "noext",
            "Game.cue", "Game.bin", "Game (Track 02).bin", "mystery.zip",
        )
        val expected = previousInlineRules(files, folders)
        val actual = select(files, folders, IgnoredFiles.NONE).map { it.item to it.system?.key }
        assertEquals(expected, actual)
    }

    @Test
    fun `an ignore list that matches nothing changes nothing`() {
        val folders = listOf("SNES")
        val files = listOf("Super Metroid.sfc", "Zelda.smc", "Game.cue", "Game.bin")
        val unrelated = IgnoredFiles(listOf("boot9.bin"))
        assertEquals(
            select(files, folders, IgnoredFiles.NONE),
            select(files, folders, unrelated),
        )
    }

    @Test
    fun `an ambiguous container no folder can place is still dropped`() {
        val accepted = select(listOf("mystery.zip", "game.gba"), emptyList(), IgnoredFiles.NONE)
        assertEquals(listOf("game.gba"), accepted.map { it.item })
    }

    @Test
    fun `an unambiguous rom is still kept even when no folder names its system`() {
        val accepted = select(listOf("game.gba"), emptyList(), IgnoredFiles.NONE)
        assertEquals("gba", accepted.single().system?.key)
    }

    // endregion

    /** The walk's candidate rules exactly as they were written inline in 1.4.0. */
    private fun previousInlineRules(files: List<String>, folders: List<String>): List<Pair<String, String?>> {
        fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
        val candidates = files.filter {
            !it.startsWith(".") && ext(it).isNotBlank() &&
                ext(it) !in SystemCatalog.imageExtensions &&
                ext(it) in SystemCatalog.knownRomExtensions
        }
        val playable = SystemCatalog.playableFileNames(candidates)
        return candidates.mapNotNull { name ->
            if (name !in playable) return@mapNotNull null
            val system = SystemCatalog.detect(name, folders)
            if (system == null && ext(name) in SystemCatalog.ambiguousExtensions) return@mapNotNull null
            name to system?.key
        }
    }

    @Test
    fun `the previous-rules helper itself sees the disc set as one game`() {
        val rules = previousInlineRules(listOf("Game.cue", "Game.bin"), listOf("PSX"))
        assertEquals(listOf("Game.cue" to "psx"), rules)
        assertFalse(rules.any { it.first == "Game.bin" })
    }
}
