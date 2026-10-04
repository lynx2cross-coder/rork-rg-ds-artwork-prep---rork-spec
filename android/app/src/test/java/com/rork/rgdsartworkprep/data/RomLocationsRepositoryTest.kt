package com.rork.rgdsartworkprep.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved ROM Locations list: adding never replaces, removing touches only the one
 * entry, duplicates are refused, and the list survives restarts and the update from a
 * single-folder build. "Restart" is a new repository over the same store.
 */
class RomLocationsRepositoryTest {

    private class MemoryStore(initial: Map<String, String> = emptyMap()) : TextStore {
        val data: MutableMap<String, String> = initial.toMutableMap()
        var writes: Int = 0
        override fun read(key: String): String? = data[key]
        override fun write(key: String, value: String) {
            writes++
            data[key] = value
        }
    }

    private var now = 1_000L
    private fun repo(store: MemoryStore, legacy: String? = null) =
        RomLocationsRepository(store, legacyTreeUri = legacy, clock = { now++ })

    private fun uris(repo: RomLocationsRepository) = repo.current.map { it.treeUri }

    // region one and several locations

    @Test
    fun `a new install has no locations`() {
        assertTrue(repo(MemoryStore()).current.isEmpty())
    }

    @Test
    fun `one saved location`() {
        val repo = repo(MemoryStore())
        val result = repo.add(INTERNAL_ROMS)
        assertTrue(result is AddLocationResult.Added)
        assertEquals(listOf(INTERNAL_ROMS), uris(repo))
    }

