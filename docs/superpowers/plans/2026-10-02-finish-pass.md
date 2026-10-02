# Finish Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the four open items from the 2026-10-02 audit — uniform PDF page sizing, drag-to-place signatures, the stability re-audit fixes, and an app-wide code-quality pass.

**Architecture:** Pure geometry/date logic lives in small top-level functions with JVM unit tests (this project has no Robolectric). Background work (OCR, scan import) moves off Activity/ViewModel scopes onto one app-lifetime `applicationScope` in `AppContainer`. Every page mutation re-reads the current row inside the detail screen's `fileMutex`. Signatures are stored per page (nullable Room `@Embedded`, migration 2→3) and rendered by one `SignatureStamper` used for PDF export and thumbnails alike.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room 2.8 (KSP), Coroutines/Flow, ML Kit (document scanner, text recognition), `android.graphics.pdf.PdfDocument`, JUnit4.

Spec: `docs/superpowers/specs/2026-10-02-finish-pass-design.md`.

## Global Constraints

- minSdk 24 — no `java.time` (no desugaring configured); use `Calendar`/`TimeZone`.
- No new dependencies.
- Room DB version 2 → 3 via a real `Migration`, schema JSON committed under `app/schemas/`.
- Every task ends green on: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- Final task additionally: `:app:assembleRelease :app:lintDebug` → 0 lint errors, 0 Kotlin compiler warnings.
- Normalized coordinates everywhere are fractions (0f..1f) of the **page image's** own width/height.
- Pages' overlays are drawn in this order everywhere (PDF, thumbnails, screen): image → signature → confidential marks (redaction always on top).
- Match existing style: KDoc-style one-line comments explaining *why*, no unrelated refactors.

---

### Task 1: Uniform page sizing + one sample-size rule

**Files:**
- Create: `app/src/main/java/com/papr/scanner/core/BitmapSampling.kt`
- Create: `app/src/main/java/com/papr/scanner/pdf/PageLayout.kt`
- Modify: `app/src/main/java/com/papr/scanner/pdf/BitmapPdfCompiler.kt`, `pdf/PdfEngine.kt`, `core/FileUtils.kt`
- Test: `app/src/test/java/com/papr/scanner/core/BitmapSamplingTest.kt`, `app/src/test/java/com/papr/scanner/pdf/PageLayoutTest.kt`

**Interfaces:**
- Produces: `internal fun sampleSizeFor(longestSide: Int, maxDimension: Int): Int`
- Produces: `const val POINTS_PER_PIXEL: Float` (top-level in `pdf/PageLayout.kt`, replaces `BitmapPdfCompiler.POINTS_PER_PIXEL`)
- Produces: `data class PagePlacement(pageWidthPx: Int, pageHeightPx: Int, offsetX: Float, offsetY: Float, scale: Float)` with `toPdfX(Float)`, `toPdfY(Float)`, `toPdfLength(Float)`
- Produces: `internal fun fitPlacement(pageWidthPx: Int, pageHeightPx: Int, imageWidth: Int, imageHeight: Int): PagePlacement`
- Produces: `BitmapPdfCompiler.compile(pageFiles, outputFile, targetSizeBytes, onPageDrawn: suspend (canvas, bitmap, pageIndex, placement: PagePlacement) -> Unit)`

- [ ] **Step 1: Write failing tests**

```kotlin
// BitmapSamplingTest.kt
class BitmapSamplingTest {
    @Test fun `no sampling at or under the cap`() { assertEquals(1, sampleSizeFor(3000, 3000)); assertEquals(1, sampleSizeFor(1200, 3000)) }
    @Test fun `just over the cap still decodes full size`() { assertEquals(1, sampleSizeFor(5999, 3000)) }
    @Test fun `twice the cap halves`() { assertEquals(2, sampleSizeFor(6000, 3000)) }
    @Test fun `rounds down to a power of two like the decoder does`() { assertEquals(2, sampleSizeFor(9000, 3000)); assertEquals(4, sampleSizeFor(12000, 3000)) }
}
```

