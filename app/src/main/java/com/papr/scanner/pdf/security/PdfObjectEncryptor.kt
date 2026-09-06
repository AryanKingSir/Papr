package com.papr.scanner.pdf.security

/** Applies Algorithm 1 (per-object key) + RC4 in place over each object's encryptable byte ranges. */
class PdfObjectEncryptor(private val securityHandler: StandardSecurityHandler) {

    fun encrypt(bytes: ByteArray, fileKey: ByteArray, objects: List<EncryptableObject>): ByteArray {
        val result = bytes.copyOf()
        for (obj in objects) {
            val objectKey = securityHandler.computeObjectKey(fileKey, obj.number, obj.generation)
            for (range in obj.ranges) {
                val plaintext = result.copyOfRange(range.start, range.end)
                val ciphertext = Rc4Cipher.crypt(objectKey, plaintext)
                System.arraycopy(ciphertext, 0, result, range.start, ciphertext.size)
            }
        }
        return result
    }
}
