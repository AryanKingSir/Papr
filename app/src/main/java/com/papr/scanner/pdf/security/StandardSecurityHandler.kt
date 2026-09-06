package com.papr.scanner.pdf.security

import java.security.MessageDigest

/**
 * ISO 32000-1 7.6.3, Standard Security Handler, Revision 3 (128-bit RC4).
 *
 * Algorithm/value correctness was cross-checked against qpdf (a mature,
 * independent reference implementation): for a fixed (ownerPassword="",
 * userPassword="userpw", id, P=-4), this implementation's computed /O
 * matches qpdf's byte-for-byte, and decrypting a real qpdf-encrypted stream
 * with this implementation's derived file key + object key recovers the
 * exact original plaintext. See StandardSecurityHandlerTest.
 */
class StandardSecurityHandler {

    fun computeOwnerValue(ownerPassword: String, userPassword: String): ByteArray {
        val effectiveOwnerPassword = ownerPassword.ifEmpty { userPassword }
        var digest = md5(pad(effectiveOwnerPassword))
        repeat(50) { digest = md5(digest) }
        val key = digest.copyOf(KEY_LENGTH)

        var result = pad(userPassword)
        result = Rc4Cipher.crypt(key, result)
        for (i in 1..19) {
            result = Rc4Cipher.crypt(xorKey(key, i), result)
        }
        return result
    }

    fun computeFileKey(userPassword: String, ownerValue: ByteArray, permissions: Int, documentId: ByteArray): ByteArray {
        val permissionBytes = byteArrayOf(
            (permissions and 0xFF).toByte(),
            ((permissions shr 8) and 0xFF).toByte(),
            ((permissions shr 16) and 0xFF).toByte(),
            ((permissions shr 24) and 0xFF).toByte(),
        )
        var digest = md5(pad(userPassword) + ownerValue + permissionBytes + documentId)
        repeat(50) { digest = md5(digest.copyOf(KEY_LENGTH)) }
        return digest.copyOf(KEY_LENGTH)
    }

    fun computeUserValue(fileKey: ByteArray, documentId: ByteArray): ByteArray {
        var result = md5(PADDING + documentId)
        result = Rc4Cipher.crypt(fileKey, result)
        for (i in 1..19) {
            result = Rc4Cipher.crypt(xorKey(fileKey, i), result)
        }
        // The trailing 16 bytes of a Revision-3 /U value are unchecked by
        // compliant readers - only the first 16 (the actual hashed result)
        // matter. Zero-padding to the required 32-byte length is spec-valid.
        return result.copyOf(32)
    }

    fun computeObjectKey(fileKey: ByteArray, objectNumber: Int, generation: Int): ByteArray {
        val extra = byteArrayOf(
            (objectNumber and 0xFF).toByte(),
            ((objectNumber shr 8) and 0xFF).toByte(),
            ((objectNumber shr 16) and 0xFF).toByte(),
            (generation and 0xFF).toByte(),
            ((generation shr 8) and 0xFF).toByte(),
        )
        val digest = md5(fileKey + extra)
        return digest.copyOf(minOf(fileKey.size + 5, 16))
    }

    private fun xorKey(key: ByteArray, round: Int): ByteArray = ByteArray(key.size) { (key[it].toInt() xor round).toByte() }

    private fun pad(password: String): ByteArray {
        val bytes = password.toByteArray(Charsets.ISO_8859_1)
        val truncated = bytes.copyOf(minOf(bytes.size, 32))
        return truncated + PADDING.copyOf(32 - truncated.size)
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    companion object {
        const val KEY_LENGTH = 16

        val PADDING = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
            0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
            0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
        )
    }
}
