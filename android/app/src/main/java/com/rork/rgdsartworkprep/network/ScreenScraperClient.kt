package com.rork.rgdsartworkprep.network

import android.util.Log
import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.model.GameCandidate
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** How close today's ScreenScraper allowance is to running out. */
enum class QuotaLevel {
    /** Plenty left; nothing is shown to the user. */
    Normal,

    /** Enough left for a small batch, but a large one will not finish. */
    Warning,

    /** Only a sliver remains. */
    Critical,

    /** Nothing left until the allowance resets. */
    Exhausted,
}

/**
 * Today's ScreenScraper usage, as reported by the service itself.
 *
 * ScreenScraper attaches the caller's counters to every reply, so this is refreshed
 * from ordinary lookups — the app never spends a request just to ask how many are
 * left. Two separate allowances exist: total lookups, and lookups for ROMs the
 * database does not recognise. Either one running out halts scraping, so the more
 * urgent of the two drives [level].
 */
data class QuotaSnapshot(
    val used: Int,
    val max: Int,
    val notFoundUsed: Int? = null,
    val notFoundMax: Int? = null,
    /** Set when the service has actually refused a call for being over the limit. */
    val isExhausted: Boolean = false,
) {
    val remaining: Int get() = (max - used).coerceAtLeast(0)

    val fraction: Float get() = if (max <= 0) 0f else (used.toFloat() / max).coerceIn(0f, 1f)

    val notFoundRemaining: Int?
        get() = if (notFoundUsed != null && notFoundMax != null && notFoundMax > 0) {
            (notFoundMax - notFoundUsed).coerceAtLeast(0)
        } else {
            null
        }

    val notFoundFraction: Float?
        get() = if (notFoundUsed != null && notFoundMax != null && notFoundMax > 0) {
            (notFoundUsed.toFloat() / notFoundMax).coerceIn(0f, 1f)
        } else {
            null
        }

    /** The allowance closest to running out, used for the meter and the colour. */
    val worstFraction: Float get() = maxOf(fraction, notFoundFraction ?: 0f)

    /** True when the unrecognised-ROM allowance is the more urgent of the two. */
    val isNotFoundLimitLeading: Boolean
        get() = (notFoundFraction ?: 0f) > fraction

    val level: QuotaLevel
        get() = when {
            isExhausted || worstFraction >= 1f -> QuotaLevel.Exhausted
            worstFraction >= CRITICAL_FRACTION -> QuotaLevel.Critical
            worstFraction >= WARNING_FRACTION -> QuotaLevel.Warning
            else -> QuotaLevel.Normal
        }

    companion object {
        /** Roughly a fifth of the day's allowance left. */
        const val WARNING_FRACTION = 0.8f
        const val CRITICAL_FRACTION = 0.95f
    }
}

/**
 * ScreenScraper API v2 client.
 *
 * All calls go through a single-slot queue with a minimum interval so the app never
 * floods the service, and transient failures are retried with backoff.
 * Credentials are supplied by the user in Settings — nothing is hard-coded.
 *
 * No call here can throw: every failure, including errors thrown by the HTTP engine
 * itself, is converted into a [ProviderError]. Only cancellation is allowed through,
 * because that is how a stopped run unwinds.
 */
class ScreenScraperClient {

