# PDF Pipeline Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the signature-placement coordinate bug and redesign compress-to-target-size to measure real output instead of a JPEG-quality proxy that doesn't reflect what `PdfDocument` actually embeds.

**Architecture:** Signature placement moves from a pre-computed `Rect` (guessed against the wrong dimensions) to computing the Rect at the exact point the real per-page bitmap dimensions are known. Compression moves from a per-page JPEG-quality search to a whole-document iterative rebuild-and-measure loop driven by real `File.length()` feedback.

**Tech Stack:** Kotlin, `android.graphics`/`android.graphics.pdf.PdfDocument`, JUnit (plain, no Robolectric — this project has none).

## Global Constraints

- No unrelated refactors — every changed line traces to one of this plan's two fixes (per project CLAUDE.md).
- No new dependencies.
- Page-size consistency (`uniformPageSizePx` producing one shared page size) is NOT touched by this plan — it already reads as correct. Don't "improve" it while in this file for other reasons.
- This project has no Robolectric/instrumented tests — only plain JUnit tests of pure logic exist (`FileUtilsTest.kt`, `CompressionIteratorTest.kt`). Anything that needs real `android.graphics`/`PdfDocument` behavior is verified by code reasoning during implementation, plus the on-device checklist in Task 3.
- Read each file's current on-disk content before editing it — don't assume line numbers from this plan match exactly if other work has touched these files since this plan was written.

---

### Task 1: Fix signature placement to use real per-page dimensions

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/pdf/SignatureStamper.kt`
- Modify: `app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SignatureStamper.stamp(canvas: Canvas, signature: Bitmap, pageBitmapWidth: Int, pageBitmapHeight: Int)` (replaces the old `stamp(canvas, signature, rect)`); `SignaturePlacement(bitmap: Bitmap, pageIndex: Int)` (drops the `rect` field) — both used by Task 2's `BitmapPdfCompiler` work only incidentally (Task 2 doesn't change the signature-drawing call site), but any future work touching signatures must use these new shapes.

- [ ] **Step 1: Move the placement math into `SignatureStamper`**

Read `app/src/main/java/com/papr/scanner/pdf/SignatureStamper.kt` first to confirm its current content, then replace the whole file with:

```kotlin
package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF

class SignatureStamper {

    /**
     * Stamps [signature] into the bottom-right corner of a page whose drawn
     * bitmap is [pageBitmapWidth]x[pageBitmapHeight] - the caller's own final
     * dimensions for that page, not the original scan file's dimensions,
     * since a page's drawn bitmap can be downsampled or further shrunk for a
     * compression target.
     */
    fun stamp(canvas: Canvas, signature: Bitmap, pageBitmapWidth: Int, pageBitmapHeight: Int) {
        val width = pageBitmapWidth / 3
        val height = (width * signature.height / signature.width.toFloat()).toInt().coerceAtLeast(1)
        val rect = Rect(
            pageBitmapWidth - width - MARGIN_PX,
            pageBitmapHeight - height - MARGIN_PX,
            pageBitmapWidth - MARGIN_PX,
            pageBitmapHeight - MARGIN_PX,
        )
        canvas.drawBitmap(signature, null, RectF(rect), null)
    }

    private companion object {
        const val MARGIN_PX = 40
    }
}
```

- [ ] **Step 2: Update `SignaturePlacement` and its call site in `PdfEngine`**

Read `app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt` first to confirm its current content. Change:
```kotlin
import android.graphics.Bitmap
import android.graphics.Rect
import com.papr.scanner.pdf.security.PdfEncryptor
```
to:
```kotlin
import android.graphics.Bitmap
import com.papr.scanner.pdf.security.PdfEncryptor
```
(dropping the now-unused `Rect` import).

Change:
```kotlin
data class SignaturePlacement(val bitmap: Bitmap, val pageIndex: Int, val rect: Rect)
```
to:
```kotlin
data class SignaturePlacement(val bitmap: Bitmap, val pageIndex: Int)
```

Change:
```kotlin
            options.signature?.let { placement ->
                if (placement.pageIndex == pageIndex) {
                    signatureStamper.stamp(canvas, placement.bitmap, placement.rect)
                }
            }
