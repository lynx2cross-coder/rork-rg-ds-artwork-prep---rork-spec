package com.rork.rgdsartworkprep.data

import android.util.Log
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.RomEntry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val scan: LibraryScan = LibraryScan(),
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val error: String? = null,
    /** Which saved location is being walked right now, for "Scanning 2 of 3 locations". */
    val progress: MultiLocationScan.Progress? = null,
) {
    /** Distinct system tags present in the library, for the filter chips. */
    val systemsPresent: List<GameSystem>
        get() = scan.roms.mapNotNull { it.system }.distinctBy { it.key }.sortedBy { it.shortName }
}

/** Shared, app-wide library scan results so Home, Library and Artwork agree. */
class LibraryStore(
    private val saf: SafRomRepository,
    private val locations: RomLocationsRepository,
) {
    /** A scan that blows up shows an empty state — it never takes the app down. */
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Library scan failed", error)
        _state.value = LibraryState(
            isScanning = false,
            hasScanned = false,
            error = "Could not read your ROM locations. Check them in Settings.",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var job: Job? = null

    /** Scans every saved ROM location and combines what they hold. */
    fun refresh(force: Boolean = false) {
        val saved = locations.current
        if (saved.isEmpty()) {
            job?.cancel()
            _state.value = LibraryState(error = null, hasScanned = false)
            return
        }
        if (!force && (_state.value.isScanning || _state.value.hasScanned)) return
        job?.cancel()
        _state.value = _state.value.copy(isScanning = true, error = null, progress = null)
        job = scope.launch {
            val result = MultiLocationScan.run(
                locations = saved,
                access = { saf.locationAccess(it) },
                scan = { saf.scanLocation(it) },
                fileKey = { location, rom -> RomLocations.fileKey(location, rom.documentId) },
                onProgress = { progress -> _state.update { it.copy(progress = progress) } },
            )
            _state.value = LibraryState(
                scan = LibraryScan(
                    roms = result.items.sortedBy { it.fileName.lowercase() },
                    scannedAtMillis = System.currentTimeMillis(),
                    ignoredCount = result.ignoredCount,
                    locations = result.summaries,
                ),
                isScanning = false,
                hasScanned = true,
                error = if (result.anyScanned) {
                    null
                } else {
                    "None of your ROM locations can be read right now. Check them in Settings."
                },
            )
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = LibraryState()
    }

    /**
     * Drops newly ignored files from the results already on screen.
     *
     * The walk itself leaves ignored files out, but a list scanned before the user
     * ignored something would otherwise keep showing it until the next rescan. Each
     * dropped file is added to the scan's ignored count, so the indicator matches what
     * a rescan would report.
     */
    fun applyIgnored(ignored: IgnoredFiles) {
        val current = _state.value
        val updated = current.scan.withoutIgnored(ignored)
        if (updated === current.scan) return
        _state.value = current.copy(scan = updated)
    }

    fun romsById(ids: Set<String>): List<RomEntry> =
        _state.value.scan.roms.filter { it.id in ids }

    private companion object {
        const val TAG = "LibraryStore"
    }
}