    @Test
    fun `several saved locations keep the order they were added in`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        repo.add(SD_ROMS)
        repo.add(SD_3DS)
        assertEquals(listOf(INTERNAL_ROMS, SD_ROMS, SD_3DS), uris(repo))
    }

    /** The bug this feature exists to prevent: picking a folder replaced the old one. */
    @Test
    fun `adding a second location does not replace the first`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        repo.add(SD_ROMS)
        assertEquals(listOf(INTERNAL_ROMS, SD_ROMS), uris(repo))
    }

    // endregion

    // region remove

    @Test
    fun `removing one location leaves the others intact and in order`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        repo.add(SD_ROMS)
        repo.add(SD_3DS)
        assertTrue(repo.remove(SD_ROMS))
        assertEquals(listOf(INTERNAL_ROMS, SD_3DS), uris(repo))
    }

    @Test
    fun `removing a location that is not saved changes nothing`() {
        val store = MemoryStore()
        val repo = repo(store)
        repo.add(INTERNAL_ROMS)
        val writes = store.writes
        assertFalse(repo.remove(SD_ROMS))
        assertEquals(writes, store.writes)
        assertEquals(listOf(INTERNAL_ROMS), uris(repo))
    }

    @Test
    fun `removing matches the folder however its uri was encoded`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        assertTrue(repo.remove("content://com.android.externalstorage.documents/tree/primary:Roms"))
        assertTrue(repo.current.isEmpty())
    }

    /** Removing a parent folder must not take a separately saved sub-folder with it. */
    @Test
    fun `removing a folder leaves a saved sub-folder of it in place`() {
        val repo = repo(MemoryStore())
        repo.add(SD_ROMS)
        repo.add(SD_3DS)
        repo.remove(SD_ROMS)
        assertEquals(listOf(SD_3DS), uris(repo))
    }

    // endregion

    // region duplicates

    @Test
    fun `the same folder cannot be added twice`() {
        val repo = repo(MemoryStore())
        repo.add(SD_ROMS)
        val again = repo.add(SD_ROMS)
        assertTrue(again is AddLocationResult.AlreadySaved)
        assertEquals(SD_ROMS, (again as AddLocationResult.AlreadySaved).existing.treeUri)
        assertEquals(1, repo.current.size)
    }

    @Test
    fun `a differently encoded uri for the same folder is a duplicate`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        val variants = listOf(
            "content://com.android.externalstorage.documents/tree/primary:Roms",
            "content://com.android.externalstorage.documents/tree/primary%3ARoms%2F",
            "content://COM.ANDROID.EXTERNALSTORAGE.DOCUMENTS/tree/primary%3ARoms",
        )
        variants.forEach { assertTrue(it, repo.add(it) is AddLocationResult.AlreadySaved) }
        assertEquals(1, repo.current.size)
    }

    @Test
    fun `a folder with the same path on another volume is not a duplicate`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        assertTrue(repo.add(SD_ROMS) is AddLocationResult.Added)
        assertTrue(repo.add(SECOND_SD_ROMS) is AddLocationResult.Added)
        assertEquals(3, repo.current.size)
    }

    @Test
    fun `folder names differing only in case are different folders`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        assertTrue(repo.add("content://com.android.externalstorage.documents/tree/primary%3AROMS") is AddLocationResult.Added)
    }

    @Test
    fun `something that is not a folder tree is refused`() {
        val repo = repo(MemoryStore())
        assertEquals(AddLocationResult.Invalid, repo.add("file:///sdcard/Roms"))
        assertEquals(AddLocationResult.Invalid, repo.add("/Roms"))
        assertTrue(repo.current.isEmpty())
    }

    // endregion

    // region persistence

    @Test
    fun `locations survive an app restart`() {
        val store = MemoryStore()
        repo(store).apply {
            add(INTERNAL_ROMS)
            add(SD_ROMS)
        }
        assertEquals(listOf(INTERNAL_ROMS, SD_ROMS), uris(repo(store)))
    }

    @Test
    fun `a removal survives an app restart`() {
        val store = MemoryStore()
        repo(store).apply {
            add(INTERNAL_ROMS)
            add(SD_ROMS)
            remove(INTERNAL_ROMS)
        }
        assertEquals(listOf(SD_ROMS), uris(repo(store)))
    }

    /** The exact uri is stored, never a path: it is what the persisted permission is for. */
    @Test
    fun `the exact picked uri is what gets stored`() {
        val store = MemoryStore()
        repo(store).add(SD_3DS)
        assertTrue(store.data.getValue(RomLocationsRepository.KEY).contains(SD_3DS))
    }

    @Test
    fun `a corrupted store starts empty instead of crashing`() {
        val store = MemoryStore(mapOf(RomLocationsRepository.KEY to "{not json"))
        assertTrue(repo(store).current.isEmpty())
    }

    @Test
    fun `storage names are pinned, because renaming them forgets every saved folder`() {
        assertEquals("rgds_rom_locations", RomLocationsRepository.PREFS)
        assertEquals("rom_locations", RomLocationsRepository.KEY)
    }

    // endregion

    // region migration from the single-folder build

    @Test
    fun `the old single library folder becomes the first location`() {
        val repo = repo(MemoryStore(), legacy = INTERNAL_ROMS)
        assertEquals(listOf(INTERNAL_ROMS), uris(repo))
        assertTrue(repo.current.single().legacyIdentity)
    }

    @Test
    fun `the migrated folder is still there after a restart`() {
        val store = MemoryStore()
        repo(store, legacy = INTERNAL_ROMS)
        assertEquals(listOf(INTERNAL_ROMS), uris(repo(store, legacy = INTERNAL_ROMS)))
    }

    @Test
    fun `adding a location after migrating keeps the migrated one`() {
        val repo = repo(MemoryStore(), legacy = INTERNAL_ROMS)
        repo.add(SD_ROMS)
        assertEquals(listOf(INTERNAL_ROMS, SD_ROMS), uris(repo))
        assertFalse(repo.current[1].legacyIdentity)
    }

    /** The migration runs once: a folder the user removed must not come back on relaunch. */
    @Test
    fun `a removed migrated folder does not return on the next launch`() {
        val store = MemoryStore()
        repo(store, legacy = INTERNAL_ROMS).remove(INTERNAL_ROMS)
        assertTrue(repo(store, legacy = INTERNAL_ROMS).current.isEmpty())
    }

    @Test
    fun `no old folder means nothing is migrated`() {
        assertTrue(repo(MemoryStore(), legacy = null).current.isEmpty())
        assertTrue(repo(MemoryStore(), legacy = " ").current.isEmpty())
    }

    @Test
    fun `an unusable old value is not migrated`() {
        assertTrue(repo(MemoryStore(), legacy = "not a uri").current.isEmpty())
    }

    // endregion

    // region identity

    @Test
    fun `only the first folder keeps the single-folder identity`() {
        val repo = repo(MemoryStore())
        repo.add(INTERNAL_ROMS)
        repo.add(SD_ROMS)
        assertTrue(repo.current[0].legacyIdentity)
        assertFalse(repo.current[1].legacyIdentity)
        assertNull(RomLocations.scopeOf(repo.current[0]))
        assertEquals(repo.current[1].key, RomLocations.scopeOf(repo.current[1]))
    }

    // endregion

    companion object {
        const val INTERNAL_ROMS = "content://com.android.externalstorage.documents/tree/primary%3ARoms"
        const val SD_ROMS = "content://com.android.externalstorage.documents/tree/1234-ABCD%3ARoms"
        const val SD_3DS = "content://com.android.externalstorage.documents/tree/1234-ABCD%3ARoms%2F3DS"
        const val SECOND_SD_ROMS = "content://com.android.externalstorage.documents/tree/9876-FEDC%3ARoms"
    }
}
