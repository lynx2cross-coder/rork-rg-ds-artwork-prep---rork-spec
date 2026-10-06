package com.rork.rgdsartworkprep.data

/**
 * What one saved location's walk saw about covers, so the Artwork screen can say why
 * a cover that is on the card is or is not shown — evidence from the device itself
 * rather than a guess.
 *
 * Counting only: the walk's decisions (which files are games, which cover belongs to
 * which game) are unchanged. Pure, so the accounting runs on the JVM.
 */
object ArtworkDiscovery {

    /** Totals for one location, summed over every folder its walk visited. */
    data class Report(
        /** Cover images seen, in a cover folder or beside the games. */
        val coverFiles: Int = 0,
        /** Of those, how many share a name with a game in the same folder. */
        val matchedCovers: Int = 0,
        /** Covers whose name matches no game in their folder, so nothing shows them. */
        val unmatchedCovers: Int = 0,
        /** A few of those, as paths inside the location, e.g. `3DS/Imgs/Kid Icarus.png`. */
        val unmatchedSamples: List<String> = emptyList(),
        /** Files the walk did not read as games, by extension, e.g. `cci -> 12`. */
        val unreadByExtension: Map<String, Int> = emptyMap(),
        /**
         * Images one folder below a cover folder (`media/box2dfront/x.png`). The walk
         * reads cover folders one level deep only, so these are never shown.
         */
        val nestedCoverFiles: Int = 0,
        /** A few of those sub-folders, e.g. `3DS/media/box2dfront`. */
        val nestedCoverFolders: List<String> = emptyList(),
    ) {
        val unreadTotal: Int get() = unreadByExtension.values.sum()

        /** The most common unread extensions, most frequent first. */
        fun topUnread(limit: Int = 3): List<Pair<String, Int>> =
            unreadByExtension.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(limit)
                .map { it.key to it.value }

        operator fun plus(other: Report): Report = Report(
            coverFiles = coverFiles + other.coverFiles,
            matchedCovers = matchedCovers + other.matchedCovers,
            unmatchedCovers = unmatchedCovers + other.unmatchedCovers,
            unmatchedSamples = (unmatchedSamples + other.unmatchedSamples).take(MAX_SAMPLES),
            unreadByExtension = (unreadByExtension.keys + other.unreadByExtension.keys).associateWith {
                (unreadByExtension[it] ?: 0) + (other.unreadByExtension[it] ?: 0)
            },
            nestedCoverFiles = nestedCoverFiles + other.nestedCoverFiles,
            nestedCoverFolders = (nestedCoverFolders + other.nestedCoverFolders).take(MAX_SAMPLES),
        )
    }

    /**
     * Accounts for one folder of the walk.
     *
     * @param folderPath the folder's path inside the location, `""` at its root
     * @param coversByBase covers the walk found for this folder, keyed by lowercase
     *   name without extension, valued by path relative to the folder (`Imgs/x.png`)
     * @param romBaseNames lowercase names, without extension, of the games accepted here
     * @param otherFileNames every other file in the folder that is not a cover
     * @param knownRomExtensions extensions the walk can read as games
     * @param isIgnored the user's Ignored Files list; those files are never reported
     */
    fun folder(
        folderPath: String,
        coversByBase: Map<String, String>,
        romBaseNames: Set<String>,
        otherFileNames: List<String>,
        knownRomExtensions: Set<String>,
        isIgnored: (String) -> Boolean,
    ): Report {
        val unmatched = coversByBase.filterKeys { it !in romBaseNames }
        val prefix = folderPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val unread = otherFileNames
            .asSequence()
            .filterNot { it.startsWith(".") || isIgnored(it) }
            .map { it.substringAfterLast('.', "").lowercase() }
            .filter { it.isNotEmpty() && it !in knownRomExtensions && it !in NOT_GAMES }
            .groupingBy { it }
            .eachCount()
        return Report(
            coverFiles = coversByBase.size,
            matchedCovers = coversByBase.size - unmatched.size,
            unmatchedCovers = unmatched.size,
            unmatchedSamples = unmatched.values.sorted().take(MAX_SAMPLES).map { "$prefix$it" },
            unreadByExtension = unread,
        )
    }

    /** Files that are never games, so listing them would only bury the useful ones. */
    private val NOT_GAMES = setOf(
        "xml", "txt", "nfo", "ini", "cfg", "json", "db", "dat", "log", "md", "pdf", "url",
        "sav", "srm", "state", "sha1", "md5", "sfv", "webp", "gif", "bmp", "mp4", "nomedia",
    )

    const val MAX_SAMPLES = 3
}
