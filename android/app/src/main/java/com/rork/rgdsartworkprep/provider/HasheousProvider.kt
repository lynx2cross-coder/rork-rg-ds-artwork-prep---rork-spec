package com.rork.rgdsartworkprep.provider

import android.util.Log
import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.ProviderId
import com.rork.rgdsartworkprep.network.ApiDiagnostic
import com.rork.rgdsartworkprep.network.ProviderError
import com.rork.rgdsartworkprep.network.ProviderResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Hasheous — a free, community-run hash-matching service that needs no account,
 * no API key and no credentials of any kind.
 *
 * How it is used here, and why:
 * - `Lookup/ByHash/crc/{crc}` turns the CRC32 the app already computes into a game.
 *   The reply carries the title, platform, publisher, release year and an image id.
 * - `Images/{id}` serves that cover directly. Both endpoints are deliberately open:
 *   the service marks its image route anonymous, and its own client library documents
 *   that only the metadata proxy and fix-match routes need a key.
 * - The authenticated metadata proxy (`MetadataProxy/...`) is **never** called. It
 *   answers 401 without a key, and the key belongs to a registered application rather
 *   than to the user, so it has no place in this app.
 *
 * Identification is by checksum only. A ROM with no usable checksum — a disc image, or
 * a file too large to hash — is reported as [ProviderError.NotFound] so the chain can
 * fall through to a provider that searches by name instead of guessing here.
 */
class HasheousProvider : ArtworkProvider {

    override val id: ProviderId = ProviderId.Hasheous

    /**
     * Resolving a search result's cover costs one extra request each, so searching is
     * offered to the user on demand rather than fired automatically at every ROM a
     * checksum could not identify.
     */
    override val supportsBatchSearch: Boolean = false

