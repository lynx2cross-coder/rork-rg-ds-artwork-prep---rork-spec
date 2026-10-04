package com.rork.rgdsartworkprep.ui.screens

import com.rork.rgdsartworkprep.data.MultiLocationScan
import com.rork.rgdsartworkprep.data.RomLocation

/**
 * "Scanning 2 of 3 locations…" while several are walked; a single location reads
 * just as the one-folder app always did.
 */
internal fun scanProgressLabel(progress: MultiLocationScan.Progress?, savedCount: Int): String = when {
    progress == null && savedCount > 1 -> "Scanning $savedCount locations\u2026"
    progress == null -> "Scanning your library\u2026"
    progress.total <= 1 -> "Scanning ${progress.location.label.full}\u2026"
    else -> "Scanning ${progress.index} of ${progress.total} locations\u2026"
}

/** Names the saved locations in one short phrase for the Home summary. */
internal fun locationsLabel(locations: List<RomLocation>): String = when (locations.size) {
    0 -> "No ROM location"
    1 -> locations.single().label.full
    else -> "${locations.size} ROM locations"
}
