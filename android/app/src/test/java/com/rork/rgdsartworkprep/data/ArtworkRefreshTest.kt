package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.INTERNAL_ROMS
import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.SD_ROMS
import com.rork.rgdsartworkprep.ui.screens.coversInPlaceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Artwork screen must show covers from every saved ROM location — including ones
 * written after the library was scanned, such as 3DS covers prepared on an SD card.
 */
class ArtworkRefreshTest {

    // region when the Artwork screen rescans

    @Test
    fun `a run that ended after the last scan triggers a rescan`() {
        assertTrue(ArtworkRefresh.needsRescan(lastRunEndedAtMillis = 2_000, scannedAtMillis = 1_000, hasScanned = true, isScanning = false))
    }

    @Test
    fun `a run that ended before the last scan does not`() {
        assertFalse(ArtworkRefresh.needsRescan(1_000, 2_000, hasScanned = true, isScanning = false))
    }

    @Test
    fun `no finished run means no rescan`() {
        assertFalse(ArtworkRefresh.needsRescan(null, 1_000, hasScanned = true, isScanning = false))
    }

    /** Guards against a loop: a rescan already running, or the first scan, is left alone. */
    @Test
    fun `never rescans while scanning or before the first scan`() {
        assertFalse(ArtworkRefresh.needsRescan(2_000, 1_000, hasScanned = true, isScanning = true))
        assertFalse(ArtworkRefresh.needsRescan(2_000, 0, hasScanned = false, isScanning = false))
    }

    // endregion

    // region marking a saved cover on the right ROM

    private data class Rom(val id: String, val location: String, val cover: String? = null)

    private val roms = listOf(
        Rom(RomLocations.romId("Roms/3DS/Kid Icarus.3ds", null), INTERNAL_ROMS),
        Rom(RomLocations.romId("Roms/3DS/Kid Icarus.3ds", RomLocations.keyOf(SD_ROMS)), SD_ROMS),
        Rom(RomLocations.romId("Roms/3DS/Zelda.3ds", RomLocations.keyOf(SD_ROMS)), SD_ROMS),
    )

    @Test
    fun `a cover saved on the sd card marks only the sd card rom`() {
        val sdIcarus = roms[1].id
        val updated = ArtworkRefresh.replaceById(roms, sdIcarus, { it.id }) { it.copy(cover = "sd-cover") }!!
        assertEquals("sd-cover", updated[1].cover)
        assertNull("same-named game on internal storage is untouched", updated[0].cover)
        assertNull(updated[2].cover)
    }

    @Test
    fun `an unknown id changes nothing`() {
        assertNull(ArtworkRefresh.replaceById(roms, "missing", { it.id }) { it.copy(cover = "x") })
    }

    // endregion

    @Test
    fun `the count line says covers come from every location`() {
        assertEquals("5 covers in place across 2 ROM locations", coversInPlaceLabel(5, 2, isScanning = false))
        assertEquals("1 cover in place", coversInPlaceLabel(1, 1, isScanning = false))
        assertEquals("3 covers in place \u00b7 refreshing\u2026", coversInPlaceLabel(3, 1, isScanning = true))
    }
}
