package com.rork.rgdsartworkprep.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optional "Add common 3DS system files" action.
 *
 * It is a shortcut into the user's own list and nothing more: never applied without
 * the button, never a detection rule, and its names become ordinary removable entries.
 */
class IgnoredFilePresetsTest {

    private class MemoryStore(initial: Map<String, Set<String>> = emptyMap()) : StringSetStore {
        val data: MutableMap<String, Set<String>> = initial.toMutableMap()
        var writes: Int = 0
        override fun read(key: String): Set<String>? = data[key]
        override fun write(key: String, value: Set<String>) {
            writes++
            data[key] = value.toSet()
        }
    }

    private val preset = IgnoredFilePresets.threeDsSystemFiles

    @Test
    fun `the preset is exactly the four requested names`() {
        assertEquals(listOf("boot9.bin", "boot11.bin", "seeddb.bin", "shared_font.bin"), preset)
    }

    // region never automatic

    @Test
    fun `a new install ignores none of the preset files`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        preset.forEach { assertFalse(it, repo.current.matches(it)) }
    }

    @Test
    fun `an existing install upgrading keeps exactly its own list`() {
        val repo = IgnoredFilesRepository(MemoryStore(mapOf(IgnoredFilesRepository.KEY to setOf("mine.bin"))))
        assertEquals(listOf("mine.bin"), repo.current.names)
    }

    /** The scanner only consults the user's list, so the preset alone excludes nothing. */
    @Test
    fun `without the button the preset files are still scanned`() {
        val accepted = ScanCandidates.select(
            files = preset + "Pokemon Y.3ds",
            fileName = { it },
            folderChain = listOf("3DS"),
            ignored = IgnoredFiles.NONE,
        ).map { it.item }
        assertTrue(accepted.containsAll(preset))
    }

    // endregion

    // region adding

    @Test
    fun `the action adds all four to an empty list in one write`() {
        val store = MemoryStore()
        val repo = IgnoredFilesRepository(store)
        assertEquals(preset, repo.addAll(preset))
        preset.forEach { assertTrue(it, repo.current.matches(it)) }
        assertEquals(1, store.writes)
    }

    @Test
    fun `names already present are not duplicated`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.add("boot9.bin")
        repo.add("SEEDDB.BIN")

        assertEquals(listOf("boot11.bin", "shared_font.bin"), repo.addAll(preset))
        assertEquals(4, repo.current.size)
        assertTrue("the user's own casing is kept", repo.current.names.contains("SEEDDB.BIN"))
    }

    @Test
    fun `pressing it when all are present changes nothing`() {
        val store = MemoryStore()
        val repo = IgnoredFilesRepository(store)
        repo.addAll(preset)
        val writes = store.writes

        assertTrue(repo.addAll(preset).isEmpty())
        assertEquals(4, repo.current.size)
        assertEquals("no redundant write", writes, store.writes)
    }

    @Test
    fun `existing unrelated entries are kept`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.add("aes_keys.txt")
        repo.addAll(preset)
        assertEquals(5, repo.current.size)
        assertTrue(repo.current.matches("aes_keys.txt"))
    }

    @Test
    fun `missing names are reported in preset order`() {
        assertEquals(preset, IgnoredFilePresets.missingFrom(IgnoredFiles.NONE))
        assertEquals(
            listOf("boot11.bin", "shared_font.bin"),
            IgnoredFilePresets.missingFrom(IgnoredFiles(listOf("BOOT9.BIN", "seeddb.bin"))),
        )
        assertTrue(IgnoredFilePresets.missingFrom(IgnoredFiles(preset)).isEmpty())
    }

    // endregion

    // region ordinary entries afterwards

    @Test
    fun `each added name can be removed on its own`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.addAll(preset)
        assertTrue(repo.remove("seeddb.bin"))
        assertFalse(repo.current.matches("seeddb.bin"))
        assertEquals(3, repo.current.size)
    }

    @Test
    fun `added names survive a restart like any other entry`() {
        val store = MemoryStore()
        IgnoredFilesRepository(store).addAll(preset)
        val restarted = IgnoredFilesRepository(store)
        preset.forEach { assertTrue(it, restarted.current.matches(it)) }
    }

    /** Exact matching still applies: only these names, never every `.bin`. */
    @Test
    fun `after adding them other bin files are still scanned`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        repo.addAll(preset)
        val accepted = ScanCandidates.select(
            files = preset + listOf("boot9_backup.bin", "Crash Bandicoot.bin"),
            fileName = { it },
            folderChain = listOf("PSX"),
            ignored = repo.current,
        ).map { it.item }
        assertEquals(listOf("boot9_backup.bin", "Crash Bandicoot.bin"), accepted)
    }

    @Test
    fun `adding a batch with repeats inside it stores each name once`() {
        val repo = IgnoredFilesRepository(MemoryStore())
        assertEquals(listOf("boot9.bin"), repo.addAll(listOf("boot9.bin", "BOOT9.BIN", " ")))
        assertEquals(1, repo.current.size)
    }

    // endregion
}
