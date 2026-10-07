package com.rork.rgdsartworkprep.data

/**
 * Decides which cover images in one cover folder (`Imgs`, `images`, `media`, `boxart`,
 * `covers`) belong to no game any more, so the optional cleanup can remove them.
 *
 * Deliberately conservative, because a deleted cover cannot be brought back:
 *
 * - Only image files directly inside a cover folder are candidates. Images beside the
 *   ROMs, images in deeper sub-folders (`media/box2dfront/`), hidden files and anything
 *   that is not jpg/jpeg/png are never touched.
 * - A cover is kept when *any* file or folder next to the cover folder shares its name,
 *   whatever its extension, whether or not it is detected as a game and whether or not
 *   it is on the Ignored Files list. A ROM the app cannot read yet therefore keeps its
 *   cover.
 * - Names compare case-insensitively, and common naming schemes count as a match: a
 *   suffix (`Game-image.png`, `Game (Box).png`) or a cover named after a multi-disc
 *   set (`Final Fantasy VII.png` for `Final Fantasy VII (Disc 1).chd`).
 * - Nothing is removed from a folder that holds no game, or from a cover folder in
 *   which no cover matches any game: that points to another naming scheme or a shared
 *   image folder, not to deleted ROMs.
 *
 * Pure, so every rule runs on the JVM.
 */
object OrphanArtwork {

    /** One child of a folder, as the storage provider listed it. */
    data class Item<F>(val name: String, val isDirectory: Boolean, val handle: F)

    /**
     * The covers in [coverFolder] that no entry of [romFolder] accounts for.
     *
     * @param romFolder the folder holding the cover folder, i.e. the ROMs' own folder
     * @param coverFolder the cover folder's children
     * @param hasGame whether [romFolder] holds at least one file read as a game
     */
    fun <F> orphans(
        romFolder: List<Item<F>>,
        coverFolder: List<Item<F>>,
        hasGame: Boolean,
        imageExtensions: Set<String>,
    ): List<Item<F>> {
        if (!hasGame) return emptyList()
        val covers = coverFolder.filter {
            !it.isDirectory && !it.name.startsWith(".") && extensionOf(it.name) in imageExtensions
        }
        if (covers.isEmpty()) return emptyList()

        val owners = romFolder
            .asSequence()
            .filter { !it.name.startsWith(".") }
            .filter { it.isDirectory || extensionOf(it.name) !in imageExtensions }
            .flatMap { sequenceOf(it.name.lowercase(), baseOf(it.name).lowercase()) }
            .filter { it.isNotBlank() }
            .toSet()

        val orphaned = covers.filterNot { cover -> isOwned(baseOf(cover.name).lowercase(), owners) }
        // No cover here matches any game: a different naming scheme, not deleted ROMs.
        if (orphaned.size == covers.size) return emptyList()
        return orphaned
    }

    private fun isOwned(cover: String, owners: Set<String>): Boolean {
        if (cover.isBlank() || cover in owners) return true
        return owners.any { owner ->
            (cover.length > owner.length && cover.startsWith(owner) && cover[owner.length] in COVER_SUFFIX_START) ||
                (owner.length > cover.length && owner.startsWith(cover) && owner[cover.length] in SET_SUFFIX_START)
        }
    }

    /** `Game-image`, `Game_thumb`, `Game (Box)`, `Game [!]`, `Game.front`. */
    private val COVER_SUFFIX_START = setOf('-', '_', ' ', '(', '[', '.')

    /** `Final Fantasy VII (Disc 1)`, `Game [b]`, `Game Rev 1`. */
    private val SET_SUFFIX_START = setOf(' ', '(', '[')

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun baseOf(name: String): String = name.substringBeforeLast('.', name)
}
