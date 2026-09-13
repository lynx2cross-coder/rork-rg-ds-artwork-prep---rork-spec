package com.rork.rgdsartworkprep.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Recognises a request the app abandoned, as opposed to one that failed.
 *
 * A timeout arrives as an ordinary exception, and the shape of it depends on which
 * layer gave up first — the HTTP client's own request timeout, the socket's read
 * timeout, or OkHttp's call watchdog closing the connection underneath a blocking
 * read. All three mean the same thing to this app, and none of them mean the game is
 * missing, so they are recognised in one place rather than by pattern-matching
 * exception names at each call site.
 *
 * This is pure Kotlin so the classification can be asserted on the JVM: the failure
 * it prevents — a timeout being filed as "not in the database" — is exactly the kind
 * that is invisible until a user reports a game they know exists as unfindable.
 */
object RequestTimeout {

    /** True when [error] means "we stopped waiting", not "the service answered no". */
    fun isTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        // Wrapped exceptions are the norm here: OkHttp's watchdog surfaces through
        // whatever read was in flight when it fired.
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current.isTimeoutItself) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * The error to report for a failed request.
     *
     * Timeouts become [ProviderError.Timeout], which is transient and therefore
     * deferrable; everything else keeps the existing [ProviderError.Network]
     * behaviour, so this narrows the meaning of a failure without widening it.
     */
    fun classify(error: Throwable, stage: String, elapsedMillis: Long): ProviderError =
        if (isTimeout(error)) {
            ProviderError.Timeout(stage, elapsedMillis)
        } else {
            ProviderError.Network(error.javaClass.simpleName)
        }

    private val Throwable.isTimeoutItself: Boolean
        get() = when (this) {
            is HttpRequestTimeoutException -> true
            is SocketTimeoutException -> true
            // OkHttp's callTimeout reports as a plain InterruptedIOException whose
            // message is "timeout"; an interrupted read for any other reason is not
            // claimed as one.
            is InterruptedIOException -> message?.contains(TIMEOUT_HINT, ignoreCase = true) == true
            else -> false
        }

    private const val TIMEOUT_HINT = "timeout"
    private const val MAX_CAUSE_DEPTH = 8
}
