package com.papr.scanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `keeps long non-Latin titles within the filesystem's byte limit`() {
        // Devanagari letters take 3 bytes each in UTF-8; Android allows 255 bytes per file name.
        val result = FileUtils.sanitizeFileName("\u0928".repeat(100))

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `never cuts an emoji in half`() {
        val result = FileUtils.sanitizeFileName("\uD83D\uDCC4".repeat(80)) // page-facing-up emoji

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(0, result.length % 2)
        assertTrue(result.all { !it.isSurrogate() } || result.chunked(2).all { it.length == 2 && Character.isSurrogatePair(it[0], it[1]) })
    }
}
