package com.rork.rgdsartworkprep.provider

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response

/**
 * Reads what actually crossed the wire for one request.
 *
 * Everything here has to be observed at OkHttp's *network* layer rather than from the
 * app's side of the client, because the two facts that matter most do not survive the
 * trip up to the caller:
 *
 *  - `Accept-Encoding` is added by OkHttp's own bridge, not by this app, so the only
 *    place to learn whether the device asked for gzip is the request as sent.
 *  - `Content-Encoding` is *removed* again once OkHttp has transparently decompressed
 *    the body. By the time a response reaches [LibretroThumbnailsProvider] the header
 *    is gone, so "was this compressed" is unanswerable from there.
 *
 * That distinction is the whole reason this class exists: a 4 MB index costs 270 KB
 * when gzip is negotiated and 4 MB when it is not, which is a fifteen-fold difference
 * in what a handheld's wifi has to carry, and nothing in the previous report could
 * tell those two apart.
 *
 * ### Why a single shared slot is safe
 *
 * Every libretro request — name guess, index, cover — is issued inside
 * [RequestPacer.run], which holds a mutex for the duration. Exactly one request is
 * therefore in flight at a time, so one set of fields cannot be written by two
 * requests at once. This mirrors the same one-at-a-time argument `ScanDiagnostics`
 * relies on for the current ROM. Fields are volatile because OkHttp raises these
 * callbacks on its own connection threads, not on the caller's.
 *
 * Purely observational. Nothing recorded here is read back by a request, a retry or a
 * match decision.
 */
class LibretroWireProbe : EventListener() {

    /** What one request's network layer reported. */
    data class Observation(
        val method: String,
        val wireBytes: Long,
        val declaredBytes: Long,
        val contentEncoding: String?,
        val acceptEncoding: String?,
    )

    @Volatile
    private var method: String = UNKNOWN_METHOD

    @Volatile
    private var wireBytes: Long = UNKNOWN

    @Volatile
    private var declaredBytes: Long = UNKNOWN

    @Volatile
    private var contentEncoding: String? = null

    @Volatile
    private var acceptEncoding: String? = null

    /** Clears the slot before a request, so a failed call cannot inherit stale numbers. */
    fun reset() {
        method = UNKNOWN_METHOD
        wireBytes = UNKNOWN
        declaredBytes = UNKNOWN
        contentEncoding = null
        acceptEncoding = null
    }

    /** The finished observation. Safe to call even when the request never got going. */
    fun take(): Observation = Observation(
        method = method,
        wireBytes = wireBytes,
        declaredBytes = declaredBytes,
        contentEncoding = contentEncoding,
        acceptEncoding = acceptEncoding,
    )

    /**
     * The request as it actually went out, after OkHttp has added its own headers.
     * This is where `Accept-Encoding: gzip` appears if the device negotiates it.
     */
    override fun requestHeadersEnd(call: Call, request: Request) {
        method = request.method
        acceptEncoding = request.header(HEADER_ACCEPT_ENCODING)
    }

    /**
     * The response headers straight off the connection, before OkHttp strips
     * `Content-Encoding` on decompressing the body.
     */
    override fun responseHeadersEnd(call: Call, response: Response) {
        contentEncoding = response.header(HEADER_CONTENT_ENCODING)
        declaredBytes = response.header(HEADER_CONTENT_LENGTH)?.toLongOrNull() ?: UNKNOWN
    }

    /**
     * Bytes read from the network for the body.
     *
     * Counted below transparent decompression, so on a gzipped response this is the
     * compressed size — which is the number that reflects what the connection had to
     * carry, and the one worth comparing against the parsed size.
     */
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        wireBytes = byteCount
    }

    private companion object {
        const val UNKNOWN = -1L
        const val UNKNOWN_METHOD = "GET"
        const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
        const val HEADER_CONTENT_ENCODING = "Content-Encoding"
        const val HEADER_CONTENT_LENGTH = "Content-Length"
    }
}
