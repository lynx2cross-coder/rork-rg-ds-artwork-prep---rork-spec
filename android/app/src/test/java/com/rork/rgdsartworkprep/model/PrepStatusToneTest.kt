package com.rork.rgdsartworkprep.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status presentation rules.
 *
 * From device testing: a row whose automatic match failed but which could be resolved
 * by tapping it was labelled "Not found" in red. Both signals were wrong — the search
 * was not over, and nothing had failed — and the cost is that red stops meaning
 * anything. These tests pin the distinction so it cannot quietly regress.
 */
class PrepStatusToneTest {

    // region red is reserved for genuine failures

    @Test
    fun `only real failures are red`() {
        val failures = PrepStatus.entries.filter { it.tone == StatusTone.Failure }
        assertEquals(
            "red must be reserved for genuine errors and genuine misses",
            setOf(PrepStatus.NotFound, PrepStatus.ApiError),
            failures.toSet(),
        )
    }

    /** The regression itself: an actionable row must never wear the failure colour. */
    @Test
    fun `a row awaiting a choice is amber and not red`() {
        assertEquals(StatusTone.Attention, PrepStatus.ChooseArtwork.tone)
    }

    @Test
    fun `a pending choice between candidates stays amber`() {
        assertEquals(StatusTone.Attention, PrepStatus.MultipleMatches.tone)
    }

    /**
     * NotFound keeps red because it now means only one thing: a search ran and came
     * back with nothing usable.
     */
    @Test
    fun `a genuine miss is still red`() {
        assertEquals(StatusTone.Failure, PrepStatus.NotFound.tone)
    }

    @Test
    fun `every status has a tone`() {
        PrepStatus.entries.forEach { status ->
            assertTrue("$status has no tone", status.tone in StatusTone.entries)
        }
    }

    // endregion

    // region wording

    @Test
    fun `the choose state reads as an invitation rather than a verdict`() {
        assertEquals("Choose artwork", PrepStatus.ChooseArtwork.label)
    }

    /** "Not found" must survive on exactly one status, or the split achieved nothing. */
    @Test
    fun `not found labels only the genuine miss`() {
        val labelled = PrepStatus.entries.filter { it.label.equals("Not found", ignoreCase = true) }
        assertEquals(listOf(PrepStatus.NotFound), labelled)
    }

    @Test
    fun `every status has a non blank label`() {
        PrepStatus.entries.forEach { status ->
            assertTrue("$status has a blank label", status.label.isNotBlank())
        }
    }

    // endregion

    // region the row stays tappable

    /**
     * The behaviour the correction must not break: the state was introduced *because*
     * these rows can be resolved by hand.
     */
    @Test
    fun `a row awaiting a choice opens the manual search`() {
        assertTrue(PrepStatus.ChooseArtwork.opensManualSearch)
    }

    @Test
    fun `the manual search stays reachable from the states that had it before`() {
        assertTrue(PrepStatus.NotFound.opensManualSearch)
        assertTrue(PrepStatus.MultipleMatches.opensManualSearch)
    }

    /** A row still being worked on must not be tappable mid-flight. */
    @Test
    fun `unfinished and successful rows do not open the manual search`() {
        listOf(
            PrepStatus.Pending,
            PrepStatus.Working,
            PrepStatus.Downloaded,
            PrepStatus.Exported,
        ).forEach {
            assertFalse("$it must not be tappable", it.opensManualSearch)
        }
    }

    /** Anything amber or red is either tappable or retryable — never a dead end. */
    @Test
    fun `no attention or failure state leaves the user without a move`() {
        PrepStatus.entries
            .filter { it.tone == StatusTone.Attention || it.tone == StatusTone.Failure }
            .forEach {
                assertTrue(
                    "$it offers the user nothing to do",
                    it.opensManualSearch || it.isRetryable,
                )
            }
    }

    // endregion

    // region retrying

    /**
     * These rows were NotFound before the split, and "Retry failed" requeued them.
     * Recolouring a row must not quietly take away a button that worked, so this
     * pins the capability rather than the appearance.
     */
    @Test
    fun `a row awaiting a choice can still be retried`() {
        assertTrue(PrepStatus.ChooseArtwork.isRetryable)
    }

    @Test
    fun `genuine failures remain retryable`() {
        assertTrue(PrepStatus.ApiError.isRetryable)
        assertTrue(PrepStatus.NotFound.isRetryable)
    }

    /** Nothing that already succeeded or is still in flight may be requeued. */
    @Test
    fun `settled and unfinished rows are not retryable`() {
        listOf(
            PrepStatus.Pending,
            PrepStatus.Working,
            PrepStatus.Downloaded,
            PrepStatus.Exported,
            PrepStatus.AlreadyExists,
            PrepStatus.MultipleMatches,
            PrepStatus.Unsupported,
        ).forEach {
            assertFalse("$it must not be retryable", it.isRetryable)
        }
    }

    // endregion
}
