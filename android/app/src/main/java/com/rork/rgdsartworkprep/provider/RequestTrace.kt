package com.rork.rgdsartworkprep.provider

/**
 * One HTTP request, as it actually happened on the wire.
 *
 * The provider-level timing already in place answers "how long did this source take",
 * which turned out not to be the question. A libretro identify can issue up to twelve
 * requests — three name guesses times three pacer attempts, plus the index — and a
 * single elapsed figure for the lot cannot say which of them was slow, whether the
 * index was fetched at all, or how many bytes crossed a handheld's wifi to answer a
 * yes/no question. This records each request separately so the report can.
 *
 * Purely observational: nothing in the app reads a trace back, and no value here
 * influences a request, a retry or a match.
 *
 * @param stage which kind of request this was — a name guess, the index, or a cover.
 * @param attempt 1-based pacer attempt for this stage. Above 1 means the previous
 *   attempt was retried, which is the multiplication that turns one slow request into
 *   a slow ROM.
 * @param method GET or HEAD, recorded rather than assumed so a later change to probe
 *   with HEAD can be proven against this baseline.
 * @param target a *shape*, never a filename — "guess 1 of 3", "index", "cover". ROM
 *   names are governed by the report's own privacy setting and must not sneak in here.
 * @param status HTTP status, or 0 when no response arrived.
 * @param wireBytes bytes actually read from the network, before any decompression.
 *   -1 when unknown (the body was never read, or the server sent no length).
 * @param declaredBytes the server's own Content-Length, before decompression. -1 when
 *   absent, which is what a chunked response looks like.
 * @param bodyBytes size after decoding, when the app read the body. -1 otherwise.
 * @param contentEncoding what the server said it encoded with, read at the network
 *   layer *before* OkHttp transparently decompresses — by the time the app sees the
 *   response this header has been stripped, so it cannot be read any later than this.
 * @param acceptEncoding what actually went out on the request. Whether the device
 *   negotiates gzip at all is the single unknown that most changes the reading of a
 *   slow index fetch, and it can only be answered by looking at the sent header.
 */
data class ProviderRequestTrace(
    val providerKey: String,
    val stage: String,
    val attempt: Int,
    val method: String,
    val target: String,
    val status: Int,
    val elapsedMillis: Long,
    val wireBytes: Long,
    val declaredBytes: Long,
    val bodyBytes: Long,
    val contentEncoding: String?,
    val acceptEncoding: String?,
    val outcome: String,
)

/**
 * A system index that was fetched and parsed, and what it cost.
 *
 * Held apart from [ProviderRequestTrace] because the request and the parse answer
 * different questions: the trace says what the download cost, this says whether the
 * megabytes bought a usable index. A fetch that succeeds and parses to zero entries
 * reads identically to a fast success in a timings-only report.
 *
 * The cost is split into its two phases because the previous build's single figure
 * could not be acted on: an index reported as 334.7 seconds against a 1.1-second
 * download left the other 333 unattributed between waiting for the request slot and
 * parsing on the device's CPU — two problems with opposite fixes.
 *
 * @param fetchMillis waiting for the slot, pacing, retries, and the transfer itself.
 * @param parseMillis turning the listing into entries, on the device's CPU.
 * @param elapsedMillis both phases together, so a reader of the total does not have
 *   to add them up.
 */
data class ProviderIndexTrace(
    val providerKey: String,
    /** The archive's folder name — a platform, never anything about the user. */
    val folder: String,
    val entries: Int,
    val elapsedMillis: Long,
    val fetchMillis: Long,
    val parseMillis: Long,
    val outcome: String,
)

/**
 * Somewhere to send request-level observations.
 *
 * Mirrors [ProviderCallObserver]: implementations must be cheap and must never throw,
 * because this is called on the scanning path.
 */
interface ProviderRequestObserver {
    fun onProviderRequest(trace: ProviderRequestTrace)
    fun onProviderIndex(trace: ProviderIndexTrace)
}
