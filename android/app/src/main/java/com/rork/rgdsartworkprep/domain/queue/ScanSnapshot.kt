package com.rork.rgdsartworkprep.domain.queue

import kotlinx.serialization.Serializable

/**
 * A ROM as it is written to disk between runs.
 *
 * [com.rork.rgdsartworkprep.model.RomEntry] cannot be persisted directly: it holds an
 * android `Uri` and a resolved `GameSystem`, neither of which is serialisable and
 * both of which are cheap to rebuild. Everything here is either a primitive or a key
 * that can be looked up again, which is what lets the same snapshot be exercised in
 * a JVM test without an emulator.
 */
@Serializable
data class RomRecord(
    val documentId: String,
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val parentDocumentId: String? = null,
    val folderChain: List<String> = emptyList(),
    val systemKey: String? = null,
    val artworkUri: String? = null,
    val artworkRelativePath: String? = null,
    val systemRootDocumentId: String? = null,
    val systemRootFolderName: String? = null,
    val subPath: String? = null,
)

/** Why a persisted scan stopped, so a resumed one knows whether to carry on. */
@Serializable
enum class ScanPhase {
    /** Work remains and the scan should continue if the process comes back. */
    Running,

    /** Everything was decided. Nothing to resume. */
    Complete,

    /** The user stopped it. Must never resume on its own. */
    Cancelled,
}

/**
 * Everything needed to rebuild a scan that was interrupted.
 *
 * This exists because Android is free to stop the process at any point — the bug
 * report from the RG DS showed exactly that happening mid-scan. Without a snapshot
 * the only options on restart are to lose the run or to redo it from the top, and
 * redoing it would re-download artwork that had already been saved.
 *
 * @param scanId identifies one run, so a stale snapshot cannot be mistaken for the
 *   live one after the user starts something new.
 */
@Serializable
data class ScanSnapshot(
    val scanId: String,
    val version: Int = CURRENT_VERSION,
    val phase: ScanPhase = ScanPhase.Running,
    /** The library tree the run was started against. */
    val sourceTreeUri: String? = null,
    val sourceLabel: String? = null,
    val rescrape: Boolean = false,
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long? = null,
    val roms: List<RomRecord> = emptyList(),
    val jobs: List<QueueJob> = emptyList(),
) {

    val discovered: Int get() = roms.size

    /** Jobs that still need work, which is what decides whether resuming is worthwhile. */
    val pendingCount: Int get() = jobs.count { it.state.isPending }

    /**
     * True when this snapshot describes work worth picking up again.
     *
     * A cancelled or completed run is never resumed: the first because the user said
     * to stop, the second because there is nothing left to do.
     */
    val isResumable: Boolean get() = phase == ScanPhase.Running && pendingCount > 0

    fun jobFor(romId: String): QueueJob? = jobs.firstOrNull { it.id == romId }

    companion object {
        /**
         * Bumped when the stored shape changes in a way an older snapshot cannot
         * satisfy. A snapshot from a different version is discarded rather than
         * guessed at — a wrong resume is worse than a fresh scan.
         */
        const val CURRENT_VERSION = 1
    }
}
