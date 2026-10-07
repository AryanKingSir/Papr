package com.papr.scanner.pdf.security

/** Applies Algorithm 1 (per-object key) + RC4 in place over each object's encryptable byte ranges. */
class PdfObjectEncryptor(private val securityHandler: StandardSecurityHandler) {

    /** Encrypts [bytes] itself rather than a copy: a large export can't afford a second copy in memory. */
    fun encryptInPlace(bytes: ByteArray, fileKey: ByteArray, objects: List<EncryptableObject>) {
        for (obj in objects) {
            val objectKey = securityHandler.computeObjectKey(fileKey, obj.number, obj.generation)
            for (range in obj.ranges) {
                Rc4Cipher.cryptInPlace(objectKey, bytes, range.start, range.end)
            }
        }
    }
}
