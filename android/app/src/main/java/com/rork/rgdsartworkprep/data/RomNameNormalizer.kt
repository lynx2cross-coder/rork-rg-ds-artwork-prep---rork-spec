package com.rork.rgdsartworkprep.data

import android.util.Log
import com.rork.rgdsartworkprep.model.SystemCatalog

/**
 * Turns messy ROM filenames into a searchable game title.
 *
 * `The Legend of Zelda - The Minish Cap (USA) [!].gba` -> `The Legend of Zelda The Minish Cap`
 * `SCOOBY_NDS_FINAL2.nds` -> `Scooby`
 * `PSP/Wipeout Pure/EBOOT.PBP` -> `Wipeout Pure` (disc games often hide behind a
 * meaningless filename inside a per-game folder)
 */
object RomNameNormalizer {

    /**
     * Stand-in for a pattern this device refused: it simply never fires.
     *
     * Declared first on purpose — these properties are built in the order they are
     * written, so a fallback defined lower down would still be empty at the moment the
     * first pattern needed it.
     */
    private val NEVER_MATCHES = Regex(Regex.escape("\u0000 no such pattern \u0000"))

    // Braces are escaped everywhere they stand for themselves. Android matches with
    // ICU, which - unlike the desktop JVM this code reads correctly on - rejects a
    // literal `{` or `}` that is not escaped. An unescaped one compiles in a JVM test
    // and throws on the handheld.
    private val bracketTags =
        compile("\\((?:[^()]*)\\)|\\[(?:[^\\[\\]]*)\\]|\\{(?:[^\\{\\}]*)\\}")
    private val separators = compile("[_.]+")
    private val whitespace = compile("\\s+")
    private val dashes = compile("\\s*-\\s*")
    private val punctuation = compile("[!#$%^&*+=|<>~`]")
    private val nonAlphanumeric = compile("[^a-z0-9]")
    private val articles = compile("\\b(the|a|an|of|and|de|le|la)\\b")
    private val trailingJunk = compile(
        "\\b(final|fixed|patched|trimmed|clean|good|verified|proto|beta|demo|sample|hack|" +
            "rev|revision|v\\d+(?:\\.\\d+)*|disc|disk|cd|track)\\s*\\d*\\b",
        RegexOption.IGNORE_CASE,
    )
    private val systemHints = compile(
        "\\b(nds|gba|gbc|gb|snes|sfc|nes|n64|md|gen|genesis|megadrive|sms|gg|psx|ps1|psp|" +
            "pce|tg16|ngp|ngpc|lynx|jag|wsc|ws|32x|3do|c64|intv|coleco|rom|usa|eur|europe|" +
            "jpn|japan|world|multi|en|fr|de|es|it|jp|us|eu)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Builds a pattern without letting a bad one take the whole class down.
     *
     * These are created when the class is first touched, so an exception here is not
     * an ordinary failure: it kills the class permanently, and every later call reports
     * it as missing rather than broken. One rejected pattern now costs a little tidying
     * of ROM names instead of every ROM in the library.
     */
    private fun compile(pattern: String, vararg options: RegexOption): Regex = try {
        if (options.isEmpty()) Regex(pattern) else Regex(pattern, options.toSet())
    } catch (error: Throwable) {
        Log.w(TAG, "Name pattern rejected by this device, skipping it: ${error.message}")
        NEVER_MATCHES
    }

    /** Filenames that say nothing about the game — the folder name is used instead. */
    private val uselessBaseNames = setOf(
        "eboot", "boot", "game", "games", "disc", "disk", "cd", "dvd", "image", "index",
        "default", "main", "data", "rom", "start", "play", "track", "iso", "bin", "cue",
    )

    /**
     * Title used for provider searches.
     *
     * @param folderChain folder names from the ROM's own folder outwards; used as a
     *   fallback when the filename itself carries no title
     */
    fun searchTitle(fileName: String, folderChain: List<String> = emptyList()): String {
        val fromFile = titleFromName(fileName)
        if (!isUseless(fromFile)) return fromFile
        // `PSX/Metal Gear Solid/disc1.cue` -> the game folder holds the real title.
        val folder = folderChain.firstOrNull {
            SystemCatalog.bySystemFolder(it) == null && !isUseless(titleFromName(it))
        }
        return folder?.let { titleFromName(it) } ?: fromFile
    }

    private fun isUseless(title: String): Boolean {
        val compact = title.lowercase().replace(nonAlphanumeric, "")
        if (compact.length < 3) return true
        if (compact.all { it.isDigit() }) return true
        return compact.trimEnd { it.isDigit() } in uselessBaseNames
    }

    private fun titleFromName(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        var cleaned = base.replace(bracketTags, " ")
        cleaned = cleaned.replace(separators, " ")
        cleaned = cleaned.replace(dashes, " ")
        cleaned = cleaned.replace(trailingJunk, " ")
        cleaned = cleaned.replace(punctuation, " ")
        cleaned = cleaned.replace(whitespace, " ").trim()
        if (cleaned.isBlank()) cleaned = base.replace(separators, " ").trim()

        // Drop leftover system/region tokens, but never empty the query out.
        val stripped = cleaned.replace(systemHints, " ").replace(whitespace, " ").trim()
        val result = if (stripped.length >= 3) stripped else cleaned
        return prettify(result)
    }

    private fun prettify(value: String): String = value
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            if (token.length > 1 && token == token.uppercase() && token.any { it.isLetter() }) {
                token.lowercase().replaceFirstChar { it.uppercase() }
            } else {
                token
            }
        }