    /**
     * One client for the whole process, so TCP and TLS are established once and then
     * kept alive across ROMs instead of being rebuilt for each one.
     *
     * The connect timeout is deliberately much shorter than the socket timeout. The
     * service is behind a CDN that publishes both IPv4 and IPv6 addresses, and on a
     * network where IPv6 is advertised but does not actually carry traffic the first
     * connect attempt goes nowhere. Waiting a long time changes nothing in that case —
     * it only means every ROM pays for the same dead route before anything else is
     * tried. A short connect timeout gives up on a route quickly; the generous socket
     * timeout still allows a slow cover to finish downloading once a route works.
     */
    private val client = HttpClient(Android) {
        expectSuccess = false
        engine {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            socketTimeout = SOCKET_TIMEOUT_MILLIS
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Mutex()

    private val _lastDiagnostic = MutableStateFlow<ApiDiagnostic?>(null)

    /**
     * The last exchange that did not go to plan, kept so Settings can show the real
     * status and the service's own words instead of the app's summary of them.
     */
    val lastDiagnostic: StateFlow<ApiDiagnostic?> = _lastDiagnostic.asStateFlow()

    @Volatile
    private var lastRequestAtMillis: Long = 0L

    /**
     * When the service last asked to be left alone until.
     *
     * This outlives a single ROM on purpose. The limiter is counting requests from
     * this app, not from this lookup, so a wait it asked for during one ROM has to be
     * respected by the next one — otherwise the batch answers a "slow down" by
     * immediately asking again, and every remaining ROM burns its attempts on refusals.
     */
    @Volatile
    private var cooldownUntilMillis: Long = 0L

    /**
     * Current spacing between requests, widened whenever the service pushes back and
     * eased back down while it is happy, so a long scan settles at a pace the server
     * actually accepts instead of one guessed here.
     */
    @Volatile
    private var pacingMillis: Long = MIN_INTERVAL_MILLIS

    /** Server failures in a row, reset by the first reply that works. */
    @Volatile
    private var consecutiveServerFaults: Int = 0

    /**
     * Forgets how badly the last batch went.
     *
     * Pacing and fault counts describe a moment, not the service. Carrying them into
     * the next run — including the automatic retry pass — meant a scan that hit a rough
     * patch handicapped every attempt that came after it, which is the opposite of what
     * a retry is for. An outstanding wait the server explicitly asked for is kept,
     * because that one was a real instruction rather than a guess made here.
     */
    override fun onRunStarted() {
        consecutiveServerFaults = 0
        pacingMillis = MIN_INTERVAL_MILLIS
    }

    /**
     * True while the service is failing or asking to be left alone.
     *
     * Used by the pipeline to tell "this game has no artwork" apart from "the source
     * was in no state to tell us".
     */
    override val isDegraded: Boolean
        get() = consecutiveServerFaults > 0 || cooldownRemainingMillis > 0

    override fun isConfigured(settings: AppSettings): Boolean = settings.useHasheous

    override fun unavailableReason(settings: AppSettings): String? =
        if (settings.useHasheous) null else "Turned off in Settings"

    override suspend fun identify(
        settings: AppSettings,
        rom: RomIdentity,
    ): ProviderResult<GameCandidate> {
        val crc = rom.crc32?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ProviderResult.Failure(ProviderError.NotFound)
        return lookupByCrc(crc)
    }

    /**
     * A Hasheous match is addressed by the checksum that found it, so a remembered
     * match costs exactly one lookup and can never drift onto a different game.
     */
    override suspend fun gameById(
        settings: AppSettings,
        system: GameSystem,
        gameId: String,
    ): ProviderResult<GameCandidate> {
        val crc = gameId.removePrefix(CRC_ID_PREFIX).takeIf { gameId.startsWith(CRC_ID_PREFIX) }
        // Ids saved by another provider mean nothing here; report a miss so the
        // pipeline simply identifies the ROM again instead of failing it.
            ?: return ProviderResult.Failure(ProviderError.NotFound)
        return lookupByCrc(crc)
    }

    /**
     * Name search, used by the manual match picker.
     *
     * The public search returns catalogue entries with their checksums but no
     * artwork, so the covers are resolved with one follow-up lookup per candidate.
     * That is capped, because each one is a request against a donated server.
     */
    override suspend fun search(
        settings: AppSettings,
        system: GameSystem?,
        query: String,
    ): ProviderResult<List<GameCandidate>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProviderResult.Success(emptyList())

        return when (val response = searchCatalogue(trimmed)) {
            is ProviderResult.Failure -> response
            is ProviderResult.Success -> {
                // Games on the ROM's own platform first, so the right one is not
                // buried under ports and remakes.
                val ordered = response.value.sortedByDescending { matchesSystem(system, it.platformName) }
                val resolved = ordered.take(MAX_SEARCH_RESOLVE).mapNotNull { hit ->
                    when (val detail = lookupByCrc(hit.crc)) {
                        is ProviderResult.Success -> detail.value
                        // One unresolvable entry must not empty the whole picker.
                        is ProviderResult.Failure -> hit.asBareCandidate()
                    }
                }
                ProviderResult.Success(resolved.sortedByDescending { it.coverUrl != null })
            }
        }
    }

    override suspend fun downloadArtwork(url: String): ProviderResult<ByteArray> =
        withRateLimit { attempt -> attemptDownload(url, attempt) }

    // region requests

    private suspend fun lookupByCrc(crc: String): ProviderResult<GameCandidate> {
        // The service accepts either case; lower-casing keeps requests identical to
        // the ones its cache already holds.
        val normalized = crc.lowercase(Locale.US)
        val response = withRateLimit { attempt ->
            attemptJson("$BASE_URL/Lookup/ByHash/crc/$normalized", attempt)
        }
        return when (response) {
            is ProviderResult.Failure -> {
                // Says plainly whether the service answered "unknown checksum" or never
                // gave a usable answer at all — the two must never look alike.
                val genuine = response.error is ProviderError.NotFound
                Log.i(
                    TAG,
                    "crc=$normalized $LOOKUP_ENDPOINT -> " +
                        if (genuine) "genuine no-match" else "could not ask: ${response.error.userMessage}",
                )
                response
            }
            is ProviderResult.Success -> {
                // Reading the reply is guarded just like sending it. A field that one
                // day arrives in an unexpected shape must surface as a failed ROM with
                // a reason, never as an exception thrown into the scan.
                val candidate = try {
                    toCandidate(response.value, normalized)
                } catch (error: Throwable) {
                    return ProviderResult.Failure(mappingError("lookup", LOOKUP_ENDPOINT, error))
                }
                if (candidate == null) {
                    ProviderResult.Failure(ProviderError.NotFound)
                } else {
                    ProviderResult.Success(candidate)
                }
            }
        }
    }

    /** Records an app-side failure to read a reply, and phrases it for the user. */
    private fun mappingError(stage: String, endpoint: String, error: Throwable): ProviderError {
        Log.w(TAG, "Could not read the Hasheous $stage reply: ${error.javaClass.simpleName}")
        val detail = "${error.javaClass.simpleName}${error.message?.take(MAX_DETAIL_LENGTH)?.let { ": $it" }.orEmpty()}"
        record(endpoint, status = 200, serverMessage = detail, verdict = "Reply could not be read")
        return ProviderError.Unexpected("Hasheous $stage", detail)
    }

    /** One entry from the public catalogue search, before its cover is resolved. */
    private data class SearchHit(
        val name: String,
        val platformName: String?,
        val publisher: String?,
        val year: String?,
        val crc: String,
    )

    /** A search result whose cover could not be resolved, kept for its title alone. */
    private fun SearchHit.asBareCandidate(): GameCandidate = GameCandidate(
        gameId = CRC_ID_PREFIX + crc,
        title = name,
        systemName = platformName.orEmpty(),
        region = "",
        coverUrl = null,
        provider = ProviderId.Hasheous,
        publisher = publisher,
        releaseDate = formatReleaseDate(year),
    )

    /**
     * Searches the catalogue through the service's public tool endpoint, which needs
     * no key. Entries without a checksum are dropped: without one there is no way to
     * reach their artwork.
     */
    private suspend fun searchCatalogue(query: String): ProviderResult<List<SearchHit>> {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", "hasheous_search_games")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("name", query)
                            put("limit", SEARCH_LIMIT)
                            put("includeRoms", true)
                        },
                    )
                },
            )
        }

        val response = withRateLimit { attempt ->
            attemptJson("$BASE_URL/Mcp", attempt, body = payload.toString())
        }
        return when (response) {
            is ProviderResult.Failure -> response
            is ProviderResult.Success -> try {
                ProviderResult.Success(parseSearchHits(response.value))
            } catch (error: Throwable) {
                ProviderResult.Failure(mappingError("search", SEARCH_ENDPOINT, error))
            }
        }
    }

    /**
     * The tool endpoint wraps its answer as JSON text inside the envelope, so the
     * payload is parsed a second time. Anything unreadable yields no results rather
     * than an error — a manual search must always be usable.
     */
    private fun parseSearchHits(envelope: JsonObject): List<SearchHit> {
        val text = envelope["result"]?.objectOrNull()
            ?.get("content")?.arrayOrNull()
            ?.firstNotNullOfOrNull { it.objectOrNull()?.get("text")?.stringOrNull() }
            ?: return emptyList()
        val parsed = runCatching { json.parseToJsonElement(text).objectOrNull() }.getOrNull()
            ?: return emptyList()

        return parsed["games"]?.arrayOrNull().orEmpty().mapNotNull { element ->
            val game = element.objectOrNull() ?: return@mapNotNull null
            val name = game["name"].stringOrNull() ?: return@mapNotNull null
            val crc = game["roms"]?.arrayOrNull()
                ?.firstNotNullOfOrNull { it.objectOrNull()?.get("crc").stringOrNull() }
                ?: return@mapNotNull null
            SearchHit(
                name = name,
                platformName = game["platform"]?.objectOrNull()?.get("name").stringOrNull(),
                publisher = game["publisher"]?.objectOrNull()?.get("name").stringOrNull(),
                year = game["year"].stringOrNull(),
                crc = crc,
            )
        }
    }

    private suspend fun attemptJson(
        url: String,
        attempt: Int,
        body: String? = null,
    ): Outcome<JsonObject> = @Suppress("TooGenericExceptionCaught") try {
        val response = if (body == null) {
            client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        } else {
            client.post(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val status = response.status.value
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val endpoint = if (body == null) LOOKUP_ENDPOINT else SEARCH_ENDPOINT
        val retryAfter = response.headers[HttpHeaders.RetryAfter]
        when {
            status in 200..299 -> {
                val parsed = runCatching { json.parseToJsonElement(text).objectOrNull() }.getOrNull()
                if (parsed == null) {
                    record(endpoint, status, text, "Reply was not readable JSON")
                    Outcome.Retry(ProviderError.Network("unreadable reply"), attempt)
                } else {
                    Outcome.Done(parsed)
                }
            }
            else -> {
                val outcome = classify<JsonObject>(status, text, attempt, retryAfter)
                Log.i(TAG, "$endpoint -> HTTP $status${retryAfter?.let { ", Retry-After: $it" }.orEmpty()}")
                record(endpoint, status, text, outcome.describe())
                outcome
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "Hasheous request failed: ${error.javaClass.simpleName}")
        record(
            endpoint = if (body == null) LOOKUP_ENDPOINT else SEARCH_ENDPOINT,
            status = 0,
            serverMessage = error.javaClass.simpleName,
            verdict = "Request never completed",
        )
        Outcome.Retry(ProviderError.Network(error.javaClass.simpleName), attempt)
    }

    /**
     * Turns a status the service returned into something the rest of the app can act
     * on. Every code is accounted for, so no reply can arrive without a meaning.
     *
     * Nothing here retires the source permanently for an ordinary miss: only a refusal
     * that would repeat for every other ROM does that.
     */
    private fun <T> classify(
        status: Int,
        body: String,
        attempt: Int,
        retryAfterHeader: String? = null,
    ): Outcome<T> = when (status) {
        // An unknown checksum, and a checksum the service considers unusable, are both
        // simply "not in the database" — the next source should get its turn.
        404, 400 -> Outcome.Fail(ProviderError.NotFound)
        408, 425 -> Outcome.Retry(ProviderError.Network("timed out"), attempt)
        429 -> Outcome.Retry(
            ProviderError.RateLimited(serverText(body) ?: "Hasheous is busy"),
            attempt,
            delayMillis = retryAfterMillis(body, retryAfterHeader),
        )
        // The open endpoints need no account, so a refusal means this app is being
        // turned away rather than any user credential being wrong.
        401, 403 -> Outcome.Fail(
            ProviderError.ServiceClosed(
                "Hasheous refused this app's requests ($status). Artwork from Hasheous is " +
                    "paused for this run \u2014 try again later.",
            ),
        )
        451 -> Outcome.Fail(
            ProviderError.ServiceClosed("Hasheous cannot serve this content ($status)."),
        )
        // The service is a small donated server behind a CDN, and 502/503/504 mean it
        // is struggling right now — nothing to do with this ROM. Retrying quickly is
        // the worst possible response: it adds load to a server that is already
        // failing. Handled as push-back, exactly like an explicit "slow down".
        in 500..599 -> Outcome.Retry(
            ProviderError.Http(status, "Hasheous is having trouble right now"),
            attempt,
        )
        else -> Outcome.Fail(ProviderError.Http(status, serverText(body) ?: "Lookup failed"))
    }

    /** The service's own words, when it sent a short readable explanation. */
    private fun serverText(body: String): String? {
        val fromJson = runCatching {
            json.parseToJsonElement(body).objectOrNull()
                ?.let { it["error"] ?: it["message"] ?: it["title"] }
                .stringOrNull()
        }.getOrNull()
        val text = fromJson ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
        return text?.replace(Regex("\\s+"), " ")?.take(MAX_DETAIL_LENGTH)
    }

    private fun <T> Outcome<T>.describe(): String = when (this) {
        is Outcome.Done -> "Reply accepted"
        is Outcome.Fail -> "${error.javaClass.simpleName}: ${error.userMessage}"
        is Outcome.Retry -> "Retrying after ${error.javaClass.simpleName}"
    }

    /**
     * Keeps the last unhappy exchange for the diagnostics panel.
     *
     * Hasheous needs no credentials, so there is nothing secret to withhold here — the
     * endpoint, the status and the service's own sentence are recorded as they were.
     */
    private fun record(endpoint: String, status: Int, serverMessage: String, verdict: String) {
        _lastDiagnostic.value = ApiDiagnostic(
            provider = "Hasheous",
            endpoint = endpoint,
            httpStatus = status,
            serverMessage = serverText(serverMessage).orEmpty(),
            sentFields = listOf("no credentials sent"),
            verdict = verdict,
        )
    }

    private suspend fun attemptDownload(url: String, attempt: Int): Outcome<ByteArray> = try {
        val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        val status = response.status.value
        when {
            status in 200..299 -> {
                val bytes = response.readRawBytes()
                if (bytes.isEmpty()) {
                    record(IMAGE_ENDPOINT, status, "", "Cover was empty")
                    Outcome.Fail(ProviderError.Http(status, "Cover came back empty"))
                } else {
                    Outcome.Done(bytes)
                }
            }
            else -> {
                val outcome =
                    classify<ByteArray>(status, "", attempt, response.headers[HttpHeaders.RetryAfter])
                Log.i(TAG, "$IMAGE_ENDPOINT -> HTTP $status")
                record(IMAGE_ENDPOINT, status, "", outcome.describe())
                outcome
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "Hasheous artwork download failed: ${error.javaClass.simpleName}")
        record(IMAGE_ENDPOINT, 0, error.javaClass.simpleName, "Download never completed")
        Outcome.Retry(ProviderError.Network(error.javaClass.simpleName), attempt)
    }

    /**
     * Honours the wait the service asks for, instead of guessing one.
     *
     * The standard `Retry-After` header wins when present, because that is where a
     * limiter in front of the service speaks; the body field is the service's own way
     * of saying the same thing.
     */
    private fun retryAfterMillis(body: String, header: String?): Long? {
        val fromHeader = header?.trim()?.toDoubleOrNull()
        val fromBody = runCatching {
            json.parseToJsonElement(body).objectOrNull()?.get("retryAfterSeconds")?.stringOrNull()
        }.getOrNull()?.toDoubleOrNull()
        val seconds = fromHeader ?: fromBody ?: return null
        return (seconds * 1000).toLong().coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
    }

    /**
     * Remembers a push-back so every later request — including the next ROM's — waits
     * for it, and widens the spacing so the same wall is not walked into again.
     *
     * Both ways a server says "too much" arrive here: an explicit 429, and a 5xx from a
     * server that is failing under load. Treating only the polite one as push-back is
     * what turns a brief wobble into a whole failed scan.
     */
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

    /** A server-side fault, as opposed to a connection that never opened. */
    private fun ProviderError.isServerFault(): Boolean =
        this is ProviderError.RateLimited || (this is ProviderError.Http && code in 500..599)

    /** How long callers should wait before expecting this provider to work again. */
    val cooldownRemainingMillis: Long
        get() = (cooldownUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * How long to wait before trying again.
     *
     * A connection that never opened is given noticeably longer than a server that
     * answered something unhelpful. Retrying a dead route after a few hundred
     * milliseconds just reproduces the same failure — the useful retry is the one that
     * happens after the network has had a moment to change its mind.
     */
    private fun backoffFor(error: ProviderError, attempt: Int): Long {
        val base = when {
            // A failing server needs seconds, not milliseconds. Doubling each time,
            // with a little randomness so a retry never lands in lockstep with
            // whatever else the server is dealing with.
            error.isServerFault() -> SERVER_BACKOFF_MILLIS shl attempt
            error is ProviderError.Network -> NETWORK_BACKOFF_MILLIS * (attempt + 1)
            else -> BACKOFF_BASE_MILLIS * (attempt + 1)
        }
        val jitter = if (error.isServerFault()) (0..JITTER_MILLIS).random() else 0
        return (base + jitter).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /** Eases the pace back toward normal while the service is answering happily. */
    private fun noteAccepted() {
        consecutiveServerFaults = 0
        if (pacingMillis > MIN_INTERVAL_MILLIS) {
            pacingMillis = (pacingMillis - PACING_RECOVERY_MILLIS).coerceAtLeast(MIN_INTERVAL_MILLIS)
        }
    }

    // endregion

    // region mapping

    /**
     * Turns a lookup reply into a candidate.
     *
     * The cover comes from the record's own image attribute, which the service hosts
     * and serves without authentication.
     */
    private fun toCandidate(payload: JsonObject, crc: String): GameCandidate? {
        val title = payload["name"].stringOrNull() ?: return null
        val attributes = payload["attributes"]?.arrayOrNull().orEmpty().mapNotNull { it.objectOrNull() }
        val signatureGame = payload["signature"]?.objectOrNull()?.get("game")?.objectOrNull()
        val signatureRom = payload["signature"]?.objectOrNull()?.get("rom")?.objectOrNull()

        val imageId = attributes
            .filter { it["attributeType"].stringOrNull() == ATTRIBUTE_IMAGE_ID }
            .sortedByDescending { it["attributeName"].stringOrNull() == ATTRIBUTE_LOGO }
            .firstNotNullOfOrNull { it["value"].stringOrNull() }

        val description = signatureGame?.get("description").stringOrNull()
            ?: attributes.firstOrNull { it["attributeName"].stringOrNull() == ATTRIBUTE_DESCRIPTION }
                ?.get("value").stringOrNull()?.let(::plainText)

        return GameCandidate(
            gameId = CRC_ID_PREFIX + crc,
            title = title,
            systemName = payload["platform"]?.objectOrNull()?.get("name").stringOrNull().orEmpty(),
            region = regionLabel(signatureRom ?: signatureGame),
            coverUrl = imageId?.let { "$BASE_URL/Images/$it" },
            provider = ProviderId.Hasheous,
            description = description,
            publisher = payload["publisher"]?.objectOrNull()?.get("name").stringOrNull()
                ?: signatureGame?.get("publisher").stringOrNull(),
            releaseDate = formatReleaseDate(signatureGame?.get("year").stringOrNull()),
        )
    }

    /** Country codes the entry was released under, e.g. `JP, US`. */
    private fun regionLabel(source: JsonObject?): String {
        val countries = source?.get("country")?.objectOrNull() ?: return ""
        return countries.keys.joinToString(", ").take(MAX_REGION_LENGTH)
    }

    /** Strips light markdown so a description reads cleanly inside gamelist.xml. */
    private fun plainText(raw: String): String = raw
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("[*_`]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_DESCRIPTION_LENGTH)

    /**
     * Converts `1985-09-13`, `1991-04` or `1985` into the EmulationStation stamp.
     * A missing month or day becomes the first, which is what gamelist readers expect.
     */
    private fun formatReleaseDate(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = Regex("(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?").find(text) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        if (year !in MIN_YEAR..MAX_YEAR) return null
        val month = match.groupValues[2].toIntOrNull()?.coerceIn(1, 12) ?: 1
        val day = match.groupValues[3].toIntOrNull()?.coerceIn(1, 31) ?: 1
        return String.format(Locale.US, "%04d%02d%02dT000000", year, month, day)
    }

    /** Loose platform comparison, used to rank search results rather than filter them. */
    private fun matchesSystem(system: GameSystem?, platformName: String?): Boolean {
        if (system == null || platformName.isNullOrBlank()) return false
        val haystack = platformName.simplify()
        if (haystack.isEmpty()) return false
        val needles = (listOf(system.displayName, system.shortName) + system.folderAliases)
            .map { it.simplify() }
            .filter { it.length >= MIN_ALIAS_LENGTH }
        return needles.any { haystack.contains(it) }
    }

    private fun String.simplify(): String = lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    // endregion

    // region plumbing

    private sealed interface Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>
        data class Retry(
            val error: ProviderError,
            val attempt: Int,
            val delayMillis: Long? = null,
        ) : Outcome<Nothing>
        data class Fail(val error: ProviderError) : Outcome<Nothing>
    }

    /**
     * Serialises calls, spaces them out, and retries the transient ones.
     *
     * Hasheous is donated infrastructure with its own rate limiter, so requests are
     * never issued in parallel and a refusal is answered with the wait it asks for.
     */
    private suspend fun <T> withRateLimit(block: suspend (attempt: Int) -> Outcome<T>): ProviderResult<T> =
        gate.withLock {
            try {
                var lastError: ProviderError = ProviderError.Network("unknown")
                // Spacing is widened at most once per call. Widening on every attempt
                // let one struggling ROM drag the whole scan down to its slowest pace
                // and keep it there long after the service had recovered.
                var widened = false
                for (attempt in 0 until MAX_ATTEMPTS) {
                    // A wait the service asked for earlier comes first, then ordinary
                    // spacing. Both survive across ROMs, which is what keeps a long
                    // batch from turning into a stream of refusals.
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
                        is Outcome.Done -> {
                            noteAccepted()
                            return@withLock ProviderResult.Success(outcome.value)
                        }
                        is Outcome.Fail -> return@withLock ProviderResult.Failure(outcome.error)
                        is Outcome.Retry -> {
                            lastError = outcome.error
                            val wait = outcome.delayMillis ?: backoffFor(outcome.error, attempt)
                            if (outcome.error.isServerFault()) {
                                notePushback(wait, widen = !widened)
                                widened = true
                            }
                            if (attempt < MAX_ATTEMPTS - 1) delay(wait)
                        }
                    }
                }
                ProviderResult.Failure(lastError)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Nothing this provider does may escape as an exception.
                Log.w(TAG, "Hasheous call failed: ${error.javaClass.simpleName}")
                ProviderResult.Failure(ProviderError.Network(error.javaClass.simpleName))
            }
        }

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement?.stringOrNull(): String? {
        if (this == null || this is JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content.takeIf { it.isNotBlank() && it != "null" }
    }

    // endregion

    private companion object {
        const val TAG = "HasheousProvider"
        const val BASE_URL = "https://hasheous.org/api/v1"
        const val USER_AGENT = "RGDSArtworkPrep/1.0 (+https://hasheous.org)"

        /** Marks an id as "the game behind this checksum". */
        const val CRC_ID_PREFIX = "crc:"

        const val LOOKUP_ENDPOINT = "/Lookup/ByHash/crc/{crc}"
        const val SEARCH_ENDPOINT = "/Mcp (hasheous_search_games)"
        const val IMAGE_ENDPOINT = "/Images/{id}"
        const val MAX_DETAIL_LENGTH = 200

        const val ATTRIBUTE_IMAGE_ID = "ImageId"
        const val ATTRIBUTE_LOGO = "Logo"
        const val ATTRIBUTE_DESCRIPTION = "AIDescription"

        const val SEARCH_LIMIT = 12
        /** Each resolved result costs one more request against a donated server. */
        const val MAX_SEARCH_RESOLVE = 6

        /**
         * Attempts per call, always. A busy service is exactly when a ROM needs its
         * full allowance of tries, so this is never reduced part-way through a scan.
         */
        const val MAX_ATTEMPTS = 3

        const val BACKOFF_BASE_MILLIS = 700L

        /** Longer pause after a connection that never opened. */
        const val NETWORK_BACKOFF_MILLIS = 1_500L

        /** First pause after a server-side failure; doubles per attempt. */
        const val SERVER_BACKOFF_MILLIS = 2_000L

        /** Keeps retries from landing in lockstep with other traffic. */
        const val JITTER_MILLIS = 600

        const val MAX_BACKOFF_MILLIS = 15_000L

        /**
         * Long enough for a slow handheld on mobile WiFi, short enough that a route
         * which is never going to answer is abandoned promptly.
         */
        const val CONNECT_TIMEOUT_MILLIS = 8_000

        /** Generous: a cover may be large and the service is donated. */
        const val SOCKET_TIMEOUT_MILLIS = 40_000

        /**
         * Floor for request spacing. A lookup and its cover are two requests per ROM,
         * so this is the fastest a scan will ever push a service that is donated.
         */
        const val MIN_INTERVAL_MILLIS = 600L

        /** Ceiling for spacing once the service has pushed back repeatedly. */
        const val MAX_INTERVAL_MILLIS = 5_000L

        /** How much of the widened spacing is given back per accepted request. */
        const val PACING_RECOVERY_MILLIS = 150L
        const val MAX_RETRY_AFTER_MILLIS = 30_000L

        const val MAX_DESCRIPTION_LENGTH = 1200
        const val MAX_REGION_LENGTH = 24
        const val MIN_ALIAS_LENGTH = 2
        const val MIN_YEAR = 1950
        const val MAX_YEAR = 2100
    }
}