    private val client = HttpClient(Android) {
        expectSuccess = false
        engine {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            socketTimeout = SOCKET_TIMEOUT_MILLIS
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Mutex()

    private val _quota = MutableStateFlow<QuotaSnapshot?>(null)

    private val _lastDiagnostic = MutableStateFlow<ApiDiagnostic?>(null)

    /** The last exchange with the service, shown in Settings when a check fails. */
    val lastDiagnostic: StateFlow<ApiDiagnostic?> = _lastDiagnostic.asStateFlow()

    /**
     * Today's allowance, refreshed from every reply the service sends. Null until a
     * call has been made, or when using developer credentials without a member
     * account (the service reports no counters for those).
     */
    val quota: StateFlow<QuotaSnapshot?> = _quota.asStateFlow()

    @Volatile
    private var lastRequestAtMillis: Long = 0L

    /**
     * When the service last asked to be left alone until.
     *
     * This outlives a single game on purpose: the limiter counts requests from the app,
     * not from one lookup, so a wait earned during one game has to be respected by the
     * next.
     */
    @Volatile
    private var cooldownUntilMillis: Long = 0L

    /** Current spacing between requests, widened on push-back and eased back down. */
    @Volatile
    private var pacingMillis: Long = MIN_INTERVAL_MILLIS

    /** Server failures in a row, reset by the first reply that works. */
    @Volatile
    private var consecutiveServerFaults: Int = 0

    /** How long callers should wait before expecting this service to work again. */
    val cooldownRemainingMillis: Long
        get() = (cooldownUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * True while the service is failing or asking to be left alone.
     *
     * Lets the pipeline tell "this game has no artwork" apart from "the source was in
     * no state to tell us", exactly as the primary source does.
     */
    val isDegraded: Boolean
        get() = consecutiveServerFaults > 0 || cooldownRemainingMillis > 0

    /**
     * Forgets how badly the last batch went.
     *
     * Pacing and fault counts describe a moment, not the service. Carrying them into a
     * retry pass would handicap the very attempt meant to recover the games. A wait the
     * server explicitly asked for is kept, because that one was a real instruction.
     */
    fun onRunStarted() {
        consecutiveServerFaults = 0
        pacingMillis = MIN_INTERVAL_MILLIS
    }

    /** Outcome of the credential check offered in Settings. */
    sealed interface CredentialCheck {
        data class Ok(val message: String) : CredentialCheck

        /**
         * @param message plain-language sentence for the user.
         * @param hint what to actually try next, when the app can suggest something.
         */
        data class Problem(val message: String, val hint: String? = null) : CredentialCheck
    }

    /**
     * Verifies the stored credentials, so a wrong password is found in Settings instead
     * of halfway through a batch.
     *
     * The two credential sets are tested **separately and in order**, because
     * ScreenScraper blames whichever set it checked first and its `ssuserInfos`
     * endpoint reports a user-credential error even when the developer pair is the
     * broken one. Testing the developer pair alone first means a failure at the second
     * stage can only be the member account, so the app can name the right field instead
     * of guessing.
     */
    suspend fun verifyCredentials(settings: AppSettings): CredentialCheck {
        malformedRequestReason(settings)?.let { reason ->
            return CredentialCheck.Problem(reason, HINT_FIELD_CONTENT)
        }

        // Stage 1 — developer pair on its own, with the member fields deliberately
        // withheld so nothing else can be blamed.
        val developerOnly = settings.copy(userId = "", userPassword = "")
        val devProbe = search(developerOnly, systemId = PROBE_SYSTEM_ID, query = PROBE_QUERY, limit = 1)
        if (devProbe is ProviderResult.Failure) {
            return problemFor(devProbe.error, stage = CredentialScope.Developer)
        }

        if (settings.userId.isBlank() && settings.userPassword.isBlank()) {
            return CredentialCheck.Ok(
                "Developer credentials accepted. Adding your member account raises the daily quota.",
            )
        }

        // Stage 2 — the developer pair is now proven good, so anything refused here is
        // the member account.
        return when (val response = request(ENDPOINT_USER_INFO, settings) {}) {
            is ProviderResult.Failure -> problemFor(response.error, stage = CredentialScope.Member)
            is ProviderResult.Success -> {
                val snapshot = _quota.value
                CredentialCheck.Ok(
                    buildString {
                        append("Connected as ${settings.userId}")
                        if (snapshot != null) {
                            append(" — ${snapshot.used} of ${snapshot.max} lookups used today")
                        }
                    },
                )
            }
        }
    }

    /**
     * Turns a failure into advice, using the stage that failed rather than the reply,
     * which is unreliable about which credential set it means.
     */
    private fun problemFor(error: ProviderError, stage: CredentialScope): CredentialCheck.Problem =
        when (error) {
            is ProviderError.InvalidCredentials -> when (stage) {
                CredentialScope.Member -> CredentialCheck.Problem(MEMBER_REJECTED, HINT_MEMBER)
                else -> CredentialCheck.Problem(DEV_REJECTED, HINT_DEVELOPER)
            }
            is ProviderError.BadRequest -> CredentialCheck.Problem(error.userMessage, HINT_MALFORMED)
            is ProviderError.Network -> CredentialCheck.Problem(error.userMessage, HINT_NETWORK)
            else -> CredentialCheck.Problem(error.userMessage)
        }

    /**
     * Catches a request the service would reject for being incomplete before it is
     * sent. ScreenScraper answers a missing field with the very same "check your
     * developer credentials" sentence it uses for a wrong password, so without this
     * check a blank field would look like a rejected login.
     */
    private fun malformedRequestReason(settings: AppSettings): String? = when {
        settings.devId.isBlank() || settings.devPassword.isBlank() ->
            "Enter your developer ID and password first."
        settings.devId != settings.devId.trim() || settings.devPassword != settings.devPassword.trim() ->
            "Your developer ID or password has a leading or trailing space. Remove it and save again."
        settings.userPassword.isNotBlank() && settings.userId.isBlank() ->
            "A member password was entered without a member username. Add the username, or clear both."
        settings.userId.isNotBlank() && settings.userPassword.isBlank() ->
            "A member username was entered without a member password. Add the password, or clear both."
        else -> null
    }

    /** Look a game up by checksum / filename. */
    suspend fun gameInfo(
        settings: AppSettings,
        systemId: Int,
        romFileName: String,
        romSize: Long,
        crc32: String?,
    ): ProviderResult<GameCandidate> {
        if (!settings.hasScreenScraperCredentials) {
            return ProviderResult.Failure(ProviderError.MissingCredentials)
        }
        val response = request(ENDPOINT_GAME_INFO, settings) {
            parameter("systemeid", systemId.toString())
            parameter("romtype", "rom")
            parameter("romnom", romFileName)
            if (romSize > 0) parameter("romtaille", romSize.toString())
            if (!crc32.isNullOrBlank()) parameter("crc", crc32)
        }
        return when (response) {
            is ProviderResult.Failure -> response
            is ProviderResult.Success -> {
                val game = response.value["response"]?.jsonObjectOrNull()?.get("jeu")?.jsonObjectOrNull()
                    ?: return ProviderResult.Failure(ProviderError.NotFound)
                val candidate = toCandidate(game, settings.preferredRegion)
                    ?: return ProviderResult.Failure(ProviderError.NotFound)
                ProviderResult.Success(candidate)
            }
        }
    }

    /** Look a game up directly by its provider id (used for remembered matches). */
    suspend fun gameById(
        settings: AppSettings,
        systemId: Int,
        gameId: String,
    ): ProviderResult<GameCandidate> {
        if (!settings.hasScreenScraperCredentials) {
            return ProviderResult.Failure(ProviderError.MissingCredentials)
        }
        val response = request(ENDPOINT_GAME_INFO, settings) {
            parameter("systemeid", systemId.toString())
            parameter("gameid", gameId)
        }
        return when (response) {
            is ProviderResult.Failure -> response
            is ProviderResult.Success -> {
                val game = response.value["response"]?.jsonObjectOrNull()?.get("jeu")?.jsonObjectOrNull()
                    ?: return ProviderResult.Failure(ProviderError.NotFound)
                val candidate = toCandidate(game, settings.preferredRegion)
                    ?: return ProviderResult.Failure(ProviderError.NotFound)
                ProviderResult.Success(candidate)
            }
        }
    }

    /** Free-text search returning up to [limit] candidates for manual selection. */
    suspend fun search(
        settings: AppSettings,
        systemId: Int?,
        query: String,
        limit: Int = 12,
    ): ProviderResult<List<GameCandidate>> {
        if (!settings.hasScreenScraperCredentials) {
            return ProviderResult.Failure(ProviderError.MissingCredentials)
        }
        if (query.isBlank()) return ProviderResult.Success(emptyList())
        val response = request(ENDPOINT_SEARCH, settings) {
            if (systemId != null) parameter("systemeid", systemId.toString())
            parameter("recherche", query)
        }
        return when (response) {
            is ProviderResult.Failure -> if (response.error is ProviderError.NotFound) {
                ProviderResult.Success(emptyList())
            } else {
                response
            }
            is ProviderResult.Success -> {
                val games = response.value["response"]?.jsonObjectOrNull()?.get("jeux")?.jsonArrayOrNull()
                    ?: return ProviderResult.Success(emptyList())
                val candidates = games.mapNotNull { it.jsonObjectOrNull() }
                    .mapNotNull { toCandidate(it, settings.preferredRegion) }
                    .distinctBy { it.gameId }
                    .take(limit)
                ProviderResult.Success(candidates)
            }
        }
    }

    /** Downloads cover bytes. */
    suspend fun downloadArtwork(url: String): ProviderResult<ByteArray> =
        withRateLimit { attempt -> attemptDownload(url, attempt) }

    // region internals

    private suspend fun attemptDownload(url: String, attempt: Int): RetryOutcome<ByteArray> = try {
        val response = client.get(url)
        val status = response.status.value
        when {
            status in 200..299 -> {
                val bytes = response.readRawBytes()
                if (bytes.isEmpty()) {
                    RetryOutcome.Fail(ProviderError.Http(status, "Empty image"))
                } else {
                    RetryOutcome.Done(bytes)
                }
            }
            status == 404 -> RetryOutcome.Fail(ProviderError.NotFound)
            status == 429 -> RetryOutcome.Retry(ProviderError.RateLimited("provider busy"), attempt)
            status == SS_DAILY_QUOTA -> RetryOutcome.Fail(ProviderError.QuotaExceeded(QUOTA_DAILY))
            status in 500..599 -> RetryOutcome.Retry(ProviderError.Http(status, "Provider unavailable"), attempt)
            else -> RetryOutcome.Fail(ProviderError.Http(status, "Download failed"))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "Artwork download failed: ${error.javaClass.simpleName}")
        RetryOutcome.Retry(ProviderError.Network(error.javaClass.simpleName), attempt)
    }

    private suspend fun request(
        endpoint: String,
        settings: AppSettings,
        block: HttpRequestBuilder.() -> Unit,
    ): ProviderResult<JsonObject> {
        val result = withRateLimit { attempt -> attemptRequest(endpoint, settings, attempt, block) }
        // Usage counters ride along with every reply, so the app can warn about the
        // daily limit without ever spending a request to ask about it.
        when (result) {
            is ProviderResult.Success -> captureQuota(result.value)
            is ProviderResult.Failure -> if (result.error is ProviderError.QuotaExceeded) markQuotaExhausted()
        }
        return result
    }

    private fun captureQuota(payload: JsonObject) {
        val user = payload["response"]?.jsonObjectOrNull()?.get("ssuser")?.jsonObjectOrNull() ?: return
        val max = user["maxrequestsperday"]?.intOrNull() ?: return
        val used = user["requeststoday"]?.intOrNull() ?: return
        if (max <= 0) return
        _quota.value = QuotaSnapshot(
            used = used,
            max = max,
            notFoundUsed = user["requestskotoday"]?.intOrNull(),
            notFoundMax = user["maxrequestskoperday"]?.intOrNull(),
        )
    }

    /**
     * The service can refuse a call before the counters catch up, so a refusal is
     * treated as the authoritative answer.
     */
    private fun markQuotaExhausted() {
        val current = _quota.value
        _quota.value = current?.copy(used = maxOf(current.used, current.max), isExhausted = true)
            ?: QuotaSnapshot(used = 1, max = 1, isExhausted = true)
    }

    private suspend fun attemptRequest(
        endpoint: String,
        settings: AppSettings,
        attempt: Int,
        block: HttpRequestBuilder.() -> Unit,
    ): RetryOutcome<JsonObject> = try {
        val response = client.get(BASE_URL + endpoint) {
            parameter("devid", settings.devId)
            parameter("devpassword", settings.devPassword)
            parameter("softname", SOFT_NAME)
            parameter("output", "json")
            if (settings.userId.isNotBlank()) parameter("ssid", settings.userId)
            if (settings.userPassword.isNotBlank()) parameter("sspassword", settings.userPassword)
            parameter("maxwidth", "512")
            block()
        }
        // ScreenScraper explains a refusal in the body and is inconsistent about the
        // status that carries it: jeuInfos.php sends a rejected login as 403 while
        // jeuRecherche.php sends the identical sentence as a plain 200. The body is
        // therefore always read and inspected before the status is considered.
        val status = response.status.value
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        val outcome = classify(status, body, attempt)
        recordDiagnostic(endpoint, status, body, settings, outcome)
        outcome
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "Provider request failed: ${error.javaClass.simpleName}")
        val failure = RetryOutcome.Retry(ProviderError.Network(error.javaClass.simpleName), attempt)
        recordDiagnostic(endpoint, status = 0, body = error.javaClass.simpleName, settings, failure)
        failure
    }

    /** Maps a ScreenScraper reply onto the app's error vocabulary. */
    private fun classify(status: Int, body: String, attempt: Int): RetryOutcome<JsonObject> {
        bodyRefusal(body)?.let { return RetryOutcome.Fail(it) }

        if (status in 200..299) {
            val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            return when {
                parsed != null -> RetryOutcome.Done(parsed)
                body.isBlank() -> RetryOutcome.Retry(ProviderError.Network("empty response"), attempt)
                normalize(body).contains("not found") -> RetryOutcome.Fail(ProviderError.NotFound)
                else -> RetryOutcome.Fail(ProviderError.Http(status, "Unreadable response"))
            }
        }

        return when (status) {
            400 -> RetryOutcome.Fail(ProviderError.BadRequest(MALFORMED_REQUEST))
            SS_API_CLOSED_GUEST -> RetryOutcome.Fail(ProviderError.ServiceClosed(API_CLOSED_GUEST))
            SS_LOGIN_ERROR -> RetryOutcome.Fail(
                ProviderError.InvalidCredentials(DEV_OR_MEMBER_REJECTED, CredentialScope.Unknown),
            )
            404 -> RetryOutcome.Fail(ProviderError.NotFound)
            SS_API_CLOSED -> RetryOutcome.Fail(ProviderError.ServiceClosed(API_CLOSED))
            SS_BLACKLISTED -> RetryOutcome.Fail(ProviderError.ServiceClosed(BLACKLISTED))
            429 -> RetryOutcome.Retry(ProviderError.RateLimited("too many requests"), attempt)
            SS_DAILY_QUOTA -> RetryOutcome.Fail(ProviderError.QuotaExceeded(QUOTA_DAILY))
            SS_NOT_FOUND_QUOTA -> RetryOutcome.Fail(ProviderError.QuotaExceeded(QUOTA_NOT_FOUND))
            in 500..599 -> RetryOutcome.Retry(ProviderError.Http(status, "Provider unavailable"), attempt)
            else -> RetryOutcome.Fail(ProviderError.Http(status, "Request rejected"))
        }
    }

    /**
     * Refusals ScreenScraper writes into the body regardless of the status code.
     *
     * The service replies in French with accented words, and names the credential set
     * it is complaining about: "identifiants développeur" versus "identifiants
     * utilisateurs". Matching happens on accent-stripped text so a charset surprise
     * cannot turn a precise message into a vague one.
     */
    private fun bodyRefusal(body: String): ProviderError? {
        if (body.isBlank()) return null
        val marker = normalize(body.take(MARKER_SCAN_LENGTH))
        return when {
            marker.contains("manque des champs obligatoires") ->
                ProviderError.BadRequest(MALFORMED_REQUEST)
            marker.contains("erreur de login") || marker.contains("login error") -> when {
                marker.contains("veloppeur") || marker.contains("developer") ->
                    ProviderError.InvalidCredentials(DEV_REJECTED, CredentialScope.Developer)
                marker.contains("utilisateur") || marker.contains("membre") ->
                    ProviderError.InvalidCredentials(MEMBER_REJECTED, CredentialScope.Member)
                else ->
                    ProviderError.InvalidCredentials(DEV_OR_MEMBER_REJECTED, CredentialScope.Unknown)
            }
            marker.contains("quota") &&
                (marker.contains("exceeded") || marker.contains("depass") || marker.contains("atteint")) ->
                ProviderError.QuotaExceeded(QUOTA_DAILY)
            marker.contains("api closed") || marker.contains("api ferm") ->
                ProviderError.ServiceClosed(API_CLOSED)
            else -> null
        }
    }

    /** Lower-cased and stripped of accents, so French replies match reliably. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.US)

    /**
     * Keeps the last exchange for the Settings diagnostics panel.
     *
     * Only the shape of the credentials is recorded — field names and lengths — so a
     * shared report can prove a field was populated without ever revealing it.
     */
    private fun recordDiagnostic(
        endpoint: String,
        status: Int,
        body: String,
        settings: AppSettings,
        outcome: RetryOutcome<JsonObject>,
    ) {
        val verdict = when (outcome) {
            is RetryOutcome.Done -> "Accepted"
            is RetryOutcome.Fail -> "Rejected — ${outcome.error.javaClass.simpleName}"
            is RetryOutcome.Retry -> "Retrying — ${outcome.error.javaClass.simpleName}"
        }
        // A normal payload is JSON and megabytes wide; only short plain-text replies
        // are ScreenScraper explaining itself, and only those are worth keeping.
        val serverMessage = body.trim()
            .takeIf { it.isNotEmpty() && !it.startsWith("{") && it.length <= MARKER_SCAN_LENGTH }
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        _lastDiagnostic.value = ApiDiagnostic(
            endpoint = endpoint,
            httpStatus = status,
            serverMessage = serverMessage,
            sentFields = describeFields(settings),
            verdict = verdict,
        )
    }

    private fun describeFields(settings: AppSettings): List<String> = listOf(
        describeField("devid", settings.devId),
        describeField("devpassword", settings.devPassword),
        "softname=$SOFT_NAME",
        "output=json",
        describeField("ssid", settings.userId),
        describeField("sspassword", settings.userPassword),
    )

    /** Field names and sizes only — never the value, which may be a password. */
    private fun describeField(name: String, value: String): String = when {
        value.isEmpty() -> "$name=(not sent)"
        value != value.trim() -> "$name=${value.length} chars (has surrounding space)"
        value.any { it.code > 127 } -> "$name=${value.length} chars (has non-ASCII)"
        else -> "$name=${value.length} chars"
    }

    private sealed interface RetryOutcome<out T> {
        data class Done<T>(val value: T) : RetryOutcome<T>
        data class Retry(val error: ProviderError, val attempt: Int) : RetryOutcome<Nothing>
        data class Fail(val error: ProviderError) : RetryOutcome<Nothing>
    }

    /**
     * A server-side fault, as opposed to a connection that never opened.
     *
     * Both ways a server says "too much" count: an explicit 429, and a 5xx from a
     * server failing under load. Treating only the polite one as push-back is what
     * turns a brief wobble into a whole failed batch.
     */
    private fun ProviderError.isServerFault(): Boolean =
        this is ProviderError.RateLimited || (this is ProviderError.Http && code in 500..599)

    /**
     * How long to wait before trying again.
     *
     * A failing server needs seconds, not milliseconds, and a connection that never
     * opened is given longer still — retrying a dead route immediately just reproduces
     * the same failure. Randomness keeps a retry from landing in lockstep with whatever
     * else the server is dealing with.
     */
    private fun backoffFor(error: ProviderError, attempt: Int): Long {
        val base = when {
            error.isServerFault() -> SERVER_BACKOFF_MILLIS shl attempt
            error is ProviderError.Network -> NETWORK_BACKOFF_MILLIS * (attempt + 1)
            else -> BACKOFF_BASE_MILLIS * (attempt + 1)
        }
        val jitter = if (error.isServerFault()) (0..JITTER_MILLIS).random() else 0
        return (base + jitter).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /** Remembers a push-back so later games wait for it too, and widens the spacing. */
    private fun notePushback(waitMillis: Long, widen: Boolean) {
        cooldownUntilMillis = System.currentTimeMillis() + waitMillis
        consecutiveServerFaults++
        if (!widen) return
        val widened = (pacingMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
        if (widened != pacingMillis) {
            pacingMillis = widened
            Log.i(TAG, "Pushed back; spacing requests ${widened}ms apart")
        }
    }

    /** Eases the pace back toward normal while the service is answering happily. */
    private fun noteAccepted() {
        consecutiveServerFaults = 0
        if (pacingMillis > MIN_INTERVAL_MILLIS) {
            pacingMillis = (pacingMillis - PACING_RECOVERY_MILLIS).coerceAtLeast(MIN_INTERVAL_MILLIS)
        }
    }

    /**
     * Serialises calls, spaces them out, and retries the transient ones.
     *
     * Attempts are never reduced part-way through a batch: a busy service is exactly
     * when a game needs its full allowance of tries.
     */
    private suspend fun <T> withRateLimit(
        block: suspend (attempt: Int) -> RetryOutcome<T>,
    ): ProviderResult<T> = gate.withLock {
        try {
            var lastError: ProviderError = ProviderError.Network("unknown")
            // Spacing is widened at most once per call, so one struggling game cannot
            // drag the whole batch down to its slowest pace and keep it there.
            var widened = false
            for (attempt in 0 until MAX_ATTEMPTS) {
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
                    is RetryOutcome.Done -> {
                        noteAccepted()
                        return@withLock ProviderResult.Success(outcome.value)
                    }
                    // A definite "no such game" proves the service is alive and well,
                    // so it counts as a healthy reply rather than a fault.
                    is RetryOutcome.Fail -> {
                        if (outcome.error is ProviderError.NotFound) noteAccepted()
                        return@withLock ProviderResult.Failure(outcome.error)
                    }
                    is RetryOutcome.Retry -> {
                        lastError = outcome.error
                        val wait = backoffFor(outcome.error, attempt)
                        if (outcome.error.isServerFault()) {
                            notePushback(wait, widen = !widened)
                            widened = true
                        }
                        if (attempt < MAX_ATTEMPTS - 1) delay(wait)
                    }
                }
            }
            Log.w(TAG, "Provider request gave up after $MAX_ATTEMPTS attempts")
            ProviderResult.Failure(lastError)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Nothing this client does may escape as an exception.
            Log.w(TAG, "Provider call failed: ${error.javaClass.simpleName}")
            ProviderResult.Failure(ProviderError.Network(error.javaClass.simpleName))
        }
    }

    private fun toCandidate(game: JsonObject, preferredRegion: String): GameCandidate? {
        val id = game["id"]?.stringOrNull() ?: return null
        val title = pickTitle(game, preferredRegion) ?: return null
        val systemName = game["systemenom"]?.stringOrNull()
            ?: game["systeme"]?.jsonObjectOrNull()?.get("text")?.stringOrNull()
            ?: ""
        val cover = pickCover(game, preferredRegion)
        val region = cover?.second ?: firstNameRegion(game) ?: ""
        return GameCandidate(
            gameId = id,
            title = title,
            systemName = systemName,
            region = prettyRegion(region),
            coverUrl = cover?.first,
            description = localizedText(game["synopsis"]),
            developer = textField(game["developpeur"]),
            publisher = textField(game["editeur"]),
            genre = firstGenre(game),
            players = textField(game["joueurs"]),
            releaseDate = releaseDate(game, preferredRegion),
            rating = rating(game),
        )
    }

    /** ScreenScraper wraps many scalars as `{ "id": .., "text": .. }`. */
    private fun textField(element: JsonElement?): String? =
        element?.jsonObjectOrNull()?.get("text")?.stringOrNull() ?: element?.stringOrNull()

    /** Picks the best language variant from a `[{ langue, text }]` array. */
    private fun localizedText(element: JsonElement?): String? {
        val entries = element?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull() }
            ?: return textField(element)
        LANGUAGE_ORDER.forEach { language ->
            entries.firstOrNull { it["langue"]?.stringOrNull()?.lowercase() == language }
                ?.get("text")?.stringOrNull()
                ?.let { return it }
        }
        return entries.firstNotNullOfOrNull { it["text"]?.stringOrNull() }
    }

    private fun firstGenre(game: JsonObject): String? {
        val genres = game["genres"]?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull() } ?: return null
        return genres.firstNotNullOfOrNull { genre ->
            localizedText(genre["noms"]) ?: genre["nomcourt"]?.stringOrNull()
        }
    }

    /** EmulationStation wants `yyyyMMddT000000`. */
    private fun releaseDate(game: JsonObject, preferredRegion: String): String? {
        val dates = game["dates"]?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull() }
            ?: return formatReleaseDate(textField(game["dates"]))
        regionPriority(preferredRegion).forEach { region ->
            dates.firstOrNull { it["region"]?.stringOrNull() == region }
                ?.get("text")?.stringOrNull()
                ?.let { raw -> formatReleaseDate(raw)?.let { return it } }
        }
        return dates.firstNotNullOfOrNull { formatReleaseDate(it["text"]?.stringOrNull()) }
    }

