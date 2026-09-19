package com.papr.scanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilsTest {

    @Test
    fun `keeps a plain title unchanged`() {
        assertEquals("Bio", FileUtils.sanitizeFileName("Bio"))
    }

    @Test
    fun `replaces path separators and other illegal characters with spaces`() {
        assertEquals("a b c d", FileUtils.sanitizeFileName("a/b:c*d"))
    }

    @Test
    fun `collapses runs of whitespace left behind by replacement`() {
        assertEquals("a b", FileUtils.sanitizeFileName("a///b"))
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals("Bio", FileUtils.sanitizeFileName("  Bio  "))
    }

    @Test
    fun `falls back to a default name when nothing legal remains`() {
        assertEquals("Document", FileUtils.sanitizeFileName("///"))
    }

    @Test
    fun `falls back to a default name for a blank title`() {
        assertEquals("Document", FileUtils.sanitizeFileName("   "))
    }

    @Test
    fun `truncates very long titles`() {
        val longTitle = "a".repeat(200)

        val result = FileUtils.sanitizeFileName(longTitle)

        assertEquals(100, result.length)
    }
}
