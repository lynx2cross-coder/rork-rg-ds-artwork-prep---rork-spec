package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.model.PrepStatus

/** Why a job was set aside, captured at the moment the pipeline decided it. */
data class DeferHint(
    val reason: DeferReason,
    val detail: String?,
    val providerKey: String? = null,
)

/** What should become of a job once its attempt has finished. */
sealed interface JobVerdict {

    /** Artwork was obtained and written. */
    data class Complete(val detail: String?) : JobVerdict

    /** A fact about the game that another attempt cannot change. */
    data class Skip(val detail: String?) : JobVerdict

    /** A fact about the moment. Worth another controlled attempt later. */
    data class Defer(
        val reason: DeferReason,
        val detail: String?,
        val providerKey: String? = null,
    ) : JobVerdict

    /** A permanent failure with no transient evidence behind it. */
    data class GiveUp(val detail: String?) : JobVerdict
}

/**
 * Turns the pipeline's verdict about a ROM into the queue's verdict about a job.
 *
 * This is the layer that got the previous build wrong, so it is deliberately pure:
 * no Android, no coroutines, no clock of its own. Everything it decides with is
 * passed in, which is what lets the regression cases be asserted on the JVM.
 *
 * Two rules matter more than the rest, and both exist because they were broken:
 *
 * 1. **Work that succeeded is never undone.** A finished status is read before the
 *    budget is even considered, so a cover that reached the disk is recorded as
 *    [JobVerdict.Complete] even if the attempt overran. The alternative — what the
 *    previous build did — tells the user a download failed while the file sits in
 *    their artwork folder.
 * 2. **A reason must be earned.** [DeferReason.SlowResponse] is only returned when
 *    the measured elapsed time actually reached the budget. Anything else uses the
 *    evidence the pipeline recorded, and a job that did no work at all can never be
 *    described as having run out of time.
 */
object JobSettlement {

    /**
     * @param status the row status the pipeline left behind for this ROM.
     * @param message the row message, reused as the job detail when nothing better exists.
     * @param hint the transient failure the pipeline recorded during this attempt, if any.
     * @param elapsedMillis measured duration of this attempt.
     * @param budgetMillis the per-job budget the attempt was given.
     * @param budgetExpired true when the attempt was abandoned for running past its budget.
     */
    fun verdictFor(
        status: PrepStatus,
        message: String?,
        hint: DeferHint?,
        elapsedMillis: Long,
        budgetMillis: Long,
        budgetExpired: Boolean,
    ): JobVerdict {
        // Decided outcomes are read first, before the budget is consulted. A row only
        // reaches one of these after the pipeline finished with it, so honouring them
        // here is what guarantees saved artwork is never re-labelled as a failure.
        when (status) {
            PrepStatus.Downloaded, PrepStatus.Exported -> return JobVerdict.Complete(message)

            // Artwork already present, a system the app does not scrape, a system the
            // user switched off, a game no source has, or a choice waiting on the user:
            // all facts about the game rather than about this attempt. A switched-off
            // system settles here for the same reason as the rest — retrying it would
            // read the same setting and skip again, so a retry is not a second chance.
            // ChooseArtwork joins them for the same reason — automatic matching has had
            // its turn, so the row is waiting on a person and must not be retried or
            // deferred behind them.
            PrepStatus.AlreadyExists,
            PrepStatus.Unsupported,
            PrepStatus.SystemDisabled,
            PrepStatus.NotFound,
            PrepStatus.MultipleMatches,
            PrepStatus.ChooseArtwork,
            -> return JobVerdict.Skip(message)

            else -> Unit
        }

        // Only now may the clock have an opinion, and only if it can back it up.
        if (budgetExpired && elapsedMillis >= budgetMillis) {
            return JobVerdict.Defer(
                reason = DeferReason.SlowResponse,
                detail = overBudgetDetail(elapsedMillis, budgetMillis),
                providerKey = hint?.providerKey,
            )
        }

        // The pipeline's own evidence outranks the fact that the attempt was stopped:
        // a job cut short while a service was failing was deferred for that failure.
        if (hint != null) {
            return JobVerdict.Defer(hint.reason, hint.detail ?: message, hint.providerKey)
        }

        // Stopped short with nothing recorded. Deferring is the safe reading — the
        // attempt never reached a verdict, so there is nothing to hold against the ROM.
        if (budgetExpired || status.isUnfinishedStatus) {
            return JobVerdict.Defer(
                reason = DeferReason.ProviderUnavailable,
                detail = "Stopped before it reached an answer",
            )
        }

        // An error with no transient evidence was permanent; retrying repeats it.
        return JobVerdict.GiveUp(message)
    }

    /**
     * Spells out the arithmetic in the report.
     *
     * The previous build printed a bare "Exceeded the time budget" beside jobs that
     * had run for 0 seconds, and that mismatch is exactly what a report is for. With
     * both numbers present a wrong label cannot survive being read.
     */
    fun overBudgetDetail(elapsedMillis: Long, budgetMillis: Long): String =
        "Exceeded the ${seconds(budgetMillis)}s budget after ${seconds(elapsedMillis)}s"

    private fun seconds(millis: Long): Long = millis / MILLIS_PER_SECOND

    private val PrepStatus.isUnfinishedStatus: Boolean
        get() = this == PrepStatus.Pending || this == PrepStatus.Working

    private const val MILLIS_PER_SECOND = 1_000L
}
