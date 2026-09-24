package com.rork.rgdsartworkprep.data

import android.net.Uri
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
import kotlinx.coroutines.launch

data class LibraryState(
    val scan: LibraryScan = LibraryScan(),
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val error: String? = null,
) {
    /** Distinct system tags present in the library, for the filter chips. */
    val systemsPresent: List<GameSystem>
        get() = scan.roms.mapNotNull { it.system }.distinctBy { it.key }.sortedBy { it.shortName }
}

/** Shared, app-wide library scan results so Home, Library and Artwork agree. */
class LibraryStore(
    private val saf: SafRomRepository,
    private val settings: SettingsRepository,
) {
    /** A scan that blows up shows an empty state — it never takes the app down. */
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Library scan failed", error)
        _state.value = LibraryState(
            isScanning = false,
            hasScanned = false,
            error = "Could not read the library folder. Re-select it in Settings.",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var job: Job? = null

    fun refresh(force: Boolean = false) {
        val treeUriString = settings.current.libraryTreeUri
        if (treeUriString == null) {
            _state.value = LibraryState(error = null, hasScanned = false)
            return
        }
        if (!force && (_state.value.isScanning || _state.value.hasScanned)) return
        job?.cancel()
        _state.value = _state.value.copy(isScanning = true, error = null)
        job = scope.launch {
            val result = runCatching { saf.scanLibrary(Uri.parse(treeUriString)) }
            _state.value = result.fold(
                onSuccess = { scan ->
                    LibraryState(scan = scan, isScanning = false, hasScanned = true)
                },
                onFailure = {
                    LibraryState(
                        isScanning = false,
                        hasScanned = false,
                        error = "Could not read the library folder. Re-select it in Settings.",
                    )
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
     * ignored something would otherwise keep showing it until the next rescan.
     */
    fun applyIgnored(ignored: IgnoredFiles) {
        if (ignored.isEmpty) return
        val current = _state.value
        val kept = current.scan.roms.filterNot { ignored.matches(it.fileName) }
        if (kept.size == current.scan.roms.size) return
        _state.value = current.copy(scan = current.scan.copy(roms = kept))
    }

    fun romsById(ids: Set<String>): List<RomEntry> =
        _state.value.scan.roms.filter { it.id in ids }

    private companion object {
        const val TAG = "LibraryStore"
    }
}
