package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.network.ProviderError
import kotlinx.serialization.Serializable

/**
 * Where one ROM stands in the scan.
 *
 * The distinction that matters is between a verdict about the *game* and a verdict
 * about the *moment*. [Skipped] and [Completed] are facts about the game and are
 * final. [Deferred] is a fact about the moment — the service was busy, the request
 * timed out — and says nothing about whether the artwork exists, so it is worth
 * another attempt later. [Failed] is what a deferred job becomes once it has used
 * up its automatic attempts, so nothing retries forever.
 */
@Serializable
enum class JobState {
    /** Waiting to be attempted. */
    Ready,

    /** Currently being worked on. At most one job is in this state at a time. */
    Processing,

    /** Artwork was obtained, saved, or was already in place. */
    Completed,

    /** Permanently decided: unsupported system, confirmed miss, user skip. */
    Skipped,

    /** Temporarily unreachable. Eligible to be retried once its backoff expires. */
    Deferred,

    /** Deferred work that ran out of automatic attempts. Only a manual retry revives it. */
    Failed,
    ;

    /** True once nothing further will happen to this job without the user asking. */
    val isFinished: Boolean get() = this == Completed || this == Skipped || this == Failed

    /** True while the job still belongs to the active scan. */
    val isPending: Boolean get() = this == Ready || this == Processing || this == Deferred
}

/**
 * Why a job was put aside, in the app's own words rather than a provider's.
 *
 * Every reason here is transient by definition — a permanent outcome is a
 * [JobState.Skipped], never a defer reason. The wording is deliberately stable and
 * machine-readable because it is what the exported report is grouped by.
 */
@Serializable
enum class DeferReason(val key: String, val label: String) {
    /** A provider call ran past its own timeout. */
    ProviderTimeout("provider_timeout", "Timed out"),

    /**
     * The whole ROM took longer than the scan is willing to spend on one game, without
     * any single call having failed. This is the case that used to stall a batch.
     */
    SlowResponse("slow_response", "Too slow"),

    /** The connection never opened, or dropped part-way. */
    NetworkFailure("network_failure", "Network problem"),

    /** The service answered, but with a failure of its own. */
    ServerFailure("server_failure", "Provider failing"),

    /** The service asked to be called less often. */
    RateLimited("rate_limited", "Rate limited"),

    /** The service could not be used right now for some other reason. */
    ProviderUnavailable("provider_unavailable", "Provider unavailable"),

    /** The ROM itself could not be read to fingerprint it this time. */
    FileUnreadable("file_unreadable", "Could not read file"),

    /** The game was identified but its cover never arrived. */
    ArtworkIncomplete("artwork_incomplete", "Cover did not arrive"),
    ;

    /**
     * True when the service itself was struggling, as opposed to the connection.
     *
     * A server under load needs far longer to recover than a stalled route, which is
     * why the two get different backoff schedules.
     */
    val isServerPaced: Boolean
        get() = this == RateLimited || this == ServerFailure