```kotlin
// PageLayoutTest.kt
class PageLayoutTest {
    @Test fun `an image matching the page fills it exactly`() {
        val p = fitPlacement(1000, 1400, 1000, 1400)
        assertEquals(1f, p.scale, 0.0001f); assertEquals(0f, p.offsetX, 0.0001f); assertEquals(0f, p.offsetY, 0.0001f)
    }
    @Test fun `a smaller image of the same shape is scaled up to fill the page`() {
        val p = fitPlacement(1000, 1400, 800, 1120)
        assertEquals(1.25f, p.scale, 0.0001f); assertEquals(0f, p.offsetX, 0.001f); assertEquals(0f, p.offsetY, 0.001f)
    }
    @Test fun `a narrower image is centered horizontally`() {
        val p = fitPlacement(1000, 1000, 500, 1000)
        assertEquals(1f, p.scale, 0.0001f); assertEquals(250f, p.offsetX, 0.001f); assertEquals(0f, p.offsetY, 0.001f)
    }
    @Test fun `a larger image is scaled down, never cropped`() {
        val p = fitPlacement(1000, 1000, 2000, 1000)
        assertEquals(0.5f, p.scale, 0.0001f); assertEquals(250f, p.offsetY, 0.001f)
    }
    @Test fun `maps bitmap pixels to bottom-up PDF points`() {
        val p = PagePlacement(pageWidthPx = 1000, pageHeightPx = 1000, offsetX = 100f, offsetY = 50f, scale = 2f)
        assertEquals((100f + 10f * 2f) * POINTS_PER_PIXEL, p.toPdfX(10f), 0.001f)
        assertEquals((1000f - (50f + 10f * 2f)) * POINTS_PER_PIXEL, p.toPdfY(10f), 0.001f)
        assertEquals(10f * 2f * POINTS_PER_PIXEL, p.toPdfLength(10f), 0.001f)
    }
}
```

- [ ] **Step 2: Run, expect compile failure** — `./gradlew :app:testDebugUnitTest --tests '*BitmapSamplingTest' --tests '*PageLayoutTest'` → unresolved references.

- [ ] **Step 3: Implement**

```kotlin
// core/BitmapSampling.kt
package com.papr.scanner.core

/**
 * The inSampleSize to decode an image whose longest side is [longestSide] so it
 * lands near (never far below) [maxDimension]. BitmapFactory silently rounds a
 * non-power-of-two sample size down to a power of two, so this does the same
 * rounding up front - callers can then predict the decoded size exactly instead
 * of assuming a divisor the decoder never actually used.
 */
internal fun sampleSizeFor(longestSide: Int, maxDimension: Int): Int =
    if (longestSide <= maxDimension) 1 else Integer.highestOneBit(longestSide / maxDimension)
```

```kotlin
// pdf/PageLayout.kt
package com.papr.scanner.pdf

/** Page pixels → PDF points assuming a nominal 200 DPI capture (see BitmapPdfCompiler). */
const val POINTS_PER_PIXEL: Float = 72f / 200f

/** Where one page's bitmap is drawn inside the document's shared page box: scaled by [scale], then offset - all in page-box pixels. */
data class PagePlacement(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val offsetX: Float,
    val offsetY: Float,
    val scale: Float,
) {
    /** Maps an x in the drawn bitmap's own pixel space to PDF user space (points). */
    fun toPdfX(bitmapX: Float): Float = (offsetX + bitmapX * scale) * POINTS_PER_PIXEL

    /** Maps a top-down bitmap y to PDF's bottom-up user space (points). */
    fun toPdfY(bitmapY: Float): Float = (pageHeightPx - (offsetY + bitmapY * scale)) * POINTS_PER_PIXEL

    fun toPdfLength(bitmapLength: Float): Float = bitmapLength * scale * POINTS_PER_PIXEL
}

/** Scales an image to fit entirely inside the page box (aspect preserved, centered), up or down - so every page fills the same page size. */
internal fun fitPlacement(pageWidthPx: Int, pageHeightPx: Int, imageWidth: Int, imageHeight: Int): PagePlacement {
    val scale = minOf(pageWidthPx / imageWidth.toFloat(), pageHeightPx / imageHeight.toFloat())
    return PagePlacement(
        pageWidthPx = pageWidthPx,
        pageHeightPx = pageHeightPx,
        offsetX = (pageWidthPx - imageWidth * scale) / 2f,
        offsetY = (pageHeightPx - imageHeight * scale) / 2f,
        scale = scale,
    )
}
```

`BitmapPdfCompiler.writePdf` per page becomes:

```kotlin
val bitmap = decodeScaled(pageFile, scale)
val placement = fitPlacement(pageWidthPx, pageHeightPx, bitmap.width, bitmap.height)
val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, index + 1).create())
page.canvas.scale(POINTS_PER_PIXEL, POINTS_PER_PIXEL)
page.canvas.translate(placement.offsetX, placement.offsetY)
page.canvas.scale(placement.scale, placement.scale)
page.canvas.drawBitmap(bitmap, 0f, 0f, FILTER_PAINT)   // Paint(Paint.FILTER_BITMAP_FLAG)
onPageDrawn(page.canvas, bitmap, index, placement)
```