    private val discTag = compile("\\b(?:dis[ck]|cd)\\s*_?(\\d{1,2})\\b", RegexOption.IGNORE_CASE)

    /** `Final Fantasy VII (Disc 2).cue` -> `Disc 2`, so multi-disc sets stay distinguishable. */
    fun discLabel(fileName: String): String? {
        val base = fileName.substringBeforeLast('.', fileName)
        val match = discTag.find(base) ?: return null
        val number = match.groupValues.getOrNull(1)?.trimStart('0')?.ifEmpty { "1" } ?: return null
        return "Disc $number"
    }

    /**
     * Aggressive form used only to compare two titles for confidence.
     *
     * ## Why this is hand-written rather than three `replace` calls
     *
     * This is the hottest function in the app. Parsing one system index calls it once
     * per thumbnail, and the device measured 65 seconds to parse the 9,339-entry
     * PlayStation listing — against a 1.1-second download. The regex form ran three
     * ICU passes and allocated four intermediate strings **per entry**, so a large
     * system meant roughly 28,000 regex executions and 37,000 throwaway strings. The
     * cost was also visibly superlinear (3.7 ms/entry at 2,300 entries, 7.0 ms/entry
     * at 9,300) — the signature of garbage-collection pressure, not of the work
     * itself. On the desktop this was invisible; on the handheld it was the scan.
     *
     * One pass over the text, writing straight into a single builder, produces the
     * same string with no intermediates. The regex definitions above are kept as the
     * specification this implements, and [comparisonKeyByRegex] runs them so a test
     * can assert the two agree.
     *
     * The three rules, in the order the regexes applied them:
     * 1. Bracketed groups — `(...)`, `[...]`, `{...}` — become a separator.
     * 2. Whole words that are articles (`the`, `a`, `an`, `of`, `and`, `de`, `le`,
     *    `la`) are dropped.
     * 3. Everything that is not `a-z0-9` is dropped.
     */
    fun comparisonKey(value: String): String {
        val text = value.lowercase()
        val out = StringBuilder(text.length)
        // Where the current word run started in [out], or -1 between runs. An article
        // can only be recognised once its run ends, so it is written first and rolled
        // back if it turns out to be one — which costs nothing and allocates nothing.
        var runStart = -1
        // True when the run contained a character that survives `\b` but not the
        // final filter (an underscore, or a letter that has no lowercase form). Such a
        // run can never equal an article, even if its remaining letters spell one.
        var runImpure = false
        var i = 0
        while (i < text.length) {
            val char = text[i]
            val closer = closerFor(char)
            if (closer != NOT_A_BRACKET) {
                val end = groupEnd(text, i, char, closer)
                if (end >= 0) {
                    // A balanced group. The regex replaced it with a space, so it ends
                    // the current word exactly as any other separator would.
                    runStart = endRun(out, runStart, runImpure)
                    runImpure = false
                    i = end + 1
                    continue
                }
                // Unbalanced: the regex could not match here either, so the bracket is
                // an ordinary character and falls through to the separator branch.
            }
            when {
                char in 'a'..'z' || char in '0'..'9' -> {
                    if (runStart < 0) {
                        runStart = out.length
                        runImpure = false
                    }
                    out.append(char)
                }
                // Word characters for `\b` that the final filter removes. They keep the
                // run going — `a_b` is one word, so its `a` is not a standalone article.
                char == '_' || char in 'A'..'Z' -> {
                    if (runStart < 0) runStart = out.length
                    runImpure = true
                }
                else -> {
                    runStart = endRun(out, runStart, runImpure)
                    runImpure = false
                }
            }
            i++
        }
        endRun(out, runStart, runImpure)
        return out.toString()
    }

