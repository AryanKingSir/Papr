package com.papr.scanner.pdf

import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/**
 * Appends an invisible, selectable text layer to a PDF already written by
 * [BitmapPdfCompiler], using a standard PDF incremental update.
 *
 * Android's PdfDocument/Skia has no Canvas-level way to emit real PDF text
 * objects: a fully transparent Canvas.drawText() is silently dropped rather
 * than producing selectable (but invisible) text. The only correct mechanism
 * is the PDF text-rendering mode 3 ("neither fill nor stroke"), which is a
 * raw content-stream operator with no Canvas equivalent - so this class
 * post-processes the already-written PDF bytes directly instead of drawing.
 *
 * Only text encodable in Latin-1 is injected, since it relies on the
 * standard (unembedded) Helvetica base-14 font, which has no CJK/Arabic/etc.
 * glyphs. Non-Latin OCR text is simply skipped from the exported PDF's
 * selectable layer; it remains available for in-app search via
 * Page.extractedText regardless.
 *
 * This assumes the classic ASCII xref table + trailer that PdfDocument is
 * confirmed (empirically, on-device) to emit - not a compressed xref stream.
 */
class InvisibleTextLayerInjector {

    private val latin1 = Charset.forName("ISO-8859-1")

    fun inject(pdfFile: File, elementsByPage: Map<Int, List<OcrTextElement>>) {
        val encodableByPage = elementsByPage.mapValues { (_, elements) ->
            elements.filter { isLatin1Encodable(it.text) }
        }
        if (encodableByPage.values.all { it.isEmpty() }) return

        val original = pdfFile.readBytes()
        val text = String(original, latin1)

        val trailer = requireNotNull(lastMatch(TRAILER_REGEX, text)) { "no trailer found" }.groupValues[1]
        val rootRef = requireNotNull(ROOT_REGEX.find(trailer)) { "no /Root in trailer" }.groupValues[1]
        val originalSize = requireNotNull(SIZE_REGEX.find(trailer)) { "no /Size in trailer" }.groupValues[1].toInt()
        val startxref = requireNotNull(lastMatch(STARTXREF_REGEX, text)) { "no startxref" }.groupValues[1]
        val kidsObjNums = findKidsObjectNumbers(text)

        val appended = StringBuilder()
        val newObjectOffsets = linkedMapOf<Int, Long>()
        var nextObjNum = originalSize

        fun currentOffset(): Long = original.size + appended.toString().toByteArray(latin1).size.toLong()

        fun appendObject(num: Int, body: String) {
            newObjectOffsets[num] = currentOffset()
            appended.append(num).append(" 0 obj\n").append(body).append("\nendobj\n")
        }

        val fontObjNum = nextObjNum++
        appendObject(fontObjNum, "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>")

        for ((pageIndex, elements) in encodableByPage) {
            if (elements.isEmpty()) continue
            val pageObjNum = kidsObjNums.getOrNull(pageIndex) ?: continue
            val pageBody = findObjectBody(text, pageObjNum) ?: continue

            val contentObjNum = nextObjNum++
            val stream = buildContentStream(elements)
            appendObject(contentObjNum, "<</Length ${stream.toByteArray(latin1).size}>>\nstream\n$stream\nendstream")
            appendObject(pageObjNum, rewritePageBody(pageBody, fontObjNum, contentObjNum))
        }

        val newSize = maxOf(originalSize, nextObjNum)
        val xrefOffset = currentOffset()
        val xref = buildXref(newObjectOffsets)
        val newTrailer = "trailer\n<</Size $newSize\n/Root $rootRef\n/Prev $startxref>>\nstartxref\n$xrefOffset\n%%EOF\n"

        pdfFile.writeBytes(original + appended.toString().toByteArray(latin1) + xref.toByteArray(latin1) + newTrailer.toByteArray(latin1))
    }

    private fun isLatin1Encodable(text: String): Boolean = text.all { it.code in 0..255 }

    private fun findKidsObjectNumbers(text: String): List<Int> {
        val kidsMatch = KIDS_REGEX.find(text) ?: return emptyList()
        return OBJ_REF_REGEX.findAll(kidsMatch.groupValues[1]).map { it.groupValues[1].toInt() }.toList()
    }

    private fun findObjectBody(text: String, objNum: Int): String? {
        val header = "\n$objNum 0 obj\n"
        val start = text.indexOf(header)
        if (start == -1) return null
        val bodyStart = start + header.length
        val end = text.indexOf("\nendobj", bodyStart)
        if (end == -1) return null
        return text.substring(bodyStart, end)
    }

    private fun rewritePageBody(body: String, fontObjNum: Int, contentObjNum: Int): String {
        val withFont = body.replaceFirst("/Resources <<", "/Resources <</Font <</F1 $fontObjNum 0 R>>")
        return CONTENTS_REGEX.replace(withFont) { match ->
            "/Contents [${match.groupValues[1]} 0 R $contentObjNum 0 R]"
        }
    }

    private fun buildContentStream(elements: List<OcrTextElement>): String {
        val builder = StringBuilder()
        for (element in elements) {
            builder.append("BT\n3 Tr\n")
            builder.append("/F1 ${formatNumber(element.fontSize)} Tf\n")
            builder.append("1 0 0 1 ${formatNumber(element.x)} ${formatNumber(element.y)} Tm\n")
            builder.append("(${escapePdfString(element.text)}) Tj\nET\n")
        }
        return builder.toString()
    }

    private fun escapePdfString(input: String): String =
        input.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    private fun formatNumber(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    private fun buildXref(offsets: Map<Int, Long>): String {
        val builder = StringBuilder("xref\n")
        for ((num, offset) in offsets) {
            builder.append(num).append(" 1\n")
            builder.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        }
        return builder.toString()
    }

    private fun lastMatch(regex: Regex, text: String) = regex.findAll(text).lastOrNull()

    companion object {
        private val TRAILER_REGEX = Regex("trailer\\s*<<(.*?)>>", RegexOption.DOT_MATCHES_ALL)
        private val ROOT_REGEX = Regex("/Root\\s+(\\d+\\s+\\d+\\s+R)")
        private val SIZE_REGEX = Regex("/Size\\s+(\\d+)")
        private val STARTXREF_REGEX = Regex("startxref\\s+(\\d+)")
        private val KIDS_REGEX = Regex("/Kids\\s*\\[([^]]*)]")
        private val OBJ_REF_REGEX = Regex("(\\d+)\\s+0\\s+R")
        private val CONTENTS_REGEX = Regex("/Contents (\\d+) 0 R")
    }
}
