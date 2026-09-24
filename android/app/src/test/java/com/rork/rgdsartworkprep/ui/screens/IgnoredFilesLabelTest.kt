package com.rork.rgdsartworkprep.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class IgnoredFilesLabelTest {

    @Test
    fun `singular and plural read naturally`() {
        assertEquals("1 file ignored", ignoredFilesLabel(1, tappable = false))
        assertEquals("4 files ignored", ignoredFilesLabel(4, tappable = false))
    }

    @Test
    fun `the tappable indicator says where it leads`() {
        assertEquals("4 files ignored \u00b7 tap to manage", ignoredFilesLabel(4, tappable = true))
    }
}
