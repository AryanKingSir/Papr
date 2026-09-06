package com.papr.scanner.pdf

import java.io.File

class PdfEngine(private val bitmapPdfCompiler: BitmapPdfCompiler) {

    suspend fun generatePdf(pageFiles: List<File>, outputFile: File): File =
        bitmapPdfCompiler.compile(pageFiles, outputFile)
}
