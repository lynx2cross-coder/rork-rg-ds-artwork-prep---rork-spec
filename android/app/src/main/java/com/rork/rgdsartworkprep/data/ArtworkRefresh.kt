package com.rork.rgdsartworkprep.data

/**
 * Keeps the Artwork screen in step with covers written after the library was scanned.
 *
 * The Artwork screen shows the library scan's ROMs that have a cover. That scan is
 * taken once and reused, so a cover saved by a later preparation run — for example
 * the 3DS games on an SD card, scanned first and prepared afterwards — stayed
 * invisible until something forced a full rescan. Pure, so both rules run on the JVM.
 */
object ArtworkRefresh {

    /**
     * True when a preparation run finished after the library was last scanned, so the
     * scan may be missing covers that run wrote into any saved location.
     *
     * Never true while a scan is already running or before the first scan, which the
     * screen starts on its own — so opening the screen can never loop.
     */
    fun needsRescan(
        lastRunEndedAtMillis: Long?,
        scannedAtMillis: Long,
        hasScanned: Boolean,
        isScanning: Boolean,
    ): Boolean {
        if (!hasScanned || isScanning) return false
        val ended = lastRunEndedAtMillis ?: return false
        return ended > scannedAtMillis
    }

    /**
     * [items] with the one whose id is [id] replaced by [update], or null when none
     * has that id. Ids are location-aware, so a same-named game in another saved
     * location is never the one updated.
     */
    fun <T> replaceById(items: List<T>, id: String, idOf: (T) -> String, update: (T) -> T): List<T>? {
        val index = items.indexOfFirst { idOf(it) == id }
        if (index < 0) return null
        return items.toMutableList().also { it[index] = update(it[index]) }
    }
}
