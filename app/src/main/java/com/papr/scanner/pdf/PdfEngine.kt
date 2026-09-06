package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File

data class SignaturePlacement(val bitmap: Bitmap, val pageIndex: Int, val rect: Rect)

data class PdfGenerationOptions(
    val ocrEnabled: Boolean = false,
    val signature: SignaturePlacement? = null,
)

class PdfEngine(
    private val bitmapPdfCompiler: BitmapPdfCompiler,
    private val ocrTextStamper: OcrTextStamper,
    private val signatureStamper: SignatureStamper,
    private val invisibleTextLayerInjector: InvisibleTextLayerInjector,
) {

    suspend fun generatePdf(
        pageFiles: List<File>,
        outputFile: File,
        options: PdfGenerationOptions = PdfGenerationOptions(),
    ): File {
        val ocrElementsByPage = mutableMapOf<Int, List<OcrTextElement>>()

        bitmapPdfCompiler.compile(pageFiles, outputFile) { canvas, bitmap, pageIndex ->
            if (options.ocrEnabled) {
                ocrElementsByPage[pageIndex] = ocrTextStamper.recognize(bitmap)
            }
            options.signature?.let { placement ->
                if (placement.pageIndex == pageIndex) {
                    signatureStamper.stamp(canvas, placement.bitmap, placement.rect)
                }
            }
        }

        if (options.ocrEnabled) {
            invisibleTextLayerInjector.inject(outputFile, ocrElementsByPage)
        }

        return outputFile
    }
}
