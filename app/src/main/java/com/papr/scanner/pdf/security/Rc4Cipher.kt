package com.papr.scanner.pdf.security

/** RC4 stream cipher (KSA + PRGA). Encryption and decryption are the same operation. */
object Rc4Cipher {

    fun crypt(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }

        val output = ByteArray(data.size)
        var i = 0
        j = 0
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val k = s[(s[i] + s[j]) and 0xFF]
            output[n] = (data[n].toInt() xor k).toByte()
        }
        return output
    }
}
