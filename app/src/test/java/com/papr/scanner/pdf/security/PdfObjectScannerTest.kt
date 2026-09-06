package com.papr.scanner.pdf.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfObjectScannerTest {

    private val scanner = PdfObjectScanner()

    @Test
    fun `finds stream range via Length even when payload contains the word endstream`() {
        val payload = "AAAA endstream BBBB"
        val text = "4 0 obj\n<</Length ${payload.length}>>\nstream\n$payload\nendstream\nendobj\n"

        val objects = scanner.scan(text)

        assertEquals(1, objects.size)
        val obj = objects.single()
        assertEquals(4, obj.number)
        val range = obj.ranges.single()
        assertEquals(payload, text.substring(range.start, range.end))
    }

    @Test
    fun `finds literal string ranges, handling escapes and nested parens`() {
        val text = "1 0 obj\n<</Producer (Skia \\(PDF\\) m11) /Title (Nested (ok) parens)>>\nendobj\n"

        val objects = scanner.scan(text)

        val obj = objects.single()
        val strings = obj.ranges.map { text.substring(it.start, it.end) }
        assertEquals(listOf("Skia \\(PDF\\) m11", "Nested (ok) parens"), strings)
    }

    @Test
    fun `skips objects with no strings or streams`() {
        val text = "2 0 obj\n<</Type /Page /Parent 1 0 R>>\nendobj\n"

        val objects = scanner.scan(text)

        assertTrue(objects.isEmpty())
    }
}
