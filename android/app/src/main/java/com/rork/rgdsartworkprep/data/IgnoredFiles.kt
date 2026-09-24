package com.rork.rgdsartworkprep.data

import java.util.Locale

/**
 * Filenames the user has told the app to treat as if they did not exist.
 *
 * Matching is exact and case-insensitive, and nothing else: no extension rules, no
 * prefixes, no wildcards, no trimming of the name being tested. `boot11.bin` matches
 * `BOOT11.BIN` and nothing more — not `boot11_backup.bin`, not `boot11.bin.bak`, and
 * above all not every other `.bin` in the library, which is where real disc games
 * live. A list that could grow into an extension filter would quietly delete a
 * PlayStation collection from every scan.
 *
 * Case is folded with [Locale.ROOT] rather than the device locale. Under a Turkish
 * locale `"I".lowercase()` is a dotless `ı`, so a list entered as `INDEX.BIN` would
 * stop matching `index.bin` the day the user changed their language.
 *
 * Immutable, and equal by content, so it is safe to publish from a StateFlow and to
 * read from the scanning thread while the UI replaces it.
 */
class IgnoredFiles(names: Collection<String>) {

    /** The stored names as they were entered, one per case-folded key, in display order. */
    val names: List<String>

    private val keys: Set<String>

    init {
        val byKey = LinkedHashMap<String, String>()
        names.forEach { name ->
            // A blank entry can only come from a damaged preference; it could never
            // match a real file, so it is dropped rather than shown as an empty row.
            if (name.isNotBlank()) byKey.putIfAbsent(keyOf(name), name)
        }
        this.names = byKey.values.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
        keys = byKey.keys
    }

    val size: Int get() = names.size

    val isEmpty: Boolean get() = keys.isEmpty()

    /** True when [fileName] is on the list. The name is compared exactly as found on disk. */
    fun matches(fileName: String): Boolean = keys.isNotEmpty() && keyOf(fileName) in keys

    /**
     * Splits [items] into those still eligible and those that are ignored, keeping the
     * original order within each half.
     */
    fun <T> partition(items: List<T>, fileName: (T) -> String): Split<T> {
        if (keys.isEmpty()) return Split(kept = items, removed = emptyList())
        val (removed, kept) = items.partition { matches(fileName(it)) }
        return Split(kept = kept, removed = removed)
    }

    /**
     * Validates a name typed by hand in Settings.
     *
     * Typed input is trimmed — a stray space from the keyboard is never intended —
     * whereas a name taken from a scan result is stored exactly as the file is called.
     */
    fun check(input: String): EntryCheck {
        val name = input.trim()
        return when {
            name.isEmpty() -> EntryCheck.Blank
            name.contains('/') || name.contains('\\') -> EntryCheck.IncludesFolder
            // Rejected outright rather than stored literally: someone typing `*.bin`
            // expects an extension filter, and silently storing a name that matches
            // nothing would leave them believing it worked.
            name.contains('*') || name.contains('?') -> EntryCheck.HasWildcard
            else -> existing(name)?.let { EntryCheck.AlreadyIgnored(it) } ?: EntryCheck.Valid(name)
        }
    }

    private fun existing(name: String): String? {
        val key = keyOf(name)
        return names.firstOrNull { keyOf(it) == key }
    }

    override fun equals(other: Any?): Boolean = other is IgnoredFiles && other.names == names

    override fun hashCode(): Int = names.hashCode()

    override fun toString(): String = "IgnoredFiles(${names.size})"

    /** The result of [partition]. */
    data class Split<T>(val kept: List<T>, val removed: List<T>)

    companion object {
        val NONE: IgnoredFiles = IgnoredFiles(emptyList())

        /** The comparison key for a filename. Exposed so storage and tests agree on it. */
        fun keyOf(fileName: String): String = fileName.lowercase(Locale.ROOT)
    }
}

/** Outcome of validating a filename typed into Settings. */
sealed interface EntryCheck {
    data class Valid(val fileName: String) : EntryCheck
    data object Blank : EntryCheck
    data object IncludesFolder : EntryCheck
    data object HasWildcard : EntryCheck
    data class AlreadyIgnored(val existing: String) : EntryCheck
}
