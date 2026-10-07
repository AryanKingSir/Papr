package com.papr.scanner.pdf

/**
 * [bytes] read as ISO-8859-1 text without copying them: char index == byte index, and each char
 * is its byte's value. A finished export can be over 100 MB, so decoding it into a String (another
 * full copy, two on Android) just to search it would double the memory a password or OCR export needs.
 */
internal class Latin1Text(
    private val bytes: ByteArray,
    private val start: Int = 0,
    override val length: Int = bytes.size - start,
) : CharSequence {

    override fun get(index: Int): Char = (bytes[start + index].toInt() and 0xFF).toChar()

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        Latin1Text(bytes, start + startIndex, endIndex - startIndex)

    override fun toString(): String = String(bytes, start, length, Charsets.ISO_8859_1)
}
