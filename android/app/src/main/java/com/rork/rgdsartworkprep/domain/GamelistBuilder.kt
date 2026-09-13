package com.rork.rgdsartworkprep.domain

import android.util.Log
import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/** One `<game>` element destined for a system's gamelist.xml. */
data class GamelistEntry(
    /** Path of the ROM relative to the system folder, e.g. `Final Fantasy VII/ff7.cue`. */
    val romFileName: String,
    val name: String?,
    val imagePath: String?,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    /**
     * True for entries built without a provider lookup. These never overwrite richer
     * values that are already in the file — they only fill in blanks.
     */
    val preferExisting: Boolean = false,
)

/**
 * Produces a standard EmulationStation gamelist.xml.
 *
 * Any existing file is parsed and merged rather than replaced, so hand-written names,
 * favourites, play counts and entries for ROMs outside this run all survive.
 */
object GamelistBuilder {

    /** @return the full XML document text for a system folder. */
    fun merge(existingXml: String?, entries: List<GamelistEntry>): String {
        val games: LinkedHashMap<String, LinkedHashMap<String, String>> =
            existingXml?.let(::parse) ?: LinkedHashMap()

        entries.forEach { entry ->
            val key = pathKey(entry.romFileName)
            val fields = games[key]?.let { LinkedHashMap(it) } ?: LinkedHashMap()
            fields["path"] = "./${entry.romFileName}"
            val keepExisting = entry.preferExisting
            put(fields, "name", entry.name, keepExisting)
            put(fields, "image", entry.imagePath, keepExisting)
            put(fields, "desc", entry.description, keepExisting)
            put(fields, "releasedate", entry.releaseDate, keepExisting)
            put(fields, "developer", entry.developer, keepExisting)
            put(fields, "publisher", entry.publisher, keepExisting)
            put(fields, "genre", entry.genre, keepExisting)
            put(fields, "players", entry.players, keepExisting)
            put(fields, "rating", entry.rating, keepExisting)
            games[key] = fields
        }

        return buildString {
            append("<?xml version=\"1.0\"?>\n")
            append("<gameList>\n")
            games.values.forEach { fields ->
                append("\t<game>\n")
                val ordered = FIELD_ORDER.filter { fields.containsKey(it) } +
                    fields.keys.filterNot { it in FIELD_ORDER }
                ordered.forEach { tag ->
                    val value = fields[tag].orEmpty()
                    append("\t\t<").append(tag).append('>')
                    append(escape(value))
                    append("</").append(tag).append(">\n")
                }
                append("\t</game>\n")
            }
            append("</gameList>\n")
        }
    }

    private fun put(
        fields: LinkedHashMap<String, String>,
        tag: String,
        value: String?,
        keepExisting: Boolean,
    ) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        if (keepExisting && !fields[tag].isNullOrBlank()) return
        fields[tag] = trimmed
    }

    /**
     * Games are keyed by their path relative to the system folder, so `./Zelda.gba` and
     * `Zelda.gba` are the same entry while two discs in different game folders are not.
     */
    private fun pathKey(path: String): String = path
        .replace('\\', '/')
        .removePrefix("./")
        .trim()
        .lowercase()

    private fun parse(xml: String): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val games = LinkedHashMap<String, LinkedHashMap<String, String>>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "game") {
                    val fields = readGame(parser)
                    val path = fields["path"]
                    if (!path.isNullOrBlank()) games[pathKey(path)] = fields
                }
                event = parser.next()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Existing gamelist could not be parsed, writing a fresh one: ${error.javaClass.simpleName}")
            return LinkedHashMap()
        }
        return games
    }

    private fun readGame(parser: XmlPullParser): LinkedHashMap<String, String> {
        val fields = LinkedHashMap<String, String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return fields
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    val text = readText(parser)
                    if (text.isNotEmpty()) fields[tag] = text
                }
                XmlPullParser.END_TAG -> if (parser.name == "game") return fields
                else -> Unit
            }
        }
    }

    /** Consumes the current element and returns its text, leaving the parser on its END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return builder.toString().trim()
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> if (depth == 1) builder.append(parser.text)
                else -> Unit
            }
        }
        return builder.toString().trim()
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\r' -> Unit
                else -> if (char.code >= 0x20 || char == '\n' || char == '\t') append(char)
            }
        }
    }

    private const val TAG = "GamelistBuilder"
    private val FIELD_ORDER = listOf(
        "path",
        "name",
        "desc",
        "image",
        "thumbnail",
        "marquee",
        "video",
        "rating",
        "releasedate",
        "developer",
        "publisher",
        "genre",
        "players",
    )
}
