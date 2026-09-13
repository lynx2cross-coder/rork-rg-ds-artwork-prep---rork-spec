package com.rork.rgdsartworkprep.provider

import com.rork.rgdsartworkprep.data.RomNameNormalizer
import java.net.URLDecoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One thumbnail in the archive: its display name and the normalised form used to
 * compare it against a ROM title.
 *
 * [key] is stored rather than computed. As a `get()` it re-ran three regular
 * expressions on every read, and matching reads it once per entry *per ROM*: a
 * 13,418-entry index paid that cost for every ROM on the system, having already paid
 * it for the ROM before. Computed once at parse time, the whole library pays it once.
 */
class LibretroEntry(val name: String, val key: String)

/**
 * Reads thumbnail names out of the archive's directory listing.
 *
 * ## Why this is its own file
 *
 * This is where the RG DS actually lost its scans. The device spent 65.0 seconds
 * parsing the 9,339-entry PlayStation listing against a 1.1-second download, blew a
 * 60-second ceiling, and reported a timeout — three times, for three different ROMs,
 * totalling around twelve minutes of a twelve-and-a-half-minute scan. It was also the
 * one step with no test coverage, because it was buried in a class that needs Android
 * to instantiate. Pulled out here it is ordinary Kotlin, so the parsing can be
 * asserted on the JVM while the provider keeps the policy around it.
 *
 * ## Why it scans instead of matching a regular expression
 *
 * `Regex.findAll` allocates a `MatchResult` and a `groupValues` list for every link
 * in a multi-megabyte page. The measured cost was superlinear in the size of the
 * listing — 3.7 ms/entry at 2,300 entries against 7.0 ms/entry at 9,300 — which is
 * the signature of garbage collection rather than of the parsing itself. Walking the
 * text with `indexOf` finds the same links with no per-match allocation, and rejects
 * the majority of links on the raw characters before anything is built.
 */
object LibretroIndexParser {

    /**
     * Every distinct thumbnail in [html], in the order the listing gives them.
     *
     * Cancellable by design: the walk checks the coroutine as it goes, so a cancelled
     * scan stops inside the parse instead of running to completion on a dead job —
     * which is how a parse once outlived its own scan and landed its result on the
     * *next* ROM's record. The caller owns the dispatcher and any time limit.
     */
    suspend fun parse(html: String): List<LibretroEntry> {
        val seen = HashSet<String>()
        val entries = ArrayList<LibretroEntry>()
        var cursor = 0
        var sinceCheck = 0
        while (true) {
            val hrefAt = html.indexOf(HREF_ATTRIBUTE, cursor, ignoreCase = true)
            if (hrefAt < 0) break
            val valueStart = hrefAt + HREF_ATTRIBUTE.length
            val valueEnd = html.indexOf('"', valueStart)
            if (valueEnd < 0) break
            cursor = valueEnd + 1

            // The walk is where a multi-megabyte parse spends its time, so this is
            // where it has to be interruptible.
            if (++sinceCheck >= CANCELLATION_CHECK_INTERVAL) {
                sinceCheck = 0
                currentCoroutineContext().ensureActive()
            }

            // Decided on the raw characters, before anything is allocated: most links
            // in a listing are not thumbnails at all.
            val nameEnd = valueEnd - PNG_SUFFIX.length
            if (nameEnd <= valueStart) continue
            if (!html.regionMatches(nameEnd, PNG_SUFFIX, 0, PNG_SUFFIX.length, ignoreCase = true)) {
                continue
            }
            // A listing may link out of the folder; only plain filenames are wanted.
            if (containsSlash(html, valueStart, valueEnd)) continue

            val decoded = decodeName(html, valueStart, nameEnd) ?: continue
            if (!seen.add(decoded)) continue
            entries += LibretroEntry(name = decoded, key = RomNameNormalizer.comparisonKey(decoded))
        }
        return entries
    }

    private fun containsSlash(html: String, from: Int, to: Int): Boolean {
        for (i in from until to) if (html[i] == '/') return true
        return false
    }

    /**
     * The name in `html[from, to)`, percent-decoded only when it needs to be.
     *
     * [URLDecoder.decode] builds a decoder and a buffer on every call and was being
     * invoked once per entry. Most archive filenames contain nothing to decode, so the
     * common case is now a plain substring and the decoder is kept for the names that
     * genuinely carry an escape.
     */
    private fun decodeName(html: String, from: Int, to: Int): String? {
        var needsDecoding = false
        for (i in from until to) {
            val char = html[i]
            if (char == '%' || char == '+') {
                needsDecoding = true
                break
            }
        }
        val raw = html.substring(from, to)
        if (!needsDecoding) return raw.takeIf { it.isNotBlank() }
        return runCatching { URLDecoder.decode(raw, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private const val HREF_ATTRIBUTE = "href=\""
    private const val PNG_SUFFIX = ".png"

    /**
     * Entries between cancellation checks.
     *
     * Frequent enough that a cancelled scan stops promptly, rare enough that the check
     * is not a measurable share of the parse.
     */
    private const val CANCELLATION_CHECK_INTERVAL = 256
}
