package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.SystemCatalog

/**
 * Decides which files in one folder become games, and which system each belongs to.
 *
 * This is the file half of the library walk, lifted out verbatim so it can run on the
 * JVM. The rules and their order are exactly what the walk applied inline before:
 * candidate filter, disc-set grouping, detection, then dropping an ambiguous container
 * that no folder could place. The one addition is the ignore list.
 *
 * The ignore check sits after grouping and before detection, and both halves of that
 * are deliberate:
 *
 * - **Before detection**, because an ignored file must cost nothing and decide nothing:
 *   no system lookup, and therefore no row, no count, no checksum, no request.
 * - **After grouping**, so ignoring a file can only ever remove games, never add one.
 *   Grouping hides a `.bin` behind the `.cue` that describes it. Were the ignore list
 *   applied first, ignoring `Game.cue` would make `Game.bin` stand alone and appear as
 *   a brand-new entry on the next scan — the opposite of what the user asked for, and
 *   inconsistent with ignoring the same row mid-scan, where nothing reappears.
 */
object ScanCandidates {

    /** A file accepted as a game, with the system detection assigned to it. */
    data class Accepted<T>(val item: T, val system: GameSystem?)

    /**
     * @param files the folder's files, directories already excluded
     * @param fileName reads the on-disk name of one file
     * @param folderChain folder names from this folder outwards to the library root
     * @param detect injectable so tests can prove an ignored file never reaches it
     * @param onIgnored called once for each file the ignore list removed, so the walk
     *   can say how many it left out. Only files that would otherwise have become a
     *   game are reported: a track hidden behind its ignored cue sheet was never a
     *   game of its own, so ignoring one sheet counts as one file.
     */
    fun <T> select(
        files: List<T>,
        fileName: (T) -> String,
        folderChain: List<String>,
        ignored: IgnoredFiles,
        detect: (String, List<String>) -> GameSystem? = SystemCatalog::detect,
        onIgnored: (T) -> Unit = {},
    ): List<Accepted<T>> {
        val candidates = files.filter { file ->
            val name = fileName(file)
            val extension = extensionOf(name)
            !name.startsWith(".") &&
                extension.isNotBlank() &&
                extension !in SystemCatalog.imageExtensions &&
                extension in SystemCatalog.knownRomExtensions
        }
        // Disc sets scatter sheets, tracks and playlists across many files — only the
        // file a player would actually launch becomes a game.
        val playable = SystemCatalog.playableFileNames(candidates.map(fileName))

        return candidates.mapNotNull { file ->
            val name = fileName(file)
            if (name !in playable) return@mapNotNull null
            if (ignored.matches(name)) {
                onIgnored(file)
                return@mapNotNull null
            }

            val system = detect(name, folderChain)
            if (system == null && extensionOf(name) in SystemCatalog.ambiguousExtensions) {
                return@mapNotNull null
            }
            Accepted(file, system)
        }
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
}
