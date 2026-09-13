package com.rork.rgdsartworkprep.provider

import com.rork.rgdsartworkprep.data.RomNameNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the archive's directory listing.
 *
 * This is the step that cost the RG DS its scans: 65 seconds to parse the
 * 9,339-entry PlayStation listing against a 1.1-second download, which blew the
 * ceiling and failed three ROMs. It previously had no tests at all, because it was
 * buried inside a class that needs Android to construct.
 */
class LibretroIndexParserTest {

    /** An Apache-style listing, which is what the archive actually serves. */
    private fun listing(vararg names: String): String = buildString {
        append("<html><head><title>Index of /Sony - PlayStation/Named_Boxarts</title></head>")
        append("<body><h1>Index of /</h1><pre>")
        append("<a href=\"../\">../</a>\n")
        names.forEach { append("<a href=\"$it\">$it</a>   01-Jan-2024 00:00  123K\n") }
        append("</pre></body></html>")
    }

    @Test
    fun `reads every thumbnail name in the listing`() = runBlocking {
        val entries = LibretroIndexParser.parse(
            listing(
                "Final%20Fantasy%20VII%20(USA)%20(Disc%201).png",
                "Metroid%20Fusion%20(USA).png",
                "Sonic%20The%20Hedgehog%20(USA,%20Europe).png",
            ),
        )

        assertEquals(
            listOf(
                "Final Fantasy VII (USA) (Disc 1)",
                "Metroid Fusion (USA)",
                "Sonic The Hedgehog (USA, Europe)",
            ),
            entries.map { it.name },
        )
    }

    /** The `.png` is the archive's, not part of the game's name. */
    @Test
    fun `the file extension is not part of the name`() = runBlocking {
        val entries = LibretroIndexParser.parse(listing("Panzer%20Dragoon%20(USA).png"))
        assertEquals("Panzer Dragoon (USA)", entries.single().name)
    }

    @Test
    fun `each entry carries the comparison key matching uses`() = runBlocking {
        val entries = LibretroIndexParser.parse(listing("The%20Legend%20of%20Zelda%20(USA).png"))
        assertEquals(
            RomNameNormalizer.comparisonKey("The Legend of Zelda (USA)"),
            entries.single().key,
        )
    }

    /**
     * A name with nothing escaped skips the decoder entirely, which is the fast path
     * most archive filenames take. It must produce exactly what decoding would have.
     */
    @Test
    fun `an unescaped name is read identically to an escaped one`() = runBlocking {
        val plain = LibretroIndexParser.parse(listing("Contra.png")).single()
        assertEquals("Contra", plain.name)

        val plus = LibretroIndexParser.parse(listing("Contra+Force.png")).single()
        assertEquals("Contra Force", plus.name)
    }

    @Test
    fun `parent and sub-folder links are ignored`() = runBlocking {
        val html = """
            <a href="../">../</a>
            <a href="nested/Game%20(USA).png">nested</a>
            <a href="Real%20Game%20(USA).png">Real Game</a>
        """.trimIndent()

        val entries = LibretroIndexParser.parse(html)
        assertEquals(listOf("Real Game (USA)"), entries.map { it.name })
    }

    @Test
    fun `non-thumbnail links are ignored`() = runBlocking {
        val html = """
            <a href="index.html">index</a>
            <a href="readme.txt">readme</a>
            <a href="Game%20(USA).png">game</a>
            <a href="">empty</a>
        """.trimIndent()

        assertEquals(listOf("Game (USA)"), LibretroIndexParser.parse(html).map { it.name })
    }

    /** The archive is generated, but a duplicate must not become a duplicate candidate. */
    @Test
    fun `a repeated name is kept once`() = runBlocking {
        val entries = LibretroIndexParser.parse(
            listing("Sonic%20(USA).png", "Sonic%20(USA).png"),
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun `an uppercase extension is still recognised`() = runBlocking {
        val entries = LibretroIndexParser.parse("<a HREF=\"Doom%20(USA).PNG\">Doom</a>")
        assertEquals(listOf("Doom (USA)"), entries.map { it.name })
    }

    @Test
    fun `an empty or markup-free page yields nothing`() = runBlocking {
        assertTrue(LibretroIndexParser.parse("").isEmpty())
        assertTrue(LibretroIndexParser.parse("<html><body>nothing here</body></html>").isEmpty())
    }

    /** A truncated download must not throw; it simply yields what it could read. */
    @Test
    fun `a truncated listing is read as far as it goes`() = runBlocking {
        val entries = LibretroIndexParser.parse(
            "<a href=\"Good%20Game.png\">g</a><a href=\"Truncated",
        )
        assertEquals(listOf("Good Game"), entries.map { it.name })
    }

    @Test
    fun `a name that cannot be decoded is skipped rather than failing the parse`() = runBlocking {
        val entries = LibretroIndexParser.parse(
            listing("Bad%ZZ%20Name.png", "Good%20Name.png"),
        )
        assertTrue("the valid entry survived", entries.any { it.name == "Good Name" })
    }

    /**
     * The parse must stop when its scan is cancelled. A parse that runs to completion
     * on a dead job is what once landed one ROM's index result on the next ROM's
     * record, and on this hardware it is minutes of CPU spent on nobody's behalf.
     */
    @Test
    fun `a cancelled parse stops instead of running to completion`() {
        val many = (1..5_000).joinToString("") { "<a href=\"Game%20$it.png\">g</a>" }
        val job = Job()
        job.cancel()

        val thrown = runCatching {
            runBlocking { withContext(job) { LibretroIndexParser.parse(many) } }
        }.exceptionOrNull()

        assertNotNull("expected the parse to stop", thrown)
        assertTrue(
            "expected cancellation, got ${thrown?.javaClass?.simpleName}",
            thrown is CancellationException,
        )
    }

    /**
     * A realistic listing at the size that actually failed on the device. This asserts
     * correctness at scale rather than a wall-clock figure — timing a shared CI box
     * would be flaky — but a regression to per-entry allocation would show up here as
     * an obvious slowdown.
     */
    @Test
    fun `a full-size listing parses completely`() = runBlocking {
        val names = (1..9_339).map { "Game%20Number%20$it%20(USA).png" }
        val entries = LibretroIndexParser.parse(listing(*names.toTypedArray()))

        assertEquals(9_339, entries.size)
        assertEquals("Game Number 1 (USA)", entries.first().name)
        assertEquals("Game Number 9339 (USA)", entries.last().name)
        assertNull("no entry should keep its extension", entries.firstOrNull { it.name.endsWith(".png") })
    }
}
