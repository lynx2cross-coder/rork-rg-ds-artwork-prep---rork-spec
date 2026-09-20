package com.rork.rgdsartworkprep.domain

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.data.Checksums
import com.rork.rgdsartworkprep.data.CrashLog
import com.rork.rgdsartworkprep.data.FileWriteResult
import com.rork.rgdsartworkprep.data.MatchCacheRepository
import com.rork.rgdsartworkprep.data.RomIdentityKey
import com.rork.rgdsartworkprep.data.RomNameNormalizer
import com.rork.rgdsartworkprep.data.SafRomRepository
import com.rork.rgdsartworkprep.data.ScanStateStore
import com.rork.rgdsartworkprep.data.SettingsRepository
import com.rork.rgdsartworkprep.domain.diagnostics.ScanDiagnostics
import com.rork.rgdsartworkprep.domain.queue.DeferHint
import com.rork.rgdsartworkprep.domain.queue.DeferReason
import com.rork.rgdsartworkprep.domain.queue.DeferralPolicy
import com.rork.rgdsartworkprep.domain.queue.JobSettlement
import com.rork.rgdsartworkprep.domain.queue.JobState
import com.rork.rgdsartworkprep.domain.queue.JobVerdict
import com.rork.rgdsartworkprep.domain.queue.QueueJob
import com.rork.rgdsartworkprep.domain.queue.RetryPolicy
import com.rork.rgdsartworkprep.domain.queue.ScanPhase
import com.rork.rgdsartworkprep.domain.queue.ScanQueue
import com.rork.rgdsartworkprep.domain.queue.ScanSnapshot
import com.rork.rgdsartworkprep.domain.queue.toEntry
import com.rork.rgdsartworkprep.domain.queue.toRecord
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.PrepItem
import com.rork.rgdsartworkprep.model.PrepStatus
import com.rork.rgdsartworkprep.model.RomEntry
import com.rork.rgdsartworkprep.model.isRetryable
import com.rork.rgdsartworkprep.model.isUnfinished
import com.rork.rgdsartworkprep.network.ProviderError
import com.rork.rgdsartworkprep.network.ProviderResult
import com.rork.rgdsartworkprep.provider.IdentifyOutcome
import com.rork.rgdsartworkprep.provider.ProviderChain
import com.rork.rgdsartworkprep.provider.RememberedMatch
import com.rork.rgdsartworkprep.provider.RomIdentity
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Live state of the current preparation run. */
data class ScrapeState(
    val items: List<PrepItem> = emptyList(),
    val isRunning: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val currentLine: String? = null,
    val banner: String? = null,
    val gamelistNote: String? = null,
    /** Monotonic clock stamp when the run began; null before the first run. */
    val runStartedAtMs: Long? = null,
    /** Stamp when the run stopped (finished or cancelled); null while running. */
    val runEndedAtMs: Long? = null,
    /**
     * The queue behind the run, exposed so the retry screen can explain why a game
     * was set aside without the UI having to infer it from a status chip.
     */
    val jobs: List<QueueJob> = emptyList(),
    /** True when the run stopped because the user asked it to. */
    val wasCancelled: Boolean = false,
    /**
     * Wall-clock stamps for the run, alongside the monotonic ones above.
     *
     * Both are kept because they answer different questions: the monotonic pair is
     * what durations are measured with (it cannot jump when the clock is corrected),
     * while these are what a report has to print as a time of day.
     */
    val scanStartedAtWallMillis: Long? = null,
    val scanEndedAtWallMillis: Long? = null,
) {
    val progress: Float get() = if (total == 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
    val failedCount: Int
        get() = items.count { it.status == PrepStatus.NotFound || it.status == PrepStatus.ApiError }

    /**
     * Rows waiting on a person rather than on the app.
     *
     * [PrepStatus.ChooseArtwork] counts here, not in [failedCount]: the progress bar and
     * its legend are the first thing read after a scan, and a row one tap from being
     * resolved sitting in the red segment overstates how badly the run went.
     */
    val needsAttentionCount: Int
        get() = items.count {
            it.status == PrepStatus.MultipleMatches || it.status == PrepStatus.ChooseArtwork
        }
    val savedCount: Int
        get() = items.count { it.status == PrepStatus.Downloaded || it.status == PrepStatus.Exported }

    /**
     * ROMs that finished without needing a download: artwork was already there, the
     * system is unknown, or the user switched that system off.
     */
    val skippedCount: Int
        get() = items.count {
            it.status == PrepStatus.AlreadyExists ||
                it.status == PrepStatus.Unsupported ||
                it.status == PrepStatus.SystemDisabled
        }

    /** ROMs skipped purely because their system is switched off in Settings. */
    val systemDisabledCount: Int
        get() = items.count { it.status == PrepStatus.SystemDisabled }
    val isComplete: Boolean get() = !isRunning && items.isNotEmpty() && processed >= total

    /** Games set aside for a later automatic attempt. */
    val deferredJobs: List<QueueJob> get() = jobs.filter { it.state == JobState.Deferred }

    /** Games that used up their automatic attempts and now need a manual retry. */
    val exhaustedJobs: List<QueueJob> get() = jobs.filter { it.state == JobState.Failed }

    /** Everything in the retry queue, whichever half it is in. */
    val retryQueue: List<QueueJob>
        get() = jobs.filter { it.state == JobState.Deferred || it.state == JobState.Failed }

    val queuedForRetryCount: Int get() = retryQueue.size
}

/**
 * Runs the ROM -> identify -> find artwork -> save artwork pipeline, and optionally
 * writes an EmulationStation gamelist.xml per system folder when the run finishes.
 *
 * A failing ROM never stops the run: it lands in the list with a retryable status.
 */
class ScrapeCoordinator(
    context: Context,
    private val saf: SafRomRepository,
    private val settingsRepository: SettingsRepository,
    private val matchCache: MatchCacheRepository,
    private val providers: ProviderChain,
    private val stateStore: ScanStateStore,
    val diagnostics: ScanDiagnostics,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {

    private val appContext = context.applicationContext

    /**
     * Last line of defence. A failure that escapes the pipeline would otherwise reach
     * Android's default handler and kill the app mid-run, so it is recorded for the
     * diagnostics report and surfaced as a banner instead.
     */
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Preparation run failed", error)
        CrashLog.record(appContext, "scrape-scope", error)
        stopWithBanner(unexpectedMessage(error))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    private val _state = MutableStateFlow(ScrapeState())
    val state: StateFlow<ScrapeState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Serialises the swap of one [drain] loop for the next.
     *
     * `Job.cancel()` only *requests* cancellation and returns immediately, so the
     * previous pattern — cancel, then launch — could start a second loop while the
     * first was still inside a request or a multi-megabyte parse. The device report
     * caught exactly that: one ROM's two identify calls overlapped (340.2s and 658.3s
     * against a 658.3s total, which cannot be sequential), and the same system index
     * was downloaded twice because both loops passed the cache check before either
     * stored a result.
     *
     * Everything that replaces the loop goes through [restartDrain], which waits for
     * the outgoing loop to actually finish first. That restores the invariant the rest
     * of the design already assumes: one ROM in flight at a time, and one writer of
     * the queue's state.
     */
    private val drainSwap = Mutex()

    /** Pending gamelist entries, grouped by the system folder their ROMs belong to. */
    private val gamelistLock = Mutex()
    private val gamelistBuckets = linkedMapOf<String, GamelistBucket>()

    /**
     * Titles already resolved during this run, used only for disc systems: the discs of
     * one game share a title, so disc 2 and 3 never cost another lookup. Concurrent
     * because a manual match can land while the batch is still running.
     */
    private val runTitleCache = ConcurrentHashMap<String, GameCandidate>()

    /** Last downloaded cover, reused when consecutive discs point at the same image. */
    @Volatile
    private var lastCover: Pair<String, ByteArray>? = null

    /**
     * Games whose request never completed — a connection that would not open, a service
     * that was busy — as opposed to games the databases genuinely do not have.
     *
     * These are worth trying again on their own, because nothing about the game caused
     * the failure. Kept separately so the second pass costs one request per affected
     * game rather than re-running the whole library.
     */
    private val unreachable = ConcurrentHashMap<String, TransientCause>()

    /** Why a game could not be reached, which decides how long to wait before retrying. */
    private enum class TransientCause {
        /** The connection never opened, or the reply was unusable. Clears in seconds. */
        Connection,

        /** The service asked for less load, or failed under it. Needs real time. */
        ServerBusy,
    }

    /**
     * Defer reasons collected during the current attempt.
     *
     * The pipeline reports failures by updating a row and calling [noteUnreachable];
     * this captures the same moment in the queue's vocabulary, so the scheduler can
     * tell a transient problem from a verdict without re-deriving it.
     */
    private val deferHints = ConcurrentHashMap<String, DeferHint>()

    /** Files a transient failure for the automatic recovery passes. */
    private fun noteUnreachable(id: String, error: ProviderError) {
        val cause = if (error is ProviderError.RateLimited ||
            (error is ProviderError.Http && error.code in 500..599)
        ) {
            TransientCause.ServerBusy
        } else {
            TransientCause.Connection
        }
        unreachable[id] = cause
        // The existing error model already knows whether a failure says anything about
        // the ROM, so the queue reuses that verdict instead of inventing a second one.
        val reason = DeferralPolicy.reasonFor(error) ?: return
        deferHints[id] = DeferHint(reason, error.userMessage)
    }

    private fun noteUnreachable(
        id: String,
        cause: TransientCause,
        reason: DeferReason,
        detail: String?,
    ) {
        unreachable[id] = cause
        deferHints[id] = DeferHint(reason, detail)
    }

    /**
     * The order of work and the state of every job.
     *
     * Replaces the old "walk the list, then walk the failures again" scheduling. The
     * queue is the single place that decides what happens next, which is what lets a
     * slow game step aside without the games behind it waiting for it.
     */
    @Volatile
    private var queue: ScanQueue? = null

    /** Identifies the current run, so a stale snapshot cannot be resumed over a new one. */
    @Volatile
    private var scanId: String? = null

    /** The ROMs of the current run, kept so a job id can be turned back into a file. */
    private val romsById = ConcurrentHashMap<String, RomEntry>()

    @Volatile
    private var rescrapeRun = false

    @Volatile
    private var scanStartedAtWallMillis = 0L

    /**
     * True from the moment a scan is started until it finishes or is cancelled.
     *
     * The foreground service watches this to know when it may stop itself, which is
     * how the scan stops depending on any Activity being alive.
     */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private data class GamelistBucket(
        val parentDocumentId: String?,
        val systemFolderName: String?,
        val entries: LinkedHashMap<String, GamelistEntry> = linkedMapOf(),
    )

    fun reset() {
        cancelDrain()
        _state.value = ScrapeState()
        runTitleCache.clear()
        lastCover = null
        queue = null
        scanId = null
        romsById.clear()
        deferHints.clear()
        stateStore.clear()
        diagnostics.clear()
        _isScanning.value = false
        scope.launch { gamelistLock.withLock { gamelistBuckets.clear() } }
    }

    fun start(roms: List<RomEntry>, rescrape: Boolean) {
        cancelDrain()
        val distinct = roms.distinctBy { it.id }
        val items = distinct.map { PrepItem(rom = it) }
        if (items.isEmpty()) {
            _state.value = ScrapeState()
            _isScanning.value = false
            return
        }

        val jobs = distinct.map {
            QueueJob(id = it.id, fileName = it.fileName, systemKey = it.system?.key)
        }
        val newQueue = ScanQueue(jobs, retryPolicy)
        queue = newQueue
        scanId = UUID.randomUUID().toString()
        rescrapeRun = rescrape
        scanStartedAtWallMillis = System.currentTimeMillis()
        romsById.clear()
        distinct.forEach { romsById[it.id] = it }

        _state.value = ScrapeState(
            items = items,
            isRunning = true,
            total = items.size,
            runStartedAtMs = SystemClock.elapsedRealtime(),
            jobs = newQueue.jobs,
            scanStartedAtWallMillis = scanStartedAtWallMillis,
        )
        runTitleCache.clear()
        lastCover = null
        unreachable.clear()
        deferHints.clear()
        diagnostics.clear()
        diagnostics.scanStarted(items.size, resumed = false)
        distinct.forEach { diagnostics.romDiscovered(it.id, it.fileName, it.system?.key) }
        providers.beginRun()
        persist(ScanPhase.Running)
        _isScanning.value = true
        restartDrain {
            gamelistLock.withLock { gamelistBuckets.clear() }
            drain()
        }
    }

    /**
     * Replaces the scanning loop, waiting for the outgoing one to finish first.
     *
     * The wait is the whole point: without it the old loop keeps running until it
     * next reaches a suspension point, and until then two loops share the queue, the
     * provider chain and the diagnostics' notion of which ROM is current.
     *
     * The swap itself runs in a coroutine because joining is a suspending wait, and
     * the callers are UI actions that must not block. Ordering is still guaranteed:
     * every swap takes [drainSwap], so two rapid starts cannot interleave.
     */
    private fun restartDrain(block: suspend () -> Unit) {
        val previous = job
        job = scope.launch {
            drainSwap.withLock {
                previous?.cancelAndJoin()
                block()
            }
        }
    }

    /**
     * Asks the scanning loop to stop and forgets it.
     *
     * Not joined here: callers are UI actions and the next [restartDrain] joins the
     * outgoing loop before starting anything, so nothing can overlap regardless.
     */
    private fun cancelDrain() {
        job?.cancel()
        job = null
    }

    /**
     * Picks a scan back up after the process was stopped.
     *
     * Android is free to kill the app at any point, and the device bug report showed
     * it doing exactly that mid-scan. Resuming from the snapshot means completed
     * artwork is never fetched twice and the retry queue keeps its attempt counts.
     *
     * @return true when a scan was resumed.
     */
    fun resumeIfInterrupted(): Boolean {
        if (_isScanning.value) return false
        val snapshot = stateStore.loadResumable() ?: return false
        val roms = snapshot.roms.map { it.toEntry() }
        if (roms.isEmpty()) return false

        // Anything caught mid-flight when the process died is put back in line: it was
        // interrupted, which says nothing about whether it would have succeeded.
        val restored = snapshot.jobs.map {
            if (it.state == JobState.Processing) it.copy(state = JobState.Ready) else it
        }
        val newQueue = ScanQueue(restored, retryPolicy)
        queue = newQueue
        scanId = snapshot.scanId
        rescrapeRun = snapshot.rescrape
        scanStartedAtWallMillis = snapshot.startedAtMillis
        romsById.clear()
        roms.forEach { romsById[it.id] = it }

        _state.value = ScrapeState(
            items = roms.map { rom ->
                PrepItem(rom = rom, status = statusForJob(newQueue.job(rom.id)))
            },
            isRunning = true,
            processed = newQueue.finishedCount,
            total = roms.size,
            runStartedAtMs = SystemClock.elapsedRealtime(),
            jobs = newQueue.jobs,
            scanStartedAtWallMillis = scanStartedAtWallMillis,
        )
        runTitleCache.clear()
        lastCover = null
        unreachable.clear()
        deferHints.clear()
        diagnostics.scanStarted(roms.size, resumed = true)
        providers.beginRun()
        _isScanning.value = true
        restartDrain { drain() }
        return true
    }

    /**
     * Requeues failed games. Pass [itemIds] to retry only a subset — for example the rows
     * visible under an active status filter — or null to retry every failure in the batch.
     * Ids that are no longer failing are ignored, so a stale selection can never requeue
     * artwork that already succeeded.
     */
    fun retryFailed(itemIds: Collection<String>? = null) {
        val active = queue ?: return
        val candidates = active.retryableIds() +
            _state.value.items.filter { it.status.isRetryable }.map { it.id }
        val allowed = itemIds?.toSet()
        val retryIds = candidates.distinct().filter { allowed == null || it in allowed }
        if (retryIds.isEmpty()) return

        active.requeue(retryIds)
        _state.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.id in retryIds) it.copy(status = PrepStatus.Pending, message = null) else it
                },
                isRunning = true,
                processed = active.finishedCount,
                banner = null,
                runEndedAtMs = null,
                wasCancelled = false,
                jobs = active.jobs,
            )
        }
        // A retry is a fresh chance for every source: a quota may have reset, or the
        // user may have just fixed the credentials that retired one.
        providers.beginRun()
        unreachable.clear()
        deferHints.clear()
        persist(ScanPhase.Running)
        _isScanning.value = true
        restartDrain { drain() }
    }

    /** Empties the retry queue without touching anything already saved. */
    fun clearRetryQueue() {
        val active = queue ?: return
        val cleared = active.clearDeferred().toSet()
        if (cleared.isEmpty()) return
        _state.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.id in cleared) {
                        it.copy(status = PrepStatus.Unsupported, message = "Removed from the retry queue")
                    } else {
                        it
                    }
                },
                processed = active.finishedCount,
                jobs = active.jobs,
            )
        }
        persist(if (active.isDrained) ScanPhase.Complete else ScanPhase.Running)
    }

    /**
     * Works the queue until nothing is left that can be attempted.
     *
     * This replaces the old "walk the whole list, then walk the failures again"
     * scheduling. The difference that matters: a game is never waited *on*. Fresh work
     * is always served first, and a game that could not be reached is put back with a
     * time before which it will not be tried again — so the queue moves on to the next
     * game immediately instead of holding everything behind the slow one.
     *
     * When only deferred work is left the loop sleeps exactly until the earliest one
     * comes due, rather than polling.
     */
    private suspend fun drain() {
        val active = queue ?: return
        try {
            if (!ensureProvidersConfigured(active)) return

            while (coroutineContext.isActive) {
                val now = SystemClock.elapsedRealtime()
                val next = active.nextReady(now)
                if (next == null) {
                    // Nothing can run yet. Either everything is decided, or the only work
                    // left is waiting out a backoff.
                    val eligibleAt = active.nextEligibleAt() ?: break
                    val waitMs = eligibleAt - now
                    if (waitMs <= 0L) continue
                    if (!countDownForQueue(active, waitMs)) return
                    // Every source gets a clean slate before a retry wave, exactly as the
                    // old recovery pass did: a quota may have reset, or credentials fixed.
                    providers.beginRun()
                    continue
                }
                processQueued(active, next)
            }

            if (coroutineContext.isActive) finishRun(active)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Preparation run failed", error)
            CrashLog.record(appContext, "scrape-run", error)
            active.finishPending(JobState.Failed, "Run stopped before this game")
            markUnfinished(active.jobs.map { it.id }, "Run stopped before this game")
            stopWithBanner(unexpectedMessage(error))
            persist(ScanPhase.Running)
            _isScanning.value = false
        }
    }

    /**
     * Stops before any work when the user has switched every source off. Preserved
     * verbatim from the previous implementation, including the wording.
     */
    private fun ensureProvidersConfigured(active: ScanQueue): Boolean {
        val settings = settingsRepository.current
        if (providers.isConfiguredAtAll(settings)) return true
        active.finishPending(JobState.Failed, "No artwork source enabled")
        _state.update { current ->
            current.copy(
                isRunning = false,
                processed = current.total,
                banner = "No artwork source is switched on. Turn Hasheous back on in Settings, " +
                    "or add your ScreenScraper credentials.",
                runEndedAtMs = SystemClock.elapsedRealtime(),
                scanEndedAtWallMillis = System.currentTimeMillis(),
                items = current.items.map {
                    it.copy(status = PrepStatus.ApiError, message = "No artwork source enabled")
                },
                jobs = active.jobs,
            )
        }
        persist(ScanPhase.Complete)
        _isScanning.value = false
        return false
    }

    /**
     * A finished [processRom] call, whatever it concluded.
     *
     * This wrapper is the fix for the defect that made the previous build report
     * successful scrapes as failures. `processRom` returns `ProviderError?`, where
     * null means "nothing fatal, carry on", and `withTimeoutOrNull` also returns null
     * when the budget expires. Kotlin flattens the resulting `ProviderError??` into a
     * single `ProviderError?`, so the two nulls became indistinguishable and *every*
     * non-fatal ROM — including every success — was read as a timeout. Wrapping the
     * result keeps the two answers separable at the type level, where a comment alone
     * could not.
     */
    private class RomOutcome(val fatal: ProviderError?)

    /**
     * The time one job is allowed while it still has nothing to show for it.
     *
     * Checked between stages rather than enforced mid-call: a blocking socket read
     * cannot be interrupted until it returns, so a checkpoint at a safe point is what
     * actually hands the slot back. Requests are separately bounded by the provider's
     * own call timeout, which is what makes those checkpoints reachable at all.
     *
     * The important rule here is [commit]. A budget exists to stop *unproductive* work
     * from monopolising the single scraper slot — it is not permission to throw away a
     * result that has already been obtained. Once a ROM has been identified, the cost
     * has been paid and the only thing left is a single cover download, so the budget
     * stands down and lets the job finish.
     *
     * The previous build had no such rule, and it lost a real match because of it:
     * Metroid Fusion was identified successfully after 290.5 seconds and then deferred
     * at the 240-second checkpoint without ever downloading its cover. The scan had the
     * right answer in hand and discarded it, then queued the whole lookup to be paid
     * for again.
     */
    private class JobBudget(private val startedAtMillis: Long, val budgetMillis: Long) {

        /** Set once the job has earned something worth finishing. */
        private var isCommitted: Boolean = false

        val elapsedMillis: Long get() = SystemClock.elapsedRealtime() - startedAtMillis

        /** True only while the job is still unproductive and out of time. */
        val hasExpired: Boolean get() = !isCommitted && elapsedMillis >= budgetMillis

        /**
         * Marks the job as having produced a result worth finishing.
         *
         * From here on the job runs to completion regardless of the clock. It is not
         * unbounded — the remaining work is one download, bounded by the provider's own
         * request timeout, and the job as a whole is still under the hard ceiling.
         */
        fun commit() {
            isCommitted = true
        }
    }

    /**
     * Runs one job, bounded by the per-job budget.
     *
     * The budget exists because every other safeguard in the app is per *request* — a
     * socket timeout, an attempt ceiling — and a ROM can exceed all of them while each
     * individual call stays inside its own limit. Bounding the whole job is what keeps
     * the queue moving, and the game is deferred rather than failed, so nothing is
     * given up on.
     *
     * Exactly one job is in flight at a time; this is called from the single [drain]
     * loop and never concurrently.
     */
    private suspend fun processQueued(active: ScanQueue, queued: QueueJob) {
        val rom = romsById[queued.id] ?: run {
            active.markSkipped(queued.id, 0L, "This file is no longer in the library")
            publish(active)
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        val attempt = (active.job(queued.id)?.attempts ?: 0) + 1
        active.markProcessing(queued.id, startedAt)
        unreachable.remove(queued.id)
        deferHints.remove(queued.id)

        _state.update { it.copy(currentLine = "${rom.fileName} \u2192 identifying\u2026") }
        updateItem(queued.id) { it.copy(status = PrepStatus.Working) }
        diagnostics.romStarted(queued.id, rom.fileName, rom.system?.key, attempt)
        publish(active)

        val treeUri = settingsRepository.current.libraryTreeUri?.let(Uri::parse)
        val budget = JobBudget(startedAt, retryPolicy.jobBudgetMillis)

        // The ordinary bound is the budget, applied at checkpoints inside the pipeline
        // where standing down is free. This outer timeout is only the hard ceiling: a
        // backstop against a defect that leaves the pipeline spinning, set far above any
        // legitimate path. Using the budget here — as the previous build did — meant a
        // lookup that succeeded slowly was cancelled mid-flight and its match thrown
        // away, which is the failure this pass removes.
        val completed: RomOutcome? = try {
            withTimeoutOrNull(retryPolicy.hardCeilingMillis) {
                RomOutcome(processRom(queued.id, rom, treeUri, rescrapeRun, budget))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "ROM failed but the run continues: ${error.javaClass.simpleName}")
            CrashLog.record(appContext, "rom-${rom.extension}", error)
            updateItem(queued.id) {
                // Naming the failure turns an unactionable row into a reportable
                // one: the same text is in the diagnostics report in Settings.
                it.copy(status = PrepStatus.ApiError, message = unexpectedRomMessage(error))
            }
            noteUnreachable(
                queued.id,
                TransientCause.Connection,
                DeferReason.ProviderUnavailable,
                error.javaClass.simpleName,
            )
            RomOutcome(null)
        }

        // Null now means one thing only: the hard ceiling cut the job off. Reaching it
        // is not normal slowness — every ordinary slow path stands down at a checkpoint
        // long before this — so it is logged as the anomaly it is.
        val ceilingHit = completed == null
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        logRomTiming(rom, startedAt, statusOf(queued.id))

        if (ceilingHit) {
            Log.w(TAG, "Hard ceiling reached after ${elapsed}ms; this should not happen")
            // Only rows that never reached a verdict are relabelled. A cover that
            // landed just before the ceiling keeps its success: re-marking it as an
            // error is precisely the bug the previous pass removed.
            updateItem(queued.id) {
                if (it.status.isUnfinished) {
                    it.copy(
                        status = PrepStatus.ApiError,
                        message = "Taking too long \u2014 queued to try again",
                    )
                } else {
                    it
                }
            }
        }

        settleJob(active, queued.id, elapsed, ceilingHit)

        _state.update { it.copy(processed = active.finishedCount) }
        publish(active)
        persist(ScanPhase.Running)

        completed?.fatal?.let { abortRun(active, it) }
    }

    /**
     * Reads the verdict the pipeline recorded for a ROM and files the job accordingly.
     *
     * The pipeline still speaks in [PrepStatus] and still decides everything about a
     * game; the decision itself lives in [JobSettlement], which is pure and therefore
     * testable without a device — the previous version of this logic was neither, and
     * it silently stopped running altogether.
     */
    private fun settleJob(active: ScanQueue, id: String, elapsed: Long, budgetExpired: Boolean) {
        val item = _state.value.items.firstOrNull { it.id == id }
        val status = item?.status ?: PrepStatus.ApiError
        val message = item?.message

        val verdict = JobSettlement.verdictFor(
            status = status,
            message = message,
            hint = deferHints[id],
            elapsedMillis = elapsed,
            budgetMillis = retryPolicy.jobBudgetMillis,
            budgetExpired = budgetExpired,
        )


        when (verdict) {
            is JobVerdict.Complete -> active.markCompleted(id, elapsed, verdict.detail)
            is JobVerdict.Skip -> active.markSkipped(id, elapsed, verdict.detail)
            is JobVerdict.Defer ->
                deferJob(active, id, verdict.reason, elapsed, verdict.detail, verdict.providerKey)
            is JobVerdict.GiveUp -> active.markFailed(id, elapsed, verdict.detail)
        }

        val settled = active.job(id)
        if (settled != null && settled.state != JobState.Deferred) {
            diagnostics.romFinished(id, status.name, elapsed, settled.attempts)
        }
    }

    /**
     * Stands down between stages when the job has used up its time.
     *
     * Called only where nothing has been earned yet, so stopping here costs the ROM
     * nothing but its place in the current pass. It is never called after artwork has
     * been written.
     *
     * @return true when the caller should stop and let the job be deferred.
     */
    private fun budgetExhausted(id: String, budget: JobBudget, stage: String): Boolean {
        if (!budget.hasExpired) return false
        val detail = JobSettlement.overBudgetDetail(budget.elapsedMillis, budget.budgetMillis)
        Log.i(TAG, "Budget reached before $stage; deferring this game")
        noteUnreachable(id, TransientCause.Connection, DeferReason.SlowResponse, detail)
        updateItem(id) {
            it.copy(
                status = PrepStatus.ApiError,
                message = "Taking too long \u2014 queued to try again",
            )
        }
        return true
    }

    /** Sets a job aside and records why, so the retry screen can explain itself. */
    private fun deferJob(
        active: ScanQueue,
        id: String,
        reason: DeferReason,
        elapsed: Long,
        detail: String?,
        providerKey: String? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        active.markDeferred(id, reason, now, elapsed, detail, providerKey)
        diagnostics.romDeferred(id, reason, elapsed, detail)
    }

    /**
     * Waits for the next deferred job to come due, visibly.
     *
     * A scan that sits still with no explanation looks broken, so the reason and the
     * remaining seconds are shown while it waits — the same treatment the old recovery
     * pause had. Returns false if the run was cancelled during the wait.
     */
    private suspend fun countDownForQueue(active: ScanQueue, waitMs: Long): Boolean {
        val waiting = active.deferredCount
        val label = if (waiting == 1) "1 game" else "$waiting games"
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            val seconds = (remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
            _state.update {
                it.copy(currentLine = "Waiting to retry $label \u2014 ${seconds}s\u2026")
            }
            delay(minOf(MILLIS_PER_SECOND, remaining))
            if (!coroutineContext.isActive) return false
        }
        return coroutineContext.isActive
    }

    /** Closes out a run that worked through everything it could. */
    private suspend fun finishRun(active: ScanQueue) {
        val settings = settingsRepository.current
        _state.update {
            it.copy(
                isRunning = false,
                currentLine = null,
                processed = it.total,
                // A source that dropped out mid-run is reported even though the batch
                // finished, so a partly-degraded result never passes for a clean one.
                banner = it.banner ?: providers.setAsideNote(settings),
                runEndedAtMs = SystemClock.elapsedRealtime(),
                scanEndedAtWallMillis = System.currentTimeMillis(),
                jobs = active.jobs,
            )
        }
        diagnostics.scanComplete(
            completed = active.completedCount,
            skipped = active.skippedCount,
            deferred = active.deferredCount,
            failed = active.failedCount,
        )
        persist(ScanPhase.Complete)
        _isScanning.value = false

        if (settings.generateGamelist) {
            withContext(NonCancellable) { writeGamelists(settings) }
        }
    }

    /**
     * Stops the scan at the user's request.
     *
     * Cancellation is not a provider failure and is never recorded as one: already
     * saved artwork is left exactly as it is, unfinished jobs are simply marked as
     * stopped, and the phase is written down so nothing resumes on its own afterwards.
     */
    fun cancel() {
        cancelDrain()
        val active = queue
        val remaining = active?.jobs?.count { it.state.isPending } ?: 0
        active?.finishPending(JobState.Skipped, "Scan cancelled")
        _state.update { current ->
            current.copy(
                isRunning = false,
                currentLine = null,
                runEndedAtMs = SystemClock.elapsedRealtime(),
                scanEndedAtWallMillis = System.currentTimeMillis(),
                wasCancelled = true,
                jobs = active?.jobs ?: current.jobs,
                items = current.items.map {
                    if (it.status.isUnfinished) {
                        it.copy(status = PrepStatus.Unsupported, message = "Scan cancelled")
                    } else {
                        it
                    }
                },
            )
        }
        diagnostics.scanCancelled(active?.finishedCount ?: 0, remaining)
        // Committed synchronously: the process may not survive long after this.
        persist(ScanPhase.Cancelled, immediate = true)
        _isScanning.value = false
    }

    /** Applies a cover the user picked manually for an ambiguous ROM. */
    fun applyCandidate(itemId: String, candidate: GameCandidate) {
        scope.launch {
            try {
                applyCandidateInternal(itemId, candidate)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Manual match failed: ${error.javaClass.simpleName}")
                CrashLog.record(appContext, "manual-match", error)
                updateItem(itemId) {
                    it.copy(
                        status = PrepStatus.ApiError,
                        message = "Could not save that cover \u2014 tap to try again",
                    )
                }
            }
        }
    }

    private suspend fun applyCandidateInternal(itemId: String, candidate: GameCandidate) {
        val item = _state.value.items.firstOrNull { it.id == itemId } ?: return
        updateItem(itemId) {
            it.copy(
                status = PrepStatus.Working,
                resolvedTitle = candidate.title,
                releaseDate = candidate.releaseDate,
                message = null,
            )
        }
        val settings = settingsRepository.current
        // Derived exactly as the batch pipeline derives it, including skipping the
        // checksum for disc systems. The two used to disagree: this path always hashed
        // the file, so a cover chosen by hand for a disc game was filed under a key the
        // pipeline never looked up, and the choice was forgotten on the next pass.
        val rom = item.rom
        val crc = if (RomIdentityKey.usesChecksum(rom.system)) {
            Checksums.crc32(appContext.contentResolver, rom.uri, rom.sizeBytes)
        } else {
            null
        }
        matchCache.remember(
            matchCache.identityKey(crc, rom.fileName, rom.sizeBytes, rom.system?.key),
            RememberedMatch(candidate.provider, candidate.gameId).serialize(),
        )
        val cover = candidate.coverUrl
        if (cover.isNullOrBlank()) {
            updateItem(itemId) {
                it.copy(status = PrepStatus.NotFound, message = "No cover art for this entry")
            }
            return
        }
        when (val bytes = fetchArtwork(candidate)) {
            is ProviderResult.Failure -> updateItem(itemId) {
                it.copy(status = PrepStatus.ApiError, message = describe(bytes.error))
            }
            is ProviderResult.Success -> {
                val result = persist(item.rom, bytes.value, overwrite = true, settingsSnapshot = settings)
                applyWriteResult(itemId, candidate.title, candidate.releaseDate, result)
                recordGamelistEntry(item.rom, candidate, result)
                if (settings.generateGamelist) writeGamelists(settings)
            }
        }
    }

    /** Manual free-text search used by the match picker sheet. */
    suspend fun searchCandidates(rom: RomEntry, query: String): ProviderResult<List<GameCandidate>> =
        providers.search(settingsRepository.current, rom.system, query)

    // region pipeline

    /**
     * Prepares one ROM.
     *
     * @return the error that makes the rest of the batch pointless (rejected
     *   credentials, exhausted quota, closed service), or null to carry on.
     */
    private suspend fun processRom(
        id: String,
        rom: RomEntry,
        treeUri: Uri?,
        rescrape: Boolean,
        budget: JobBudget,
    ): ProviderError? {
        val settings = settingsRepository.current
        val system = rom.system
        if (system == null) {
            updateItem(id) {
                it.copy(status = PrepStatus.Unsupported, message = "Could not tell which system this is")
            }
            return null
        }
        if (!system.scrapingEnabled) {
            updateItem(id) {
                it.copy(status = PrepStatus.Unsupported, message = "${system.displayName} lookup is not enabled yet")
            }
            return null
        }
        // The user's own filter, checked here and nowhere else: after detection, so the
        // ROM is still recognised and still says which system it belongs to, and before
        // anything is spent on it — no checksum read, no request, no budget.
        if (!settings.isSystemEnabled(system.key)) {
            updateItem(id) {
                it.copy(
                    status = PrepStatus.SystemDisabled,
                    message = "Skipped \u2014 ${system.displayName} is switched off in Settings",
                )
            }
            return null
        }

        if (!rescrape) {
            val existingPath = rom.artworkRelativePath
                ?: treeUri?.let { saf.findExistingArtwork(it, rom) }?.relativePath
            val hasArtwork = existingPath != null || rom.artworkUri != null
            if (hasArtwork) {
                updateItem(id) { it.copy(status = PrepStatus.AlreadyExists, message = "Artwork already in place") }
                // Still listed in gamelist.xml, but never overwriting richer existing data.
                recordExistingGamelistEntry(rom, existingPath)
                return null
            }
        }

        // Disc images are gigabytes of data whose checksum the database does not index,
        // so they are identified by name instead of being read end to end.
        val crc = if (!RomIdentityKey.usesChecksum(system)) {
            null
        } else {
            Checksums.crc32(appContext.contentResolver, rom.uri, rom.sizeBytes)
        }
        // A checksum that could not be computed is not the same as a game the database
        // does not have. Without one, a hash-matching source can only answer "unknown",
        // and that answer would be written down as a permanent "Not found" for a file
        // the app simply failed to read this time. Files that are deliberately too big
        // to hash are excluded — those are meant to fall through to a name search.
        val tooLargeToHash = rom.sizeBytes > Checksums.MAX_HASHED_BYTES
        if (!system.discBased && crc == null && !tooLargeToHash) {
            noteUnreachable(
                id,
                TransientCause.Connection,
                DeferReason.FileUnreadable,
                "Could not read the file to fingerprint it",
            )
            updateItem(id) {
                it.copy(
                    status = PrepStatus.ApiError,
                    message = "Could not read this file to fingerprint it \u2014 will try again",
                )
            }
            return null
        }

        // Checkpoint: fingerprinting a large ROM is disk-bound and can take a while on
        // this hardware. Nothing has been earned yet, so standing down here costs the
        // game nothing and hands the slot to the next one immediately.
        if (budgetExhausted(id, budget, "identifying")) return null

        val identityKey = matchCache.identityKey(crc, rom.fileName, rom.sizeBytes, system.key)
        val searchTitle = RomNameNormalizer.searchTitle(rom.fileName, rom.folderChain)
        val titleKey = "${system.key}|${RomNameNormalizer.comparisonKey(searchTitle)}"

        // The discs of one game share a title, so disc 2 and 3 cost no further lookups.
        val cached = if (system.discBased) runTitleCache[titleKey] else null
        val outcome = if (cached != null) {
            IdentifyOutcome.Identified(cached)
        } else {
            providers.identify(
                settings = settings,
                rom = RomIdentity(
                    fileName = rom.fileName,
                    searchTitle = searchTitle,
                    sizeBytes = rom.sizeBytes,
                    crc32 = crc,
                    system = system,
                ),
                remembered = RememberedMatch.parse(matchCache.lookup(identityKey)),
            )
        }

        val resolved = when (outcome) {
            is IdentifyOutcome.Identified -> outcome.candidate
            is IdentifyOutcome.Ambiguous -> {
                updateItem(id) {
                    it.copy(
                        status = PrepStatus.MultipleMatches,
                        resolvedTitle = outcome.best.title,
                        releaseDate = outcome.best.releaseDate,
                        candidates = outcome.candidates,
                        message = "Tap to choose the right game",
                    )
                }
                return null
            }
            // Every source was asked and none of them could match this ROM automatically.
            //
            // Not a failure, and not "no artwork exists": filename-based matching simply
            // did not reach a confident answer, which is routine for unusual naming, and
            // a manual search usually finds the game straight away. Reported in amber as
            // an invitation to choose, because calling it "Not found" in red told users
            // the search was over when the row was one tap from being resolved.
            IdentifyOutcome.NoMatch -> {
                updateItem(id) {
                    it.copy(
                        status = PrepStatus.ChooseArtwork,
                        message = "Automatic match unavailable \u00b7 tap to choose",
                    )
                }
                return null
            }
            // A source was unable to answer. Reported as a failed request, not as a
            // missing game, and left for the retry pass rather than sending the user
            // off to search manually for something that is probably there.
            is IdentifyOutcome.Unresolved -> {
                noteUnreachable(id, outcome.error)
                updateItem(id) {
                    it.copy(status = PrepStatus.ApiError, message = describe(outcome.error))
                }
                return null
            }
            is IdentifyOutcome.NoProviderLeft -> {
                updateItem(id) { it.copy(status = PrepStatus.ApiError, message = describe(outcome.error)) }
                return outcome.error
            }
        }

        matchCache.remember(identityKey, RememberedMatch(resolved.provider, resolved.gameId).serialize())
        if (system.discBased) runTitleCache[titleKey] = resolved
        _state.update { it.copy(currentLine = "${rom.fileName} → ${resolved.title}") }

        // The game has been identified, so the expensive half is done and paid for.
        // From here the budget stands down: what remains is a single cover download,
        // bounded by the provider's own request timeout.
        //
        // This used to be a checkpoint that deferred the job instead, and it cost a
        // real result — Metroid Fusion was identified after 290.5 seconds and set aside
        // here without ever fetching its cover, so the next attempt had to buy the same
        // lookup again. A budget is there to stop work that is going nowhere, not to
        // discard work that has arrived.
        budget.commit()
        diagnostics.romIdentified(id, resolved.provider.key, budget.elapsedMillis)

        val coverUrl = resolved.coverUrl
        if (coverUrl.isNullOrBlank()) {
            // "No cover exists" is only a fact about the game when the source that
            // answered was healthy. A source that is failing or throttling can return a
            // record whose artwork reference never arrived, and recording that as a
            // permanent miss would send the user hunting for a cover that is there.
            if (providers.isDegraded(resolved.provider)) {
                noteUnreachable(
                    id,
                    TransientCause.ServerBusy,
                    DeferReason.ArtworkIncomplete,
                    "The source was degraded when it answered",
                )
                updateItem(id) {
                    it.copy(
                        status = PrepStatus.ApiError,
                        resolvedTitle = resolved.title,
                        releaseDate = resolved.releaseDate,
                        message = "Cover art did not come through \u2014 will try again",
                    )
                }
                return null
            }
            // Reaching here means every source was asked and none had a cover, so the
            // message says so — the game was recognised, the artwork simply is not in
            // any of them. This stays a genuine miss rather than an invitation to choose:
            // a real search ran and produced nothing usable. Tapping still opens a manual
            // search, since the game may be listed elsewhere under another title.
            updateItem(id) {
                it.copy(
                    status = PrepStatus.NotFound,
                    resolvedTitle = resolved.title,
                    releaseDate = resolved.releaseDate,
                    message = "Recognised as \"${resolved.title}\", but no source has cover art \u2014 " +
                        "tap to search manually",
                )
            }
            return null
        }

        return when (val download = fetchArtwork(resolved)) {
            is ProviderResult.Failure -> {
                // The game was identified, so a failed cover download is purely a
                // transport problem and belongs in the automatic second pass.
                if (download.error.isTransient) noteUnreachable(id, download.error)
                updateItem(id) {
                    it.copy(
                        status = PrepStatus.ApiError,
                        resolvedTitle = resolved.title,
                        releaseDate = resolved.releaseDate,
                        message = describe(download.error),
                    )
                }
                download.error.takeIf { it.stopsRun }
            }
            is ProviderResult.Success -> {
                val result = persist(rom, download.value, overwrite = rescrape, settingsSnapshot = settings)
                applyWriteResult(id, resolved.title, resolved.releaseDate, result)
                recordGamelistEntry(rom, resolved, result)
                null
            }
        }
    }

    /**
     * Ends the batch when the provider says nothing else can succeed. Every game still
     * waiting is marked with the same reason so the list explains itself, and one clear
     * banner replaces the same failure repeated hundreds of times.
     *
     * The remaining jobs are failed rather than deferred: a source that has refused
     * everything will refuse them too, so queueing them for an automatic retry would
     * only repeat the same refusal on a timer.
     */
    private fun abortRun(active: ScanQueue, error: ProviderError) {
        Log.w(TAG, "Run stopped early: ${error.javaClass.simpleName}")
        val stopped = active.finishPending(JobState.Failed, error.userMessage)
        markUnfinished(stopped, error.userMessage)
        _state.update {
            it.copy(
                isRunning = false,
                processed = it.total,
                currentLine = null,
                banner = error.userMessage,
                runEndedAtMs = SystemClock.elapsedRealtime(),
                scanEndedAtWallMillis = System.currentTimeMillis(),
                jobs = active.jobs,
            )
        }
        persist(ScanPhase.Complete)
        _isScanning.value = false
    }

    /** Marks everything still queued or in flight, so no row is left spinning forever. */
    private fun markUnfinished(ids: Collection<String>, reason: String) {
        val scoped = ids.toSet()
        _state.update { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.id in scoped && item.status.isUnfinished) {
                        item.copy(status = PrepStatus.ApiError, message = reason)
                    } else {
                        item
                    }
                },
            )
        }
    }

    private fun stopWithBanner(message: String) {
        _state.update {
            it.copy(
                isRunning = false,
                currentLine = null,
                banner = message,
                runEndedAtMs = it.runEndedAtMs ?: SystemClock.elapsedRealtime(),
            )
        }
    }

    /** Names an unforeseen per-ROM failure so it can be reported rather than guessed at. */
    private fun unexpectedRomMessage(error: Throwable): String {
        val detail = error.message?.trim()?.take(MAX_DETAIL_LENGTH)?.takeIf { it.isNotEmpty() }
        val named = error.javaClass.simpleName + (detail?.let { ": $it" } ?: "")
        return "$named \u2014 tap retry, or send the report from Settings"
    }

    private fun unexpectedMessage(error: Throwable): String =
        "The run stopped unexpectedly (${error.javaClass.simpleName}). Your ROMs and artwork " +
            "are untouched — tap Retry, or send the report from Settings."

    /**
     * Downloads a cover through the source that offered it, reusing the previous one
     * when several discs of the same game point at the same image.
     */
    private suspend fun fetchArtwork(candidate: GameCandidate): ProviderResult<ByteArray> {
        val url = candidate.coverUrl ?: return ProviderResult.Failure(ProviderError.NotFound)
        lastCover?.let { (cachedUrl, bytes) ->
            if (cachedUrl == url) return ProviderResult.Success(bytes)
        }
        return when (val result = providers.downloadArtwork(candidate)) {
            is ProviderResult.Success -> {
                lastCover = url to result.value
                result
            }
            is ProviderResult.Failure -> result
        }
    }

    private suspend fun persist(
        rom: RomEntry,
        bytes: ByteArray,
        overwrite: Boolean,
        settingsSnapshot: AppSettings,
    ): FileWriteResult {
        val treeUri = settingsSnapshot.libraryTreeUri?.let(Uri::parse)
        val writable = treeUri != null && saf.hasWriteAccess(treeUri)
        return saf.saveArtwork(
            treeUri = if (writable) treeUri else null,
            rom = rom,
            bytes = bytes,
            overwrite = overwrite,
            forceExport = settingsSnapshot.forceExportFallback,
        )
    }

    private fun applyWriteResult(id: String, title: String, releaseDate: String?, result: FileWriteResult) {
        when (result) {
            is FileWriteResult.SavedToLibrary -> updateItem(id) {
                it.copy(
                    status = PrepStatus.Downloaded,
                    resolvedTitle = title,
                    releaseDate = releaseDate,
                    savedPath = result.displayPath,
                    message = result.displayPath,
                    candidates = emptyList(),
                )
            }
            is FileWriteResult.ExportedToAppStorage -> updateItem(id) {
                it.copy(
                    status = PrepStatus.Exported,
                    resolvedTitle = title,
                    releaseDate = releaseDate,
                    savedPath = result.displayPath,
                    message = "${result.reason} — ready to copy",
                    candidates = emptyList(),
                )
            }
            is FileWriteResult.Failed -> updateItem(id) {
                it.copy(
                    status = PrepStatus.ApiError,
                    resolvedTitle = title,
                    releaseDate = releaseDate,
                    message = result.reason,
                )
            }
        }
    }

    // endregion

    // region gamelist.xml

    private suspend fun recordGamelistEntry(rom: RomEntry, candidate: GameCandidate, result: FileWriteResult) {
        if (!settingsRepository.current.generateGamelist) return
        val imagePath = when (result) {
            is FileWriteResult.SavedToLibrary -> rom.gamelistRelativePath(result.fileName)
            is FileWriteResult.ExportedToAppStorage -> rom.gamelistRelativePath(result.fileName)
            is FileWriteResult.Failed -> rom.artworkRelativePath?.let { rom.gamelistRelativePath(it) }
        }
        putEntry(
            rom,
            GamelistEntry(
                romFileName = rom.gamelistRomPath,
                name = discAwareName(rom, candidate.title),
                imagePath = imagePath,
                description = candidate.description,
                developer = candidate.developer,
                publisher = candidate.publisher,
                genre = candidate.genre,
                players = candidate.players,
                releaseDate = candidate.releaseDate,
                rating = candidate.rating,
            ),
        )
    }

    /** For ROMs skipped because artwork was already there — fills blanks only. */
    private suspend fun recordExistingGamelistEntry(rom: RomEntry, relativePath: String?) {
        if (!settingsRepository.current.generateGamelist) return
        putEntry(
            rom,
            GamelistEntry(
                romFileName = rom.gamelistRomPath,
                name = discAwareName(rom, RomNameNormalizer.searchTitle(rom.fileName, rom.folderChain)),
                imagePath = relativePath?.let { rom.gamelistRelativePath(it) },
                preferExisting = true,
            ),
        )
    }

    /** Keeps `Final Fantasy VII (Disc 2)` distinguishable from disc 1 in the list. */
    private fun discAwareName(rom: RomEntry, title: String): String {
        val label = RomNameNormalizer.discLabel(rom.fileName) ?: return title
        return if (title.contains(label, ignoreCase = true)) title else "$title ($label)"
    }

    private suspend fun putEntry(rom: RomEntry, entry: GamelistEntry) {
        // gamelist.xml belongs at the system root, even when games sit in their own folders.
        val folderId = rom.systemRootDocumentId ?: rom.parentDocumentId
        val key = folderId ?: rom.systemFolderName ?: UNSORTED_KEY
        gamelistLock.withLock {
            val bucket = gamelistBuckets.getOrPut(key) {
                GamelistBucket(
                    parentDocumentId = folderId,
                    systemFolderName = rom.systemFolderName ?: rom.system?.shortName,
                )
            }
            bucket.entries[rom.gamelistRomPath.lowercase()] = entry
        }
    }

    /** Merges every pending bucket into its system folder's gamelist.xml. */
    private suspend fun writeGamelists(settings: AppSettings) {
        val buckets = gamelistLock.withLock { gamelistBuckets.values.toList() }
        if (buckets.isEmpty()) return

        _state.update { it.copy(currentLine = "Writing gamelist.xml…") }

        val treeUri = settings.libraryTreeUri?.let(Uri::parse)
        val writable = treeUri != null && saf.hasWriteAccess(treeUri)
        val effectiveTree = if (writable) treeUri else null

        var savedInLibrary = 0
        var exported = 0
        var failed = 0

        buckets.forEach { bucket ->
            if (bucket.entries.isEmpty()) return@forEach
            try {
                val existingXml = if (effectiveTree != null && bucket.parentDocumentId != null) {
                    saf.readGamelist(effectiveTree, bucket.parentDocumentId)
                } else {
                    null
                }
                val xml = GamelistBuilder.merge(existingXml, bucket.entries.values.toList())
                when (
                    saf.saveGamelist(
                        treeUri = effectiveTree,
                        parentDocumentId = bucket.parentDocumentId,
                        systemFolderName = bucket.systemFolderName,
                        xml = xml,
                        forceExport = settings.forceExportFallback,
                    )
                ) {
                    is FileWriteResult.SavedToLibrary -> savedInLibrary++
                    is FileWriteResult.ExportedToAppStorage -> exported++
                    is FileWriteResult.Failed -> failed++
                }
            } catch (error: Exception) {
                Log.w(TAG, "gamelist write failed for one folder: ${error.javaClass.simpleName}")
                failed++
            }
        }

        val note = buildString {
            when {
                savedInLibrary > 0 -> append("gamelist.xml updated in $savedInLibrary ${folderWord(savedInLibrary)}")
                exported > 0 -> append("gamelist.xml exported for $exported ${folderWord(exported)}")
                else -> append("gamelist.xml could not be written")
            }
            if (savedInLibrary > 0 && exported > 0) append(", $exported exported")
            if (failed > 0) append(" • $failed skipped")
        }

        _state.update { it.copy(currentLine = null, gamelistNote = note) }
    }

    private fun folderWord(count: Int): String = if (count == 1) "folder" else "folders"

    // endregion

    private fun describe(error: ProviderError): String = error.userMessage

    /** Mirrors the queue into the observable state so the UI and notification follow it. */
    private fun publish(active: ScanQueue) {
        _state.update { it.copy(jobs = active.jobs, processed = active.finishedCount) }
    }

    /**
     * Writes the run down so it can be picked up if the process is stopped.
     *
     * Called after every job rather than at intervals: the whole point is to survive a
     * kill that gives no warning, and the cost is one small JSON write against a queue
     * step that just spent seconds on the network.
     */
    private fun persist(phase: ScanPhase, immediate: Boolean = false) {
        val active = queue ?: return
        val id = scanId ?: return
        val settings = settingsRepository.current
        stateStore.save(
            ScanSnapshot(
                scanId = id,
                phase = phase,
                sourceTreeUri = settings.libraryTreeUri,
                sourceLabel = settings.libraryLabel,
                rescrape = rescrapeRun,
                startedAtMillis = scanStartedAtWallMillis,
                endedAtMillis = if (phase == ScanPhase.Running) null else System.currentTimeMillis(),
                roms = romsById.values.map { it.toRecord() },
                jobs = active.jobs,
            ),
            immediate = immediate,
        )
    }

    /** The row status that matches a restored job, so a resumed list looks unchanged. */
    private fun statusForJob(queued: QueueJob?): PrepStatus = when (queued?.state) {
        null, JobState.Ready, JobState.Processing -> PrepStatus.Pending
        JobState.Completed -> PrepStatus.Downloaded
        JobState.Skipped -> PrepStatus.AlreadyExists
        JobState.Deferred, JobState.Failed -> PrepStatus.ApiError
    }

    /**
     * How long one ROM took end to end.
     *
     * Purely observational: the value is written to the log and nothing else reads
     * it, so no decision anywhere depends on it. The durable copy of the same figure
     * lives in the queue and the in-app diagnostic log, which is what survives to be
     * exported — logcat had already rotated these away by the time the device bug
     * report was taken.
     */
    private fun logRomTiming(rom: RomEntry, startedAtMillis: Long, outcome: String) {
        Log.i(
            TAG,
            "$TIMING_MARKER rom=\"${rom.fileName}\" system=${rom.system?.key ?: "unknown"} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMillis} " +
                "outcome=$outcome",
        )
    }

    /** The status a row ended up with, for the timing line. */
    private fun statusOf(id: String): String =
        _state.value.items.firstOrNull { it.id == id }?.status?.name ?: "unknown"

    private fun updateItem(id: String, transform: (PrepItem) -> PrepItem) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) transform(it) else it })
        }
    }

    private inline fun MutableStateFlow<ScrapeState>.update(transform: (ScrapeState) -> ScrapeState) {
        value = transform(value)
    }

    private companion object {
        const val TAG = "ScrapeCoordinator"

        /**
         * One grep-able marker on every timing line, so a controlled test run can be
         * filtered out of the log without reading around it.
         */
        const val TIMING_MARKER = "TIMING"
        const val UNSORTED_KEY = "unsorted"
        const val MAX_DETAIL_LENGTH = 120

        const val MILLIS_PER_SECOND = 1_000L
    }
}
