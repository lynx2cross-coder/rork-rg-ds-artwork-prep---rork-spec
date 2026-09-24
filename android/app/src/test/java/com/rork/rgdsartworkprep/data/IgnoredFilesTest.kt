package com.rork.rgdsartworkprep.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact, case-insensitive filename matching, and nothing looser.
 *
 * The danger here is over-matching, not under-matching: `.bin` is the extension most
 * disc games in the library use, so a rule that grew into an extension filter would
 * quietly erase a PlayStation collection from every scan.
 */
class IgnoredFilesTest {

    private val list = IgnoredFiles(listOf("boot11.bin"))

    // region exact, case-insensitive

    @Test
    fun `the exact name matches`() {
        assertTrue(list.matches("boot11.bin"))
    }

    @Test
    fun `a different case matches`() {
        assertTrue(list.matches("BOOT11.BIN"))
        assertTrue(list.matches("Boot11.Bin"))
    }

    @Test
    fun `an entry stored in capitals matches a lowercase file`() {
        assertTrue(IgnoredFiles(listOf("SEEDDB.BIN")).matches("seeddb.bin"))
    }

    // endregion

    // region similar but different names stay eligible

    @Test
    fun `a longer name with the same start does not match`() {
        assertFalse(list.matches("boot11_backup.bin"))
    }

    @Test
    fun `an extra extension does not match`() {
        assertFalse(list.matches("boot11.bin.bak"))
    }

    @Test
    fun `a shorter name does not match`() {
        assertFalse(list.matches("boot1.bin"))
        assertFalse(list.matches("boot11"))
    }

    @Test
    fun `surrounding whitespace in the file name does not match`() {
        assertFalse(list.matches(" boot11.bin"))
        assertFalse(list.matches("boot11.bin "))
    }

    /** The rule the whole design turns on: one ignored `.bin` is not every `.bin`. */
    @Test
    fun `ignoring one bin file leaves every other bin file eligible`() {
        val library = listOf(
            "boot11.bin",
            "Final Fantasy VII (Disc 1).bin",
            "Crash Bandicoot.bin",
            "boot9.bin",
            "Sonic.bin",
        )
        val split = list.partition(library) { it }
        assertEquals(listOf("boot11.bin"), split.removed)
        assertEquals(library - "boot11.bin", split.kept)
    }

    @Test
    fun `the real 3DS support files are each matched only by their own entry`() {
        val support = IgnoredFiles(listOf("boot11.bin", "boot9.bin", "seeddb.bin", "shared_font.bin"))
        listOf("boot11.bin", "boot9.bin", "seeddb.bin", "shared_font.bin").forEach {
            assertTrue(it, support.matches(it))
        }
        listOf("boot10.bin", "seeddb_old.bin", "shared_font.bin.bak", "font.bin").forEach {
            assertFalse(it, support.matches(it))
        }
    }

    /**
     * Case folding must not depend on the device language. Under Turkish rules `I`
     * lowercases to a dotless `ı`, which would break matching the day the user changed
     * their locale.
     */
    @Test
    fun `matching does not change with the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = IgnoredFiles(listOf("INDEX.BIN"))
            assertTrue(turkish.matches("index.bin"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `an empty list matches nothing`() {
        assertFalse(IgnoredFiles.NONE.matches("boot11.bin"))
        assertFalse(IgnoredFiles.NONE.matches(""))
    }

    // endregion

    // region list shape

    @Test
    fun `entries differing only by case are stored once, keeping the first form`() {
        val names = IgnoredFiles(listOf("boot9.bin", "BOOT9.BIN")).names
        assertEquals(listOf("boot9.bin"), names)
    }

    @Test
    fun `blank entries from a damaged preference are dropped`() {
        assertEquals(listOf("boot9.bin"), IgnoredFiles(listOf("", "  ", "boot9.bin")).names)
    }

    @Test
    fun `names are listed alphabetically regardless of case`() {
        val names = IgnoredFiles(listOf("seeddb.bin", "Boot9.bin", "aes_keys.txt")).names
        assertEquals(listOf("aes_keys.txt", "Boot9.bin", "seeddb.bin"), names)
    }

    // endregion

    // region typed entries

    @Test
    fun `a typed name is trimmed and accepted`() {
        assertEquals(EntryCheck.Valid("boot9.bin"), IgnoredFiles.NONE.check("  boot9.bin "))
    }

    @Test
    fun `a blank typed name is rejected`() {
        assertEquals(EntryCheck.Blank, IgnoredFiles.NONE.check("   "))
    }

    @Test
    fun `a path is rejected so a folder is never mistaken for a filename`() {
        assertEquals(EntryCheck.IncludesFolder, IgnoredFiles.NONE.check("3DS/boot9.bin"))
        assertEquals(EntryCheck.IncludesFolder, IgnoredFiles.NONE.check("3DS\\boot9.bin"))
    }

    /** Stored literally, `*.bin` would match nothing while looking like it worked. */
    @Test
    fun `a wildcard is rejected rather than stored`() {
        assertEquals(EntryCheck.HasWildcard, IgnoredFiles.NONE.check("*.bin"))
        assertEquals(EntryCheck.HasWildcard, IgnoredFiles.NONE.check("boot?.bin"))
    }

    @Test
    fun `a name already on the list in another case is reported with its stored form`() {
        assertEquals(EntryCheck.AlreadyIgnored("boot11.bin"), list.check("BOOT11.BIN"))
    }

    // endregion
}
