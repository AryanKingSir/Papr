package com.papr.scanner.pdf.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Rc4CipherTest {

    /** Standard published RC4 test vectors. */
    @Test
    fun `matches known test vectors`() {
        assertVector(key = "Key", plaintext = "Plaintext", expectedHex = "BBF316E8D940AF0AD3")
        assertVector(key = "Wiki", plaintext = "pedia", expectedHex = "1021BF0420")
        assertVector(key = "Secret", plaintext = "Attack at dawn", expectedHex = "45A01F645FC35B383552544B9BF5")
    }

    @Test
    fun `is its own inverse`() {
        val key = "some key".toByteArray()
        val plaintext = "round trip this".toByteArray()

        val ciphertext = Rc4Cipher.crypt(key, plaintext)
        val recovered = Rc4Cipher.crypt(key, ciphertext)

        assertArrayEquals(plaintext, recovered)
    }

    private fun assertVector(key: String, plaintext: String, expectedHex: String) {
        val result = Rc4Cipher.crypt(key.toByteArray(Charsets.US_ASCII), plaintext.toByteArray(Charsets.US_ASCII))
        assertArrayEquals(hexToBytes(expectedHex), result)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
