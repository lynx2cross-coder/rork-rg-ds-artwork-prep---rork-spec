package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.data.IgnoredFiles

/**
 * Removes ignored files from a scan that already exists.
 *
 * The library walk keeps ignored files out of new scans. This covers what the walk
 * cannot reach: a run already in progress when the user ignores one of its files, and
 * a run saved to disk before the list changed and resumed after it.
 *
 * Removal is not a verdict. Nothing here marks a job skipped or finished; the job is
 * simply gone, so the run's totals read as though the file had never been found.
 * Pure, so every rule is exercised on the JVM.
 */
object ScanExclusion {

    /** A run's rows and jobs with some ids taken out, and the counts that follow. */
    data class Pruned<T>(
        val items: List<T>,
        val jobs: List<QueueJob>,
        val total: Int,
        val processed: Int,
    )

    /**
     * Drops [removedIds] from a run's rows and jobs.
     *
     * [processed] loses exactly the removed jobs that had already finished, so the
     * progress bar neither jumps backwards for work that still counts nor keeps credit
     * for a file that no longer exists. It never exceeds the new total.
     */
    fun <T> prune(
        items: List<T>,
        jobs: List<QueueJob>,
        processed: Int,
        removedIds: Set<String>,
        id: (T) -> String,
    ): Pruned<T> {
        val keptItems = items.filterNot { id(it) in removedIds }
        if (removedIds.isEmpty()) return Pruned(keptItems, jobs, keptItems.size, processed)
        val finishedRemoved = jobs.count { it.id in removedIds && it.state.isFinished }
        val total = keptItems.size
        return Pruned(
            items = keptItems,
            jobs = jobs.filterNot { it.id in removedIds },
            total = total,
            processed = (processed - finishedRemoved).coerceIn(0, total),
        )
    }

    /**
     * A saved scan with every ignored file taken out, checked before anything in it
     * is rebuilt.
     *
     * It matters that this runs on the stored records: rebuilding a record re-detects
     * its system, and an ignored file must not reach detection by any route.
     */
    fun filterSnapshot(snapshot: ScanSnapshot, ignored: IgnoredFiles): ScanSnapshot {
        if (ignored.isEmpty) return snapshot
        val roms = snapshot.roms.filterNot { ignored.matches(it.fileName) }
        if (roms.size == snapshot.roms.size) return snapshot
        val keptIds = roms.mapTo(HashSet()) { it.documentId }
        return snapshot.copy(
            roms = roms,
            jobs = snapshot.jobs.filter { it.id in keptIds && !ignored.matches(it.fileName) },
        )
    }
}
