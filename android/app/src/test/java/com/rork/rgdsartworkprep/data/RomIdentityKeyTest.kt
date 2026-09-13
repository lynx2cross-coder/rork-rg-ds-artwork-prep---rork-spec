package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.model.SystemCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that manually choosing artwork for one ROM cannot change any other ROM's.
 *
 * From device testing: a cover picked by hand for `Final Fantasy VII.pbp` appeared on
 * all five games in the test library — Adventure, Chrono Trigger, Final Fantasy VII,
 * Metroid Fusion and Sonic the Hedgehog — each written into its own correctly named
 * file. Nothing was wrong with the writer. The five placeholder ROMs held identical
 * bytes, the remembered-match key was the checksum alone, so all five shared one
 * cache entry: the manual choice was recorded there and every other ROM read it back
 * as its own identification on the next pass.
 *
 * These tests are about the key, because the key is what decides which artwork a ROM
 * receives. Two distinct library entries must never produce the same one.
 */
class RomIdentityKeyTest {

    private val identicalBytes = "EFF54D2B"

    // region the reported bug

    /**
     * The exact scenario from the device, reduced to its cause: five different games
     * whose files happen to hold the same bytes must have five different keys.
     */
    @Test
    fun `roms with identical contents never share a key`() {
        val keys = listOf(
            RomIdentityKey.of(identicalBytes, "Adventure.a26", 1024L, "atari2600"),
            RomIdentityKey.of(identicalBytes, "Chrono Trigger.sfc", 1024L, "snes"),
            RomIdentityKey.of(identicalBytes, "Final Fantasy VII.pbp", 1024L, "psx"),
            RomIdentityKey.of(identicalBytes, "Metroid Fusion.gba", 1024L, "gba"),
            RomIdentityKey.of(identicalBytes, "Sonic the Hedgehog.md", 1024L, "megadrive"),
        )
        assertEquals(
            "every ROM must key to itself, whatever its contents",
            keys.size,
            keys.toSet().size,
        )
    }

    /**
     * Empty and stub files are the worst case: every zero-byte ROM has the same
     * checksum, so a content-only key collapses them all into one entry.
     */
    @Test
    fun `empty roms of different games never share a key`() {
        val zeroByteCrc = "00000000"
        val first = RomIdentityKey.of(zeroByteCrc, "Adventure.a26", 0L, "atari2600")
        val second = RomIdentityKey.of(zeroByteCrc, "Sonic the Hedgehog.md", 0L, "megadrive")
        assertNotEquals(first, second)
    }

    /** The same dump saved under two names is still two library entries. */
    @Test
    fun `the same contents under two filenames stay distinct`() {
        val first = RomIdentityKey.of(identicalBytes, "Sonic.md", 512L, "megadrive")
        val second = RomIdentityKey.of(identicalBytes, "Sonic the Hedgehog.md", 512L, "megadrive")
        assertNotEquals(first, second)
    }

    /** One game on two platforms is two games, and needs two covers. */
    @Test
    fun `the same file on two systems stays distinct`() {
        val genesis = RomIdentityKey.of(identicalBytes, "Aladdin.bin", 2048L, "megadrive")
        val snes = RomIdentityKey.of(identicalBytes, "Aladdin.bin", 2048L, "snes")
        assertNotEquals(genesis, snes)
    }

    /**
     * Disc games carry no checksum, so without the rest of the identity every disc
     * game on a system would collapse into a single entry.
     */
    @Test
    fun `disc games without checksums never share a key`() {
        val ff7 = RomIdentityKey.of(null, "Final Fantasy VII.pbp", 700L, "psx")
        val mgs = RomIdentityKey.of(null, "Metal Gear Solid.pbp", 700L, "psx")
        assertNotEquals(ff7, mgs)
    }

    /** Two files differing only in size are different dumps and key differently. */
    @Test
    fun `size participates in identity`() {
        val small = RomIdentityKey.of(null, "Final Fantasy VII.pbp", 700L, "psx")
        val large = RomIdentityKey.of(null, "Final Fantasy VII.pbp", 1400L, "psx")
        assertNotEquals(small, large)
    }

    // endregion

    // region the key still does its original job

    /** The same ROM must key identically every time, or its match is never reused. */
    @Test
    fun `the same rom always produces the same key`() {
        val first = RomIdentityKey.of(identicalBytes, "Chrono Trigger.sfc", 4096L, "snes")
        val second = RomIdentityKey.of(identicalBytes, "Chrono Trigger.sfc", 4096L, "snes")
        assertEquals(first, second)
    }

    /** Case differences in the filename are the same file on this storage. */
    @Test
    fun `filename casing does not split one rom into two`() {
        val lower = RomIdentityKey.of(identicalBytes, "chrono trigger.sfc", 4096L, "snes")
        val upper = RomIdentityKey.of(identicalBytes, "Chrono Trigger.SFC", 4096L, "snes")
        assertEquals(lower, upper)
    }

    /**
     * A checksum that could not be read this time must not silently become a
     * different ROM's key, but it must still be distinguishable from a real one.
     */
    @Test
    fun `a missing checksum is not confused with a real one`() {
        val hashed = RomIdentityKey.of(identicalBytes, "Metroid Fusion.gba", 8192L, "gba")
        val unhashed = RomIdentityKey.of(null, "Metroid Fusion.gba", 8192L, "gba")
        assertNotEquals(hashed, unhashed)
    }

    @Test
    fun `an undetected system still keys by file identity`() {
        val first = RomIdentityKey.of(null, "Unknown Game.bin", 64L, null)
        val second = RomIdentityKey.of(null, "Another Game.bin", 64L, null)
        assertNotEquals(first, second)
    }

    // endregion

    // region the two write paths must agree

    /**
     * The manual picker and the batch pipeline must derive the same key for the same
     * ROM. They did not: the manual path always hashed the file while the pipeline
     * skips hashing for disc systems, so a cover chosen by hand for a PlayStation
     * game was filed under a key the pipeline never read back.
     */
    @Test
    fun `disc systems are identified without a checksum on both paths`() {
        val psx = SystemCatalog.byKey("psx")
        assertTrue("psx must be a disc system for this test to mean anything", psx?.discBased == true)
        assertFalse(RomIdentityKey.usesChecksum(psx))
    }

    @Test
    fun `cartridge systems are identified with a checksum`() {
        assertTrue(RomIdentityKey.usesChecksum(SystemCatalog.byKey("gba")))
        assertTrue(RomIdentityKey.usesChecksum(SystemCatalog.byKey("snes")))
    }

    /** An unknown system is hashed: a checksum is the only identity left to use. */
    @Test
    fun `an unknown system is still hashed`() {
        assertTrue(RomIdentityKey.usesChecksum(null))
    }

    // endregion

    // region superseded entries

    /**
     * Old checksum-only entries must not be readable as current ones. They cannot be
     * migrated — the key does not record which file it was written for, and on the
     * reporting user's device it may have been written for the wrong one.
     */
    @Test
    fun `keys from the superseded format are not mistaken for current ones`() {
        val current = RomIdentityKey.of(identicalBytes, "Adventure.a26", 1024L, "atari2600")
        assertTrue(current.startsWith("${RomIdentityKey.PREFIX}:"))

        val supersededCrcKey = "crc:$identicalBytes"
        val supersededNameKey = "name:adventure.a26:1024"
        assertFalse(supersededCrcKey.startsWith("${RomIdentityKey.PREFIX}:"))
        assertFalse(supersededNameKey.startsWith("${RomIdentityKey.PREFIX}:"))
    }

    // endregion
}
