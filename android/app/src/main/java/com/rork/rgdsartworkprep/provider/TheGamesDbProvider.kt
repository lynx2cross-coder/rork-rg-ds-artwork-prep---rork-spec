package com.rork.rgdsartworkprep.provider

import android.util.Log
import com.rork.rgdsartworkprep.data.AppSettings
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.GameSystem
import com.rork.rgdsartworkprep.model.ProviderId
import com.rork.rgdsartworkprep.network.ApiDiagnostic
import com.rork.rgdsartworkprep.network.CredentialScope
import com.rork.rgdsartworkprep.network.ProviderError
import com.rork.rgdsartworkprep.network.ProviderResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TheGamesDB — a community database with its own artwork CDN.
 *
 * It sits between Hasheous and ScreenScraper for a specific reason: it can identify a
 * ROM by checksum *and* search by name, and its covers are served from a CDN with no
 * per-image authentication. That makes it the natural second opinion when Hasheous
 * recognises a game but has no picture mapped to it.
 *
 * How it is used here:
 * - `Games/ByGameHash` turns the CRC32 the app already computes into a game, so a
 *   checksum match stays a checksum match and is never downgraded to a name guess.
 * - `Games/ByGameName` (v1.1) covers disc-based systems and anything the checksum
 *   route does not know, in a single request per ROM.
 * - `include=boxart` returns the artwork alongside the game, so a cover costs no extra
 *   request. Only the front boxart is used — banners and fanart are the wrong shape
 *   for a cover.
 *
 * An API key is required and belongs to the user: it is requested from
 * `thegamesdb.net` and typed into Settings, exactly like the ScreenScraper
 * credentials. Nothing is shipped with the app. The key carries a **monthly** request
 * allowance, which is why the reply's own `remaining_monthly_allowance` is surfaced in
 * Settings rather than being left for the user to discover by running out.
 */
class TheGamesDbProvider : ArtworkProvider {

    override val id: ProviderId = ProviderId.TheGamesDb

    /** One request answers a search, artwork included, so it is safe to use unattended. */
    override val supportsBatchSearch: Boolean = true