`downsampleFactorFor` → `sampleSizeFor(maxOf(w, h), MAX_PAGE_DIMENSION)`; `uniformPageSizePx` unchanged otherwise; `POINTS_PER_PIXEL` companion const removed (top-level one used). `createScaledBitmap` → KTX `downsampled.scale(w, h)`.
`FileUtils.rotateJpeg` and `FileUtils.decodeDownsampled` use `sampleSizeFor(...)`.
`PdfEngine` OCR mapping becomes `x = placement.toPdfX(e.x)`, `y = placement.toPdfY(e.y)`, `fontSize = placement.toPdfLength(e.fontSize)`, `width = placement.toPdfLength(e.width)`.

- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5: Commit** — "Fit every PDF page's scan to the shared page size, and share one decoder-accurate sample-size rule".

---

### Task 2: Page geometry (rotation, signature placement) — pure logic

**Files:**
- Create: `app/src/main/java/com/papr/scanner/domain/model/PageSignature.kt`
- Modify: `app/src/main/java/com/papr/scanner/domain/model/ConfidentialMarkStroke.kt`
- Test: `app/src/test/java/com/papr/scanner/domain/model/PageSignatureTest.kt`, extend `ConfidentialMarkStrokeTest.kt`

**Interfaces:**
- Produces: `data class PageSignature(imagePath: String, x: Float, y: Float, width: Float, aspectRatio: Float)` — `x`/`y` top-left, `width` as page-width fraction, `aspectRatio` = signature height / width.
- Produces: `fun PageSignature.heightFraction(pageAspect: Float): Float` (`pageAspect` = page width / height)
- Produces: `fun PageSignature.clampedTo(pageAspect: Float): PageSignature`
- Produces: `fun PageSignature.rotatedClockwise(newPageAspect: Float): PageSignature`
- Produces: `fun defaultSignaturePlacement(imagePath: String, aspectRatio: Float, pageAspect: Float): PageSignature`
- Produces: `fun ConfidentialMarkStroke.rotatedClockwise(): ConfidentialMarkStroke`

- [ ] **Step 1: Write failing tests**

```kotlin
class PageSignatureTest {
    private fun sig(x: Float = 0.1f, y: Float = 0.1f, width: Float = 0.3f, aspect: Float = 0.5f) =
        PageSignature("s.png", x, y, width, aspect)

    @Test fun `height fraction accounts for the page's own shape`() {
        // 0.3 of a 1000px-wide page = 300px wide, 150px tall; on a 2000px-tall page that's 0.075
        assertEquals(0.075f, sig().heightFraction(pageAspect = 0.5f), 0.0001f)
    }
    @Test fun `default placement sits in the bottom-right corner, inside the page`() {
        val s = defaultSignaturePlacement("s.png", aspectRatio = 0.5f, pageAspect = 0.75f)
        assertEquals(1f / 3f, s.width, 0.0001f)
        assertTrue(s.x + s.width < 1f && s.x > 0.5f)
        assertTrue(s.y + s.heightFraction(0.75f) < 1f && s.y > 0.5f)
    }
    @Test fun `clamping keeps the signature fully on the page`() {
        val s = sig(x = 0.9f, y = 0.98f).clampedTo(pageAspect = 0.75f)
        assertEquals(0.7f, s.x, 0.0001f)
        assertEquals(1f - s.heightFraction(0.75f), s.y, 0.0001f)
    }
    @Test fun `clamping enforces a minimum and maximum width`() {
        assertEquals(MIN_SIGNATURE_WIDTH, sig(width = 0.001f).clampedTo(0.75f).width, 0.0001f)
        assertEquals(1f, sig(x = 0f, width = 3f, aspect = 0.1f).clampedTo(0.75f).width, 0.0001f)
    }
    @Test fun `clamping shrinks a signature too tall for the page`() {
        val s = sig(x = 0f, y = 0f, width = 1f, aspect = 2f).clampedTo(pageAspect = 1f)
        assertEquals(1f, s.heightFraction(1f), 0.0001f)
        assertEquals(0.5f, s.width, 0.0001f)
    }
    @Test fun `rotating clockwise moves the center with the page and keeps the width`() {
        // On a square page, center (0.25, 0.25) rotates to (0.75, 0.25)
        val s = PageSignature("s.png", x = 0.15f, y = 0.2f, width = 0.2f, aspectRatio = 0.5f)
        val r = s.rotatedClockwise(newPageAspect = 1f)
        assertEquals(0.2f, r.width, 0.0001f)
        assertEquals(0.75f, r.x + r.width / 2f, 0.0001f)
        assertEquals(0.25f, r.y + r.heightFraction(1f) / 2f, 0.0001f)
    }
    @Test fun `four rotations on a square page return to the start`() {
        val s = PageSignature("s.png", x = 0.1f, y = 0.6f, width = 0.3f, aspectRatio = 0.4f)
        val back = s.rotatedClockwise(1f).rotatedClockwise(1f).rotatedClockwise(1f).rotatedClockwise(1f)
        assertEquals(s.x, back.x, 0.0001f); assertEquals(s.y, back.y, 0.0001f)
    }
}
```

```kotlin
// ConfidentialMarkStrokeTest.kt additions
@Test fun `rotating a stroke clockwise maps x,y to 1-y,x`() {
    val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.5f, 0.9f)))
    assertEquals(listOf(NormalizedPoint(0.8f, 0.1f), NormalizedPoint(0.1f, 0.5f)), stroke.rotatedClockwise().points.map { NormalizedPoint(round3(it.x), round3(it.y)) })
}
@Test fun `four clockwise rotations restore the stroke`() {
    val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0.25f, 0.5f), NormalizedPoint(0.75f, 0.125f)))
    assertEquals(stroke, stroke.rotatedClockwise().rotatedClockwise().rotatedClockwise().rotatedClockwise())
}
private fun round3(v: Float) = Math.round(v * 1000f) / 1000f
```

- [ ] **Step 2: Run, expect unresolved references.**

- [ ] **Step 3: Implement**

```kotlin
// domain/model/PageSignature.kt
package com.papr.scanner.domain.model

