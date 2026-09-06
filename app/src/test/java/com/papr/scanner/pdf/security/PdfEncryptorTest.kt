package com.papr.scanner.pdf.security

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfEncryptorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val latin1: Charset = Charset.forName("ISO-8859-1")

    private fun fixturePdf(streamPayload: String): String = """
        %PDF-1.4
        1 0 obj
        <</Producer (Test Producer)>>
        endobj
        2 0 obj
        <</Type /Catalog /Pages 3 0 R>>
        endobj
        3 0 obj
        <</Type /Pages /Count 1 /Kids [4 0 R]>>
        endobj
        4 0 obj
        <</Type /Page /Contents 5 0 R /Parent 3 0 R>>
        endobj
        5 0 obj
        <</Length ${streamPayload.length}>>
        stream
        $streamPayload
        endstream
        endobj
        trailer
        <</Size 6
        /Root 2 0 R>>
        startxref
        0
        %%EOF
    """.trimIndent() + "\n"

    @Test
    fun `encrypts objects in place and content is recoverable with the same derived keys`() {
        val streamPayload = "BT /F1 12 Tf (Hello) Tj ET"
        val file = tempFolder.newFile("fixture.pdf")
        file.writeBytes(fixturePdf(streamPayload).toByteArray(latin1))

        PdfEncryptor().encrypt(file, userPassword = "secret")

        val bytes = file.readBytes()
        val text = String(bytes, latin1)

        assertFalse("plaintext string should no longer appear once encrypted", text.contains("Test Producer"))

        // Re-derive the file key exactly as a real reader would, from what's now in the file.
        val ownerValue = hexToBytes(requireMatch(Regex("/O <([0-9A-Fa-f]+)>"), text))
        val documentId = hexToBytes(requireMatch(Regex("/ID \\[<([0-9A-Fa-f]+)>"), text))
        val permissions = requireMatch(Regex("/P (-?\\d+)"), text).toInt()

        val handler = StandardSecurityHandler()
        val fileKey = handler.computeFileKey("secret", ownerValue, permissions, documentId)

        val streamObject = PdfObjectScanner().scan(text).single { it.number == 5 }
        val objectKey = handler.computeObjectKey(fileKey, streamObject.number, streamObject.generation)
        val range = streamObject.ranges.single()
        val decrypted = Rc4Cipher.crypt(objectKey, bytes.copyOfRange(range.start, range.end))

        assertEquals(streamPayload, String(decrypted, latin1))
    }

    private fun requireMatch(regex: Regex, text: String): String =
        requireNotNull(regex.find(text)) { "expected to find $regex" }.groupValues[1]

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
