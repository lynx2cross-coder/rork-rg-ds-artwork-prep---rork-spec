package com.rork.rgdsartworkprep.network

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why a provider call did not produce a game.
 *
 * Shared by every metadata source so the rest of the app never has to know which
 * service it is talking to. Every case carries the sentence shown to the user, so a
 * failure can never reach the UI as a raw exception or a bare status code.
 */
private const val MILLIS_PER_SECOND = 1_000L

sealed interface ProviderError {

    /** Plain-language explanation. Never contains credentials or internals. */
    val userMessage: String

    /**
     * True when no further ROM can succeed *through this provider* until the user
     * acts — wrong credentials, exhausted quota, closed service. The provider is then
     * set aside for the rest of the run instead of repeating the same failure for
     * every remaining game. The run itself only stops once no provider is left.
     */
    val stopsRun: Boolean get() = false

    /**
     * True when the call failed for a reason that says nothing about the ROM — the
     * service was busy, the request timed out, the reply was unusable.
     *
     * Retrying such a call may well succeed, so it must never be presented as "this
     * game is not in the database". Only [NotFound] means that.
     */
    val isTransient: Boolean get() = false

    data object NotFound : ProviderError {
        override val userMessage: String get() = "Not in the database"
    }

    data object MissingCredentials : ProviderError {
        override val userMessage: String
            get() = "Add your ScreenScraper developer ID and password in Settings."
        override val stopsRun: Boolean get() = true
    }

    /** The provider actively refused the credentials it was given. */
    data class InvalidCredentials(
        val detail: String,
        val scope: CredentialScope = CredentialScope.Unknown,
    ) : ProviderError {
        override val userMessage: String get() = detail
        override val stopsRun: Boolean get() = true
    }

    /**
     * The request itself was wrong — a required field was missing or unusable. This is
     * an app defect, never something the user can fix by retyping a password, so it is
     * reported separately from an authentication failure.
     */
    data class BadRequest(val detail: String) : ProviderError {
        override val userMessage: String get() = detail
        override val stopsRun: Boolean get() = true
    }

    data class QuotaExceeded(val detail: String) : ProviderError {
        override val userMessage: String get() = detail
        override val stopsRun: Boolean get() = true
    }

    /** The service itself is unavailable to this account or this app version. */
    data class ServiceClosed(val detail: String) : ProviderError {
        override val userMessage: String get() = detail
        override val stopsRun: Boolean get() = true
    }

    data class RateLimited(val detail: String) : ProviderError {
        override val userMessage: String get() = "Rate limited — $detail"
        override val isTransient: Boolean get() = true
    }

    data class Http(val code: Int, val detail: String) : ProviderError {
        override val userMessage: String get() = "Provider error $code — $detail"
        override val isTransient: Boolean get() = true
    }

    data class Network(val detail: String) : ProviderError {
        override val userMessage: String get() = "Network unavailable ($detail)"
        override val isTransient: Boolean get() = true
    }

    /**
     * The app stopped waiting for a request that never finished.
     *
     * Held apart from [Network] because the two read differently in a report: a
     * refused connection means the service was not there, while this means it was
     * there and too slow to be worth the queue's only worker. Critically it is also
     * not [NotFound] — the service never answered, so nothing at all is known about
     * whether it has this game, and recording a miss would be an invention.
     *
     * @param stage which request gave up, so a report names it without guessing.
     * @param afterMillis how long was spent before giving up.
     */
    data class Timeout(val stage: String, val afterMillis: Long) : ProviderError {
        override val userMessage: String
            get() = "Timed out after ${afterMillis / MILLIS_PER_SECOND}s \u2014 will try again"
        override val isTransient: Boolean get() = true
    }

    /**
     * This device could not finish processing a reply that arrived intact.
     *
     * Deliberately **not** transient, which is the whole reason it exists. A timeout
     * waiting on a service says nothing about the next attempt, so retrying is right.
     * This says the opposite: the data arrived, the device worked on it, and the work
     * did not fit. The same device given the same listing will reach the same point
     * again, so the three automatic retries cost minutes to re-prove a settled fact —
     * on the RG DS that was 266 seconds for one ROM, all of it spent failing the same
     * way. Reported honestly rather than as [NotFound]: the covers may well exist, and
     * claiming otherwise would be an invention.
     *
     * @param stage which step ran out of room, so a report names it without guessing.
     * @param afterMillis how long was spent before standing down.
     */
    data class ProcessingLimit(val stage: String, val afterMillis: Long) : ProviderError {
        override val userMessage: String
            get() = "This device could not process the $stage in " +
                "${afterMillis / MILLIS_PER_SECOND}s"
    }

    /**
     * A failure the app did not anticipate — a reply shaped differently than expected,
     * a bug in the app's own handling of one.
     *
     * It exists so that no defect can reach the user as a bare "unexpected error" with
     * nothing to act on: the stage and the failure's own name travel with it, which is
     * usually enough to say which source and which step were involved. It deliberately
     * does not stop the run, because one unreadable reply says nothing about the next
     * ROM.
     */
    data class Unexpected(val stage: String, val detail: String) : ProviderError {
        override val userMessage: String get() = "$stage failed \u2014 $detail"
        override val isTransient: Boolean get() = true
    }
}

/** Which set of credentials the service named when it refused a call. */
enum class CredentialScope {
    /** The developer ID / password pair. */
    Developer,

    /** The optional member account. */
    Member,

    /** The reply did not say. */
    Unknown,
}

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data class Failure(val error: ProviderError) : ProviderResult<Nothing>
}

/**
 * A verbatim record of the last exchange with a provider, for when the app's own
 * summary is not enough to tell what went wrong.
 *
 * ScreenScraper answers a missing parameter and a wrong password with the same
 * sentence, so the only way to tell an authentication failure from a malformed
 * request is to see the status code and the untouched reply side by side with the
 * fields that were actually sent.
 *
 * Credential *values* are never stored here — only field names, their lengths, and
 * whether they carried anything unusual.
 */
data class ApiDiagnostic(
    /** Which source this exchange was with, so two sources cannot be confused. */
    val provider: String = "ScreenScraper",
    val endpoint: String,
    val httpStatus: Int,
    /** The service's own words, trimmed. Empty when it sent a normal payload. */
    val serverMessage: String,
    /** Field names with value lengths — never the values themselves. */
    val sentFields: List<String>,
    /** What the app concluded, so a misclassification is visible. */
    val verdict: String,
    val atMillis: Long = System.currentTimeMillis(),
) {
    /** Multi-line report suitable for sharing. Contains no secrets. */
    fun asReport(): String = buildString {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMillis))
        appendLine("$stamp · $provider API")
        appendLine("Endpoint: $endpoint")
        appendLine("HTTP status: $httpStatus")
        appendLine("Verdict: $verdict")
        appendLine("Fields sent: ${sentFields.joinToString(", ")}")
        if (serverMessage.isNotBlank()) appendLine("Server said: $serverMessage")
    }.trim()
}