    private fun formatReleaseDate(raw: String?): String? {
        val parts = raw?.trim()?.split('-', '/', '.')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        val year = parts.getOrNull(0)?.takeIf { it in 1950..2100 } ?: return null
        val month = parts.getOrNull(1)?.coerceIn(1, 12) ?: 1
        val day = parts.getOrNull(2)?.coerceIn(1, 31) ?: 1
        return String.format(Locale.US, "%04d%02d%02dT000000", year, month, day)
    }

    /** ScreenScraper rates out of 20; EmulationStation expects 0..1. */
    private fun rating(game: JsonObject): String? {
        val raw = textField(game["note"])?.toFloatOrNull() ?: return null
        if (raw <= 0f) return null
        return String.format(Locale.US, "%.2f", (raw / 20f).coerceIn(0f, 1f))
    }

    private fun firstNameRegion(game: JsonObject): String? =
        game["noms"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()?.get("region")?.stringOrNull()

    private fun pickTitle(game: JsonObject, preferredRegion: String): String? {
        val names = game["noms"]?.jsonArrayOrNull() ?: return game["nom"]?.stringOrNull()
        val entries = names.mapNotNull { it.jsonObjectOrNull() }
        val order = regionPriority(preferredRegion)
        order.forEach { region ->
            entries.firstOrNull { it["region"]?.stringOrNull() == region }
                ?.get("text")?.stringOrNull()
                ?.let { return it }
        }
        return entries.firstNotNullOfOrNull { it["text"]?.stringOrNull() }
    }

    /** @return cover url paired with its region. */
    private fun pickCover(game: JsonObject, preferredRegion: String): Pair<String, String>? {
        val medias = game["medias"]?.jsonArrayOrNull() ?: return null
        val entries = medias.mapNotNull { it.jsonObjectOrNull() }
            .filter { it["parent"]?.stringOrNull() != "bezel" }

        COVER_TYPES.forEach { type ->
            val ofType = entries.filter { it["type"]?.stringOrNull() == type }
            if (ofType.isEmpty()) return@forEach
            regionPriority(preferredRegion).forEach { region ->
                ofType.firstOrNull { it["region"]?.stringOrNull() == region }
                    ?.let { media ->
                        val url = media["url"]?.stringOrNull()
                        if (!url.isNullOrBlank()) return url to region
                    }
            }
            ofType.firstOrNull()?.let { media ->
                val url = media["url"]?.stringOrNull()
                if (!url.isNullOrBlank()) return url to (media["region"]?.stringOrNull() ?: "")
            }
        }
        return null
    }

    private fun regionPriority(preferred: String): List<String> =
        (listOf(preferred) + DEFAULT_REGION_ORDER).distinct()

    private fun prettyRegion(code: String): String = when (code.lowercase()) {
        "wor" -> "World"
        "us", "usa" -> "USA"
        "eu", "eur" -> "Europe"
        "jp", "jpn" -> "Japan"
        "fr" -> "France"
        "de" -> "Germany"
        "sp", "es" -> "Spain"
        "it" -> "Italy"
        "" -> "Unknown region"
        else -> code.uppercase()
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    /** ScreenScraper sends its counters as strings. */
    private fun JsonElement.intOrNull(): Int? = stringOrNull()?.trim()?.toIntOrNull()

    // endregion

    companion object {
        private const val TAG = "ScreenScraper"
        private const val BASE_URL = "https://api.screenscraper.fr/api2/"
        private const val ENDPOINT_GAME_INFO = "jeuInfos.php"
        private const val ENDPOINT_SEARCH = "jeuRecherche.php"
        private const val ENDPOINT_USER_INFO = "ssuserInfos.php"
        private const val SOFT_NAME = "RGDSArtworkPrep"
        /**
         * Long enough for a slow handheld on mobile WiFi, short enough that a route
         * which is never going to answer is abandoned promptly. A stall used to cost
         * 20 seconds per attempt on the fallback path, which made a wobble on the
         * second source feel like the app had frozen.
         */
        private const val CONNECT_TIMEOUT_MILLIS = 8_000

        /** Generous: a cover may be large. */
        private const val SOCKET_TIMEOUT_MILLIS = 40_000

        /** Floor for request spacing. ScreenScraper counts every call against a quota. */
        private const val MIN_INTERVAL_MILLIS = 1_100L

        /** Ceiling for spacing once the service has pushed back repeatedly. */
        private const val MAX_INTERVAL_MILLIS = 5_000L

        /** How much of the widened spacing is given back per accepted request. */
        private const val PACING_RECOVERY_MILLIS = 150L

        private const val BACKOFF_BASE_MILLIS = 900L

        /** Longer pause after a connection that never opened. */
        private const val NETWORK_BACKOFF_MILLIS = 1_500L

        /** First pause after a server-side failure; doubles per attempt. */
        private const val SERVER_BACKOFF_MILLIS = 2_000L

        /** Keeps retries from landing in lockstep with other traffic. */
        private const val JITTER_MILLIS = 600

        private const val MAX_BACKOFF_MILLIS = 15_000L

        /**
         * Attempts per call, always. A busy service is exactly when a game needs its
         * full allowance of tries, so this is never reduced part-way through a batch.
         */
        private const val MAX_ATTEMPTS = 3
        private const val MARKER_SCAN_LENGTH = 400

        /** Harmless lookup used to prove developer-only credentials work. */
        private const val PROBE_SYSTEM_ID = 12
        private const val PROBE_QUERY = "mario"

        // ScreenScraper's own status codes, which do not follow HTTP conventions.
        private const val SS_API_CLOSED_GUEST = 401
        private const val SS_LOGIN_ERROR = 403
        private const val SS_API_CLOSED = 423
        private const val SS_BLACKLISTED = 426
        private const val SS_DAILY_QUOTA = 430
        private const val SS_NOT_FOUND_QUOTA = 431

        private val DIACRITICS = Regex("\\p{Mn}+")

        private const val DEV_REJECTED =
            "ScreenScraper refused the developer ID and password."
        private const val MEMBER_REJECTED =
            "Your developer credentials work, but ScreenScraper refused the member account."
        private const val DEV_OR_MEMBER_REJECTED =
            "ScreenScraper rejected your credentials. Check the developer ID and password in " +
                "Settings, and the member account if you entered one."
        private const val MALFORMED_REQUEST =
            "ScreenScraper says the request was missing required information. This is a fault " +
                "in the app, not your credentials."

        // Developer credentials are issued separately from a screenscraper.fr account,
        // and the service reports both failures with the same sentence, so the hint has
        // to cover the mix-up the message cannot.
        private const val HINT_DEVELOPER =
            "Developer credentials are not your screenscraper.fr website login — they are " +
                "issued separately after requesting API access on the ScreenScraper forum. If " +
                "you entered your website username here, put it in the member fields instead. " +
                "Both fields are case-sensitive."
        private const val HINT_MEMBER =
            "Retype the member username and password, or clear both fields to scrape with " +
                "developer credentials only at a lower daily quota."
        private const val HINT_MALFORMED =
            "Please report this with the diagnostic details below."
        private const val HINT_NETWORK =
            "Check the handheld's Wi-Fi connection and try again."
        private const val HINT_FIELD_CONTENT =
            "Nothing was sent to ScreenScraper — the fields were corrected first."
        private const val QUOTA_DAILY =
            "Your ScreenScraper daily quota is used up. Artwork can be prepared again tomorrow."
        private const val QUOTA_NOT_FOUND =
            "ScreenScraper's daily limit of unrecognised ROMs was reached. Try again tomorrow."
        private const val API_CLOSED_GUEST =
            "ScreenScraper is busy and currently closed to guests. Add your member account in " +
                "Settings, or try again later."
        private const val API_CLOSED =
            "ScreenScraper is temporarily closed. Please try again later."
        private const val BLACKLISTED =
            "ScreenScraper refused this app version. An update is needed before scraping again."

        private val COVER_TYPES = listOf("box-2D", "box-texture", "box-3D", "support-2D")
        private val DEFAULT_REGION_ORDER = listOf("wor", "us", "eu", "jp", "ss")
        private val LANGUAGE_ORDER = listOf("en", "us", "wor")

        val regionOptions: List<Pair<String, String>> = listOf(
            "wor" to "World",
            "us" to "USA",
            "eu" to "Europe",
            "jp" to "Japan",
        )
    }
}
