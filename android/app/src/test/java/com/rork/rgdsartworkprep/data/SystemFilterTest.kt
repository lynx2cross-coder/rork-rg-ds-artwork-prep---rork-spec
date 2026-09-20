package com.rork.rgdsartworkprep.data

import com.rork.rgdsartworkprep.model.SystemCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's per-system artwork filter.
 *
 * [AppSettings] holds opt-outs, never opt-ins, and every rule below exists to keep
 * that choice honest. The danger this guards against is not a wrong toggle — it is an
 * upgrade silently switching a console off, which looks exactly like the app breaking
 * and gives the user nothing to investigate.
 */
class SystemFilterTest {

    private val scrapableKeys: List<String> = SystemCatalog.scrapable.map { it.key }

    // region defaults

    /**
     * The behaviour of every existing install, pinned. A user who never opens the new
     * screen must get precisely the scan they got before it existed.
     */
    @Test
    fun `a fresh install looks up every scrapable system`() {
        val settings = AppSettings()
        val disabled = scrapableKeys.filterNot { settings.isSystemEnabled(it) }
        assertTrue("switched off out of the box: $disabled", disabled.isEmpty())
    }

    @Test
    fun `no preference at all records no opt-outs`() {
        assertEquals(emptySet<String>(), AppSettings().disabledSystemKeys)
    }

    /**
     * The reason opt-outs are stored rather than opt-ins.
     *
     * A system that did not exist when the user last touched Settings cannot appear in
     * their saved set. Under an opt-in scheme that absence would read as "off", so
     * every console added in a later release would ship switched off to everyone who
     * had ever opened this screen.
     */
    @Test
    fun `a system added after the user last chose is on by default`() {
        val settings = AppSettings(disabledSystemKeys = setOf("nes", "snes"))
        assertTrue(settings.isSystemEnabled("system-that-ships-next-year"))
    }

    // endregion

    // region turning systems on and off

    @Test
    fun `a disabled system is filtered out`() {
        val settings = AppSettings(disabledSystemKeys = setOf("psx"))
        assertFalse(settings.isSystemEnabled("psx"))
    }

    @Test
    fun `disabling one system leaves the others alone`() {
        val settings = AppSettings(disabledSystemKeys = setOf("psx"))
        val stillOn = scrapableKeys.filter { settings.isSystemEnabled(it) }
        assertEquals(scrapableKeys.size - 1, stillOn.size)
        assertTrue(stillOn.contains("nes"))
    }

    /** Keys are matched exactly; nothing here is prefix or case folded. */
    @Test
    fun `a disabled key does not disable a similarly named system`() {
        val settings = AppSettings(disabledSystemKeys = setOf("gb"))
        assertFalse(settings.isSystemEnabled("gb"))
        assertTrue(settings.isSystemEnabled("gbc"))
        assertTrue(settings.isSystemEnabled("gba"))
    }

    // endregion

    // region everything off

    /**
     * Turning everything off is a real choice and must survive being made. An empty
     * opt-out set means "all on"; a full one means "all off", and the two must never
     * be confused by a value that treats absence as default.
     */
    @Test
    fun `every system off is a state the settings can express`() {
        val settings = AppSettings(disabledSystemKeys = scrapableKeys.toSet())
        val stillOn = scrapableKeys.filter { settings.isSystemEnabled(it) }
        assertTrue("still on: $stillOn", stillOn.isEmpty())
        assertFalse(settings.hasAnyEnabledSystem(scrapableKeys))
    }

    @Test
    fun `one remaining system counts as having something to scan`() {
        val settings = AppSettings(disabledSystemKeys = (scrapableKeys - "nes").toSet())
        assertTrue(settings.hasAnyEnabledSystem(scrapableKeys))
    }

    @Test
    fun `a fresh install has systems to scan`() {
        assertTrue(AppSettings().hasAnyEnabledSystem(scrapableKeys))
    }

    // endregion

    // region the filter is independent of detection

    /**
     * The filter answers for systems this release cannot scrape too, because it is
     * consulted after the catalog's own gate rather than instead of it. Both must
     * agree before a query runs, and they are separate facts: one is a property of the
     * release, the other of the user's choice.
     */
    @Test
    fun `the user filter says nothing about whether a release can scrape a system`() {
        val settings = AppSettings()
        val mame = SystemCatalog.byKey("mame")
        assertEquals(false, mame?.scrapingEnabled)
        assertTrue("the user never switched MAME off", settings.isSystemEnabled("mame"))
    }

    // endregion
}
