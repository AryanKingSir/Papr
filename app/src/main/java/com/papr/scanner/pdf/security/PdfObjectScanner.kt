package com.papr.scanner.pdf.security

/** Byte range [start, end) within the source PDF bytes. */
data class ByteRange(val start: Int, val end: Int)

/** An indirect object's number/generation plus the byte ranges within it that need RC4 encryption. */
data class EncryptableObject(val number: Int, val generation: Int, val ranges: List<ByteRange>)

/**
 * Walks a PDF's bytes (decoded 1:1 via ISO-8859-1, so char index == byte
 * index) to find every indirect object and the byte ranges inside it that
 * the Standard Security Handler must encrypt: either its one stream body
 * (located via its own /Length, never by searching for the literal
 * "endstream" keyword - compressed binary can coincidentally contain that
 * text) or, for a non-stream object, its literal `(...)` string values.
 *
 * This is deliberately not a general PDF parser - only enough of one to
 * walk PdfDocument's specific, self-produced output shape (confirmed
 * empirically across Phase 3a/3b: a classic ASCII xref table + trailer,
 * never a compressed cross-reference stream). Hex `<...>` strings are out
 * of scope: nothing this app generates uses them, and detecting them
 * robustly would risk false-positives against `<<`/`>>` dict delimiters.
 */
class PdfObjectScanner {

    fun scan(text: String): List<EncryptableObject> {
        val objects = mutableListOf<EncryptableObject>()
        for (match in OBJECT_HEADER_REGEX.findAll(text)) {
            val number = match.groupValues[1].toInt()
            val generation = match.groupValues[2].toInt()
            val bodyStart = match.range.last + 1
            val bodyEnd = text.indexOf("endobj", bodyStart)
            if (bodyEnd == -1) continue

            val streamRange = findStreamRange(text, bodyStart, bodyEnd)
            val ranges = if (streamRange != null) {
                listOf(streamRange)
            } else {
                findLiteralStringRanges(text, bodyStart, bodyEnd)
            }
            if (ranges.isNotEmpty()) {
                objects.add(EncryptableObject(number, generation, ranges))
            }
        }
        return objects
    }

    private fun findStreamRange(text: String, bodyStart: Int, bodyEnd: Int): ByteRange? {
        val streamKeywordAt = text.indexOf("stream", bodyStart)
        if (streamKeywordAt == -1 || streamKeywordAt >= bodyEnd) return null

        val length = LENGTH_REGEX.find(text, bodyStart)
            ?.takeIf { it.range.first < streamKeywordAt }
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        var dataStart = streamKeywordAt + "stream".length
        if (text.getOrNull(dataStart) == '\r') dataStart++
        if (text.getOrNull(dataStart) == '\n') dataStart++
        return ByteRange(dataStart, dataStart + length)
    }

    private fun findLiteralStringRanges(text: String, bodyStart: Int, bodyEnd: Int): List<ByteRange> {
        val ranges = mutableListOf<ByteRange>()
        var i = bodyStart
        while (i < bodyEnd) {
            if (text[i] == '(') {
                val contentStart = i + 1
                var depth = 1
                var j = contentStart
                while (j < bodyEnd && depth > 0) {
                    when (text[j]) {
                        '\\' -> j++
                        '(' -> depth++
                        ')' -> depth--
                    }
                    j++
                }
                ranges.add(ByteRange(contentStart, j - 1))
                i = j
            } else {
                i++
            }
        }
        return ranges
    }

    companion object {
        private val OBJECT_HEADER_REGEX = Regex("(?m)^(\\d+)\\s+(\\d+)\\s+obj\\b")
        private val LENGTH_REGEX = Regex("/Length\\s+(\\d+)")
    }
}
