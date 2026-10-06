package com.rork.rgdsartworkprep.data

/**
 * Keeps a gamelist.xml in step with the folder it sits in: a `<game>` whose ROM file is
 * gone from that folder is removed, and everything else in the file — other games,
 * favourites, play counts, `<folder>` entries, comments, formatting — is left exactly
 * as it was.
 *
 * Deliberately conservative. An entry is only removed when its file is *confirmed*
 * missing: the folders on its path were listed successfully and the name is not there.
 * A folder that could not be read, an absolute path, or a path leaving the folder
 * (`../`) is "unknown", and unknown entries are always kept. That is what stops an SD
 * card unmounting mid-scan from emptying its gamelist.
 *
 * Paths are resolved only from the gamelist's own folder, inside its own saved
 * location, so one location's gamelist can never be changed by another's contents.
 *
 * Pure text work, so every rule is checked on the JVM.
 */
object GamelistReconcile {

    enum class Presence { Present, Missing, Unknown }

    /** One child of a folder, as the storage provider listed it. */
    data class Listed<F>(val name: String, val isDirectory: Boolean, val handle: F)

    /** The document after removal, and the `<path>` of every `<game>` taken out. */
    data class Pruned(val xml: String, val removedPaths: List<String>) {
        val removedCount: Int get() = removedPaths.size
    }

    /**
     * One `<game>` element with the whitespace that indents it and the line break that
     * ends it, so removing it leaves no blank line. `<gameList>` is not matched (no
     * space or `>` after `game`), and neither is a self-closing `<game ... />`.
     */
    private val GAME_BLOCK = Regex("""(?s)[ \t]*<game(?:\s[^>]*?)?(?<!/)>(.*?)</game\s*>[ \t]*(?:\r?\n)?""")
    private val PATH = Regex("""(?s)<path(?:\s[^>]*)?>(.*?)</path\s*>""")
    private val CDATA = Regex("""(?s)<!\[CDATA\[(.*?)]]>""")
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    private val NUMERIC_ENTITY = Regex("""&#(x[0-9a-fA-F]+|[0-9]+);""")

    /** The `<path>` of every `<game>`, in document order. */
    fun gamePaths(xml: String): List<String> =
        GAME_BLOCK.findAll(xml).mapNotNull { pathIn(it.groupValues[1]) }.toList()

    /**
     * Removes every `<game>` whose path [isStale] says is gone. Returns [xml] itself,
     * untouched, when nothing is removed.
     */
    fun removeGames(xml: String, isStale: (String) -> Boolean): Pruned {
        val removed = mutableListOf<String>()
        val result = GAME_BLOCK.replace(xml) { match ->
            val path = pathIn(match.groupValues[1])
            if (path != null && isStale(path)) {
                removed += path
                ""
            } else {
                match.value
            }
        }
        return Pruned(if (removed.isEmpty()) xml else result, removed)
    }

    /**
     * Whether the file a gamelist [path] names exists, resolved from [root], the
     * folder that holds the gamelist. [list] returns a folder's children, or null when
     * it could not be read. Names compare case-insensitively, as on FAT/exFAT SD cards
     * and Android's shared storage; a directory counts too, for folder-style games.
     */
    fun <F> presence(path: String, root: F, list: (F) -> List<Listed<F>>?): Presence {
        val segments = relativeSegments(path) ?: return Presence.Unknown
        if (segments.isEmpty()) return Presence.Unknown
        var folder = root
        segments.forEachIndexed { index, segment ->
            val children = list(folder) ?: return Presence.Unknown
            val isLast = index == segments.lastIndex
            val match = children.firstOrNull {
                it.name.equals(segment, ignoreCase = true) && (isLast || it.isDirectory)
            } ?: return Presence.Missing
            if (isLast) return Presence.Present
            folder = match.handle
        }
        return Presence.Unknown
    }

    /** Removes every `<game>` confirmed missing from [root]; each folder is listed once. */
    fun <F> prune(xml: String, root: F, list: (F) -> List<Listed<F>>?): Pruned {
        val cache = HashMap<F, List<Listed<F>>?>()
        val cached: (F) -> List<Listed<F>>? = { folder ->
            if (cache.containsKey(folder)) cache[folder] else list(folder).also { cache[folder] = it }
        }
        return removeGames(xml) { presence(it, root, cached) == Presence.Missing }
    }

    /** `./GBA/x.gba` -> [GBA, x.gba]; null when the path cannot be checked from here. */
    internal fun relativeSegments(path: String): List<String>? {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.startsWith("~")) return null
        if (SCHEME.containsMatchIn(normalized)) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        // `%ROMPATH%/x.gba`, `$HOME/x.gba`: a base some frontend expands, not this folder.
        if (segments.any { it.startsWith("%") || it.startsWith("$") }) return null
        return segments
    }

    private fun pathIn(body: String): String? {
        val raw = PATH.find(body)?.groupValues?.get(1) ?: return null
        val text = CDATA.find(raw)?.groupValues?.get(1) ?: unescape(raw)
        return text.trim().ifEmpty { null }
    }

    private fun unescape(value: String): String = NUMERIC_ENTITY
        .replace(value) { match ->
            val code = match.groupValues[1]
            val number = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            number?.let { String(Character.toChars(it)) } ?: match.value
        }
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
