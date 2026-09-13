# OCR Rework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run OCR automatically right after a page is scanned (persisting it to `Page.extractedText` for future search) and fix the two real causes of inaccurate invisible-text selection on exported PDFs (wrong font encoding, missing horizontal scaling).

**Architecture:** Two independent pieces. (1) A new `PageTextExtractor` class, wired through `AppContainer` and called from `MainActivity`'s existing scan-handling paths, runs ML Kit recognition on each newly-scanned or replaced page in the background and persists the plain text. (2) `InvisibleTextLayerInjector` gets a real Adobe Helvetica width table (fetched from the authoritative AFM metrics during this sub-project's design phase, not approximated) to compute a `Tz` horizontal-scaling value per text element, plus a font-dictionary encoding fix, both driven by a new `width` field on `OcrTextElement` threaded through `OcrTextStamper` and `PdfEngine`.

**Tech Stack:** Kotlin, Jetpack Compose, ML Kit Text Recognition, Room, raw PDF byte manipulation (existing `InvisibleTextLayerInjector` pattern), JUnit.

## Global Constraints

- No unrelated refactors — every changed line traces to one of this plan's two pieces (per project CLAUDE.md).
- No new dependencies (no WorkManager — OCR-at-scan-time is best-effort per the approved design).
- Curved/book-page dewarping gets no code — documented limitation, not addressed here.
- This project has no Robolectric — only plain JUnit tests of pure logic are possible. Pure logic extracted from an Android-framework-dependent class goes in a top-level `internal fun` in the same file, the established pattern from `BitmapPdfCompiler.kt`'s `nextScale` (sub-project 2 of this engagement) — match that style exactly.
- Read each file's current on-disk content before editing — confirm it matches what's shown in each step rather than assuming, in case something else touched it since this plan was written.

---

### Task 1: OCR at scan time

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/core/FileUtils.kt`
- Modify: `app/src/main/java/com/papr/scanner/pdf/OcrTextStamper.kt`
- Create: `app/src/main/java/com/papr/scanner/pdf/PageTextExtractor.kt`
- Modify: `app/src/main/java/com/papr/scanner/di/AppContainer.kt`
- Modify: `app/src/main/java/com/papr/scanner/MainActivity.kt`

**Interfaces:**
- Produces: `FileUtils.decodeDownsampled(file: File, maxDimension: Int): Bitmap` (suspend); `OcrTextStamper.recognizePlainText(bitmap: Bitmap): String` (suspend); `PageTextExtractor(ocrTextStamper: OcrTextStamper, documentRepository: DocumentRepository)` with `suspend fun extractAndSave(page: Page)`; `AppContainer.pageTextExtractor: PageTextExtractor`.
- Consumes: nothing from Task 2 (fully independent).

No TDD test-first step here: `PageTextExtractor` is Android-framework-dependent (Bitmap decode, ML Kit, Room-backed repository) with no meaningful pure logic to unit test without Robolectric — consistent with how `BitmapPdfCompiler`'s own analogous downsample-factor logic was never unit tested either. Verified by compile + the manual checklist in Task 3.

- [ ] **Step 1: Add a capped-decode helper to `FileUtils`**

Read `app/src/main/java/com/papr/scanner/core/FileUtils.kt` first to confirm current content. Add this function inside the `FileUtils` object (e.g. after `generateThumbnail`, before the closing `}`):
```kotlin
    /** Decodes a bitmap capped at [maxDimension] on its longest side, to bound memory for a one-off read (e.g. OCR). */
    suspend fun decodeDownsampled(file: File, maxDimension: Int): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = if (longestSide > maxDimension) maxOf(1, longestSide / maxDimension) else 1
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Failed to decode bitmap: ${file.absolutePath}")
    }