    companion object {
        fun fromKey(key: String): DeferReason? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Decides whether a provider failure is worth another attempt, and files it under a
 * reason the user can read.
 *
 * This reuses the existing [ProviderError] model rather than inventing a second
 * failure vocabulary: `isTransient` already encodes "this says nothing about the
 * ROM", and `stopsRun` already encodes "no ROM can succeed until the user acts".
 * Only the mapping to a reason is new.
 */
object DeferralPolicy {

    /**
     * The reason to defer under, or null when the failure is permanent and the job
     * should be skipped instead.
     */
    fun reasonFor(error: ProviderError): DeferReason? = when {
        // A provider that has refused everything is not a per-ROM problem. Deferring
        // would queue the same refusal against every game in the library.
        error.stopsRun -> null
        !error.isTransient -> null
        error is ProviderError.RateLimited -> DeferReason.RateLimited
        // A request the app abandoned is a timeout in its own right and does not need
        // to be recognised from the text of an exception name.
        error is ProviderError.Timeout -> DeferReason.ProviderTimeout
        error is ProviderError.Http && error.code in SERVER_ERROR_RANGE -> DeferReason.ServerFailure
        error is ProviderError.Http -> DeferReason.ProviderUnavailable
        error is ProviderError.Network -> networkReasonFor(error.detail)
        error is ProviderError.Unexpected -> DeferReason.ProviderUnavailable
        else -> DeferReason.ProviderUnavailable
    }

    /**
     * Separates a timeout from a connection that failed outright.
     *
     * Ktor reports both as network failures, but they mean different things in a
     * report: a timeout is the app giving up on a slow service, a refused connection
     * is the service not being there at all.
     */
    private fun networkReasonFor(detail: String): DeferReason {
        val lower = detail.lowercase()
        val timedOut = TIMEOUT_HINTS.any { lower.contains(it) }
        return if (timedOut) DeferReason.ProviderTimeout else DeferReason.NetworkFailure
    }

    private val SERVER_ERROR_RANGE = 500..599

    private val TIMEOUT_HINTS = listOf("timeout", "timed out")
}

/**
 * How often and how soon a deferred job may be tried again.
 *
 * The numbers are the ones the sequential recovery passes already used, so this
 * change alters *when* work is retried, not how hard the app leans on a provider.
 */
@Serializable
data class RetryPolicy(
    /** Automatic attempts per job, including the first. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** Backoff for a stalled connection, indexed by attempts already made. */
    val connectionBackoffMillis: List<Long> = listOf(5_000L, 15_000L, 30_000L),
    /** Backoff for a service that was failing or throttling. */
    val serverBackoffMillis: List<Long> = listOf(15_000L, 45_000L, 90_000L),
    /**
     * How long one ROM may occupy the scan **while it still has nothing to show for
     * it**, before it is set aside so the rest of the queue can move.
     *
     * This is a soft bound, checked between stages rather than enforced mid-call. It
     * stops a game that is getting nowhere from holding the scan hostage; it is not
     * permission to throw away a result that has already been obtained. Once a ROM has
     * been identified the budget stops applying to it — see `JobBudget.commit`.
     */
    val jobBudgetMillis: Long = DEFAULT_JOB_BUDGET_MILLIS,
    /**
     * The last-resort ceiling on a single job, enforced by cancellation.
     *
     * Distinct from [jobBudgetMillis] because the two do different work. The budget is
     * the ordinary bound and is polite: it waits for a safe point, so it can never
     * interrupt a download that is about to succeed. This exists only so a defect that
     * left the pipeline spinning could not hang a scan forever, which means it must sit
     * far enough above every legitimate path that reaching it is always a bug.
     *
     * Requests are individually bounded now, so the realistic worst case is a handful
     * of slow-but-legal calls in sequence; this is comfortably above that.
     */
    val hardCeilingMillis: Long = DEFAULT_HARD_CEILING_MILLIS,
) {

    /** When a job deferred now becomes eligible again. */
    fun nextEligibleAt(nowMillis: Long, attempts: Int, reason: DeferReason): Long {
        val schedule = if (reason.isServerPaced) serverBackoffMillis else connectionBackoffMillis
        if (schedule.isEmpty()) return nowMillis
        val index = (attempts - 1).coerceIn(0, schedule.lastIndex)
        return nowMillis + schedule[index]
    }

    fun hasAttemptsLeft(attempts: Int): Boolean = attempts < maxAttempts

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3

        /**
         * Four minutes. Long enough that a genuinely slow but working lookup still
         * finishes — the longest legitimate path measured was a multi-megabyte index
         * download followed by a paced retry — and short enough that one bad game
         * cannot swallow a scan. A deferred job is not abandoned, only re-queued.
         */
        const val DEFAULT_JOB_BUDGET_MILLIS = 240_000L

        /**
         * Fifteen minutes. Deliberately not a tuning knob: it is the point past which
         * the app must be wrong, not the point past which a game is slow.
         *
         * The previous build used the four-minute budget for this and paid for it. A
         * Metroid Fusion lookup that genuinely succeeded at 290.5 seconds was cancelled
         * at 240, and because the match was discarded the work was simply lost — the
         * scan had the right answer and threw it away.
         */
        const val DEFAULT_HARD_CEILING_MILLIS = 900_000L
    }
}

/** One ROM's place in the queue, with everything needed to explain or resume it. */
@Serializable
data class QueueJob(
    val id: String,
    val fileName: String,
    val systemKey: String?,
    val state: JobState = JobState.Ready,
    /** Attempts already made. Zero until the job has been processed once. */
    val attempts: Int = 0,
    val deferReason: DeferReason? = null,
    /** Short explanation for the row and the report. Never contains credentials. */
    val detail: String? = null,
    /** Which source was involved when it was deferred, when that is known. */
    val providerKey: String? = null,
    /** Wall-clock stamp of the last attempt, for the report. */
    val lastAttemptAtMillis: Long? = null,
    /** Earliest time an automatic retry may run. Null when not deferred. */
    val nextEligibleAtMillis: Long? = null,
    /** How long the last attempt took. */
    val lastElapsedMillis: Long = 0L,
    /** Total time spent across every attempt, which is what the report quotes. */
    val totalElapsedMillis: Long = 0L,
) {
    /** True when this job is deferred and its backoff has expired. */
    fun isEligible(nowMillis: Long): Boolean =
        state == JobState.Deferred && (nextEligibleAtMillis ?: 0L) <= nowMillis
}
