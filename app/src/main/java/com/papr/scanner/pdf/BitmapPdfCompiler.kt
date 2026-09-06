package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BitmapPdfCompiler {

    suspend fun compile(
        pageFiles: List<File>,
        outputFile: File,
        onPageDrawn: suspend (canvas: Canvas, bitmap: Bitmap, pageIndex: Int) -> Unit = { _, _, _ -> },
    ): File =
        withContext(Dispatchers.IO) {
            val document = PdfDocument()
            try {
                pageFiles.forEachIndexed { index, pageFile ->
                    val bitmap = BitmapFactory.decodeFile(pageFile.absolutePath)
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    onPageDrawn(page.canvas, bitmap, index)
                    document.finishPage(page)
                    bitmap.recycle()
                }
                outputFile.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
            outputFile
        }
}