```
No new imports needed — `Bitmap`, `BitmapFactory`, `Dispatchers`, `withContext`, `IOException`, `File` are all already imported in this file.

- [ ] **Step 2: Add a whole-page-text method to `OcrTextStamper`**

Read `app/src/main/java/com/papr/scanner/pdf/OcrTextStamper.kt` first to confirm current content. Add this method to the `OcrTextStamper` class (e.g. after `recognize`, before the private `recognizeText` helper):
```kotlin
    /** The whole page's recognized text, in ML Kit's own natural reading order - for search indexing, not PDF stamping. */
    suspend fun recognizePlainText(bitmap: Bitmap): String = recognizeText(bitmap).text
```

- [ ] **Step 3: Create `PageTextExtractor`**

```kotlin
package com.papr.scanner.pdf

import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Page
import java.io.File

/** Runs OCR on a page's image right after it's scanned, storing the plain text for future search. */
class PageTextExtractor(
    private val ocrTextStamper: OcrTextStamper,
    private val documentRepository: DocumentRepository,
) {
    suspend fun extractAndSave(page: Page) {
        val bitmap = FileUtils.decodeDownsampled(File(page.imageUri), MAX_DIMENSION)
        val text = try {
            ocrTextStamper.recognizePlainText(bitmap)
        } finally {
            bitmap.recycle()
        }
        documentRepository.updatePage(page.copy(extractedText = text.ifBlank { null }))
    }

    private companion object {
        const val MAX_DIMENSION = 3000
    }
}
```
Save as `app/src/main/java/com/papr/scanner/pdf/PageTextExtractor.kt`.

- [ ] **Step 4: Wire it into `AppContainer`**

Read `app/src/main/java/com/papr/scanner/di/AppContainer.kt` first to confirm current content. Add the import alongside the other `com.papr.scanner.pdf.*` imports:
```kotlin
import com.papr.scanner.pdf.PageTextExtractor
```
Add this property (e.g. right after the existing `pdfEngine` property):
```kotlin
    val pageTextExtractor: PageTextExtractor by lazy {
        PageTextExtractor(OcrTextStamper(), documentRepository)
    }
```
This constructs its own `OcrTextStamper` instance, separate from the one `pdfEngine` builds inline — don't touch `pdfEngine`'s existing construction, that's out of scope here.

- [ ] **Step 5: Call it from `MainActivity`'s scan-handling paths**

Read `app/src/main/java/com/papr/scanner/MainActivity.kt` first to confirm current content.

In `handleNewDocument`, change:
```kotlin
        container.documentRepository.insertPages(pages)
```
to:
```kotlin
        val pageIds = container.documentRepository.insertPages(pages)
        startTextExtraction(container, pages.zip(pageIds) { page, id -> page.copy(id = id) })
```

In `handleAppendPages`, change:
```kotlin
        container.documentRepository.insertPages(newPages)
```
to:
```kotlin
        val newPageIds = container.documentRepository.insertPages(newPages)
        startTextExtraction(container, newPages.zip(newPageIds) { page, id -> page.copy(id = id) })
```

In `handleReplacePage`, change:
```kotlin
        container.documentRepository.updatePage(page.copy(imageUri = newFile.absolutePath))
```
to:
```kotlin
        val updatedPage = page.copy(imageUri = newFile.absolutePath)
        container.documentRepository.updatePage(updatedPage)
        startTextExtraction(container, listOf(updatedPage))
```

Add this new private helper right after `defaultTitle`, before the class's closing brace:
```kotlin

    /** Fires OCR for these pages in the background, independent of whatever coroutine is already saving/navigating - so scanning isn't held up waiting for it. */
    private fun startTextExtraction(container: AppContainer, pages: List<Page>) {
        lifecycleScope.launch {
            pages.forEach { page -> container.pageTextExtractor.extractAndSave(page) }
        }
    }