/** Smallest width a signature can be resized to, as a fraction of the page width. */
const val MIN_SIGNATURE_WIDTH = 0.08f
private const val DEFAULT_SIGNATURE_WIDTH = 1f / 3f
private const val DEFAULT_MARGIN = 0.04f

/**
 * A signature placed on one page. [x]/[y] (top-left) and [width] are fractions
 * of the page image's own width/height; [aspectRatio] is the signature image's
 * height / width, so layout never has to decode the image to know its shape.
 */
data class PageSignature(
    val imagePath: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val aspectRatio: Float,
)

/** The signature's height as a fraction of the page height, for a page whose width / height is [pageAspect]. */
fun PageSignature.heightFraction(pageAspect: Float): Float = width * aspectRatio * pageAspect

/** Keeps the signature a sensible size and fully on the page. */
fun PageSignature.clampedTo(pageAspect: Float): PageSignature {
    val maxWidthForHeight = 1f / (aspectRatio * pageAspect)
    val clampedWidth = width.coerceIn(MIN_SIGNATURE_WIDTH, minOf(1f, maxWidthForHeight))
    val sized = copy(width = clampedWidth)
    return sized.copy(
        x = x.coerceIn(0f, 1f - clampedWidth),
        y = y.coerceIn(0f, (1f - sized.heightFraction(pageAspect)).coerceAtLeast(0f)),
    )
}

/**
 * Follows a 90° clockwise page rotation: the signature's center rotates with
 * the page, while the signature itself stays upright (it's a signature, not
 * page content) and keeps its width as a fraction of the new page width.
 * [newPageAspect] is the page's width / height *after* rotation.
 */
fun PageSignature.rotatedClockwise(newPageAspect: Float): PageSignature {
    val oldPageAspect = 1f / newPageAspect
    val centerX = x + width / 2f
    val centerY = y + heightFraction(oldPageAspect) / 2f
    val newCenterX = 1f - centerY
    val newCenterY = centerX
    return copy(
        x = newCenterX - width / 2f,
        y = newCenterY - heightFraction(newPageAspect) / 2f,
    ).clampedTo(newPageAspect)
}

/** Where a freshly drawn signature lands: the bottom-right corner, a third of the page wide. */
fun defaultSignaturePlacement(imagePath: String, aspectRatio: Float, pageAspect: Float): PageSignature {
    val unplaced = PageSignature(imagePath, x = 0f, y = 0f, width = DEFAULT_SIGNATURE_WIDTH, aspectRatio = aspectRatio)
    return unplaced.copy(
        x = 1f - DEFAULT_SIGNATURE_WIDTH - DEFAULT_MARGIN,
        y = 1f - unplaced.heightFraction(pageAspect) - DEFAULT_MARGIN * pageAspect,
    ).clampedTo(pageAspect)
}
```

```kotlin
// ConfidentialMarkStroke.kt addition
/** Follows a 90° clockwise rotation of the page image, so the mark keeps covering the same content. */
fun ConfidentialMarkStroke.rotatedClockwise(): ConfidentialMarkStroke =
    ConfidentialMarkStroke(points.map { NormalizedPoint(x = 1f - it.y, y = it.x) })