    private val client = HttpClient(Android) {
        expectSuccess = false
        engine {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            socketTimeout = SOCKET_TIMEOUT_MILLIS
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pacer = RequestPacer(TAG, MIN_INTERVAL_MILLIS)

    private val _lastDiagnostic = MutableStateFlow<ApiDiagnostic?>(null)

    /** The last exchange that did not go to plan, for the Settings diagnostics panel. */
    val lastDiagnostic: StateFlow<ApiDiagnostic?> = _lastDiagnostic.asStateFlow()

    private val _remainingAllowance = MutableStateFlow<Int?>(null)

    /**
     * Requests left this month, as reported by the service itself on every reply.
     *
     * Shown in Settings because this is a monthly budget rather than a per-second
     * limit: running it dry is a fact worth seeing before a thousand-ROM scan, not
     * after one.
     */
    val remainingAllowance: StateFlow<Int?> = _remainingAllowance.asStateFlow()

    override fun onRunStarted() = pacer.onRunStarted()

    override val isDegraded: Boolean get() = pacer.isDegraded

    override fun isConfigured(settings: AppSettings): Boolean =
        settings.theGamesDbApiKey.isNotBlank()

    override fun unavailableReason(settings: AppSettings): String? =
        if (settings.theGamesDbApiKey.isNotBlank()) null else "No API key saved"

    override suspend fun identify(
        settings: AppSettings,
        rom: RomIdentity,
    ): ProviderResult<GameCandidate> {
        val key = settings.theGamesDbApiKey.trim()
        if (key.isEmpty()) return ProviderResult.Failure(ProviderError.MissingCredentials)

        // A checksum is the only way to be certain, so it is always tried first.
        val crc = rom.crc32?.trim()?.takeIf { it.isNotEmpty() }
        if (crc != null) {
            val byHash = lookupByHash(key, crc, rom.system)
            if (byHash is ProviderResult.Success) return byHash
        }

        // Falling back to the name is what makes this source useful for disc-based
        // systems, which have no checksum the database would recognise.
        return when (val results = search(settings, rom.system, rom.searchTitle)) {
            is ProviderResult.Failure -> results
            is ProviderResult.Success -> {
                val best = results.value.firstOrNull()
                    ?: return ProviderResult.Failure(ProviderError.NotFound)
                // Deciding *how confident* a name match has to be is the chain's job,
                // not a provider's, so a single result is offered and the chain judges
                // it against the runner-up like it does for every other source.
                if (results.value.size == 1) {
                    ProviderResult.Success(best)
                } else {
                    ProviderResult.Failure(ProviderError.NotFound)
                }
            }
        }
    }

    override suspend fun gameById(
        settings: AppSettings,
        system: GameSystem,
        gameId: String,
    ): ProviderResult<GameCandidate> {
        val key = settings.theGamesDbApiKey.trim()
        if (key.isEmpty()) return ProviderResult.Failure(ProviderError.MissingCredentials)
        // Ids issued by another source mean nothing here; reporting a miss lets the
        // pipeline simply identify the ROM again instead of failing the row.
        val numeric = gameId.toIntOrNull() ?: return ProviderResult.Failure(ProviderError.NotFound)

        val url = "$BASE_URL/v1/Games/ByGameID" +
            "?apikey=${key.encodeURLParameter()}&id=$numeric&include=boxart&fields=$FIELDS"
        return when (val response = request(url, GAME_BY_ID_ENDPOINT)) {
            is ProviderResult.Failure -> response
            is ProviderResult.Success -> firstCandidate(response.value, system)
                ?.let { ProviderResult.Success(it) }
                ?: ProviderResult.Failure(ProviderError.NotFound)
        }
    }

    override suspend fun search(
        settings: AppSettings,
        system: GameSystem?,
        query: String,
    ): ProviderResult<List<GameCandidate>> {
        val key = settings.theGamesDbApiKey.trim()
        if (key.isEmpty()) return ProviderResult.Failure(ProviderError.MissingCredentials)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProviderResult.Success(emptyList())

        val platform = system?.let { platformId(it) }
        val url = buildString {
            append("$BASE_URL/v1.1/Games/ByGameName")
            append("?apikey=${key.encodeURLParameter()}")
            append("&name=${trimmed.encodeURLParameter()}")
            append("&include=boxart&fields=$FIELDS")
            // Filtering server-side keeps ports and remakes from crowding out the one
            // platform the user actually has.
            if (platform != null) append("&filter%5Bplatform%5D=$platform")
        }

        return when (val response = request(url, SEARCH_ENDPOINT)) {
            is ProviderResult.Failure -> response
            is ProviderResult.Success -> try {
                ProviderResult.Success(candidates(response.value, system))
            } catch (error: Throwable) {
                ProviderResult.Failure(mappingError("search", SEARCH_ENDPOINT, error))
            }
        }
    }

    override suspend fun downloadArtwork(url: String): ProviderResult<ByteArray> =
        pacer.run { attemptDownload(url) }

    /**
     * Confirms a key works and reports the allowance left, for the Settings button.
     *
     * This is the one place a key can be checked without spending a lookup on a ROM,
     * and it is deliberately explicit: a rejected key looks exactly like a database
     * miss from the outside, so the user gets a way to tell them apart.
     */
    suspend fun checkKey(apiKey: String): KeyCheck {
        val key = apiKey.trim()
        if (key.isEmpty()) return KeyCheck.Problem("Enter your TheGamesDB API key first.")
        val url = "$BASE_URL/v1/API/Limit?apikey=${key.encodeURLParameter()}"
        return when (val response = request(url, LIMIT_ENDPOINT)) {
            is ProviderResult.Success -> {
                val remaining = response.value["remaining_monthly_allowance"].intOrNull()
                    ?: response.value["data"]?.objectOrNull()
                        ?.get("remaining_monthly_allowance").intOrNull()
                KeyCheck.Ok(
                    remaining?.let { "Key accepted — $it requests left this month." }
                        ?: "Key accepted.",
                )
            }
            is ProviderResult.Failure -> KeyCheck.Problem(response.error.userMessage)
        }
    }

    /** Outcome of a key check, phrased for the user. */
    sealed interface KeyCheck {
        data class Ok(val message: String) : KeyCheck
        data class Problem(val message: String) : KeyCheck
    }

    // region requests

    private suspend fun lookupByHash(
        apiKey: String,
        crc: String,
        system: GameSystem,
    ): ProviderResult<GameCandidate> {
        // The service stores checksums in lower case; matching that keeps the request
        // identical to the ones its cache already holds.
        val normalized = crc.lowercase(Locale.US)
        val platform = platformId(system)
        val url = buildString {
            append("$BASE_URL/v1/Games/ByGameHash")
            append("?apikey=${apiKey.encodeURLParameter()}")
            append("&hash=$normalized")
            append("&filter%5Btype%5D=crc")
            append("&include=boxart&fields=$FIELDS")
            if (platform != null) append("&filter%5Bplatform%5D=$platform")
        }

        return when (val response = request(url, HASH_ENDPOINT)) {
            is ProviderResult.Failure -> {
                val genuine = response.error is ProviderError.NotFound
                Log.i(
                    TAG,
                    "crc=$normalized $HASH_ENDPOINT -> " +
                        if (genuine) "genuine no-match" else "could not ask: ${response.error.userMessage}",
                )
                response
            }
            is ProviderResult.Success -> {
                val candidate = try {
                    firstCandidate(response.value, system)
                } catch (error: Throwable) {
                    return ProviderResult.Failure(mappingError("lookup", HASH_ENDPOINT, error))
                }
                candidate?.let { ProviderResult.Success(it) }
                    ?: ProviderResult.Failure(ProviderError.NotFound)
            }
        }
    }

    private suspend fun request(url: String, endpoint: String): ProviderResult<JsonObject> =
        pacer.run { attemptJson(url, endpoint) }

    private suspend fun attemptJson(
        url: String,
        endpoint: String,
    ): RequestPacer.Attempt<JsonObject> = @Suppress("TooGenericExceptionCaught") try {
        val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        val status = response.status.value
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val retryAfter = response.headers[HttpHeaders.RetryAfter]
        if (status in 200..299) {
            val parsed = runCatching { json.parseToJsonElement(text).objectOrNull() }.getOrNull()
            if (parsed == null) {
                record(endpoint, status, text, "Reply was not readable JSON")
                RequestPacer.Attempt.Retry(ProviderError.Network("unreadable reply"))
            } else {
                noteAllowance(parsed)
                RequestPacer.Attempt.Done(parsed)
            }
        } else {
            val outcome = classify<JsonObject>(status, text, retryAfter)
            Log.i(TAG, "$endpoint -> HTTP $status")
            record(endpoint, status, text, outcome.describe())
            outcome
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "Request failed: ${error.javaClass.simpleName}")
        record(endpoint, 0, error.javaClass.simpleName, "Request never completed")
        RequestPacer.Attempt.Retry(ProviderError.Network(error.javaClass.simpleName))
    }

    private suspend fun attemptDownload(url: String): RequestPacer.Attempt<ByteArray> = try {
        val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        val status = response.status.value
        if (status in 200..299) {
            val bytes = response.readRawBytes()
            if (bytes.isEmpty()) {
                record(IMAGE_ENDPOINT, status, "", "Cover was empty")
                RequestPacer.Attempt.Fail(ProviderError.Http(status, "Cover came back empty"))
            } else {
                RequestPacer.Attempt.Done(bytes)
            }
        } else {
            val outcome = classify<ByteArray>(status, "", response.headers[HttpHeaders.RetryAfter])
            record(IMAGE_ENDPOINT, status, "", outcome.describe())
            outcome
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "Artwork download failed: ${error.javaClass.simpleName}")
        record(IMAGE_ENDPOINT, 0, error.javaClass.simpleName, "Download never completed")
        RequestPacer.Attempt.Retry(ProviderError.Network(error.javaClass.simpleName))
    }

    /**
     * Turns a status into something the rest of the app can act on.
     *
     * The important distinction is between a refusal that will repeat for every
     * remaining ROM — a bad key, a spent monthly allowance — and one that says
     * something about this ROM alone. Only the first retires the source for the run.
     */
    private fun <T> classify(
        status: Int,
        body: String,
        retryAfterHeader: String?,
    ): RequestPacer.Attempt<T> = when (status) {
        401 -> RequestPacer.Attempt.Fail(
            ProviderError.InvalidCredentials(
                "TheGamesDB rejected the API key. Check it in Settings.",
                CredentialScope.Developer,
            ),
        )
        // The service documents 403 as "bad API key **or** hit rate-limit cap", so the
        // two are told apart by what it actually said rather than assumed.
        403 -> {
            val said = serverText(body).orEmpty()
            val exhausted = ALLOWANCE_WORDS.any { said.contains(it, ignoreCase = true) }
            if (exhausted) {
                RequestPacer.Attempt.Fail(
                    ProviderError.QuotaExceeded(
                        "TheGamesDB monthly allowance is used up. " +
                            said.ifBlank { "It resets at the start of next month." },
                    ),
                )
            } else {
                RequestPacer.Attempt.Fail(
                    ProviderError.InvalidCredentials(
                        said.ifBlank { "TheGamesDB refused the API key ($status)." },
                        CredentialScope.Developer,
                    ),
                )
            }
        }
        // 404 is a plain miss. 400 and 418 are what this API answers for a query it
        // cannot make sense of — an unsupported hash, in practice. Treating those as
        // misses rather than defects matters: a single odd ROM must not retire the
        // source for the whole run, and a genuinely bad key still surfaces as 401/403
        // and through the Settings key check.
        400, 404, 418 -> RequestPacer.Attempt.Fail(ProviderError.NotFound)
        408, 425 -> RequestPacer.Attempt.Retry(ProviderError.Network("timed out"))
        429 -> RequestPacer.Attempt.Retry(
            ProviderError.RateLimited(serverText(body) ?: "TheGamesDB is busy"),
            delayMillis = retryAfterMillis(retryAfterHeader),
        )
        in 500..599 -> RequestPacer.Attempt.Retry(
            ProviderError.Http(status, "TheGamesDB is having trouble right now"),
        )
        else -> RequestPacer.Attempt.Fail(
            ProviderError.Http(status, serverText(body) ?: "Lookup failed"),
        )
    }

    private fun <T> RequestPacer.Attempt<T>.describe(): String = when (this) {
        is RequestPacer.Attempt.Done -> "Reply accepted"
        is RequestPacer.Attempt.Fail -> "${error.javaClass.simpleName}: ${error.userMessage}"
        is RequestPacer.Attempt.Retry -> "Retrying after ${error.javaClass.simpleName}"
    }

    private fun retryAfterMillis(header: String?): Long? {
        val seconds = header?.trim()?.toDoubleOrNull() ?: return null
        return (seconds * 1000).toLong().coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
    }

    private fun noteAllowance(payload: JsonObject) {
        payload["remaining_monthly_allowance"].intOrNull()?.let { _remainingAllowance.value = it }
    }

    private fun mappingError(stage: String, endpoint: String, error: Throwable): ProviderError {
        Log.w(TAG, "Could not read the TheGamesDB $stage reply: ${error.javaClass.simpleName}")
        val detail = error.javaClass.simpleName +
            (error.message?.take(MAX_DETAIL_LENGTH)?.let { ": $it" }.orEmpty())
        record(endpoint, status = 200, serverMessage = detail, verdict = "Reply could not be read")
        return ProviderError.Unexpected("TheGamesDB $stage", detail)
    }

    private fun serverText(body: String): String? {
        val fromJson = runCatching {
            json.parseToJsonElement(body).objectOrNull()
                ?.let { it["status"] ?: it["message"] ?: it["error"] }
                .stringOrNull()
        }.getOrNull()
        val text = fromJson ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
        return text?.replace(Regex("\\s+"), " ")?.take(MAX_DETAIL_LENGTH)
    }

    /**
     * Keeps the last unhappy exchange for the diagnostics panel.
     *
     * The key travels in the query string, so the URL is never recorded — only the
     * endpoint's shape, and the key's length as proof that something was sent.
     */
    private fun record(endpoint: String, status: Int, serverMessage: String, verdict: String) {
        _lastDiagnostic.value = ApiDiagnostic(
            provider = "TheGamesDB",
            endpoint = endpoint,
            httpStatus = status,
            serverMessage = serverText(serverMessage).orEmpty(),
            sentFields = listOf("apikey (value withheld)"),
            verdict = verdict,
        )
    }

    // endregion

    // region mapping

    private fun firstCandidate(payload: JsonObject, system: GameSystem?): GameCandidate? =
        candidates(payload, system).firstOrNull()

    /**
     * Reads the games out of a reply and attaches their covers.
     *
     * Results are ordered so that anything with a front boxart comes first: this
     * source exists in the chain to supply artwork, and a game whose record has no
     * picture is no more useful here than the coverless match that led to it.
     */
    private fun candidates(payload: JsonObject, system: GameSystem?): List<GameCandidate> {
        val data = payload["data"]?.objectOrNull()
        val games = data?.get("games")?.arrayOrNull().orEmpty()
        val boxart = payload["include"]?.objectOrNull()?.get("boxart")?.objectOrNull()
        val baseUrl = boxart?.get("base_url")?.objectOrNull()?.get("original").stringOrNull()
        val images = boxart?.get("data")?.objectOrNull()

        val wanted = system?.let { platformId(it) }
        return games.mapNotNull { element ->
            val game = element.objectOrNull() ?: return@mapNotNull null
            val gameId = game["id"].intOrNull() ?: return@mapNotNull null
            val title = game["game_title"].stringOrNull() ?: return@mapNotNull null
            // The platform travels with the candidate rather than being looked up again
            // during sorting, which also keeps the ordering from being quadratic.
            val platform = game["platform"].intOrNull()
            platform to GameCandidate(
                gameId = gameId.toString(),
                title = title,
                systemName = system?.displayName.orEmpty(),
                region = "",
                coverUrl = coverUrl(images, gameId, baseUrl),
                provider = ProviderId.TheGamesDb,
                description = game["overview"].stringOrNull()?.take(MAX_DESCRIPTION_LENGTH),
                players = game["players"].intOrNull()?.takeIf { it > 0 }?.toString(),
                releaseDate = formatReleaseDate(game["release_date"].stringOrNull()),
                rating = formatRating(game["rating"].stringOrNull()),
            )
        }.sortedWith(
            compareByDescending<Pair<Int?, GameCandidate>> { it.second.coverUrl != null }
                // Matters when no platform filter was applied: it keeps a match on the
                // ROM's own platform ahead of a same-named game from another one.
                .thenByDescending { (platform, _) -> wanted == null || platform == wanted },
        ).map { it.second }
    }

    /**
     * Picks the front boxart.
     *
     * A banner or a screenshot is the wrong shape for a cover and would be worse than
     * none at all, so nothing but a front boxart is accepted — with the unsided boxart
     * as a fallback for older records that never recorded a side.
     */
    private fun coverUrl(images: JsonObject?, gameId: Int, baseUrl: String?): String? {
        if (baseUrl.isNullOrBlank()) return null
        val entries = images?.get(gameId.toString())?.arrayOrNull().orEmpty()
            .mapNotNull { it.objectOrNull() }
            .filter { it["type"].stringOrNull() == "boxart" }
        val front = entries.firstOrNull { it["side"].stringOrNull() == "front" }
            ?: entries.firstOrNull { it["side"].stringOrNull() == null }
            ?: return null
        val filename = front["filename"].stringOrNull() ?: return null
        return baseUrl.trimEnd('/') + "/" + filename.trimStart('/')
    }

    /** `1985-09-13` to the EmulationStation stamp gamelist.xml expects. */
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

    /** The service rates out of ten; gamelist.xml wants `0.00`–`1.00`. */
    private fun formatRating(raw: String?): String? {
        val score = raw?.trim()?.substringBefore('/')?.toDoubleOrNull() ?: return null
        if (score <= 0) return null
        return String.format(Locale.US, "%.2f", (score / RATING_SCALE).coerceIn(0.0, 1.0))
    }

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement?.stringOrNull(): String? {
        if (this == null || this is JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonElement?.intOrNull(): Int? = stringOrNull()?.toIntOrNull()

    // endregion

    private companion object {
        const val TAG = "TheGamesDbProvider"
        const val BASE_URL = "https://api.thegamesdb.net"
        const val USER_AGENT = "RGDSArtworkPrep/1.0"

        const val HASH_ENDPOINT = "/v1/Games/ByGameHash"
        const val SEARCH_ENDPOINT = "/v1.1/Games/ByGameName"
        const val GAME_BY_ID_ENDPOINT = "/v1/Games/ByGameID"
        const val LIMIT_ENDPOINT = "/v1/API/Limit"
        const val IMAGE_ENDPOINT = "cdn.thegamesdb.net/images"

        /**
         * Only fields that arrive as usable text. Publishers, developers and genres
         * come back as numeric ids that would each need their own lookup, and this key
         * has a monthly budget — the descriptive metadata is better taken from the
         * source that already identified the ROM.
         */
        const val FIELDS = "overview,players,rating,platform"

        val ALLOWANCE_WORDS = listOf("allowance", "limit", "quota", "exceeded")

        const val MIN_INTERVAL_MILLIS = 400L
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val SOCKET_TIMEOUT_MILLIS = 40_000
        const val MAX_RETRY_AFTER_MILLIS = 30_000L
        const val MAX_DETAIL_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 1200
        const val RATING_SCALE = 10.0
        const val MIN_YEAR = 1950
        const val MAX_YEAR = 2100
    }
}

/**
 * TheGamesDB's own platform ids, taken from its published platform list.
 *
 * Kept next to the provider rather than inside [GameSystem] because it is one
 * service's numbering, and the model already carries ScreenScraper's. Systems with no
 * id here are still searched — just without a platform filter.
 */
private val PLATFORM_IDS: Map<String, Int> = mapOf(
    "nes" to 7,
    "snes" to 6,
    "n64" to 3,
    "gb" to 4,
    "gbc" to 41,
    "gba" to 5,
    "nds" to 8,
    "n3ds" to 4912,
    "vb" to 4918,
    "megadrive" to 18,
    "mastersystem" to 35,
    "gamegear" to 20,
    "segacd" to 21,
    "sega32x" to 33,
    "saturn" to 17,
    "dreamcast" to 16,
    "psx" to 10,
    "psp" to 13,
    "mame" to 23,
    "fbneo" to 23,
    "neogeo" to 24,
    "neogeocd" to 4956,
    "ngp" to 4922,
    "ngpc" to 4923,
    "atari2600" to 22,
    "atari5200" to 26,
    "atari7800" to 27,
    "lynx" to 4924,
    "jaguar" to 28,
    "pcengine" to 34,
    "pcenginecd" to 4955,
    "wonderswan" to 4925,
    "wonderswancolor" to 4926,
    "3do" to 25,
    "colecovision" to 31,
    "intellivision" to 32,
    "c64" to 40,
)

private fun platformId(system: GameSystem): Int? = PLATFORM_IDS[system.key]