```
No new imports needed — `Page`, `lifecycleScope`, and `launch` are all already imported in this file.

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/papr/scanner/core/FileUtils.kt app/src/main/java/com/papr/scanner/pdf/OcrTextStamper.kt app/src/main/java/com/papr/scanner/pdf/PageTextExtractor.kt app/src/main/java/com/papr/scanner/di/AppContainer.kt app/src/main/java/com/papr/scanner/MainActivity.kt
git commit -m "$(cat <<'EOF'
Run OCR automatically right after a page is scanned

Page.extractedText already existed in the schema (and was referenced in
a comment as if it were used for search) but was always null - nothing
ever populated it. PageTextExtractor now runs ML Kit recognition on
each newly scanned or replaced page and persists the result, fired as
its own background coroutine so it doesn't delay the save-and-navigate
flow the user already sees.
EOF
)"
```

---

### Task 2: Fix invisible-text selection accuracy (encoding + horizontal scaling)

Two real causes were found (not the position math, which was re-derived and confirmed correct): the invisible text's font dictionary never sets `/Encoding`, so accented characters (which are written as Latin-1 bytes) get interpreted under the font's default `StandardEncoding` instead — selecting the wrong glyph. And the invisible text is always rendered at Helvetica's *natural* width for its font size, with no adjustment to match the OCR-detected bounding box's actual width — so on any page not actually set in Helvetica (i.e. virtually all of them), the selectable text drifts further from the real words the further right you go on a line.

The Helvetica character widths used below were fetched directly from Adobe's published `Helvetica.afm` font metrics during this sub-project's design phase — not approximated from memory.

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/pdf/InvisibleTextLayerInjector.kt`
- Modify: `app/src/main/java/com/papr/scanner/pdf/OcrTextStamper.kt`
- Modify: `app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt`
- Test: `app/src/test/java/com/papr/scanner/pdf/InvisibleTextLayerInjectorScaleTest.kt`
- Modify: `app/src/test/java/com/papr/scanner/pdf/InvisibleTextLayerInjectorTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `internal fun helveticaWidth(code: Int): Int` and `internal fun horizontalScalePercent(text: String, fontSize: Float, targetWidth: Float): Float`, top-level functions in `InvisibleTextLayerInjector.kt`, used by this task's own test and by `buildContentStream`. `OcrTextElement` gains a `width: Float` field (after `fontSize`), consumed by `InvisibleTextLayerInjector`.

- [ ] **Step 1: Write the failing tests for the Helvetica width lookup and scale computation**

