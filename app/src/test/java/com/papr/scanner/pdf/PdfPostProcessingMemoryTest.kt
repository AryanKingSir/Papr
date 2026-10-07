package com.papr.scanner.pdf

import com.papr.scanner.pdf.security.PdfEncryptor
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A 30-page export can be well over 100 MB (PdfDocument stores page images losslessly). The steps
 * that rewrite it afterwards must not hold several copies of it at once: the unit-test JVM's 512 MB
 * heap stands in for a phone's large heap here.
 */
class PdfPostProcessingMemoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bigPdf(): File {
        val file = tempFolder.newFile("big.pdf")
        file.outputStream().buffered().use { out ->
            fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
            write("%PDF-1.4\n")
            write("1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n")
            write("2 0 obj\n<</Type /Pages /Count 1 /Kids [3 0 R]>>\nendobj\n")
            write("3 0 obj\n<</Type /Page /Parent 2 0 R /Resources <<>> /Contents 4 0 R>>\nendobj\n")
            write("4 0 obj\n<</Length $PAYLOAD_BYTES>>\nstream\n")
            val chunk = ByteArray(1 shl 20) { (it % 200 + 32).toByte() }
            repeat(PAYLOAD_BYTES / chunk.size) { out.write(chunk) }
            write("\nendstream\nendobj\n")
            write("trailer\n<</Size 5\n/Root 1 0 R>>\nstartxref\n0\n%%EOF\n")
        }
        return file
    }

    @Test
    fun `password protecting a very large PDF doesn't run out of memory`() {
        val file = bigPdf()
        val before = file.length()

        PdfEncryptor().encrypt(file, userPassword = "secret")

        assertTrue(file.length() > before)
        assertTrue(String(file.readBytes().copyOfRange(file.length().toInt() - 300, file.length().toInt()), Charsets.ISO_8859_1).contains("/Encrypt"))
    }

    @Test
    fun `adding a text layer to a very large PDF doesn't run out of memory`() {
        val file = bigPdf()
        val before = file.length()

        InvisibleTextLayerInjector().inject(
            file,
            mapOf(0 to listOf(OcrTextElement(text = "Hello", x = 10f, y = 10f, fontSize = 12f, width = 40f))),
        )

        assertTrue(file.length() > before)
    }

    private companion object {
        const val PAYLOAD_BYTES = 140 shl 20
    }
}