```
to:
```kotlin
            options.signature?.let { placement ->
                if (placement.pageIndex == pageIndex) {
                    signatureStamper.stamp(canvas, placement.bitmap, bitmap.width, bitmap.height)
                }
            }
```

- [ ] **Step 3: Remove the now-obsolete `buildSignaturePlacement` from `DocumentDetailViewModel`**

Read `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt` first to confirm its current content. Change the signature-building line inside `export`:
```kotlin
                    val signature = state.pendingSignature
                        ?.takeIf { settings.signatureEnabled }
                        ?.let { pending -> buildSignaturePlacement(pending, pageFiles) }
```
to:
```kotlin
                    val signature = state.pendingSignature
                        ?.takeIf { settings.signatureEnabled }
                        ?.let { pending -> SignaturePlacement(pending.bitmap, pending.pageIndex) }
```

Delete the whole `buildSignaturePlacement` function, i.e. change:
```kotlin
    private suspend fun buildSignaturePlacement(pending: PendingSignature, pageFiles: List<File>): SignaturePlacement? {
        val pageFile = pageFiles.getOrNull(pending.pageIndex) ?: return null
        val bounds = withContext(Dispatchers.IO) {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeFile(pageFile.absolutePath, it) }
        }
        val width = bounds.outWidth / 3
        val height = (width * pending.bitmap.height / pending.bitmap.width.toFloat()).toInt().coerceAtLeast(1)
        val rect = Rect(
            bounds.outWidth - width - MARGIN_PX,
            bounds.outHeight - height - MARGIN_PX,
            bounds.outWidth - MARGIN_PX,
            bounds.outHeight - MARGIN_PX,
        )
        return SignaturePlacement(pending.bitmap, pending.pageIndex, rect)
    }

    /** Bumps updatedAt so the home grid's thumbnail (same file path, new bytes) also refreshes when we return to it. */
    private suspend fun touchDocument() {
```
to:
```kotlin
    /** Bumps updatedAt so the home grid's thumbnail (same file path, new bytes) also refreshes when we return to it. */
    private suspend fun touchDocument() {
```

Remove the now-unused imports `android.graphics.BitmapFactory` and `android.graphics.Rect` from the top of the file (keep `android.graphics.Bitmap` — it's still used by `setPendingSignature(bitmap: Bitmap)`).

Remove the now-unused `MARGIN_PX` constant from the file's private companion object:
```kotlin
    private companion object {
        const val MARGIN_PX = 40
        const val BYTES_PER_MB = 1024 * 1024
    }
```
becomes:
```kotlin
    private companion object {
        const val BYTES_PER_MB = 1024 * 1024
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (no unresolved references, no unused-import warnings for the ones just removed).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/papr/scanner/pdf/SignatureStamper.kt app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt
git commit -m "$(cat <<'EOF'
Fix signature placement to use the page's real drawn dimensions

buildSignaturePlacement computed the signature's Rect against the
original full-resolution file bounds, but BitmapPdfCompiler draws a
downsampled (and possibly further-shrunk) bitmap - any scan over 3000px
on its long side got a signature positioned outside the actual page.
The Rect is now computed by SignatureStamper at the point the real
per-page bitmap dimensions are known, inside BitmapPdfCompiler's own
draw callback.
EOF
)"
```

---

### Task 2: Redesign compress-to-target-size to measure real output

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/pdf/BitmapPdfCompiler.kt`
- Delete: `app/src/main/java/com/papr/scanner/pdf/CompressionIterator.kt`
- Delete: `app/src/test/java/com/papr/scanner/pdf/CompressionIteratorTest.kt`
- Test: `app/src/test/java/com/papr/scanner/pdf/BitmapPdfCompilerScaleTest.kt`

**Interfaces:**
- Consumes: `BitmapPdfCompiler`'s public `compile(pageFiles, outputFile, targetSizeBytes, onPageDrawn)` signature is unchanged — `PdfEngine.kt` (already fixed in Task 1) calls it exactly as before, no changes needed there for this task.
- Produces: `internal fun nextScale(currentScale: Float, targetBytes: Long, actualBytes: Long): Float`, a top-level function in `BitmapPdfCompiler.kt`, used only by `compile()` internally and by this task's own test.

- [ ] **Step 1: Write the failing test for the scale-search math**

Create `app/src/test/java/com/papr/scanner/pdf/BitmapPdfCompilerScaleTest.kt`:

```kotlin
package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapPdfCompilerScaleTest {

    @Test
    fun `halves the scale when actual size is four times the target`() {
        val result = nextScale(currentScale = 1f, targetBytes = 1_000_000, actualBytes = 4_000_000)

        assertEquals(0.5f, result, 0.001f)
    }

    @Test
    fun `returns the same scale when actual size already equals the target`() {
        val result = nextScale(currentScale = 1f, targetBytes = 1_000_000, actualBytes = 1_000_000)

        assertEquals(1f, result, 0.001f)
    }

    @Test
    fun `compounds with the current scale rather than resetting it`() {
        val result = nextScale(currentScale = 0.5f, targetBytes = 1_000_000, actualBytes = 4_000_000)

        assertEquals(0.25f, result, 0.001f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.BitmapPdfCompilerScaleTest"`
Expected: FAIL to compile — `nextScale` is unresolved (it doesn't exist yet).

- [ ] **Step 3: Add `nextScale` to `BitmapPdfCompiler.kt`**

Read `app/src/main/java/com/papr/scanner/pdf/BitmapPdfCompiler.kt` first to confirm its current content (this file still has the old per-page JPEG-quality-driven `recompress`/`CompressionIterator`-based implementation at this point — that's expected, Step 5 replaces it).

Change the import line:
```kotlin
import kotlin.math.roundToInt
```
to:
```kotlin
import kotlin.math.roundToInt
import kotlin.math.sqrt
```

The file ends with the class's closing brace:
```kotlin
        private const val MIN_RECOMPRESS_DIMENSION = 300
    }
}
```
Append this top-level function immediately after that final `}` (i.e. after the class, not inside it):
```kotlin
        private const val MIN_RECOMPRESS_DIMENSION = 300
    }
}

/** Raster output size scales roughly with pixel area, so scaling by sqrt(target/actual) converges in a few rounds instead of a fixed-step search. */
internal fun nextScale(currentScale: Float, targetBytes: Long, actualBytes: Long): Float =
    currentScale * sqrt(targetBytes.toDouble() / actualBytes).toFloat()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.BitmapPdfCompilerScaleTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Replace `BitmapPdfCompiler`'s compression mechanism with the iterative rebuild-and-measure loop**

Read `app/src/main/java/com/papr/scanner/pdf/BitmapPdfCompiler.kt` again to get its exact current content (it now has the `nextScale` function added at the bottom from Step 3, plus the original class body). Replace the entire file with:

```kotlin
package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BitmapPdfCompiler {

    suspend fun compile(
        pageFiles: List<File>,
        outputFile: File,
        targetSizeBytes: Long? = null,
        // pageWidthPx/pageHeightPx is the shared page size (see below) - callers
        // that don't draw through the given Canvas (e.g. raw PDF content-stream
        // injection, which bypasses Canvas's own transform) need it to place
        // content relative to the actual page rather than this page's own,
        // possibly smaller/off-center, bitmap.
        onPageDrawn: suspend (canvas: Canvas, bitmap: Bitmap, pageIndex: Int, pageWidthPx: Int, pageHeightPx: Int) -> Unit =
            { _, _, _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        // PdfDocument gives no direct control over embedded image size or
        // quality - the only lever that actually changes the written file's
        // size is the pixel dimensions of what's drawn on each page. So
        // hitting a target size means actually building the PDF, measuring
        // its real size, and - if it's over - shrinking every page's
        // dimensions and rebuilding, rather than guessing from an
        // encoded-byte-count proxy that has no reliable relationship to what
        // PdfDocument actually writes.
        var scale = 1f
        for (attempt in 0 until MAX_ATTEMPTS) {
            writePdf(pageFiles, outputFile, scale, onPageDrawn)
            // Written as two direct checks (not one `val` combining them) so the
            // compiler can still smart-cast targetSizeBytes to non-null below -
            // smart-casting doesn't see through an intermediate Boolean val.
            if (targetSizeBytes == null || outputFile.length() <= targetSizeBytes) break
            if (attempt == MAX_ATTEMPTS - 1) break
            scale = nextScale(scale, targetSizeBytes, outputFile.length())
        }
        outputFile
    }

    private suspend fun writePdf(
        pageFiles: List<File>,
        outputFile: File,
        scale: Float,
        onPageDrawn: suspend (canvas: Canvas, bitmap: Bitmap, pageIndex: Int, pageWidthPx: Int, pageHeightPx: Int) -> Unit,
    ) {
        val document = PdfDocument()
        try {
            // All pages share one physical page size (the largest decoded
            // page, post-downsample-cap, post-target-scale) and are centered
            // within it, so a multi-page scan of e.g. a book doesn't end up
            // with a differently-sized PDF page per page just because each
            // scan's pixel dimensions differ slightly.
            val (pageWidthPx, pageHeightPx) = uniformPageSizePx(pageFiles, scale)
            val pageWidthPt = (pageWidthPx * POINTS_PER_PIXEL).roundToInt()
            val pageHeightPt = (pageHeightPx * POINTS_PER_PIXEL).roundToInt()
            pageFiles.forEachIndexed { index, pageFile ->
                val bitmap = decodeScaled(pageFile, scale)
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, index + 1).create()
                val page = document.startPage(pageInfo)
                page.canvas.scale(POINTS_PER_PIXEL, POINTS_PER_PIXEL)
                // Center this page's (possibly smaller than the shared size)
                // bitmap. Anything the caller draws through this same Canvas
                // (e.g. the signature) is automatically kept aligned with the
                // image by this translate; anything NOT drawn through Canvas
                // needs pageWidthPx/pageHeightPx (passed below) to place
                // itself relative to the page instead.
                page.canvas.translate(
                    (pageWidthPx - bitmap.width) / 2f,
                    (pageHeightPx - bitmap.height) / 2f,
                )
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                onPageDrawn(page.canvas, bitmap, index, pageWidthPx, pageHeightPx)
                document.finishPage(page)
                bitmap.recycle()
            }
            outputFile.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    /** The largest post-downsample-cap, post-[scale] page dimensions across the document, used as the uniform PDF page size. */
    private fun uniformPageSizePx(pageFiles: List<File>, scale: Float): Pair<Int, Int> {
        var maxWidth = 1
        var maxHeight = 1
        for (pageFile in pageFiles) {
            val bounds = decodeBounds(pageFile)
            val sampleSize = downsampleFactorFor(bounds)
            maxWidth = maxOf(maxWidth, bounds.outWidth / sampleSize)
            maxHeight = maxOf(maxHeight, bounds.outHeight / sampleSize)
        }
        return scaledDimension(maxWidth, scale) to scaledDimension(maxHeight, scale)
    }

    /** Caps decoded page resolution so a multi-page export of very high-res scans can't exhaust the heap, then applies [scale] on top (used to hit a compression target). */
    private fun decodeScaled(pageFile: File, scale: Float): Bitmap {
        val downsampled = decodeDownsampled(pageFile)
        if (scale >= 1f) return downsampled
        val scaled = Bitmap.createScaledBitmap(
            downsampled,
            scaledDimension(downsampled.width, scale),
            scaledDimension(downsampled.height, scale),
            true,
        )
        if (scaled !== downsampled) downsampled.recycle()
        return scaled
    }

    private fun decodeDownsampled(pageFile: File): Bitmap {
        val sampleSize = downsampleFactorFor(decodeBounds(pageFile))
        return BitmapFactory.decodeFile(
            pageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Failed to decode bitmap: ${pageFile.absolutePath}")
    }

    private fun decodeBounds(pageFile: File): BitmapFactory.Options =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(pageFile.absolutePath, it) }

    private fun downsampleFactorFor(bounds: BitmapFactory.Options): Int {
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        return if (maxDimension > MAX_PAGE_DIMENSION) maxOf(1, maxDimension / MAX_PAGE_DIMENSION) else 1
    }

    private fun scaledDimension(dimension: Int, scale: Float): Int =
        (dimension * scale).roundToInt().coerceAtLeast(MIN_DIMENSION)

    companion object {
        /**
         * A scanned page's pixel dimensions can't be used directly as PDF point
         * dimensions - PdfDocument.PageInfo is in points (1/72 inch), so a
         * ~2000px-wide scan would otherwise become a ~28-inch-wide page,
         * appearing absurdly zoomed in to most viewers. Scale down assuming a
         * nominal 200 DPI capture, which lands page size in a normal
         * letter/A4-ish range regardless of a given scan's true resolution.
         */
        const val POINTS_PER_PIXEL: Float = 72f / 200f

        /** Longest-side cap on decoded page bitmaps, to bound peak memory during multi-page export. */
        private const val MAX_PAGE_DIMENSION = 3000

        /** How many times [compile] will rebuild the whole document while searching for a target size. */
        private const val MAX_ATTEMPTS = 6

        /** Floor on any page dimension, so an unreasonably small target can't shrink a page to nothing. */
        private const val MIN_DIMENSION = 300
    }
}

/** Raster output size scales roughly with pixel area, so scaling by sqrt(target/actual) converges in a few rounds instead of a fixed-step search. */
internal fun nextScale(currentScale: Float, targetBytes: Long, actualBytes: Long): Float =
    currentScale * sqrt(targetBytes.toDouble() / actualBytes).toFloat()
```

(This replaces the `nextScale` placement from Step 3 too — it now lives at the bottom of the fully-rewritten file, unchanged in content from Step 3.)

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Delete the now-unused `CompressionIterator` and its test**

```bash
rm app/src/main/java/com/papr/scanner/pdf/CompressionIterator.kt
rm app/src/test/java/com/papr/scanner/pdf/CompressionIteratorTest.kt
```

- [ ] **Step 8: Compile and run the full unit test suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (including the 3 new `BitmapPdfCompilerScaleTest` tests; `CompressionIteratorTest` no longer exists to run).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/papr/scanner/pdf/BitmapPdfCompiler.kt app/src/test/java/com/papr/scanner/pdf/BitmapPdfCompilerScaleTest.kt app/src/main/java/com/papr/scanner/pdf/CompressionIterator.kt app/src/test/java/com/papr/scanner/pdf/CompressionIteratorTest.kt
git commit -m "$(cat <<'EOF'
Redesign compress-to-target-size to measure real PDF output, not a JPEG proxy

PdfDocument doesn't preserve JPEG quality when a bitmap is drawn onto
its canvas - the embedded size tracks pixel dimensions, not the quality
byte-count CompressionIterator was using as a stand-in. That mismatch
is why a 1MB target produced an 11MB file. compile() now writes the
whole PDF, checks its real file size, and rebuilds at a smaller global
scale (computed from the actual/target ratio) until it's within target
or attempts run out. CompressionIterator is unused by the new design.
EOF
)"
```

---

### Task 3: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full Gradle check**

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL` for all four, 0 lint errors, all unit tests pass.

- [ ] **Step 2: Manual on-device verification checklist**

No device/emulator is available in this environment (same constraint as sub-project 1) — install and hand this checklist to the user:

```bash
./gradlew :app:installDebug
```
1. Scan a document, sign a page (use the signature editor), export without any compression target — confirm the signature appears in the bottom-right corner of the correct page, fully visible and not cut off or offset. Repeat with a high-resolution scan (a modern phone camera scan, which should exceed 3000px on its long side) to specifically exercise the bug this task fixed.
2. Scan a multi-page document from a single source (e.g. several pages of the same book) and export it — open the resulting PDF and confirm every page renders at the same size (no page appears zoomed in/out relative to the others).
3. Export a document with "compress to target size" enabled, targeting something clearly smaller than the natural export size (e.g. target 1MB on a document that exports to several MB uncompressed) — check the actual resulting file's size (e.g. via a file manager or `adb shell ls -l`) and confirm it's at or reasonably close to the target, not multiple times over it.
4. Combine signature + compress-to-target-size in the same export — confirm the signature still appears correctly positioned even when the page has been shrunk to hit the target.

Report what you observe for each.

- [ ] **Step 3: Report findings**

Summarize what worked and what didn't. Anything that doesn't match expectations is a new finding for follow-up, not something to guess a fix for blind.