```

Note: the four-rotations stroke test uses binary-exact fractions (0.25/0.5/0.75/0.125) so float equality is exact.

- [ ] **Step 4: Run tests → PASS.**
- [ ] **Step 5: Commit** — "Add PageSignature placement geometry and page-rotation transforms for marks and signatures".

---

### Task 3: Persistence — signature columns, one-shot reads, targeted OCR write

**Files:**
- Modify: `domain/model/Page.kt` (+ `signature: PageSignature? = null`)
- Create: `data/local/db/SignatureColumns.kt`
- Modify: `data/local/db/PageEntity.kt`, `PageDao.kt`, `DocumentDao.kt`, `PaprDatabase.kt` (version 3), `PaprDatabaseMigrations.kt`, `data/repository/DocumentRepository.kt`, `DocumentRepositoryImpl.kt`, `di/AppContainer.kt`
- Modify: `.gitignore` (stop ignoring `app/schemas/`), commit `app/schemas/**/{1,2,3}.json`

**Interfaces:**
- Produces (repository): `suspend fun getDocument(id: Long): Document?`, `suspend fun getPage(id: Long): Page?`, `suspend fun getPages(documentId: Long): List<Page>` (ordered by pageIndex), `suspend fun updateExtractedText(pageId: Long, imageUri: String, text: String?)` — writes only if the page still shows that image.
- Produces: `MIGRATION_2_3`.

- [ ] **Step 1: Implement**

```kotlin
// SignatureColumns.kt
/** PageEntity's embedded signature; Room makes every column nullable because the embedding field is nullable. */
data class SignatureColumns(val imagePath: String, val x: Float, val y: Float, val width: Float, val aspectRatio: Float)
```
`PageEntity` gains `@Embedded(prefix = "signature_") val signature: SignatureColumns? = null`.

```kotlin
// PageDao additions
@Query("SELECT * FROM pages WHERE id = :id") suspend fun getById(id: Long): PageEntity?
@Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex ASC") suspend fun getForDocumentOnce(documentId: Long): List<PageEntity>
// Only touches the text column, and only if the page still shows the image that was recognized -
// so a slow OCR pass can never overwrite edits made meanwhile, or label a replaced page with old text.
@Query("UPDATE pages SET extractedText = :text WHERE id = :id AND imageUri = :imageUri") suspend fun updateExtractedText(id: Long, imageUri: String, text: String?)
// DocumentDao addition
@Query("SELECT * FROM documents WHERE id = :id") suspend fun getByIdOnce(id: Long): DocumentEntity?
```

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_imagePath TEXT")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_x REAL")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_y REAL")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_width REAL")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_aspectRatio REAL")
    }
}
```
Mapping: `signature?.let { PageSignature(it.imagePath, it.x, it.y, it.width, it.aspectRatio) }` and back.

- [ ] **Step 2: Build** `./gradlew :app:assembleDebug` → generates `app/schemas/.../3.json`.
- [ ] **Step 3: Verify migration matches schema** — in `3.json`, the five `signature_*` columns have `affinity` TEXT/REAL and `notNull: false`, and no `defaultValue` (Room validates ALTER-added columns against exactly this).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** — "Persist per-page signatures (Room v3 migration), add one-shot reads and a targeted OCR text write; commit Room schemas".

---

### Task 4: Background work — application scope, safe OCR, ScanImporter, DocumentDeleter, thumbnails with signatures

**Files:**
- Create: `data/repository/DocumentDeleter.kt`, `scanner/ScanTarget.kt`, `scanner/ScanImporter.kt`
- Modify: `pdf/SignatureStamper.kt`, `pdf/PageTextExtractor.kt`, `core/FileUtils.kt`, `di/AppContainer.kt`, `MainActivity.kt`

**Interfaces:**
- Produces: `AppContainer.applicationScope: CoroutineScope`, `AppContainer.documentDeleter`, `AppContainer.scanImporter`
- Produces: `class DocumentDeleter(repository, context) { suspend fun delete(document: Document) }` — row (pages cascade), then folder + thumbnail on IO.
- Produces: `class SignatureStamper { fun draw(canvas: Canvas, signature: PageSignature, pageWidthPx: Int, pageHeightPx: Int) }` — decodes the PNG, draws at the normalized rect; missing file → no-op.
- Produces: `suspend fun FileUtils.generateThumbnail(context: Context, page: Page): File` — page image + signature + marks.
- Produces: `suspend fun FileUtils.imageAspectRatio(file: File): Float` (width / height, from bounds only).
- Produces: `PageTextExtractor.extractInBackground(pages: List<Page>)` — launches on applicationScope, catches per page, writes via `updateExtractedText(id, imageUri, text)`.
- Produces: `sealed interface ScanTarget { NewDocument; AppendPages(documentId); ReplacePage(documentId, pageId) }`
- Produces: `ScanImporter.import(target: ScanTarget, pageUris: List<Uri>)`, `ScanImporter.events: Flow<ScanImportEvent>` with `ScanImportEvent.OpenDocument(documentId)` and `ScanImportEvent.Failed`.

