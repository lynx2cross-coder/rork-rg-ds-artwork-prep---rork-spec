package com.rork.rgdsartworkprep.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A request the app gave up on must never be reported as a game that does not exist.
 *
 * On the device, the libretro PSX index ran for 723.1 and then 661.6 seconds and both
 * times ended as `Failure(NotFound)` in the report — which reads as "this game is not
 * in the archive" when in truth nothing was ever learned about it. That wrong label
 * would send a user searching manually for a cover that is sitting there.
 */
class RequestTimeoutTest {

    @Test
    fun `a socket timeout is recognised`() {
        assertTrue(RequestTimeout.isTimeout(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun `the http client request timeout is recognised`() {
        val error = HttpRequestTimeoutException("https://thumbnails.libretro.com/index", 60_000L)
        assertTrue(RequestTimeout.isTimeout(error))
    }

    /** OkHttp's call watchdog surfaces exactly this way when it fires. */
    @Test
    fun `an okhttp call timeout is recognised`() {
        assertTrue(RequestTimeout.isTimeout(InterruptedIOException("timeout")))
    }

    /** The watchdog fires underneath whatever read was in flight, so it arrives wrapped. */
    @Test
    fun `a timeout wrapped in another failure is still recognised`() {
        val wrapped = IOException("stream closed", SocketTimeoutException("timeout"))
        assertTrue(RequestTimeout.isTimeout(wrapped))
    }

    @Test
    fun `an unrelated interruption is not claimed as a timeout`() {
        assertFalse(RequestTimeout.isTimeout(InterruptedIOException("socket closed")))
    }

    @Test
    fun `an ordinary network failure is not a timeout`() {
        assertFalse(RequestTimeout.isTimeout(UnknownHostException("thumbnails.libretro.com")))
    }

    /**
     * A pathological cause chain must not hang the classifier.
     *
     * This runs on the scan's only worker, so an unbounded walk here would stall the
     * queue — the precise failure mode this pass exists to remove.
     */
    @Test
    fun `a very deep cause chain terminates`() {
        var error: Throwable = SocketTimeoutException("timeout")
        repeat(5_000) { error = IOException("wrapped", error) }
        // Deeper than the classifier looks: it stops rather than walking forever, and
        // reports what it can actually see.
        assertFalse(RequestTimeout.isTimeout(error))
    }

    /** A timeout within reach of the cause walk is still found. */
    @Test
    fun `a timeout a few causes down is still recognised`() {
        var error: Throwable = SocketTimeoutException("timeout")
        repeat(3) { error = IOException("wrapped", error) }
        assertTrue(RequestTimeout.isTimeout(error))
    }

    // region classification

    @Test
    fun `a timeout classifies as a timeout error carrying stage and elapsed time`() {
        val classified = RequestTimeout.classify(
            error = SocketTimeoutException("timeout"),
            stage = "index download",
            elapsedMillis = 420_000L,
        )
        assertTrue(classified is ProviderError.Timeout)
        val timeout = classified as ProviderError.Timeout
        assertEquals("index download", timeout.stage)
        assertEquals(420_000L, timeout.afterMillis)
    }

    /** This is the regression: a timeout must never become a permanent NotFound. */
    @Test
    fun `a timeout is never classified as not found`() {
        val classified = RequestTimeout.classify(
            error = SocketTimeoutException("timeout"),
            stage = "identify",
            elapsedMillis = 661_600L,
        )
        assertTrue(classified !is ProviderError.NotFound)
        assertTrue("a timeout must be retryable", classified.isTransient)
        assertFalse("one slow request must not retire the provider", classified.stopsRun)
    }

    @Test
    fun `a non timeout keeps the existing network behaviour`() {
        val classified = RequestTimeout.classify(
            error = UnknownHostException("thumbnails.libretro.com"),
            stage = "identify",
            elapsedMillis = 1_200L,
        )
        assertTrue(classified is ProviderError.Network)
    }

    @Test
    fun `the timeout message says how long was spent and never leaks a url`() {
        val message = ProviderError.Timeout("index download", 420_000L).userMessage
        assertTrue("got: $message", message.contains("420"))
        assertFalse(message.contains("http"))
    }

    // endregion
}
