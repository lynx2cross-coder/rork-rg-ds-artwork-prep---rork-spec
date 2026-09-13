package com.rork.rgdsartworkprep.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison key, which is the hottest function in the app.
 *
 * Parsing one system index calls it once per thumbnail, and the RG DS spent 65
 * seconds parsing the 9,339-entry PlayStation listing against a 1.1-second download.
 * The regex form ran three ICU passes and allocated four intermediate strings per
 * entry; the replacement does one pass into a single builder.
 *
 * That makes these tests unusually load-bearing. A faster function that answers
 * differently would silently re-point artwork at the wrong games, so the first group
 * asserts the new implementation against the regexes it replaced — which are kept in
 * the source precisely so they can serve as the specification.
 */
class RomNameNormalizerTest {

    // region equivalence with the regex specification

    /**
     * Names shaped like the ones the archive and real libraries actually contain,
     * including the awkward cases: nested and unbalanced brackets, articles at every
     * position, non-Latin text, and punctuation that survives one filter but not the
     * next.
     */
    private val corpus = listOf(
        "Final Fantasy VII (USA) (Disc 1)",
        "The Legend of Zelda - A Link to the Past (USA)",
        "Super Mario Bros. (World)",
        "Metroid Fusion (USA, Europe) (En,Fr,De,Es,It)",
        "Sonic The Hedgehog (USA, Europe)",
        "Panzer Dragoon (USA)",
        "Chrono Trigger (USA) [!]",
        "Pokemon - Red Version (USA, Europe) [S][!]",
        "Tony Hawk's Pro Skater 2 (USA)",
        "Spider-Man (USA)",
        "R-Type III - The Third Lightning (USA)",
        "Cool Boarders 2001 [NTSC-U]",
        "Densha de Go! 2 (Japan)",
        "Ys I & II (Japan)",
        "Mega Man X3 (Europe) (Beta) {early}",
        "A Bug's Life (USA)",
        "An American Tail (USA)",
        "Of Light and Darkness (USA)",
        "De La Jet Set Radio (Japan)",
        "Le Mans 24 (Europe)",
        "La Pucelle - Tactics (USA)",
        "AND1 Streetball (USA)",
        "THE King of Fighters",
        "the the the",
        "a",
        "an",
        "of",
        "and",
        "de",
        "le",
        "la",
        "A_B",
        "the_end",
        "under_score name",
        "Broken (bracket",
        "Broken bracket)",
        "Nested ((inner)) outer",
        "Mismatched [square)",
        "Empty ()",
        "Empty []",
        "Empty {}",
        "",
        "   ",
        "123",
        "(USA)",
        "\u30c9\u30e9\u30b4\u30f3\u30af\u30a8\u30b9\u30c8",
        "Pok\u00e9mon Ruby",
        "Caf\u00e9 International",
        "MIXED case AND Articles OF Doom",
        "Hyphen-Ated-Name",
        "dots.in.name",
        "Multi   space",
        "Trailing space ",
        " Leading space",
        "!!!",
        "The",
        "THE",
    )

    @Test
    fun `the fast key matches the regular expressions it replaced`() {
        corpus.forEach { name ->
            assertEquals(
                "disagreed on \"$name\"",
                RomNameNormalizer.comparisonKeyByRegex(name),
                RomNameNormalizer.comparisonKey(name),
            )
        }
    }

    /**
     * The corpus is hand-written, so it can only cover what was thought of. This walks
     * a large space of generated combinations of the characters that actually decide
     * the outcome — brackets, articles, separators, digits — to catch a disagreement
     * nobody anticipated.
     */
    @Test
    fun `the fast key matches the regular expressions across generated names`() {
        val pieces = listOf(
            "the", "a", "an", "of", "and", "de", "le", "la",
            "(", ")", "[", "]", "{", "}", " ", "_", "-", ".", "!", "1", "x", "zelda",
        )
        val random = java.util.Random(20260912L)
        repeat(4_000) {
            val length = 1 + random.nextInt(8)
            val name = buildString {
                repeat(length) { append(pieces[random.nextInt(pieces.size)]) }
            }
            assertEquals(
                "disagreed on \"$name\"",
                RomNameNormalizer.comparisonKeyByRegex(name),
                RomNameNormalizer.comparisonKey(name),
            )
        }
    }

    // endregion

    // region behaviour the matching depends on

    /**
     * `to` is deliberately absent from the article list, so it survives. Asserted
     * explicitly because the obvious expectation here is the wrong one, and a future
     * change that "tidied up" the list would alter every stored key in the archive.
     */
    @Test
    fun `bracketed tags and articles are removed`() {
        assertEquals(
            "legendzeldalinktopast",
            RomNameNormalizer.comparisonKey("The Legend of Zelda - A Link to the Past (USA)"),
        )
    }

    @Test
    fun `an article inside a word is not treated as an article`() {
        // "theatre" must not lose its leading "the".
        assertEquals("theatre", RomNameNormalizer.comparisonKey("Theatre"))
        assertEquals("android", RomNameNormalizer.comparisonKey("Android"))
    }

    @Test
    fun `two spellings of the same game agree`() {
        assertEquals(
            RomNameNormalizer.comparisonKey("Sonic the Hedgehog (USA, Europe)"),
            RomNameNormalizer.comparisonKey("Sonic The Hedgehog"),
        )
    }

    // endregion

    // region the length gate

    /**
     * The gate exists to avoid running a Levenshtein matrix against every entry of a
     * 9,339-entry index. It may only ever exclude candidates the full measure would
     * also have excluded — an over-eager gate would silently lose correct matches.
     */
    @Test
    fun `the length gate never rejects a pair the full measure would accept`() {
        val threshold = 0.93f
        val samples = corpus.map { RomNameNormalizer.comparisonKey(it) }.filter { it.isNotEmpty() }
        samples.forEach { a ->
            samples.forEach { b ->
                val actual = RomNameNormalizer.similarityOfKeys(a, b)
                if (actual >= threshold) {
                    assertTrue(
                        "gate wrongly excluded \"$a\" vs \"$b\" (similarity $actual)",
                        RomNameNormalizer.similarityCanReach(a, b, threshold),
                    )
                }
            }
        }
    }

    @Test
    fun `the length gate rejects pairs that cannot possibly match`() {
        assertFalse(RomNameNormalizer.similarityCanReach("sonic", "finalfantasyviisquaresoft", 0.93f))
    }

    /**
     * Equal-length keys differing by one letter are exactly what the gate must let
     * through — a misspelling or a regional spelling of the same game.
     */
    @Test
    fun `the length gate admits a plausible pair`() {
        assertTrue(RomNameNormalizer.similarityCanReach("supermariobros", "supermariobras", 0.93f))
    }

    /**
     * A short key inside a long one scores by containment, not by edit distance, so
     * the length bound does not apply to it and the gate must not use it.
     */
    @Test
    fun `the gate defers to containment scoring below its ceiling`() {
        assertTrue(RomNameNormalizer.similarityCanReach("sonic", "sonicthehedgehog", 0.85f))
    }

    // endregion
}