Create `app/src/test/java/com/papr/scanner/pdf/InvisibleTextLayerInjectorScaleTest.kt`:
```kotlin
package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class InvisibleTextLayerInjectorScaleTest {

    @Test
    fun `looks up known Helvetica character widths`() {
        assertEquals(944, helveticaWidth('W'.code))
        assertEquals(222, helveticaWidth('i'.code))
        assertEquals(278, helveticaWidth(' '.code))
    }

    @Test
    fun `falls back to the average width outside the mapped ASCII range`() {
        assertEquals(526, helveticaWidth('é'.code))
    }

    @Test
    fun `scales up when the detected box is wider than Helvetica's natural width`() {
        // "ii" in Helvetica at size 100 is naturally 2 * 222 / 1000 * 100 = 44.4pt wide.
        val result = horizontalScalePercent(text = "ii", fontSize = 100f, targetWidth = 88.8f)
        assertEquals(200f, result, 0.5f)
    }

    @Test
    fun `returns 100 percent when the target width already matches the natural width`() {
        val naturalWidth = 222 / 1000f * 100f // "i" at size 100
        val result = horizontalScalePercent(text = "i", fontSize = 100f, targetWidth = naturalWidth)
        assertEquals(100f, result, 0.5f)
    }

    @Test
    fun `clamps an extreme wide target instead of producing unreadable text`() {
        val result = horizontalScalePercent(text = "i", fontSize = 100f, targetWidth = 10000f)
        assertEquals(400f, result, 0.01f)
    }

    @Test
    fun `clamps an extreme narrow target instead of collapsing to nothing`() {
        val result = horizontalScalePercent(text = "i", fontSize = 100f, targetWidth = 0.01f)
        assertEquals(25f, result, 0.01f)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.InvisibleTextLayerInjectorScaleTest"`
Expected: FAIL to compile — `helveticaWidth` and `horizontalScalePercent` are unresolved (they don't exist yet).

- [ ] **Step 3: Implement the Helvetica width table and scale functions, plus the encoding fix**

Read `app/src/main/java/com/papr/scanner/pdf/InvisibleTextLayerInjector.kt` first to confirm current content.

Change the font dictionary line:
```kotlin
        appendObject(fontObjNum, "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>")
```
to:
```kotlin
        appendObject(fontObjNum, "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>")
```

Append these top-level declarations at the very end of the file, after the class's closing `}` (matching the pattern `BitmapPdfCompiler.kt`'s `nextScale` uses — a top-level `internal fun` alongside its class, not inside it):
```kotlin

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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.InvisibleTextLayerInjectorScaleTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Add `width` to `OcrTextElement` and populate it**

Read `app/src/main/java/com/papr/scanner/pdf/OcrTextStamper.kt` first to confirm current content (it now also has `recognizePlainText` from Task 1 — that's expected, unrelated to this change). Change:
```kotlin
data class OcrTextElement(val text: String, val x: Float, val y: Float, val fontSize: Float)
```
to:
```kotlin
data class OcrTextElement(val text: String, val x: Float, val y: Float, val fontSize: Float, val width: Float)
```
Change:
```kotlin
            OcrTextElement(
                text = element.text,
                x = box.left.toFloat(),
                y = box.bottom.toFloat(),
                fontSize = box.height().toFloat(),
            )
```
to:
```kotlin
            OcrTextElement(
                text = element.text,
                x = box.left.toFloat(),
                y = box.bottom.toFloat(),
                fontSize = box.height().toFloat(),
                width = box.width().toFloat(),
            )
```

- [ ] **Step 6: Scale `width` alongside the other OCR fields in `PdfEngine`**

Read `app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt` first to confirm current content. Change:
```kotlin
                ocrElementsByPage[pageIndex] = ocrTextStamper.recognize(bitmap).map { element ->
                    element.copy(
                        x = (element.x + offsetXPx) * scale,
                        y = (pageHeightPx - (element.y + offsetYPx)) * scale,
                        fontSize = element.fontSize * scale,
                    )
                }
```
to:
```kotlin
                ocrElementsByPage[pageIndex] = ocrTextStamper.recognize(bitmap).map { element ->
                    element.copy(
                        x = (element.x + offsetXPx) * scale,
                        y = (pageHeightPx - (element.y + offsetYPx)) * scale,
                        fontSize = element.fontSize * scale,
                        width = element.width * scale,
                    )
                }
```
(`width` is a distance, not a position, so it only needs the same `scale` multiplier the other dimensions get — no offset.)

- [ ] **Step 7: Emit `Tz` in `buildContentStream`**

Still in `app/src/main/java/com/papr/scanner/pdf/InvisibleTextLayerInjector.kt`, change:
```kotlin
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
```
to:
```kotlin
    private fun buildContentStream(elements: List<OcrTextElement>): String {
        val builder = StringBuilder()
        for (element in elements) {
            val horizontalScale = horizontalScalePercent(element.text, element.fontSize, element.width)
            builder.append("BT\n3 Tr\n")
            builder.append("/F1 ${formatNumber(element.fontSize)} Tf\n")
            builder.append("${formatNumber(horizontalScale)} Tz\n")
            builder.append("1 0 0 1 ${formatNumber(element.x)} ${formatNumber(element.y)} Tm\n")
            builder.append("(${escapePdfString(element.text)}) Tj\nET\n")
        }
        return builder.toString()
    }
```

- [ ] **Step 8: Update the existing structural tests for the new `width` field and `Tz` output**

Read `app/src/test/java/com/papr/scanner/pdf/InvisibleTextLayerInjectorTest.kt` first to confirm current content. Change all three `OcrTextElement` construction calls to add a `width` argument (the exact value doesn't matter for these structural tests — they're not testing the scale math, `InvisibleTextLayerInjectorScaleTest` already covers that):
```kotlin
        injector.inject(file, mapOf(0 to listOf(OcrTextElement("Hello", 10f, 20f, 12f))))
```
to:
```kotlin
        injector.inject(file, mapOf(0 to listOf(OcrTextElement("Hello", 10f, 20f, 12f, 50f))))
```
```kotlin
        injector.inject(file, mapOf(0 to listOf(OcrTextElement("日本語", 10f, 20f, 12f))))
```
to:
```kotlin
        injector.inject(file, mapOf(0 to listOf(OcrTextElement("日本語", 10f, 20f, 12f, 50f))))
```
```kotlin
        injector.inject(file, mapOf(0 to listOf(OcrTextElement("a(b)c\\d", 0f, 0f, 12f))))
```
to:
```kotlin
        injector.inject(file, mapOf(0 to listOf(OcrTextElement("a(b)c\\d", 0f, 0f, 12f, 50f))))
```

Add one new assertion to the first test (`injects font, content stream, and rewritten page with encodable text`), right after the existing `"3 Tr"` assertion:
```kotlin
        assertTrue("expected invisible text render mode", result.contains("3 Tr"))
```
to:
```kotlin
        assertTrue("expected invisible text render mode", result.contains("3 Tr"))
        assertTrue("expected a horizontal-scale Tz operator", result.contains(" Tz"))
```

- [ ] **Step 9: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (including the 6 new `InvisibleTextLayerInjectorScaleTest` tests and the updated `InvisibleTextLayerInjectorTest`).

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/papr/scanner/pdf/InvisibleTextLayerInjector.kt app/src/main/java/com/papr/scanner/pdf/OcrTextStamper.kt app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt app/src/test/java/com/papr/scanner/pdf/InvisibleTextLayerInjectorScaleTest.kt app/src/test/java/com/papr/scanner/pdf/InvisibleTextLayerInjectorTest.kt
git commit -m "$(cat <<'EOF'
Fix invisible-text selection accuracy: font encoding and horizontal scaling

The position math was re-derived and confirmed correct - the real bugs
were elsewhere. The font dictionary never set /Encoding, so Latin-1
text bytes were interpreted under the font's default StandardEncoding
instead, misselecting any accented character. And the invisible text
was always rendered at Helvetica's natural width for its font size,
with no adjustment to the OCR-detected box's actual width - since the
real page is essentially never set in Helvetica, that mismatch
compounds across a line. Fixed with /Encoding /WinAnsiEncoding and a
per-element Tz operator computed from real (fetched, not approximated)
Helvetica AFM widths.
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

- [ ] **Step 2: Manual on-device checklist**

No device/emulator is available in this environment — install and hand this checklist to the user:

```bash
./gradlew :app:installDebug
```
1. Scan a new multi-page document. Confirm the app navigates to it immediately (doesn't wait for OCR). A few seconds later, `extractedText` should be populated for its pages — there's no UI to see this directly yet (that's the future search sub-project), so check it however is easiest for you (e.g. a device file/database inspector on the Room database, or ask for a temporary debug log statement if you'd like one added and then removed).
2. Append pages to an existing document, and separately replace a page — confirm both also get `extractedText` populated (and that replacing a page doesn't leave the *old* page's stale text lying around under the new image).
3. Export a flat (non-curved) scanned page with OCR enabled. Open the resulting PDF in a PDF viewer and try selecting text across a full line, not just near its start — confirm the selection now tracks the real printed words much more closely than before, including near the right edge of long lines.
4. If the scan has any accented characters (café, naïve, résumé, or similar), confirm they now select correctly too.

Report what you observe for each.

- [ ] **Step 3: Report findings**

Summarize what worked and what didn't. Anything unexpected is a new finding for a follow-up, not something to guess a fix for blind.
