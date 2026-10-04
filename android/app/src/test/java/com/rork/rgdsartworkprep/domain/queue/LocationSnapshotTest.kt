package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.data.IgnoredFiles
import com.rork.rgdsartworkprep.data.RomLocation
import com.rork.rgdsartworkprep.data.RomLocations
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A saved scan remembers which location each ROM came from, across restarts and the update. */
class LocationSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val internal = "content://com.android.externalstorage.documents/tree/primary%3ARoms"
    private val sd = "content://com.android.externalstorage.documents/tree/1234-ABCD%3ARoms"

    private fun record(id: String, name: String, tree: String?, scope: String?, parent: String? = "parent") =
        RomRecord(
            documentId = id,
            uri = "content://doc/$id",
            fileName = name,
            sizeBytes = 1L,
            parentDocumentId = parent,
            locationTreeUri = tree,
            locationScope = scope,
        )

    @Test
    fun `each rom keeps its location through a save and restore`() {
        val sdScope = RomLocations.scopeOf(RomLocation(sd))
        val snapshot = ScanSnapshot(
            scanId = "s",
            roms = listOf(
                record("Roms/GBA/Mario.gba", "Mario.gba", internal, null),
                record("Roms/GBA/Mario.gba", "Mario.gba", sd, sdScope),
            ),
        )
        val restored = json.decodeFromString<ScanSnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot, restored)
        assertEquals(listOf(internal, sd), restored.roms.map { it.locationTreeUri })
        assertNotEquals(restored.roms[0].id, restored.roms[1].id)
    }

    /** A 1.5.x snapshot has no location fields at all; it must still load. */
    @Test
    fun `a snapshot written before locations existed still loads`() {
        val old = """{"scanId":"s","sourceTreeUri":"$internal","roms":[{"documentId":"a","uri":"u","fileName":"a.gba","sizeBytes":1,"parentDocumentId":"p"}]}"""
        val restored = json.decodeFromString<ScanSnapshot>(old)
        assertNull(restored.roms.single().locationTreeUri)
        assertEquals("a", restored.roms.single().id)
    }

    @Test
    fun `an interrupted single-folder scan resumes writing into that folder`() {
        val old = ScanSnapshot(
            scanId = "s",
            sourceTreeUri = internal,
            roms = listOf(
                record("a", "a.gba", null, null),
                record("picked", "b.gba", null, null, parent = null),
            ),
        ).withLegacyLocations()
        assertEquals(internal, old.roms[0].locationTreeUri)
        assertNull("a file from outside the library stays outside it", old.roms[1].locationTreeUri)
        assertEquals("ids are unchanged, so jobs still line up", "a", old.roms[0].id)
    }

    @Test
    fun `filling legacy locations never overrides a recorded one`() {
        val snap = ScanSnapshot(scanId = "s", sourceTreeUri = internal, roms = listOf(record("a", "a.gba", sd, null)))
        assertEquals(sd, snap.withLegacyLocations().roms.single().locationTreeUri)
    }

    @Test
    fun `ignoring a file removes it from a saved multi-location scan`() {
        val sdScope = RomLocations.scopeOf(RomLocation(sd))
        val keep = record("Roms/GBA/Mario.gba", "Mario.gba", sd, sdScope)
        val drop = record("Roms/3DS/boot9.bin", "boot9.bin", sd, sdScope)
        val snapshot = ScanSnapshot(
            scanId = "s",
            roms = listOf(keep, drop),
            jobs = listOf(
                QueueJob(keep.id, keep.fileName, "gba"),
                QueueJob(drop.id, drop.fileName, "n3ds"),
            ),
        )
        val filtered = ScanExclusion.filterSnapshot(snapshot, IgnoredFiles(listOf("boot9.bin")))
        assertEquals(listOf(keep), filtered.roms)
        assertEquals(listOf(keep.id), filtered.jobs.map { it.id })
    }
}
