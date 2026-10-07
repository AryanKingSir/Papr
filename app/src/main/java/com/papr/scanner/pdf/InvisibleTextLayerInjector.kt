package com.papr.scanner.pdf

import java.io.File
import java.io.FileOutputStream
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
 * Only text WinAnsiEncoding can write is injected (Latin-1 plus the euro sign,
 * curly quotes, dashes, bullet and ellipsis), since it relies on the
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

    // The font's /Encoding is WinAnsiEncoding, which is windows-1252.
    private val winAnsi = Charset.forName("windows-1252")

    fun inject(pdfFile: File, elementsByPage: Map<Int, List<OcrTextElement>>) {
        val encodableByPage = elementsByPage.mapValues { (_, elements) ->
            elements.filter { winAnsi.newEncoder().canEncode(it.text) }
        }
        if (encodableByPage.values.all { it.isEmpty() }) return

        // Only searched, through a view of the bytes, and the new objects are appended: a large
        // export never needs a second copy of itself in memory.
        val original = pdfFile.readBytes()
        val text = Latin1Text(original)

        val trailer = requireNotNull(lastMatch(TRAILER_REGEX, text)) { "no trailer found" }.groupValues[1]
        val rootRef = requireNotNull(ROOT_REGEX.find(trailer)) { "no /Root in trailer" }.groupValues[1]
        val originalSize = requireNotNull(SIZE_REGEX.find(trailer)) { "no /Size in trailer" }.groupValues[1].toInt()
        val startxref = requireNotNull(lastMatch(STARTXREF_REGEX, text)) { "no startxref" }.groupValues[1]
        val pageObjNums = findPageObjectNumbers(text, rootRef)

        val appended = StringBuilder()
        val newObjectOffsets = linkedMapOf<Int, Long>()
        var nextObjNum = originalSize

        fun currentOffset(): Long = original.size + appended.toString().toByteArray(latin1).size.toLong()

        fun appendObject(num: Int, body: String) {
            newObjectOffsets[num] = currentOffset()
            appended.append(num).append(" 0 obj\n").append(body).append("\nendobj\n")
        }

        val fontObjNum = nextObjNum++
        appendObject(fontObjNum, "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>")

        // The streams in a /Contents array share graphics state, and Skia opens each
        // page stream with a y-flipping `cm` it never restores (seen on Android 14) -
        // so the page's own stream is bracketed in q ... Q, or the text layer would
        // inherit the flip and land mirrored on the page.
        val saveStateObjNum = nextObjNum++
        appendObject(saveStateObjNum, "<</Length 1>>\nstream\nq\nendstream")

        for ((pageIndex, elements) in encodableByPage) {
            if (elements.isEmpty()) continue
            val pageObjNum = pageObjNums.getOrNull(pageIndex) ?: continue
            val pageBody = findObjectBody(text, pageObjNum) ?: continue

            val contentObjNum = nextObjNum++
            val stream = buildContentStream(elements)
            appendObject(contentObjNum, "<</Length ${stream.toByteArray(latin1).size}>>\nstream\n$stream\nendstream")
            appendObject(pageObjNum, rewritePageBody(pageBody, fontObjNum, saveStateObjNum, contentObjNum))
        }

        val newSize = maxOf(originalSize, nextObjNum)
        val xrefOffset = currentOffset()
        val xref = buildXref(newObjectOffsets)
        val newTrailer = "trailer\n<</Size $newSize\n/Root $rootRef\n/Prev $startxref>>\nstartxref\n$xrefOffset\n%%EOF\n"

        FileOutputStream(pdfFile, true).use { out ->
            out.write(appended.toString().toByteArray(latin1))
            out.write(xref.toByteArray(latin1))
            out.write(newTrailer.toByteArray(latin1))
        }
    }

    /**
     * Every page's object number, in page order. Skia splits the page tree into
     * nodes of at most 8 pages, so beyond that the root's /Kids are intermediate
     * /Pages nodes rather than pages - walk the tree instead of reading one /Kids.
     */
    private fun findPageObjectNumbers(text: CharSequence, rootRef: String): List<Int> {
        val catalog = findObjectBody(text, objectNumber(rootRef)) ?: return emptyList()
        val pagesRef = PAGES_REF_REGEX.find(catalog) ?: return emptyList()
        val pages = mutableListOf<Int>()
        fun walk(objNum: Int, depth: Int) {
            val body = findObjectBody(text, objNum) ?: return
            val kids = KIDS_REGEX.find(body)
            if (kids == null || depth > MAX_PAGE_TREE_DEPTH) {
                pages += objNum
                return
            }
            OBJ_REF_REGEX.findAll(kids.groupValues[1]).forEach { walk(it.groupValues[1].toInt(), depth + 1) }
        }
        walk(pagesRef.groupValues[1].toInt(), 0)
        return pages
    }

    private fun objectNumber(ref: String): Int = ref.trim().substringBefore(' ').toInt()

    private fun findObjectBody(text: CharSequence, objNum: Int): String? {
        val header = "\n$objNum 0 obj\n"
        val start = text.indexOf(header)
        if (start == -1) return null
        val bodyStart = start + header.length
        val end = text.indexOf("\nendobj", bodyStart)
        if (end == -1) return null
        return text.subSequence(bodyStart, end).toString()
    }

    private fun rewritePageBody(body: String, fontObjNum: Int, saveStateObjNum: Int, contentObjNum: Int): String {
        val withFont = body.replaceFirst("/Resources <<", "/Resources <</Font <</F1 $fontObjNum 0 R>>")
        return CONTENTS_REGEX.replace(withFont) { match ->
            "/Contents [$saveStateObjNum 0 R ${match.groupValues[1]} 0 R $contentObjNum 0 R]"
        }
    }

    private fun buildContentStream(elements: List<OcrTextElement>): String {
        // Restores the state saved before the page's own stream (see inject).
        val builder = StringBuilder("Q\n")
        for (element in elements) {
            val horizontalScale = horizontalScalePercent(element.text, element.fontSize, element.width)
            builder.append("BT\n3 Tr\n")
            builder.append("/F1 ${formatNumber(element.fontSize)} Tf\n")
            builder.append("${formatNumber(horizontalScale)} Tz\n")
            builder.append("1 0 0 1 ${formatNumber(element.x)} ${formatNumber(element.y)} Tm\n")
            builder.append("(${escapePdfString(toWinAnsiCodes(element.text))}) Tj\nET\n")
        }
        return builder.toString()
    }

    /**
     * [text] with each character replaced by the char whose code is its WinAnsi byte, so that
     * writing the stream as Latin-1 (as the whole file is) puts the right byte in the PDF -
     * the euro sign becomes 0x80 rather than a '?'.
     */
    private fun toWinAnsiCodes(text: String): String = String(text.toByteArray(winAnsi), latin1)

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

    private fun lastMatch(regex: Regex, text: CharSequence) = regex.findAll(text).lastOrNull()

    companion object {
        private val TRAILER_REGEX = Regex("trailer\\s*<<(.*?)>>", RegexOption.DOT_MATCHES_ALL)
        private val ROOT_REGEX = Regex("/Root\\s+(\\d+\\s+\\d+\\s+R)")
        private val SIZE_REGEX = Regex("/Size\\s+(\\d+)")
        private val STARTXREF_REGEX = Regex("startxref\\s+(\\d+)")
        private val KIDS_REGEX = Regex("/Kids\\s*\\[([^]]*)]")
        private val PAGES_REF_REGEX = Regex("/Pages\\s+(\\d+)\\s+0\\s+R")
        private const val MAX_PAGE_TREE_DEPTH = 32
        private val OBJ_REF_REGEX = Regex("(\\d+)\\s+0\\s+R")
        private val CONTENTS_REGEX = Regex("/Contents (\\d+) 0 R")
    }
}