Behavior:
- New document: insert → copy files → insert pages → OCR in background → thumbnail → update. Any failure (incl. cancellation) deletes the partial document under `NonCancellable`, then emits `Failed`. Success emits `OpenDocument`.
- Append: copy, insert, OCR, update `pageCount` from a fresh read. On failure, delete the copied files.
- Replace: fresh `getPage`; update row to the new image with `extractedText = null` (marks/signature kept), *then* delete the old file; OCR; regenerate thumbnail via `generateThumbnail(context, updatedPage)` if `pageIndex == 0`.
- Events go through `Channel(Channel.BUFFERED).receiveAsFlow()` so one survives Activity recreation and is delivered exactly once.
- `MainActivity` keeps only: ScanTarget save/restore (Bundle), `startScan` (toast "Couldn't open the scanner. Please try again." on failure), and collecting `scanImporter.events` under `repeatOnLifecycle(STARTED)` (navigate / toast "Couldn't save the scan. Please try again.").

- [ ] **Step 1: Implement as above.** All `generateThumbnail` call sites move to the `(context, page)` form.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 3: Commit** — "Move scan import and OCR onto an app-lifetime scope with cleanup and error handling; thumbnails include signatures".

---

### Task 5: Export draws placed signatures

**Files:** Modify `pdf/PdfEngine.kt`, `presentation/detail/DocumentDetailUiState.kt` (`ExportSettings` loses `signatureEnabled`), `presentation/detail/components/ExportOptionsSheet.kt`.

**Interfaces:**
- `PdfGenerationOptions.signaturesByPageIndex: Map<Int, PageSignature>` replaces `signature: SignaturePlacement?` (class deleted).
- `ExportOptionsSheet(isExporting: Boolean, onExport, onDismiss)` — no signature toggle; Export button disabled and reads "Exporting…" while `isExporting`.

Per page in `PdfEngine`: signature (`signatureStamper.draw(canvas, it, bitmap.width, bitmap.height)`) then marks.

