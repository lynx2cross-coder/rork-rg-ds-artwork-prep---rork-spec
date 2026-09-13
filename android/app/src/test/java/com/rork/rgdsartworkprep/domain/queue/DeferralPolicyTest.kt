package com.rork.rgdsartworkprep.domain.queue

import com.rork.rgdsartworkprep.network.CredentialScope
import com.rork.rgdsartworkprep.network.ProviderError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which failures are worth another attempt, and which are verdicts.
 *
 * Getting this wrong in either direction is costly: treating a busy server as a
 * missing game sends the user hunting for artwork that exists, while retrying a
 * rejected password just repeats the same refusal on a timer.
 */
class DeferralPolicyTest {

    @Test
    fun `a rate limit is deferred and paced for a server`() {
        val reason = DeferralPolicy.reasonFor(ProviderError.RateLimited("slow down"))
        assertEquals(DeferReason.RateLimited, reason)
        assertTrue(reason!!.isServerPaced)
    }

    @Test
    fun `a server error is deferred and paced for a server`() {
        val reason = DeferralPolicy.reasonFor(ProviderError.Http(503, "unavailable"))
        assertEquals(DeferReason.ServerFailure, reason)
        assertTrue(reason!!.isServerPaced)
    }

    @Test
    fun `a timeout is told apart from a refused connection`() {
        assertEquals(
            DeferReason.ProviderTimeout,
            DeferralPolicy.reasonFor(ProviderError.Network("Read timed out")),
        )
        assertEquals(
            DeferReason.ProviderTimeout,
            DeferralPolicy.reasonFor(ProviderError.Network("SocketTimeoutException")),
        )
        assertEquals(
            DeferReason.NetworkFailure,
            DeferralPolicy.reasonFor(ProviderError.Network("Connection refused")),
        )
    }

    /** A connection problem must wait seconds, not the minute a busy server needs. */
    @Test
    fun `connection problems are not paced as server problems`() {
        val reason = DeferralPolicy.reasonFor(ProviderError.Network("Connection refused"))
        assertEquals(false, reason!!.isServerPaced)
    }

    @Test
    fun `an unreadable reply is deferred rather than blamed on the game`() {
        assertEquals(
            DeferReason.ProviderUnavailable,
            DeferralPolicy.reasonFor(ProviderError.Unexpected("Hasheous identify", "JsonDecodingException")),
        )
    }

    /** "Not in the database" is a fact about the game — retrying cannot change it. */
    @Test
    fun `not found is never deferred`() {
        assertNull(DeferralPolicy.reasonFor(ProviderError.NotFound))
    }

    @Test
    fun `failures that stop the whole run are never deferred`() {
        val fatal = listOf(
            ProviderError.MissingCredentials,
            ProviderError.InvalidCredentials("rejected", CredentialScope.Developer),
            ProviderError.QuotaExceeded("used up"),
            ProviderError.ServiceClosed("closed"),
            ProviderError.BadRequest("missing field"),
        )
        fatal.forEach { error ->
            assertNull(
                "${error.javaClass.simpleName} must not be queued for retry",
                DeferralPolicy.reasonFor(error),
            )
        }
    }

    /**
     * The queue's verdict has to agree with the existing error model, since that is
     * the contract the rest of the scraper was written against.
     */
    @Test
    fun `deferral agrees with the existing transient flag`() {
        val transient = listOf(
            ProviderError.RateLimited("x"),
            ProviderError.Http(500, "x"),
            ProviderError.Network("x"),
            ProviderError.Unexpected("stage", "x"),
        )
        transient.forEach { error ->
            assertTrue(error.isTransient)
            assertNotNull(
                "a transient failure should produce a reason",
                DeferralPolicy.reasonFor(error),
            )
        }
    }

    @Test
    fun `a client side http error is deferred as provider unavailable`() {
        assertEquals(
            DeferReason.ProviderUnavailable,
            DeferralPolicy.reasonFor(ProviderError.Http(418, "teapot")),
        )
    }

    @Test
    fun `every reason has a stable key that round trips`() {
        DeferReason.entries.forEach { reason ->
            assertEquals(reason, DeferReason.fromKey(reason.key))
        }
    }

    // region device-side limits

    /**
     * The RG DS spent 266 seconds on Final Fantasy VII across three attempts, each one
     * downloading the index in about a second and then failing to process it in the
     * time allowed. Retrying could not have helped: the same device given the same
     * listing reaches the same point. Three ROMs failed this way and between them
     * consumed most of a twelve-and-a-half-minute scan.
     */
    @Test
    fun `a device processing limit is not retried`() {
        assertNull(
            "retrying only re-proves the same limit",
            DeferralPolicy.reasonFor(ProviderError.ProcessingLimit("index", 65_000L)),
        )
    }

    /** The distinction it turns on: waiting on a service is still worth another go. */
    @Test
    fun `a provider timeout is still retried`() {
        assertEquals(
            DeferReason.ProviderTimeout,
            DeferralPolicy.reasonFor(ProviderError.Timeout("index", 65_000L)),
        )
    }

    /**
     * It must not be mistaken for a missing game either. The covers were never looked
     * for, so claiming the archive does not have them would be an invention.
     */
    @Test
    fun `a device processing limit explains itself without blaming the game`() {
        val error = ProviderError.ProcessingLimit("index", 65_000L)
        assertFalse("it is not transient", error.isTransient)
        assertFalse("it must not set every other ROM aside", error.stopsRun)
        assertTrue("it should name the device", error.userMessage.contains("device"))
        assertTrue("it should name the stage", error.userMessage.contains("index"))
    }

    // endregion
}
