package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.INTERNAL_ROMS
import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.SD_3DS
import com.rork.rgdsartworkprep.data.RomLocationsRepositoryTest.Companion.SD_ROMS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scanning every saved location. The walk is faked per location with the same
 * [ScanCandidates] step the real walk uses, so ignored-file handling is the real one.
 */
class MultiLocationScanTest {

    /** A found game, reduced to what the orchestration needs. */
    private data class Found(val location: String, val documentId: String, val fileName: String)

    private val internal = RomLocation(INTERNAL_ROMS, legacyIdentity = true)
    private val sd = RomLocation(SD_ROMS)
    private val sd3ds = RomLocation(SD_3DS)

    /** Each location's files, as (document id, folder) pairs. */
    private val disk = mapOf(
        INTERNAL_ROMS to listOf(
            "primary:Roms/GBA/Super Mario Advance.gba" to "GBA",
            "primary:Roms/SNES/Zelda.sfc" to "SNES",
        ),
        SD_ROMS to listOf(
            "1234-ABCD:Roms/GBA/Super Mario Advance.gba" to "GBA",
            "1234-ABCD:Roms/NDS/Mario Kart DS.nds" to "NDS",
            "1234-ABCD:Roms/3DS/boot9.bin" to "3DS",
            "1234-ABCD:Roms/3DS/Pokemon Y.3ds" to "3DS",
        ),
        SD_3DS to listOf(
            "1234-ABCD:Roms/3DS/boot9.bin" to "3DS",
            "1234-ABCD:Roms/3DS/Pokemon Y.3ds" to "3DS",
        ),
    )

    private fun walk(location: RomLocation, ignored: IgnoredFiles): LocationItems<Found> {
        var ignoredCount = 0
        val found = disk.getValue(location.treeUri).groupBy { it.second }.flatMap { (folder, files) ->
            ScanCandidates.select(
                files = files.map { it.first },
                fileName = { it.substringAfterLast('/') },
                folderChain = listOf(folder),
                ignored = ignored,
                onIgnored = { ignoredCount++ },
            ).map { Found(location.treeUri, it.item, it.item.substringAfterLast('/')) }
        }
        return LocationItems(found, ignoredCount)
    }

    private fun scan(
        locations: List<RomLocation>,
        ignored: IgnoredFiles = IgnoredFiles.NONE,
        access: (RomLocation) -> LocationAccess = { LocationAccess.Available },
        failing: Set<String> = emptySet(),
        progress: MutableList<MultiLocationScan.Progress> = mutableListOf(),
    ) = runBlocking {
        MultiLocationScan.run(
            locations = locations,
            access = { access(it) },
            scan = {
                if (it.treeUri in failing) error("walk failed")
                walk(it, ignored)
            },
            fileKey = { location, item -> RomLocations.fileKey(location, item.documentId) },
            onProgress = { progress += it },
        )
    }

    @Test
    fun `one saved location scans exactly as before`() {
        val result = scan(listOf(internal))
        assertEquals(listOf("Super Mario Advance.gba", "Zelda.sfc"), result.items.map { it.fileName })
        assertTrue(result.items.all { it.location == INTERNAL_ROMS })
    }

    @Test
    fun `every saved location is scanned and the results combined`() {
        val result = scan(listOf(internal, sd))
        assertEquals(6, result.items.size)
        assertEquals(setOf(INTERNAL_ROMS, SD_ROMS), result.items.map { it.location }.toSet())
        assertEquals(listOf(2, 4), result.summaries.map { it.romCount })
    }

    @Test
    fun `the same filename in two locations stays two games`() {
        val marios = scan(listOf(internal, sd)).items.filter { it.fileName == "Super Mario Advance.gba" }
        assertEquals(2, marios.size)
        assertEquals(setOf(INTERNAL_ROMS, SD_ROMS), marios.map { it.location }.toSet())
    }

    @Test
    fun `progress counts the locations being scanned`() {
        val progress = mutableListOf<MultiLocationScan.Progress>()
        scan(listOf(internal, sd, sd3ds), progress = progress)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress.map { it.index to it.total })
        assertEquals(listOf(internal, sd, sd3ds), progress.map { it.location })
    }

    @Test
    fun `an unavailable location is reported, skipped, and kept`() {
        val progress = mutableListOf<MultiLocationScan.Progress>()
        val locations = listOf(internal, sd)
        val result = scan(
            locations,
            access = { if (it == sd) LocationAccess.PermissionLost else LocationAccess.Available },
            progress = progress,
        )
        assertEquals(setOf(INTERNAL_ROMS), result.items.map { it.location }.toSet())
        assertEquals(LocationAccess.PermissionLost, result.summaries[1].access)
        assertFalse(result.summaries[1].scanned)
        assertEquals("only reachable locations are counted", listOf(1 to 1), progress.map { it.index to it.total })
        assertEquals(locations, result.summaries.map { it.location })
    }

    @Test
    fun `a removed sd card does not stop internal storage being scanned`() {
        val result = scan(listOf(sd, internal), access = { if (it == sd) LocationAccess.NotFound else LocationAccess.Available })
        assertTrue(result.anyScanned)
        assertEquals(2, result.items.size)
    }

    @Test
    fun `one location failing mid-walk does not stop the others`() {
        val result = scan(listOf(internal, sd), failing = setOf(INTERNAL_ROMS))
        assertTrue(result.summaries[0].failed)
        assertEquals(4, result.items.size)
    }

    @Test
    fun `nothing reachable is reported as such`() {
        val result = scan(listOf(internal, sd), access = { LocationAccess.PermissionLost })
        assertTrue(result.items.isEmpty())
        assertFalse(result.anyScanned)
    }

    /** `/Roms` and `/Roms/3DS` both saved: each 3DS game is found once, under the first. */
    @Test
    fun `nested locations do not list the same file twice`() {
        val result = scan(listOf(sd, sd3ds))
        val pokemon = result.items.filter { it.fileName == "Pokemon Y.3ds" }
        assertEquals(1, pokemon.size)
        assertEquals(SD_ROMS, pokemon.single().location)
        assertEquals(0, result.summaries[1].romCount)
    }

    // region ignored files keep working

    @Test
    fun `ignored files are left out of every location`() {
        val result = scan(listOf(internal, sd, sd3ds), ignored = IgnoredFiles(IgnoredFilePresets.threeDsSystemFiles))
        assertTrue(result.items.none { it.fileName == "boot9.bin" })
        assertTrue(result.items.any { it.fileName == "Pokemon Y.3ds" })
    }

    @Test
    fun `ignored counts are summed across locations`() {
        val result = scan(listOf(internal, sd3ds), ignored = IgnoredFiles(listOf("boot9.bin", "Zelda.sfc")))
        assertEquals(2, result.ignoredCount)
        assertEquals(listOf(1, 1), result.summaries.map { it.ignoredCount })
    }

    @Test
    fun `an ignored name is ignored in every location it appears in`() {
        val result = scan(listOf(internal, sd), ignored = IgnoredFiles(listOf("super mario advance.gba")))
        assertTrue(result.items.none { it.fileName == "Super Mario Advance.gba" })
        assertEquals(2, result.ignoredCount)
    }

    // endregion
}
