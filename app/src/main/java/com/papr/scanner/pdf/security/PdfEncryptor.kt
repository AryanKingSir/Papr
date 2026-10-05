package com.papr.scanner.pdf.security

import java.io.File
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.Locale

/**
 * Password-protects an already-written PDF (RC4, Revision 3 / 128-bit,
 * ISO 32000-1 7.6.3), as the final PdfEngine step - after PdfEngine's
 * BitmapPdfCompiler/InvisibleTextLayerInjector stages, since encrypting
 * first would turn their subsequent structural edits into ciphertext.
 *
 * RC4 preserves plaintext length, so every existing object's bytes are
 * encrypted strictly in place (same offsets, same xref) - only one new
 * object (the Encrypt dictionary) and one incremental trailer need
 * appending, mirroring InvisibleTextLayerInjector's technique rather than
 * rebuilding the whole xref table.
 */
class PdfEncryptor(
    private val objectScanner: PdfObjectScanner = PdfObjectScanner(),
    private val securityHandler: StandardSecurityHandler = StandardSecurityHandler(),
) {
    private val objectEncryptor = PdfObjectEncryptor(securityHandler)
    private val latin1 = Charset.forName("ISO-8859-1")

    /** [ownerPassword] defaults to [userPassword] when blank, per Algorithm 3. */
    fun encrypt(pdfFile: File, userPassword: String, ownerPassword: String = "") {
        val original = pdfFile.readBytes()
        val text = String(original, latin1)

        val trailer = requireNotNull(lastMatch(TRAILER_REGEX, text)) { "no trailer found" }.groupValues[1]
        val rootRef = requireNotNull(ROOT_REGEX.find(trailer)) { "no /Root in trailer" }.groupValues[1]
        val size = requireNotNull(SIZE_REGEX.find(trailer)) { "no /Size in trailer" }.groupValues[1].toInt()
        val startxref = requireNotNull(lastMatch(STARTXREF_REGEX, text)) { "no startxref" }.groupValues[1]

        val documentId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ownerValue = securityHandler.computeOwnerValue(ownerPassword, userPassword)
        val fileKey = securityHandler.computeFileKey(userPassword, ownerValue, PERMISSIONS_ALLOW_ALL, documentId)
        val userValue = securityHandler.computeUserValue(fileKey, documentId)

        val objects = objectScanner.scan(text)
        val encrypted = objectEncryptor.encrypt(original, fileKey, objects)

        val encryptObjNum = size
        val idHex = documentId.toPdfHex()
        val encryptBody = "<</Filter /Standard /V 2 /R 3 /Length 128" +
            "\n/O <${ownerValue.toPdfHex()}>" +
            "\n/U <${userValue.toPdfHex()}>" +
            "\n/P $PERMISSIONS_ALLOW_ALL>>"

        val appendedObject = "$encryptObjNum 0 obj\n$encryptBody\nendobj\n".toByteArray(latin1)
        val objectOffset = encrypted.size.toLong()
        val xrefOffset = objectOffset + appendedObject.size

        // Locale.ROOT: the default locale can format digits in another script (Arabic, for one).
        val xref = "xref\n$encryptObjNum 1\n${String.format(Locale.ROOT, "%010d 00000 n \n", objectOffset)}"
        val newTrailer = "trailer\n<</Size ${encryptObjNum + 1}\n/Root $rootRef" +
            "\n/Encrypt $encryptObjNum 0 R\n/ID [<$idHex> <$idHex>]\n/Prev $startxref>>" +
            "\nstartxref\n$xrefOffset\n%%EOF\n"

        pdfFile.writeBytes(encrypted + appendedObject + xref.toByteArray(latin1) + newTrailer.toByteArray(latin1))
    }

    private fun ByteArray.toPdfHex(): String = joinToString("") { String.format(Locale.ROOT, "%02X", it) }

    private fun lastMatch(regex: Regex, text: String) = regex.findAll(text).lastOrNull()

    companion object {
        // "Allow everything a valid password grants" per ISO 32000-1 Table 22;
        // exact reserved-bit correctness is confirmed by the external-reader
        // manual test, the real acceptance bar for this feature.
        private const val PERMISSIONS_ALLOW_ALL = -4

        private val TRAILER_REGEX = Regex("trailer\\s*<<(.*?)>>", RegexOption.DOT_MATCHES_ALL)
        private val ROOT_REGEX = Regex("/Root\\s+(\\d+\\s+\\d+\\s+R)")
        private val SIZE_REGEX = Regex("/Size\\s+(\\d+)")
        private val STARTXREF_REGEX = Regex("startxref\\s+(\\d+)")
    }
}