    /**
     * The specification [comparisonKey] implements, expressed as the original three
     * regular expressions. Kept so the fast path can be proven equivalent by test
     * rather than by argument; nothing in the app calls it on a scanning path.
     */
    fun comparisonKeyByRegex(value: String): String {
        var text = value.lowercase()
        text = text.replace(bracketTags, " ")
        text = text.replace(articles, " ")
        text = text.replace(nonAlphanumeric, "")
        return text
    }

    /** Closes the current word, removing it when it was an article. */
    private fun endRun(out: StringBuilder, runStart: Int, runImpure: Boolean): Int {
        if (runStart >= 0 && !runImpure && isArticle(out, runStart)) out.setLength(runStart)
        return -1
    }

    /** Whether the characters written since [from] spell an article, without allocating. */
    private fun isArticle(out: StringBuilder, from: Int): Boolean = when (out.length - from) {
        1 -> out[from] == 'a'
        2 -> {
            val a = out[from]
            val b = out[from + 1]
            (a == 'a' && b == 'n') || (a == 'o' && b == 'f') ||
                (a == 'd' && b == 'e') || (a == 'l' && (b == 'e' || b == 'a'))
        }
        3 -> {
            val a = out[from]
            val b = out[from + 1]
            val c = out[from + 2]
            (a == 't' && b == 'h' && c == 'e') || (a == 'a' && b == 'n' && c == 'd')
        }
        else -> false
    }

    private fun closerFor(char: Char): Char = when (char) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        else -> NOT_A_BRACKET
    }

    /**
     * The index of the closing bracket, or -1 when the group is not balanced.
     *
     * Mirrors `\([^()]*\)` exactly: a second opening bracket before the closing one
     * means no match here, and the regex would then retry from the inner bracket.
     */
    private fun groupEnd(text: String, start: Int, opener: Char, closer: Char): Int {
        var i = start + 1
        while (i < text.length) {
            val char = text[i]
            if (char == closer) return i
            if (char == opener) return -1
            i++
        }
        return -1
    }

    /** 0f..1f similarity between a ROM filename and a provider title. */
    fun similarity(romTitle: String, providerTitle: String): Float =
        similarityOfKeys(comparisonKey(romTitle), comparisonKey(providerTitle))

    /**
     * The same measure as [similarity], for a caller that already holds both keys.
     *
     * Normalising is three regular expressions, and matching one title against a whole
     * system index re-normalised every entry for every ROM — thousands of identical
     * results recomputed from scratch per game. Separating the comparison from the
     * normalisation lets an index normalise its entries once, when it is parsed.
     *
     * The verdict is unchanged: [similarity] is now literally this applied to two
     * freshly computed keys.
     */
    /**
     * Whether [similarityOfKeys] could possibly reach [threshold], decided on lengths
     * alone.
     *
     * Matching a title against a system index measured every entry with a full
     * Levenshtein pass — an O(n×m) matrix per entry, nine thousand times for one
     * PlayStation lookup, when almost every entry is a different game entirely. Edit
     * distance is at least the difference in length, so `1 - |Δ| / longest` is an
     * upper bound on the similarity, and an entry whose *best case* falls short can
     * be rejected on arithmetic.
     *
     * This is a filter, never a verdict: it only ever excludes candidates that the
     * full measure would also have excluded, so results are unchanged.
     *
     * The length bound only governs the edit-distance branch. [similarityOfKeys] also
     * awards a fixed score when one key contains the other, and a short key inside a
     * long one is exactly the case the length bound would reject — so below that fixed
     * score the gate cannot safely decide anything and declines to exclude.
     */
    fun similarityCanReach(a: String, b: String, threshold: Float): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (threshold <= CONTAINMENT_SCORE) return true
        val longest = maxOf(a.length, b.length)
        val difference = kotlin.math.abs(a.length - b.length)
        return 1f - difference.toFloat() / longest >= threshold
    }

    fun similarityOfKeys(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        if (a.length >= 4 && b.contains(a)) return CONTAINMENT_SCORE
        if (b.length >= 4 && a.contains(b)) return REVERSE_CONTAINMENT_SCORE
        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return (1f - distance.toFloat() / longest).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private const val TAG = "RomNameNormalizer"

    /** Sentinel for "this character does not open a group". */
    private const val NOT_A_BRACKET = '\u0000'

    /** Score awarded when one key contains the other; the ceiling of that branch. */
    private const val CONTAINMENT_SCORE = 0.9f
    private const val REVERSE_CONTAINMENT_SCORE = 0.85f
}
