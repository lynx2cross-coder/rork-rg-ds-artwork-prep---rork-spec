package com.rork.rgdsartworkprep.provider

import android.util.Log
import com.rork.rgdsartworkprep.network.ProviderError
import com.rork.rgdsartworkprep.network.ProviderResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises a provider's requests, spaces them out, and retries the transient ones.
 *
 * This exists so a newly added source arrives already house-trained, instead of each
 * one growing its own copy of the same logic. It is deliberately **only** used by the
 * sources added after it: Hasheous and ScreenScraper keep their own tuned pacing,
 * which is working and is not touched.
 *
 * @param minIntervalMillis floor for the gap between two requests.
 * @param maxIntervalMillis ceiling once the service has pushed back repeatedly.
 */
class RequestPacer(
    private val tag: String,
    private val minIntervalMillis: Long,
    private val maxIntervalMillis: Long = minIntervalMillis * MAX_WIDENING,
    private val maxAttempts: Int = DEFAULT_ATTEMPTS,
) {

    /** What one attempt concluded. */
    sealed interface Attempt<out T> {
        data class Done<T>(val value: T) : Attempt<T>

        /** Worth trying again; [delayMillis] honours a wait the server asked for. */
        data class Retry(
            val error: ProviderError,
            val delayMillis: Long? = null,
        ) : Attempt<Nothing>

        /** A settled answer — trying again would produce the same thing. */
        data class Fail(val error: ProviderError) : Attempt<Nothing>
    }

    private val gate = Mutex()

    @Volatile
    private var lastRequestAtMillis: Long = 0L

    @Volatile
    private var cooldownUntilMillis: Long = 0L

    @Volatile
    private var pacingMillis: Long = minIntervalMillis

    @Volatile
    private var consecutiveFaults: Int = 0

    /** True while the service is failing or has asked to be left alone. */
    val isDegraded: Boolean
        get() = consecutiveFaults > 0 || cooldownUntilMillis > System.currentTimeMillis()

    /**
     * Forgets how the previous batch went, so a retry pass is a real second chance
     * rather than a continuation of a bad run. A wait the server explicitly asked for
     * is kept, because that one was an instruction rather than a guess made here.
     */
    fun onRunStarted() {
        consecutiveFaults = 0
        pacingMillis = minIntervalMillis
    }

    suspend fun <T> run(block: suspend (attempt: Int) -> Attempt<T>): ProviderResult<T> =
        gate.withLock {
            @Suppress("TooGenericExceptionCaught")
            try {
                var lastError: ProviderError = ProviderError.Network("unknown")
                // Spacing is widened at most once per call, so one bad ROM cannot drag
                // the whole scan down to its slowest pace and leave it there.
                var widened = false
                for (attempt in 0 until maxAttempts) {
                    val cooldown = cooldownUntilMillis - System.currentTimeMillis()
                    if (cooldown > 0) delay(cooldown)
                    val elapsed = System.currentTimeMillis() - lastRequestAtMillis
                    if (elapsed < pacingMillis) delay(pacingMillis - elapsed)

                    val outcome = try {
                        block(attempt)
                    } finally {
                        lastRequestAtMillis = System.currentTimeMillis()
                    }

                    when (outcome) {
                        is Attempt.Done -> {
                            noteAccepted()
                            return@withLock ProviderResult.Success(outcome.value)
                        }
                        is Attempt.Fail -> return@withLock ProviderResult.Failure(outcome.error)
                        is Attempt.Retry -> {
                            lastError = outcome.error
                            val wait = outcome.delayMillis ?: backoffFor(outcome.error, attempt)
                            notePushback(wait, widen = !widened)
                            widened = true
                            if (attempt < maxAttempts - 1) delay(wait)
                        }
                    }
                }
                ProviderResult.Failure(lastError)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Nothing a provider does may escape as an exception.
                Log.w(tag, "Call failed: ${error.javaClass.simpleName}")
                ProviderResult.Failure(ProviderError.Network(error.javaClass.simpleName))
            }
        }

    private fun notePushback(waitMillis: Long, widen: Boolean) {
        cooldownUntilMillis = System.currentTimeMillis() + waitMillis
        consecutiveFaults++
        if (!widen) return
        val next = (pacingMillis * 2).coerceAtMost(maxIntervalMillis)
        if (next != pacingMillis) {
            pacingMillis = next
            Log.i(tag, "Pushed back; spacing requests ${next}ms apart")
        }
    }

    private fun noteAccepted() {
        consecutiveFaults = 0
        if (pacingMillis > minIntervalMillis) {
            pacingMillis = (pacingMillis - RECOVERY_MILLIS).coerceAtLeast(minIntervalMillis)
        }
    }

    /**
     * A connection that never opened is given noticeably longer than a server that
     * answered something unhelpful: retrying a dead route immediately just reproduces
     * the same failure.
     */
    private fun backoffFor(error: ProviderError, attempt: Int): Long {
        val base = when {
            error is ProviderError.RateLimited -> RATE_LIMIT_BACKOFF_MILLIS shl attempt
            error is ProviderError.Network -> NETWORK_BACKOFF_MILLIS * (attempt + 1)
            else -> BASE_BACKOFF_MILLIS * (attempt + 1)
        }
        return (base + (0..JITTER_MILLIS).random()).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private companion object {
        const val DEFAULT_ATTEMPTS = 3
        const val MAX_WIDENING = 6
        const val BASE_BACKOFF_MILLIS = 700L
        const val NETWORK_BACKOFF_MILLIS = 1_500L
        const val RATE_LIMIT_BACKOFF_MILLIS = 2_000L
        const val JITTER_MILLIS = 400
        const val MAX_BACKOFF_MILLIS = 15_000L
        const val RECOVERY_MILLIS = 150L
    }
}
