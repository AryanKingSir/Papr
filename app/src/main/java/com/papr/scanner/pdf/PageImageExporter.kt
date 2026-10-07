package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import com.papr.scanner.core.FileUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exports pages as JPG images, with their markup, signature and confidential marks drawn into the pixels. */
class PageImageExporter(private val overlayRenderer: PageOverlayRenderer) {

    /**
     * Writes one JPG per page into [outputDir] (emptied first, so a previous
     * export never lingers), named "[baseName].jpg" for a single page or
     * "[baseName]-1.jpg", "[baseName]-2.jpg"... otherwise. One page is held in
     * memory at a time.
     */
    suspend fun export(pages: List<Pair<File, PageOverlays>>, outputDir: File, baseName: String): List<File> =
        withContext(Dispatchers.IO) {
            outputDir.deleteRecursively()
            outputDir.mkdirs()
            pages.mapIndexed { index, (pageFile, overlays) ->
                ensureActive()
                val bitmap = FileUtils.decodeDownsampled(pageFile, MAX_IMAGE_DIMENSION, mutable = true)
                try {
                    overlayRenderer.draw(Canvas(bitmap), overlays, bitmap.width, bitmap.height)
                    val name = if (pages.size == 1) "$baseName.jpg" else "$baseName-${index + 1}.jpg"
                    File(outputDir, name).also { file ->
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

    private companion object {
        /** Same longest-side cap as the PDF export, so images and PDF pages come out alike. */
        const val MAX_IMAGE_DIMENSION = 3000
        const val JPEG_QUALITY = 90
    }
}
