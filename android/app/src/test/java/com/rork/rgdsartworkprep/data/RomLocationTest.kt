package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.INTERNAL_ROMS
import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.SD_3DS
import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.SD_ROMS
import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.SECOND_SD_ROMS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules behind ROM Locations: how a folder is named, when two URIs are the
 * same folder, which location a file belongs to, whether a location is usable, and
 * how two same-named games in two locations are kept apart.
 */
class RomLocationTest {

    private val internal = RomLocation(INTERNAL_ROMS, legacyIdentity = true)
    private val sd = RomLocation(SD_ROMS)
    private val sd3ds = RomLocation(SD_3DS)

    // region labels

    @Test
    fun `internal storage and sd card are named in plain language`() {
        assertEquals(RomLocationLabel.Parts("Internal storage", "/Roms"), RomLocationLabel.of(INTERNAL_ROMS))
        assertEquals(RomLocationLabel.Parts("SD card", "/Roms"), RomLocationLabel.of(SD_ROMS))
        assertEquals("SD card /Roms/3DS", RomLocationLabel.of(SD_3DS).full)
    }

    @Test
    fun `a volume root reads as a slash`() {
        val root = "content://com.android.externalstorage.documents/tree/1234-ABCD%3A"
        assertEquals("SD card /", RomLocationLabel.of(root).full)
    }

    @Test
    fun `a non-storage provider still gets a readable label`() {
        val other = "content://com.example.provider/tree/abc%3ARoms"
        assertEquals("Storage", RomLocationLabel.of(other).volume)
    }

    @Test
    fun `two sd cards export into different folders`() {
        assertNotEquals(
            RomLocationLabel.exportFolderName(SD_ROMS),
            RomLocationLabel.exportFolderName(SECOND_SD_ROMS),
        )
    }

    @Test
    fun `export folder names hold no path separators`() {
        val name = RomLocationLabel.exportFolderName(SD_3DS)
        assertFalse(name, name.contains('/') || name.contains(':'))
    }

    // endregion

    // region parsing and keys

    @Test
    fun `tree uris decode to provider and folder`() {
        assertEquals(
            TreeRef("com.android.externalstorage.documents", "1234-ABCD:Roms/3DS"),
            TreeRef.parse(SD_3DS),
        )
    }

    @Test
    fun `a document uri inside a tree still resolves to its tree`() {
        val doc = "$SD_ROMS/document/1234-ABCD%3ARoms%2FGBA%2FMario.gba"
        assertEquals(RomLocations.keyOf(SD_ROMS), RomLocations.keyOf(doc))
    }

    @Test
    fun `non tree uris do not parse`() {
        assertNull(TreeRef.parse("file:///sdcard/Roms"))
        assertNull(TreeRef.parse("content://com.android.externalstorage.documents/document/primary%3ARoms"))
        assertNull(TreeRef.parse(""))
    }

    @Test
    fun `percent decoding handles utf-8 and leaves plus signs alone`() {
        assertEquals("primary:Rōms+1", percentDecode("primary%3AR%C5%8Dms+1"))
    }

    // endregion

    // region which location a picked file belongs to

    @Test
    fun `a file is matched to the location that contains it`() {
        val found = RomLocations.containing(
            listOf(internal, sd),
            "com.android.externalstorage.documents",
            "1234-ABCD:Roms/GBA/Mario.gba",
        )
        assertEquals(sd, found)
    }

    @Test
    fun `same path on another volume is not a match`() {
        val found = RomLocations.containing(
            listOf(sd),
            "com.android.externalstorage.documents",
            "primary:Roms/GBA/Mario.gba",
        )
        assertNull(found)
    }

    @Test
    fun `a sibling folder sharing a name prefix is not inside`() {
        val found = RomLocations.containing(
            listOf(sd),
            "com.android.externalstorage.documents",
            "1234-ABCD:RomsBackup/GBA/Mario.gba",
        )
        assertNull(found)
    }

    @Test
    fun `nested locations resolve to the one listed first`() {
        val doc = "1234-ABCD:Roms/3DS/Zelda.3ds"
        val authority = "com.android.externalstorage.documents"
        assertEquals(sd, RomLocations.containing(listOf(sd, sd3ds), authority, doc))
        assertEquals(sd3ds, RomLocations.containing(listOf(sd3ds, sd), authority, doc))
    }

    @Test
    fun `a file at a volume root location is inside it`() {
        val root = RomLocation("content://com.android.externalstorage.documents/tree/1234-ABCD%3A")
        val found = RomLocations.containing(listOf(root), "com.android.externalstorage.documents", "1234-ABCD:GBA/x.gba")
        assertEquals(root, found)
    }

    // endregion

    // region access status

    @Test
    fun `a readable folder with write access is available`() {
        assertEquals(LocationAccess.Available, LocationAccess.decide(true, true, true))
    }

