package com.papr.scanner.pdf.security

/** RC4 stream cipher (KSA + PRGA). Encryption and decryption are the same operation. */
object Rc4Cipher {

    fun crypt(key: ByteArray, data: ByteArray): ByteArray = data.copyOf().also { cryptInPlace(key, it, 0, it.size) }

    /** Encrypts (or decrypts) data[from, to) in place, so a large stream needs no copy. */
    fun cryptInPlace(key: ByteArray, data: ByteArray, from: Int, to: Int) {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }

        var i = 0
        j = 0
        for (n in from until to) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val k = s[(s[i] + s[j]) and 0xFF]
            data[n] = (data[n].toInt() xor k).toByte()
        }
    }
}