- [ ] **Step 1: Implement.** (`DocumentDetailViewModel.export` is rewired in Task 6; this task leaves it compiling by mapping pages' signatures.)
- [ ] **Step 2: Run** tests + assemble → PASS.
- [ ] **Step 3: Commit** — "Export every page's placed signature; drop the opt-in signature toggle".

---

### Task 6: DocumentDetailViewModel — fresh reads, rotation transforms, signature actions, reliable exit

**Files:** Modify `presentation/detail/DocumentDetailViewModel.kt`, `DocumentDetailUiState.kt`, `DocumentDetailScreen.kt` (factory args).

**Interfaces (public VM API after this task):**
```kotlin
val uiState: StateFlow<DocumentDetailUiState>   // + isLoading, isPlacingSignature; - pendingSignature
val pdfReady: SharedFlow<File>; val errors: SharedFlow<String>
fun selectPage(index: Int)
fun updateTitle(newTitle: String)                 // trims; blank ignored
fun rotatePage(pageId: Long)
fun movePage(pageId: Long, direction: Int)
fun deletePage(pageId: Long)
fun toggleMarkingConfidential()                   // also ends signature placement
fun addConfidentialMarkStroke(pageId: Long, stroke: ConfidentialMarkStroke)
fun eraseConfidentialMarkStroke(pageId: Long, stroke: ConfidentialMarkStroke)
fun setSignature(pageId: Long, signatureBitmap: Bitmap)   // saves PNG, default placement, starts placement mode
fun updateSignaturePlacement(pageId: Long, x: Float, y: Float, width: Float)
fun removeSignature(pageId: Long)
fun setPlacingSignature(placing: Boolean)          // also ends marking mode
fun export(settings: ExportSettings)
```
Constructor: `(documentId, documentRepository, documentDeleter, pdfEngine, appContext)`.

Rules:
- Every mutation runs `fileMutex.withLock { val page = repository.getPage(pageId) ?: return@withLock; … }` — never the UI's captured `Page`.
- `rotatePage`: rotate file → `aspect = imageAspectRatio(file)` → `page.copy(confidentialMarks = marks.map { it.rotatedClockwise() }, signature = signature?.rotatedClockwise(aspect))` → update → thumbnail if `pageIndex == 0` → touch → `revision++`.
- `deletePage`: delete row, image file, signature PNG; renumber from `getPages`; empty → `documentDeleter.delete(document)` + `isDeleted`.
- `setSignature`: PNG written on IO to `documents/<id>/signature_<uuid>.png`; replaces (and deletes) any previous signature file of that page.
- `uiState` = `combine(documentFlow, pagesFlow, localState)` where `localState` is one `MutableStateFlow` holding currentPageIndex/isExporting/isDeleted/revision/isMarkingConfidential/isPlacingSignature, so `isDeleted` always reaches the UI. `isLoading` is true until the document flow's first emission.
- Thumbnail regeneration always via `FileUtils.generateThumbnail(appContext, page)` with the updated page.
- `deleteDocument()` (unused) removed.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Run** tests + assemble → PASS.
- [ ] **Step 3: Commit** — "Rework document detail edits: fresh page reads, marks/signature follow rotation, persisted signature actions, reliable exit".

---

### Task 7: Detail UI — signature overlay, mark overlay fixes, pager/strip

**Files:**
- Create: `presentation/common/ImageSize.kt` (`@Composable fun rememberImageSize(file: File): IntSize?` — bounds decoded on `Dispatchers.IO`, keyed on path + lastModified)
- Create: `presentation/detail/components/SignatureOverlay.kt`
- Create: `presentation/detail/components/SignatureCrop.kt` + test `app/src/test/java/com/papr/scanner/presentation/detail/components/SignatureCropTest.kt`
- Modify: `SignatureOverlayEditor.kt`, `ConfidentialMarkOverlay.kt`, `PagePager.kt`, `PageThumbnailStrip.kt`, `DocumentDetailScreen.kt`

**Interfaces:**
- `internal fun signatureCropRect(strokes: List<List<Offset>>, padding: Float, canvasWidth: Int, canvasHeight: Int): IntRect?` — bounding box of all points, padded, clamped to the canvas; null when there are no points.
- `SignatureOverlay(signature: PageSignature, imageSize: IntSize, isPlacing: Boolean, onTap: () -> Unit, onPlacementChange: (x: Float, y: Float, width: Float) -> Unit, onRemove: () -> Unit, modifier, crop: Boolean = false)`
- `ConfidentialMarkOverlay(imageSize: IntSize, marks, editingEnabled, onStrokeAdded, onStrokeErased, modifier, crop)` — takes the size from `rememberImageSize` at the call site; pointer input attached only while editing; tap-to-erase via `detectTapGestures`, drawing via `detectDragGestures`.
- `PagePager(..., isPlacingSignature, onSignatureTap(pageId), onSignaturePlacementChange(pageId, x, y, w), onSignatureRemove(pageId))`, `userScrollEnabled = !isMarkingConfidential && !isPlacingSignature`.

- [ ] **Step 1: Failing test for the crop**

```kotlin
class SignatureCropTest {
    @Test fun `no strokes means nothing to crop`() { assertNull(signatureCropRect(emptyList(), 4f, 100, 100)) }
    @Test fun `crops to the strokes plus padding`() {
        val r = signatureCropRect(listOf(listOf(Offset(20f, 30f), Offset(60f, 40f))), padding = 5f, canvasWidth = 200, canvasHeight = 100)
        assertEquals(IntRect(15, 25, 65, 45), r)
    }
    @Test fun `padding never extends past the canvas`() {
        val r = signatureCropRect(listOf(listOf(Offset(1f, 1f), Offset(99f, 99f))), padding = 10f, canvasWidth = 100, canvasHeight = 100)
        assertEquals(IntRect(0, 0, 100, 100), r)
    }
}
```
- [ ] **Step 2: Run → fails (unresolved).**
- [ ] **Step 3: Implement crop + editor** (editor renders only the crop rect via KTX `createBitmap`, transparent background, so the placed signature is exactly the ink).
- [ ] **Step 4: Implement overlays, pager, strip (list position for selection), detail screen:** Sign icon → editor → `setSignature(currentPage.id, bitmap)`; tapping a signature → placement mode; hint row "Drag to move. Drag the corner to resize." with a Done button; loading spinner while `isLoading`; `!isLoading && document == null` → `onBack()`; AutoMirrored icons.
- [ ] **Step 5: Run** tests + assemble → PASS.
- [ ] **Step 6: Commit** — "Drag-to-place signatures on the page; fix mark overlay tap-to-erase, main-thread decoding, and post-rotate misalignment".

---

### Task 8: Home — off-main search, loading/no-results states, correct date filter

**Files:**
- Create: `presentation/home/DateRanges.kt` + test `app/src/test/java/com/papr/scanner/presentation/home/DateRangesTest.kt`
- Modify: `HomeViewModel.kt`, `HomeUiState.kt`, `HomeScreen.kt`, `components/DateRangeFilterButton.kt`, `components/DocumentGridItem.kt`, `components/EmptyState.kt`

**Interfaces:**
- `internal fun localDayRange(startUtcMillis: Long, endUtcMillis: Long?, zone: TimeZone): LongRange` — picker dates are UTC midnights; result is local start-of-start-day .. local end-of-end-day (inclusive, ms precision); null end = single day.
- `HomeUiState.isLoading` (default true), `HomeUiState.hasActiveFilters`.
- `HomeViewModel(documentRepository, documentDeleter)`; `errors: SharedFlow<String>`.

- [ ] **Step 1: Failing tests**

```kotlin
class DateRangesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun utcMidnight(y: Int, m: Int, d: Int) = Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d) }.timeInMillis

    @Test fun `a single picked day covers that whole local day`() {
        val zone = TimeZone.getTimeZone("Asia/Kolkata")
        val range = localDayRange(utcMidnight(2026, 9, 7), null, zone)
        val start = Calendar.getInstance(zone).apply { clear(); set(2026, 8, 7) }.timeInMillis
        assertEquals(start, range.first)
        assertEquals(start + 24 * 60 * 60 * 1000L - 1, range.last)
    }
    @Test fun `the end day is included in full`() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val range = localDayRange(utcMidnight(2026, 9, 1), utcMidnight(2026, 9, 3), zone)
        val lastDayEvening = Calendar.getInstance(zone).apply { clear(); set(2026, 8, 3, 21, 30) }.timeInMillis
        assertTrue(lastDayEvening in range)
        val nextMorning = Calendar.getInstance(zone).apply { clear(); set(2026, 8, 4, 0, 0) }.timeInMillis
        assertFalse(nextMorning in range)
    }
    @Test fun `a western timezone doesn't shift the start a day early`() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val range = localDayRange(utcMidnight(2026, 9, 7), null, zone)
        val previousEvening = Calendar.getInstance(zone).apply { clear(); set(2026, 8, 6, 20, 0) }.timeInMillis
        assertFalse(previousEvening in range)
    }
}
```
- [ ] **Step 2: Run → fails.**
- [ ] **Step 3: Implement** with `Calendar` (UTC calendar reads y/m/d, local calendar builds 00:00:00.000; end = next day's start − 1).
- [ ] **Step 4: Wire up:** `searchResults.flowOn(Dispatchers.Default)`; `isLoading = false` once data arrives; grid shows nothing (spinner) while loading; `hasActiveFilters` → "No matching documents" (no scan CTA) instead of "No documents yet"; date picker OK enabled once a start date is picked and passes `localDayRange(start, end, TimeZone.getDefault())`; grid date = `createdAt`, formatter remembered inside the composable; `deleteSelected` uses `DocumentDeleter`, catches, emits to `errors` (toast in screen); `MergeType` → AutoMirrored.
- [ ] **Step 5: Run** tests + assemble → PASS.
- [ ] **Step 6: Commit** — "Home: search off the main thread, loading and no-results states, inclusive local-day date filter".

---

### Task 9: Merge and paywall robustness

**Files:** Modify `data/repository/DocumentMerger.kt`, `presentation/merge/MergeConfirmViewModel.kt`, `MergeConfirmScreen.kt`, `billing/BillingManager.kt`, `presentation/paywall/ProUpgradeViewModel.kt`, `ProUpgradeSheet.kt`, `di/AppContainer.kt`.

- `DocumentMerger(repository, deleter, context)`: copies image, `extractedText`, `confidentialMarks`, and signature (PNG copied to a new `signature_<uuid>.png` in the new folder); throws if no pages; on any failure/cancellation deletes the partial document (`NonCancellable`) and rethrows. Thumbnail via `generateThumbnail(context, firstPage)`.
- `MergeConfirmViewModel`: catches merge failure → `errors` emits "Couldn't merge. Please try again.", `isMerging = false`; screen shows a toast.
- `BillingManager.connect()`: any non-cancellation exception → `Disconnected`.
- `ProUpgradeViewModel.retry()`; sheet shows "Couldn't reach Google Play. Check your connection and try again." + "Try again" for `Disconnected` instead of an endless spinner.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Run** tests (incl. `BillingManagerStateMachineTest`) + assemble → PASS.
- [ ] **Step 3: Commit** — "Merge keeps marks and signatures and cleans up on failure; paywall recovers from billing connection failures".

---

### Task 10: Code-quality sweep and full verification

**Files:** remaining compiler/lint warnings, `app/src/main/res/drawable/ic_launcher.xml` (delete — unused since the adaptive icon), `docs/superpowers/manual-testing-checklist.md`.

- [ ] **Step 1:** `./gradlew :app:compileDebugKotlin --rerun-tasks` → zero `w:` lines. Fix any remaining.
- [ ] **Step 2:** `./gradlew :app:lintDebug` → 0 errors; `UseKtx`, `ConstantLocale`, `UnusedResources` gone (dependency-version and monochrome-icon warnings intentionally remain, see spec).
- [ ] **Step 3:** Update the manual checklist: page-size, signature drag-to-place, rotation-with-marks, date-filter, merge-keeps-marks, offline-paywall, kill-during-scan items.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --rerun-tasks` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — "Code-quality sweep: clear compiler and lint warnings, update the manual checklist".
