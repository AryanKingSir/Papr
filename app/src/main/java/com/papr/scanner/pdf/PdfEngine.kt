package com.papr.scanner.pdf

import com.papr.scanner.pdf.security.PdfEncryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [ownerPassword] defaults to [userPassword] when blank, per the Standard Security Handler. */
data class PasswordProtection(val userPassword: String, val ownerPassword: String = "")

data class PdfGenerationOptions(
    val ocrEnabled: Boolean = false,
    val overlaysByPageIndex: Map<Int, PageOverlays> = emptyMap(),
    val passwordProtection: PasswordProtection? = null,
    val targetSizeBytes: Long? = null,
)

class PdfEngine(
    private val bitmapPdfCompiler: BitmapPdfCompiler,
    private val ocrTextStamper: OcrTextStamper,
    private val pageOverlayRenderer: PageOverlayRenderer,
    private val invisibleTextLayerInjector: InvisibleTextLayerInjector,
    private val pdfEncryptor: PdfEncryptor,
) {

    suspend fun generatePdf(
        pageFiles: List<File>,
        outputFile: File,
        options: PdfGenerationOptions = PdfGenerationOptions(),
    ): CompiledPdf = withContext(Dispatchers.IO) {
        val ocrElementsByPage = mutableMapOf<Int, List<OcrTextElement>>()
        // Each page is recognized once, on the first build, at its best resolution, and kept in
        // units of that bitmap's width: a compression attempt shrinks the pages, and recognizing
        // the shrunken images would lose words (and repeat the slow part on every attempt).
        val recognizedByPage = mutableMapOf<Int, List<OcrTextElement>>()

        bitmapPdfCompiler.compile(
            pageFiles,
            outputFile,
            options.targetSizeBytes,
            preparePage = { canvas, bitmap, pageIndex ->
                // Drawn into the page image's own pixels rather than layered on top
                // as separate PDF objects: a mark can't be lifted off in a PDF editor
                // to reveal what's beneath, overlays shrink with a compression target,
                // and the mark's label font never has to be embedded in the file.
                options.overlaysByPageIndex[pageIndex]?.let { overlays ->
                    pageOverlayRenderer.draw(canvas, overlays, bitmap.width, bitmap.height)
                }
            },
            onPageDrawn = { bitmap, pageIndex, placement ->
                if (options.ocrEnabled) {
                    // Runs on the already-marked image, so text hidden by a confidential
                    // mark is never recognized into the selectable text layer.
                    // Injected as raw PDF operators, bypassing Canvas entirely - so
                    // placement applies the page's fit transform by hand (and flips Y,
                    // since PDF space is bottom-up while OcrTextStamper's boxes are top-down).
                    val recognized = recognizedByPage.getOrPut(pageIndex) {
                        ocrTextStamper.recognize(bitmap).map { it.scaledBy(1f / bitmap.width) }
                    }
                    ocrElementsByPage[pageIndex] = recognized.map { it.scaledBy(bitmap.width.toFloat()) }.map { element ->
                        element.copy(
                            x = placement.toPdfX(element.x),
                            y = placement.toPdfY(element.y),
                            fontSize = placement.toPdfLength(element.fontSize),
                            width = placement.toPdfLength(element.width),
                        )
                    }
                }
            },
            finish = { pdf ->
                // Runs on every compression attempt, before the file is measured, so the
                // text layer and encryption count toward the target size.
                if (options.ocrEnabled) {
                    invisibleTextLayerInjector.inject(pdf, ocrElementsByPage)
                }

                // Must run last: encryption turns every other object's bytes into
                // ciphertext, so any later structural edit (OCR injection, etc.)
                // would corrupt the file.
                options.passwordProtection?.let { protection ->
                    pdfEncryptor.encrypt(pdf, protection.userPassword, protection.ownerPassword)
                }
            },
        )
    }
}

private fun OcrTextElement.scaledBy(factor: Float) =
    copy(x = x * factor, y = y * factor, fontSize = fontSize * factor, width = width * factor)
