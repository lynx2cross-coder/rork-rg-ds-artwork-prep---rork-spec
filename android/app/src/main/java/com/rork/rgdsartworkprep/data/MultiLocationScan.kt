package com.rork.rgdsartworkprep.data

import kotlin.coroutines.cancellation.CancellationException

/** How one saved location fared in the last library scan. */
data class LocationScanSummary(
    val location: RomLocation,
    val access: LocationAccess,
    /** Games found there; zero when it was not scanned. */
    val romCount: Int = 0,
    val ignoredCount: Int = 0,
    /** True when the walk itself failed part-way, as opposed to never starting. */
    val failed: Boolean = false,
    /** What the walk saw about covers here, for the Artwork screen's breakdown. */
    val discovery: ArtworkDiscovery.Report = ArtworkDiscovery.Report(),
) {
    val scanned: Boolean get() = access.isScannable && !failed
}

/** What one location's walk produced. */
data class LocationItems<T>(
    val items: List<T>,
    val ignoredCount: Int,
    val discovery: ArtworkDiscovery.Report = ArtworkDiscovery.Report(),
)

/**
 * Scans every saved ROM location, one after another, and combines the results.
 *
 * Pure orchestration with the walk injected, so the multi-location rules are tested
 * on the JVM without a device:
 *
 * - Locations are walked in the user's order. One that cannot be reached is reported,
 *   never scanned, and never deleted; one whose walk fails does not stop the others.
 * - Each location's files stay its own: nothing is merged on disk or renamed. A file
 *   only collapses into one entry when two *nested* saved locations both reach the
 *   very same document, and then the location listed first keeps it.
 * - Ignored-file handling is untouched: each walk applies the ignore list exactly as
 *   the single-folder walk did, and the counts are simply summed.
 */
object MultiLocationScan {

    /** "Scanning location [index] of [total]", 1-based over the locations being walked. */
    data class Progress(val index: Int, val total: Int, val location: RomLocation)

    data class Result<T>(
        val items: List<T>,
        val ignoredCount: Int,
        val summaries: List<LocationScanSummary>,
    ) {
        /** True when at least one location could be read. */
        val anyScanned: Boolean get() = summaries.any { it.scanned }
    }

    /**
     * @param access whether each location can be reached right now
     * @param scan walks one reachable location
     * @param fileKey identifies one document across locations, see [RomLocations.fileKey]
     */
    suspend fun <T> run(
        locations: List<RomLocation>,
        access: suspend (RomLocation) -> LocationAccess,
        scan: suspend (RomLocation) -> LocationItems<T>,
        fileKey: (RomLocation, T) -> String,
        onProgress: (Progress) -> Unit = {},
    ): Result<T> {
        val checked = locations.map { it to access(it) }
        val reachable = checked.count { it.second.isScannable }
        val seen = HashSet<String>()
        val items = mutableListOf<T>()
        var ignored = 0
        var index = 0

        val summaries = checked.map { (location, status) ->
            if (!status.isScannable) return@map LocationScanSummary(location, status)
            index++
            onProgress(Progress(index, reachable, location))
            val found = try {
                scan(location)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return@map LocationScanSummary(location, status, failed = true)
            }
            var kept = 0
            found.items.forEach { item ->
                if (seen.add(fileKey(location, item))) {
                    items += item
                    kept++
                }
            }
            ignored += found.ignoredCount
            LocationScanSummary(
                location,
                status,
                romCount = kept,
                ignoredCount = found.ignoredCount,
                discovery = found.discovery,
            )
        }
        return Result(items, ignored, summaries)
    }
}