    @Test
    fun `read-only access is still scanned`() {
        val access = LocationAccess.decide(hasReadGrant = true, hasWriteGrant = false, rootReadable = true)
        assertEquals(LocationAccess.ReadOnly, access)
        assertTrue(access.isScannable)
    }

    @Test
    fun `a revoked permission is reported, not scanned`() {
        val access = LocationAccess.decide(hasReadGrant = false, hasWriteGrant = false, rootReadable = false)
        assertEquals(LocationAccess.PermissionLost, access)
        assertFalse(access.isScannable)
        assertEquals("Folder access is no longer available", access.description)
    }

    /** Without a lasting grant the folder will be gone after a restart, so it counts as lost. */
    @Test
    fun `a folder readable only through temporary access counts as lost`() {
        assertEquals(LocationAccess.PermissionLost, LocationAccess.decide(false, false, true))
    }

    @Test
    fun `a moved or deleted folder, or removed card, is not found`() {
        val access = LocationAccess.decide(hasReadGrant = true, hasWriteGrant = true, rootReadable = false)
        assertEquals(LocationAccess.NotFound, access)
        assertFalse(access.isScannable)
    }

    // endregion

    // region same filename in two locations

    @Test
    fun `the first location keeps its single-folder identity`() {
        assertNull(RomLocations.scopeOf(internal))
        assertEquals("primary:Roms/GBA/Mario.gba", RomLocations.romId("primary:Roms/GBA/Mario.gba", null))
    }

    /**
     * Some providers do not prefix document ids with the volume, so two locations can
     * report the same id for two different files. The scope keeps them apart.
     */
    @Test
    fun `the same document id in two locations gives two ids`() {
        val docId = "Roms/GBA/Super Mario Advance.gba"
        assertNotEquals(
            RomLocations.romId(docId, RomLocations.scopeOf(internal)),
            RomLocations.romId(docId, RomLocations.scopeOf(sd)),
        )
    }

    @Test
    fun `remembered matches for same-named files in two locations never share a key`() {
        val name = "Super Mario Advance.gba"
        val size = 4_194_304L
        val first = RomIdentityKey.of("ABCD1234", name, size, "gba", RomLocations.scopeOf(internal))
        val second = RomIdentityKey.of("ABCD1234", name, size, "gba", RomLocations.scopeOf(sd))
        assertNotEquals(first, second)
    }

    /** Updating must not forget a single-folder library's remembered matches. */
    @Test
    fun `an unscoped key is exactly the key the single-folder build wrote`() {
        assertEquals("v2:gba:mario.gba:10:ABCD", RomIdentityKey.of("abcd", "Mario.gba", 10L, "gba"))
        assertEquals("v2:gba:mario.gba:10:ABCD", RomIdentityKey.of("abcd", "Mario.gba", 10L, "gba", null))
    }

    // endregion

    // region artwork and gamelist go to the ROM's own location

    @Test
    fun `artwork is written into the rom's own location`() {
        val writable = setOf(INTERNAL_ROMS, SD_ROMS)
        assertEquals(SD_ROMS, RomLocations.outputTree(SD_ROMS) { it in writable })
        assertEquals(INTERNAL_ROMS, RomLocations.outputTree(INTERNAL_ROMS) { it in writable })
    }

    /** A read-only location exports; it never borrows another location's write access. */
    @Test
    fun `a read-only location falls back to export rather than another location`() {
        assertNull(RomLocations.outputTree(SD_ROMS) { it == INTERNAL_ROMS })
    }

    @Test
    fun `a rom from no saved location exports`() {
        assertNull(RomLocations.outputTree(null) { true })
    }

    @Test
    fun `exports from two locations go to separate folders`() {
        val internalPrefix = RomLocations.exportPrefix(INTERNAL_ROMS, RomLocations.scopeOf(internal))
        val sdPrefix = RomLocations.exportPrefix(SD_ROMS, RomLocations.scopeOf(sd))
        assertNull("single-folder export layout unchanged", internalPrefix)
        assertEquals(RomLocationLabel.exportFolderName(SD_ROMS), sdPrefix)
    }

    @Test
    fun `a gba folder in each location gets its own gamelist`() {
        val a = RomLocations.gamelistBucketKey(INTERNAL_ROMS, "Roms/GBA", "GBA")
        val b = RomLocations.gamelistBucketKey(SD_ROMS, "Roms/GBA", "GBA")
        assertNotEquals(a, b)
        assertEquals(a, RomLocations.gamelistBucketKey(INTERNAL_ROMS, "Roms/GBA", "GBA"))
    }

    @Test
    fun `saved artwork paths name the location`() {
        assertEquals(
            "SD card /Roms/GBA/Imgs/Mario.png",
            RomLocationLabel.displayPath(SD_ROMS, "GBA/Imgs/Mario.png"),
        )
    }

    // endregion
}
