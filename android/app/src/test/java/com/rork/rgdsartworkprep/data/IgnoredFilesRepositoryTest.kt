package com.rork.rgdsartworkprep.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding, removing, and above all keeping the list.
 *
 * A restart is modelled as a new repository over the same store, which is what
 * happens on device: the process dies and a fresh repository reads the preference
 * file back. An upgrade is a new repository over a store an older build wrote. The
 * store's data lives in the app's private directory, which Android keeps across an
 * update as long as the applicationId is unchanged — pinned by VersionStampTest.
 */
class IgnoredFilesRepositoryTest {

    private class MemoryStore(initial: Map<String, Set<String>> = emptyMap()) : StringSetStore {
        val data: MutableMap<String, Set<String>> = initial.toMutableMap()
        var writes: Int = 0

        override fun read(key: String): Set<String>? = data[key]

        override fun write(key: String, value: Set<String>) {
            writes++
            data[key] = value.toSet()
        }
    }

    // region adding

    @Test
    fun `adding a name puts it on the list`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        assertTrue(repo.add("boot11.bin"))
        assertTrue(repo.current.matches("boot11.bin"))
        assertEquals(listOf("boot11.bin"), repo.current.names)
    }

    @Test
    fun `a name added from a scan is stored exactly as the file is called`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.add("Shared_Font.bin")
        assertEquals(listOf("Shared_Font.bin"), repo.current.names)
    }

    @Test
    fun `adding a name already present in another case changes nothing`() {
        val store = MemoryStore()
        val repo = IgnoredFilesRepository(store)
        repo.add("boot9.bin")
        val writesBefore = store.writes

        assertFalse(repo.add("BOOT9.BIN"))
        assertEquals(listOf("boot9.bin"), repo.current.names)
        assertEquals("no redundant write", writesBefore, store.writes)
    }

    @Test
    fun `a blank name is refused`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        assertFalse(repo.add("  "))
        assertTrue(repo.current.isEmpty)
    }

    /** The manual path in Settings: validate, then add the trimmed name. */
    @Test
    fun `a manually typed name is added after validation`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        val check = repo.current.check("  seeddb.bin  ")
        assertTrue(check is EntryCheck.Valid)
        repo.add((check as EntryCheck.Valid).fileName)
        assertEquals(listOf("seeddb.bin"), repo.current.names)
    }

    @Test
    fun `the published list updates the moment a name is added`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.add("boot11.bin")
        assertTrue(repo.ignored.value.matches("boot11.bin"))
    }

    // endregion

    // region removing

    @Test
    fun `removing a name takes it off the list`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.add("boot11.bin")
        repo.add("boot9.bin")

        assertTrue(repo.remove("boot11.bin"))
        assertFalse(repo.current.matches("boot11.bin"))
        assertTrue("the other entry is untouched", repo.current.matches("boot9.bin"))
    }

    @Test
    fun `removing matches the entry under any case`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.add("boot11.bin")
        assertTrue(repo.remove("BOOT11.BIN"))
        assertTrue(repo.current.isEmpty)
    }

    @Test
    fun `removing a name that is not listed reports so`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.add("boot11.bin")
        assertFalse(repo.remove("boot9.bin"))
        assertEquals(1, repo.current.size)
    }

    @Test
    fun `removing the last name leaves an empty list that persists as empty`() {
        val store = MemoryStore()
        IgnoredFilesRepository(store).apply {
            add("boot11.bin")
            remove("boot11.bin")
        }
        assertTrue(IgnoredFilesRepository(store).current.isEmpty)
    }

    // endregion

    // region persistence

    @Test
    fun `the list survives a restart`() {
        val store = MemoryStore()
        IgnoredFilesRepository(store).apply {
            add("boot11.bin")
            add("boot9.bin")
            add("seeddb.bin")
            add("shared_font.bin")
        }

        val afterRestart = IgnoredFilesRepository(store)
        assertEquals(
            listOf("boot11.bin", "boot9.bin", "seeddb.bin", "shared_font.bin"),
            afterRestart.current.names,
        )
    }

    @Test
    fun `a removal survives a restart`() {
        val store = MemoryStore()
        IgnoredFilesRepository(store).apply {
            add("boot11.bin")
            add("boot9.bin")
            remove("boot11.bin")
        }
        assertEquals(listOf("boot9.bin"), IgnoredFilesRepository(store).current.names)
    }

    /** Written before it is published, so nothing acts on an entry a restart forgets. */
    @Test
    fun `every change is written to the store before it is visible`() {
        val store = MemoryStore()
        val repo = IgnoredFilesRepository(store)
        repo.add("boot11.bin")
        assertEquals(setOf("boot11.bin"), store.data[IgnoredFilesRepository.KEY])
    }

    /** What the next release reads when this one has written its list. */
    @Test
    fun `a list written by an earlier build is read back after an upgrade`() {
        val written = MemoryStore(mapOf(IgnoredFilesRepository.KEY to setOf("boot11.bin", "BOOT9.BIN")))
        val upgraded = IgnoredFilesRepository(written)
        assertTrue(upgraded.current.matches("boot11.bin"))
        assertTrue(upgraded.current.matches("boot9.bin"))
    }

    /** Every install from before this feature has no list at all, and must scan as before. */
    @Test
    fun `an install upgrading from a build without the feature starts with nothing ignored`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        assertTrue(repo.current.isEmpty)
        assertFalse(repo.current.matches("boot11.bin"))
    }

    /**
     * The storage names are the upgrade contract. Renaming either makes every
     * existing install silently forget its list, with nothing to show it happened.
     */
    @Test
    fun `the storage names never change`() {
        assertEquals("rgds_ignored_files", IgnoredFilesRepository.PREFS)
        assertEquals("ignored_file_names", IgnoredFilesRepository.KEY)
    }

    // endregion
}
