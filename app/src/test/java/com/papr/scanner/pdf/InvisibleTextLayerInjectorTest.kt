package com.papr.scanner.pdf

import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InvisibleTextLayerInjectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val latin1: Charset = Charset.forName("ISO-8859-1")
    private val injector = InvisibleTextLayerInjector()

    /** Mirrors the classic ASCII xref + trailer shape PdfDocument is confirmed to emit. */
    private fun fixturePdf(): String = """
        %PDF-1.4
        1 0 obj
        <</Type /Catalog
        /Pages 3 0 R>>
        endobj
        2 0 obj
        <</Type /Page
        /Resources <</ProcSet [/PDF /Text]>>
        /MediaBox [0 0 100 100]
        /Contents 4 0 R
        /Parent 3 0 R>>
        endobj
        3 0 obj
        <</Type /Pages
        /Count 1
        /Kids [2 0 R]>>
        endobj
        4 0 obj
        <</Length 10>>
        stream
        0123456789
        endstream
        endobj
        xref
        0 5
        0000000000 65535 f
        0000000009 00000 n
        0000000058 00000 n
        0000000180 00000 n
        0000000230 00000 n
        trailer
        <</Size 5
        /Root 1 0 R>>
        startxref
        300
        %%EOF
    """.trimIndent() + "\n"

    private fun writeFixture(): File {
        val file = tempFolder.newFile("fixture.pdf")
        file.writeBytes(fixturePdf().toByteArray(latin1))
        return file
    }

    @Test
    fun `injects font, content stream, and rewritten page with encodable text`() {
        val file = writeFixture()
        val originalLength = file.length()

        injector.inject(file, mapOf(0 to listOf(OcrTextElement("Hello", 10f, 20f, 12f, 50f))))

        val result = String(file.readBytes(), latin1)

        assertTrue("expected appended bytes", file.length() > originalLength)
        assertTrue("expected a Helvetica font object", result.contains("/BaseFont /Helvetica"))
        assertTrue("expected invisible text render mode", result.contains("3 Tr"))
        assertTrue("expected a horizontal-scale Tz operator", result.contains(" Tz"))
        assertTrue("expected the recognized text as a Tj operand", result.contains("(Hello) Tj"))
        assertTrue("expected page 2 to be redefined with an updated /Contents array", Regex("/Contents \\[\\d+ 0 R 4 0 R \\d+ 0 R]").containsMatchIn(result))
        assertTrue("expected the page's Resources to reference the new font", result.contains("/Font <</F1"))
        assertTrue("expected an incremental trailer chained via /Prev", result.contains("/Prev 300"))
    }

    @Test
    fun `isolates the text layer from graphics state the page's own stream leaves behind`() {
        // Skia (Android 14) opens each page stream with a y-flipping `cm` it never
        // restores; content streams in a /Contents array share graphics state, so
        // without a q/Q bracket the text layer would land mirrored on the page.
        val file = writeFixture()

        injector.inject(file, mapOf(0 to listOf(OcrTextElement("Hello", 10f, 20f, 12f, 50f))))

        val result = String(file.readBytes(), latin1)
        val contents = Regex("/Contents \\[(\\d+) 0 R 4 0 R (\\d+) 0 R]").find(result)
        assertTrue("expected the original stream bracketed between two new streams", contents != null)
        val (saveObj, textObj) = contents!!.destructured
        assertEquals("q", streamBody(result, saveObj.toInt()).trim())
        assertTrue(
            "expected the text layer to restore the graphics state before drawing",
            streamBody(result, textObj.toInt()).trimStart().startsWith("Q\nBT"),
        )
    }

    private fun streamBody(pdf: String, objNum: Int): String {
        val obj = pdf.substring(pdf.lastIndexOf("\n$objNum 0 obj\n"))
        return obj.substringAfter("stream\n").substringBefore("\nendstream")
    }

    @Test
    fun `leaves file untouched when no page has encodable text`() {
        val file = writeFixture()
        val originalBytes = file.readBytes()

        injector.inject(file, mapOf(0 to listOf(OcrTextElement("日本語", 10f, 20f, 12f, 50f))))

        assertEquals(originalBytes.toList(), file.readBytes().toList())
    }

    @Test
    fun `escapes parentheses and backslashes in recognized text`() {
        val file = writeFixture()

        injector.inject(file, mapOf(0 to listOf(OcrTextElement("a(b)c\\d", 0f, 0f, 12f, 50f))))

        val result = String(file.readBytes(), latin1)
        assertTrue(result.contains("(a\\(b\\)c\\\\d) Tj"))
    }
}
