package com.rork.rgdsartworkprep.ui.screens

import com.rork.rgdsartworkprep.data.LocationAccess
import com.rork.rgdsartworkprep.data.LocationScanSummary

/**
 * Plain-language lines for one saved location on the Artwork screen, built from what
 * the last walk actually saw on the device. Pure, so every case is checked on the JVM.
 */
internal object ArtworkSourceNotes {

    /** One location's headline and the reasons any of its covers are not shown. */
    data class Entry(val headline: String, val notes: List<String>) {
        val hasProblems: Boolean get() = notes.isNotEmpty()
    }

    /** @param shownCount covers on the Artwork screen that came from this location */
    fun of(summary: LocationScanSummary, shownCount: Int): Entry {
        if (!summary.access.isScannable) {
            return Entry(headline = "Not scanned", notes = listOf(summary.access.description))
        }
        if (summary.failed) {
            return Entry(
                headline = "Scan stopped part-way",
                notes = listOf("This location could not be read to the end. Tap rescan to try again."),
            )
        }
        val discovery = summary.discovery
        val notes = buildList {
            if (summary.romCount == 0) add("No games were found in this location.")
            if (discovery.unmatchedCovers > 0) {
                val sample = discovery.unmatchedSamples.firstOrNull()?.let { " \u2014 e.g. $it" }.orEmpty()
                add(
                    "${plural(discovery.unmatchedCovers, "cover")} found here " +
                        "${if (discovery.unmatchedCovers == 1) "doesn't" else "don't"} share a name " +
                        "with any game in the same folder, so ${if (discovery.unmatchedCovers == 1) "it isn't" else "they aren't"} shown$sample",
                )
            }
            if (discovery.unreadTotal > 0) {
                val kinds = discovery.topUnread().joinToString(", ") { (ext, count) -> ".$ext \u00d7$count" }
                add("Files not read as games: $kinds")
            }
            if (discovery.nestedCoverFiles > 0) {
                val folder = discovery.nestedCoverFolders.firstOrNull()?.let { " such as $it" }.orEmpty()
                add(
                    "${plural(discovery.nestedCoverFiles, "image")} sit in sub-folders$folder. " +
                        "Only images directly inside Imgs, images, media, boxart or covers are read.",
                )
            }
            if (summary.access == LocationAccess.ReadOnly) {
                add("Read-only: covers prepared for these games go to the export folder, not this location.")
            }
        }
        return Entry(
            headline = "${plural(summary.romCount, "game")} \u00b7 ${plural(shownCount, "cover")} shown",
            notes = notes,
        )
    }

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
}
