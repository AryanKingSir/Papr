package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotatedPageTextTest {

    @Test
    fun `a page turned upright replaces the little that was read sideways`() {
        assertEquals("INVOICE Total due 853.20", textWorthSavingAfterRotation(previous = "", recognized = "INVOICE Total due 853.20"))
    }

    @Test
    fun `a page turned away from upright keeps the text read before`() {
        assertNull(textWorthSavingAfterRotation(previous = "INVOICE Total due 853.20", recognized = "IN"))
    }

    @Test
    fun `a page never read takes whatever is found`() {
        assertEquals("", textWorthSavingAfterRotation(previous = null, recognized = ""))
    }
}