/**
 * Real Adobe Helvetica character widths (1/1000 em units) for the printable
 * ASCII range (32-126), fetched from the published Helvetica.afm font
 * metrics - covers the large majority of real-world OCR'd English text.
 * Index 0 is code 32 (space).
 */
private val HELVETICA_ASCII_WIDTHS = intArrayOf(
    278, 278, 355, 556, 556, 889, 667, 222, 333, 333, 389, 584, 278, 333, 278, 278,
    556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
    1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
    667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
    222, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
    556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
)

private const val HELVETICA_FIRST_CODE = 32

/** The real average of [HELVETICA_ASCII_WIDTHS], used as a fallback for any character outside 32-126 (e.g. accented Latin). */
private const val HELVETICA_AVERAGE_WIDTH = 526

private const val MIN_HORIZONTAL_SCALE = 25f
private const val MAX_HORIZONTAL_SCALE = 400f

/** Helvetica's width for [code] (1/1000 em units), or the table's average outside the mapped ASCII range. */
internal fun helveticaWidth(code: Int): Int {
    val index = code - HELVETICA_FIRST_CODE
    return if (index in HELVETICA_ASCII_WIDTHS.indices) HELVETICA_ASCII_WIDTHS[index] else HELVETICA_AVERAGE_WIDTH
}

/**
 * The PDF `Tz` horizontal-scaling percentage that makes [text], rendered in
 * Helvetica at [fontSize], visually span [targetWidth] instead of its
 * natural width - so invisible selectable text lines up with the real
 * printed word underneath it, which is essentially never actually set in
 * Helvetica. Clamped to a sane range so a bad OCR box can't produce
 * illegible-if-visible text.
 */
internal fun horizontalScalePercent(text: String, fontSize: Float, targetWidth: Float): Float {
    val totalUnits = text.map { helveticaWidth(it.code) }.sum()
    val naturalWidth = totalUnits / 1000f * fontSize
    if (naturalWidth <= 0f) return 100f
    return (targetWidth / naturalWidth * 100f).coerceIn(MIN_HORIZONTAL_SCALE, MAX_HORIZONTAL_SCALE)
}
