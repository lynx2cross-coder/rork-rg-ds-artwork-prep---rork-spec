package com.rork.rgdsartworkprep.ui.screens

import com.rork.rgdsartworkprep.data.ArtworkDiscovery
import com.rork.rgdsartworkprep.data.LocationAccess
import com.rork.rgdsartworkprep.data.LocationScanSummary
import com.rork.rgdsartworkprep.data.RomLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkSourceNotesTest {

    private val sd = RomLocation("content://com.android.externalstorage.documents/tree/1234-ABCD%3ARoms")

    private fun summary(
        access: LocationAccess = LocationAccess.Available,
        roms: Int = 2,
        discovery: ArtworkDiscovery.Report = ArtworkDiscovery.Report(),
        failed: Boolean = false,
    ) = LocationScanSummary(sd, access, romCount = roms, failed = failed, discovery = discovery)

    @Test
    fun `a healthy location has a headline and nothing to explain`() {
        val entry = ArtworkSourceNotes.of(summary(), shownCount = 2)
        assertEquals("2 games \u00b7 2 covers shown", entry.headline)
        assertFalse(entry.hasProblems)
    }

    @Test
    fun `an unreachable location says why it was not scanned`() {
        val entry = ArtworkSourceNotes.of(summary(access = LocationAccess.NotFound, roms = 0), shownCount = 0)
        assertEquals("Not scanned", entry.headline)
        assertEquals(listOf(LocationAccess.NotFound.description), entry.notes)
    }

    @Test
    fun `unmatched covers, unread files and nested images are each explained`() {
        val discovery = ArtworkDiscovery.Report(
            coverFiles = 2,
            unmatchedCovers = 2,
            unmatchedSamples = listOf("3DS/Imgs/Kid Icarus Uprising.png"),
            unreadByExtension = mapOf("cci" to 5),
            nestedCoverFiles = 3,
            nestedCoverFolders = listOf("3DS/media/box2dfront"),
        )
        val notes = ArtworkSourceNotes.of(summary(roms = 0, discovery = discovery), shownCount = 0).notes
        assertTrue(notes.any { it.startsWith("No games were found") })
        assertTrue(notes.any { "2 covers found here don't share a name" in it && "Kid Icarus Uprising.png" in it })
        assertTrue(notes.any { ".cci \u00d75" in it })
        assertTrue(notes.any { "3 images sit in sub-folders such as 3DS/media/box2dfront" in it })
    }

    @Test
    fun `a read-only location warns that new covers go to the export folder`() {
        val entry = ArtworkSourceNotes.of(summary(access = LocationAccess.ReadOnly), shownCount = 1)
        assertTrue(entry.notes.single().startsWith("Read-only"))
    }
}
