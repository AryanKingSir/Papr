# Confidential Marker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user drag over part of a page to cover it with tiled "CONFIDENTIAL" text (highlighter-style, non-destructive), persisted per page, visible everywhere that page is shown (full view, thumbnail strip, home grid thumbnail) and in the exported PDF.

**Architecture:** Marks are stored as a new encoded column on `Page`/`PageEntity` (list of freeform strokes, points normalized 0f..1f relative to the page image). A single non-Compose `ConfidentialMarkRenderer` (`android.graphics.Canvas`-based) draws them, reused identically by the live on-screen overlay (via `drawIntoCanvas { it.nativeCanvas }`), the home-thumbnail cache regeneration, and `PdfEngine`'s existing per-page export callback (the same integration point the signature stamp already uses). A shared pure geometry helper computes the displayed image's fitted/cropped bounds within its container (matching `ContentScale.Fit`/`Crop`) so touch coordinates and rendering stay aligned regardless of screen size.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room, `android.graphics`, JUnit.

## Global Constraints

- No unrelated refactors — every changed line traces to this plan's confidential-marker feature (per project CLAUDE.md).
- No new dependencies.
- This project has no Robolectric — only plain JUnit tests of pure logic are possible. Pure logic extracted from a class/composable that isn't otherwise unit-testable goes in a top-level `internal fun` in its own file — the established pattern from every prior sub-project of this engagement (e.g. `BitmapPdfCompiler.kt`'s `nextScale`, `HomeViewModel.kt`'s `matchDocument`) — match that style exactly.
- **Git hygiene:** check diff *content*, not just diff size, before committing any file. Two files in this working tree (`app/src/main/java/com/papr/scanner/billing/BillingManager.kt` and `app/src/main/java/com/papr/scanner/scanner/DocumentScannerManager.kt`) carry real pre-existing uncommitted work from before this whole engagement, unrelated to this feature — do not touch them, and make sure no `git add` in this plan ever sweeps them in (stage by explicit file path, never `git add -A`/`git add .`).
- Material icon used below (`Icons.Filled.Brush`) was verified to actually exist in this project's `material-icons-extended` dependency (unzipped and grepped the actual jar, not assumed from memory).
- Read each file's current on-disk content before editing — confirm it matches what's shown in each step rather than assuming, in case something else touched it since this plan was written.
- This is the project's first Room schema change (`PaprDatabase` is at `version = 1`) — it needs a real `Migration`, not `fallbackToDestructiveMigration`, since that would silently wipe every existing user's documents on upgrade.

---

### Task 1: `ConfidentialMarkStroke` domain model + encode/decode

**Files:**
- Create: `app/src/main/java/com/papr/scanner/domain/model/ConfidentialMarkStroke.kt`
- Test: `app/src/test/java/com/papr/scanner/domain/model/ConfidentialMarkStrokeTest.kt`

**Interfaces:**
- Produces: `data class NormalizedPoint(val x: Float, val y: Float)`, `data class ConfidentialMarkStroke(val points: List<NormalizedPoint>)`, `internal fun encodeMarkStrokes(strokes: List<ConfidentialMarkStroke>): String`, `internal fun decodeMarkStrokes(raw: String?): List<ConfidentialMarkStroke>`. Consumed by Task 2 (`PageEntity` storage), Task 3/4 (geometry), Task 5 (renderer), Task 6/7 (UI wiring).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.papr.scanner.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidentialMarkStrokeTest {

    @Test
    fun `encodes a single stroke with multiple points`() {
        val strokes = listOf(
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.15f, 0.22f))),
        )

        val result = encodeMarkStrokes(strokes)

        assertEquals("0.1,0.2;0.15,0.22", result)
    }

    @Test
    fun `encodes multiple strokes separated by a pipe`() {
        val strokes = listOf(
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f))),
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.5f, 0.6f))),
        )

        val result = encodeMarkStrokes(strokes)

        assertEquals("0.1,0.2|0.5,0.6", result)
    }

    @Test
    fun `encodes an empty list as an empty string`() {
        assertEquals("", encodeMarkStrokes(emptyList()))
    }

    @Test
    fun `decodes back to the original strokes`() {
        val strokes = listOf(
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.15f, 0.22f))),
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.5f, 0.6f))),
        )

        val result = decodeMarkStrokes(encodeMarkStrokes(strokes))

        assertEquals(strokes, result)
    }

    @Test
    fun `decodes null as an empty list`() {
        assertEquals(emptyList<ConfidentialMarkStroke>(), decodeMarkStrokes(null))
    }

    @Test
    fun `decodes a blank string as an empty list`() {
        assertEquals(emptyList<ConfidentialMarkStroke>(), decodeMarkStrokes(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.domain.model.ConfidentialMarkStrokeTest"`
Expected: FAIL to compile — `ConfidentialMarkStroke`/`NormalizedPoint`/`encodeMarkStrokes`/`decodeMarkStrokes` don't exist yet.

- [ ] **Step 3: Create the domain model + codec**

```kotlin
package com.papr.scanner.domain.model

data class NormalizedPoint(val x: Float, val y: Float)

/** One freeform confidential-mark stroke, points normalized 0f..1f relative to the page image's own width/height. */
data class ConfidentialMarkStroke(val points: List<NormalizedPoint>)

/** Strokes joined by "|", points within a stroke by ";", each point "x,y" - e.g. "0.1,0.2;0.15,0.22|0.5,0.6". */
internal fun encodeMarkStrokes(strokes: List<ConfidentialMarkStroke>): String =
    strokes.joinToString("|") { stroke -> stroke.points.joinToString(";") { "${it.x},${it.y}" } }

/** Inverse of [encodeMarkStrokes]. A null or blank string (no marks saved yet) decodes to an empty list. */
internal fun decodeMarkStrokes(raw: String?): List<ConfidentialMarkStroke> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split("|").map { strokeRaw ->
        val points = strokeRaw.split(";").map { pointRaw ->
            val (x, y) = pointRaw.split(",")
            NormalizedPoint(x.toFloat(), y.toFloat())
        }
        ConfidentialMarkStroke(points)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.domain.model.ConfidentialMarkStrokeTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/papr/scanner/domain/model/ConfidentialMarkStroke.kt app/src/test/java/com/papr/scanner/domain/model/ConfidentialMarkStrokeTest.kt
git commit -m "Add ConfidentialMarkStroke domain model with tested encode/decode"
```

---

### Task 2: Persist marks on `Page` — schema, entity, mapping, migration

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/domain/model/Page.kt`
- Modify: `app/src/main/java/com/papr/scanner/data/local/db/PageEntity.kt`
- Modify: `app/src/main/java/com/papr/scanner/data/local/db/PaprDatabase.kt`
- Create: `app/src/main/java/com/papr/scanner/data/local/db/PaprDatabaseMigrations.kt`
- Modify: `app/src/main/java/com/papr/scanner/data/repository/DocumentRepositoryImpl.kt`
- Modify: `app/src/main/java/com/papr/scanner/di/AppContainer.kt`

**Interfaces:**
- Consumes: `ConfidentialMarkStroke`, `encodeMarkStrokes`, `decodeMarkStrokes` (Task 1).
- Produces: `Page.confidentialMarks: List<ConfidentialMarkStroke>` (default `emptyList()`), flowing through the existing `documentRepository.getPagesForDocument`/`updatePage` — consumed by Tasks 6/7.

No TDD test-first step: this is schema/mapping plumbing with no new pure logic beyond what Task 1 already tested. Verified by existing tests still passing plus a compile/assemble check (Room generates and validates the migration at compile time via KSP).

- [ ] **Step 1: Add the field to the domain model**

In `app/src/main/java/com/papr/scanner/domain/model/Page.kt`, replace the whole file:

```kotlin
package com.papr.scanner.domain.model

data class Page(
    val id: Long,
    val documentId: Long,
    val pageIndex: Int,
    val imageUri: String,
    val extractedText: String?,
    val confidentialMarks: List<ConfidentialMarkStroke> = emptyList(),
)
```

- [ ] **Step 2: Add the column to the entity**

In `app/src/main/java/com/papr/scanner/data/local/db/PageEntity.kt`, replace the whole file:

```kotlin
package com.papr.scanner.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val imageUri: String,
    val extractedText: String?,
    val confidentialMarks: String? = null,
)
```

- [ ] **Step 3: Bump the database version**

In `app/src/main/java/com/papr/scanner/data/local/db/PaprDatabase.kt`, change:

```kotlin
@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 1,
    exportSchema = true,
)
```

to:

```kotlin
@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 2,
    exportSchema = true,
)
```

- [ ] **Step 4: Add the migration**

Create `app/src/main/java/com/papr/scanner/data/local/db/PaprDatabaseMigrations.kt`:

```kotlin
package com.papr.scanner.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN confidentialMarks TEXT")
    }
}
```

- [ ] **Step 5: Register the migration**

In `app/src/main/java/com/papr/scanner/di/AppContainer.kt`, add the import:

```kotlin
import com.papr.scanner.data.local.db.MIGRATION_1_2
```

and change:

```kotlin
    private val database: PaprDatabase by lazy {
        Room.databaseBuilder(context, PaprDatabase::class.java, PaprDatabase.NAME).build()
    }
```

to:

```kotlin
    private val database: PaprDatabase by lazy {
        Room.databaseBuilder(context, PaprDatabase::class.java, PaprDatabase.NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
    }
```

- [ ] **Step 6: Update the entity/domain mapping**

In `app/src/main/java/com/papr/scanner/data/repository/DocumentRepositoryImpl.kt`, add the import:

```kotlin
import com.papr.scanner.domain.model.decodeMarkStrokes
import com.papr.scanner.domain.model.encodeMarkStrokes
```

and change:

```kotlin
private fun PageEntity.toDomain() = Page(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    imageUri = imageUri,
    extractedText = extractedText,
)

private fun Page.toEntity() = PageEntity(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    imageUri = imageUri,
    extractedText = extractedText,
)
```

to:

```kotlin
private fun PageEntity.toDomain() = Page(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    imageUri = imageUri,
    extractedText = extractedText,
    confidentialMarks = decodeMarkStrokes(confidentialMarks),
)

private fun Page.toEntity() = PageEntity(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    imageUri = imageUri,
    extractedText = extractedText,
    confidentialMarks = encodeMarkStrokes(confidentialMarks).ifEmpty { null },
)
```

- [ ] **Step 7: Verify existing tests and build still pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, same test count as before plus Task 1's 6 new tests.

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. This also runs KSP, which regenerates `app/schemas/com.papr.scanner.data.local.db.PaprDatabase/2.json` — check it was created:

Run: `ls app/schemas/com.papr.scanner.data.local.db.PaprDatabase/`
Expected: `1.json` and `2.json` both present.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/papr/scanner/domain/model/Page.kt app/src/main/java/com/papr/scanner/data/local/db/PageEntity.kt app/src/main/java/com/papr/scanner/data/local/db/PaprDatabase.kt app/src/main/java/com/papr/scanner/data/local/db/PaprDatabaseMigrations.kt app/src/main/java/com/papr/scanner/data/repository/DocumentRepositoryImpl.kt app/src/main/java/com/papr/scanner/di/AppContainer.kt "app/schemas/com.papr.scanner.data.local.db.PaprDatabase/2.json"
git commit -m "Add confidentialMarks column to Page/PageEntity with a real Room migration"
```

---

### Task 3: Fitted/cropped image bounds (pure geometry)

**Files:**
- Create: `app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkGeometry.kt`
- Test: `app/src/test/java/com/papr/scanner/pdf/ConfidentialMarkGeometryTest.kt`

**Interfaces:**
- Produces: `data class FittedBounds(val left: Float, val top: Float, val width: Float, val height: Float)`, `internal fun fittedImageBounds(containerWidth: Float, containerHeight: Float, imageWidth: Float, imageHeight: Float, crop: Boolean = false): FittedBounds`. Consumed by Task 7 (drag overlay + live rendering, `crop = false` for the full page view's `ContentScale.Fit`, `crop = true` for the thumbnail strip's `ContentScale.Crop`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidentialMarkGeometryTest {

    @Test
    fun `fits a wider-than-container image by full width, letterboxed vertically`() {
        val result = fittedImageBounds(containerWidth = 100f, containerHeight = 100f, imageWidth = 200f, imageHeight = 100f)

        assertEquals(FittedBounds(left = 0f, top = 25f, width = 100f, height = 50f), result)
    }

    @Test
    fun `fits a taller-than-container image by full height, letterboxed horizontally`() {
        val result = fittedImageBounds(containerWidth = 100f, containerHeight = 100f, imageWidth = 100f, imageHeight = 200f)

        assertEquals(FittedBounds(left = 25f, top = 0f, width = 50f, height = 100f), result)
    }

    @Test
    fun `fills the container exactly when aspect ratios match`() {
        val result = fittedImageBounds(containerWidth = 100f, containerHeight = 50f, imageWidth = 200f, imageHeight = 100f)

        assertEquals(FittedBounds(left = 0f, top = 0f, width = 100f, height = 50f), result)
    }

    @Test
    fun `crop mode fills the container fully and overflows the larger dimension`() {
        val result = fittedImageBounds(
            containerWidth = 100f,
            containerHeight = 100f,
            imageWidth = 200f,
            imageHeight = 100f,
            crop = true,
        )

        assertEquals(FittedBounds(left = -50f, top = 0f, width = 200f, height = 100f), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.ConfidentialMarkGeometryTest"`
Expected: FAIL to compile — `fittedImageBounds`/`FittedBounds` don't exist yet.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkGeometry.kt`:

```kotlin
package com.papr.scanner.pdf

/** Fraction of the page's width used as the confidential-mark stroke's on-screen/export width. */
internal const val CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION = 0.04f

data class FittedBounds(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Where an image of [imageWidth]x[imageHeight] lands inside a container of
 * [containerWidth]x[containerHeight], matching Compose's `ContentScale.Fit`
 * (default, [crop] = false - scaled down to fit entirely inside, centered,
 * letterboxed) or `ContentScale.Crop` ([crop] = true - scaled up to cover
 * the whole container, centered, overflow clipped by the caller).
 */
internal fun fittedImageBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    crop: Boolean = false,
): FittedBounds {
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return FittedBounds(0f, 0f, 0f, 0f)
    }
    val widthScale = containerWidth / imageWidth
    val heightScale = containerHeight / imageHeight
    val scale = if (crop) maxOf(widthScale, heightScale) else minOf(widthScale, heightScale)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedBounds(
        left = (containerWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.ConfidentialMarkGeometryTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkGeometry.kt app/src/test/java/com/papr/scanner/pdf/ConfidentialMarkGeometryTest.kt
git commit -m "Add fittedImageBounds geometry helper for confidential-mark alignment"
```

---

### Task 4: Tap-to-erase hit-testing (pure geometry)

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkGeometry.kt`
- Modify: `app/src/test/java/com/papr/scanner/pdf/ConfidentialMarkGeometryTest.kt`

**Interfaces:**
- Consumes: `ConfidentialMarkStroke`, `NormalizedPoint` (Task 1).
- Produces: `internal fun isPointNearStroke(point: NormalizedPoint, stroke: ConfidentialMarkStroke, toleranceRadius: Float): Boolean`. Consumed by Task 7 (tap-to-erase in `ConfidentialMarkOverlay`).

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/papr/scanner/pdf/ConfidentialMarkGeometryTest.kt`, inside the `ConfidentialMarkGeometryTest` class (add the import too):

```kotlin
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.NormalizedPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
```

```kotlin
    @Test
    fun `detects a tap directly on a stroke segment`() {
        val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f)))

        val result = isPointNearStroke(NormalizedPoint(0.5f, 0f), stroke, toleranceRadius = 0.05f)

        assertTrue(result)
    }

    @Test
    fun `detects a tap within tolerance of a stroke segment`() {
        val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f)))

        val result = isPointNearStroke(NormalizedPoint(0.5f, 0.03f), stroke, toleranceRadius = 0.05f)

        assertTrue(result)
    }

    @Test
    fun `rejects a tap far from every segment`() {
        val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f)))

        val result = isPointNearStroke(NormalizedPoint(0.5f, 0.5f), stroke, toleranceRadius = 0.05f)

        assertFalse(result)
    }

    @Test
    fun `checks distance against the nearest of multiple segments`() {
        val stroke = ConfidentialMarkStroke(
            listOf(NormalizedPoint(0f, 0f), NormalizedPoint(0f, 1f), NormalizedPoint(1f, 1f)),
        )

        val result = isPointNearStroke(NormalizedPoint(0.97f, 1f), stroke, toleranceRadius = 0.05f)

        assertTrue(result)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.ConfidentialMarkGeometryTest"`
Expected: FAIL to compile — `isPointNearStroke` doesn't exist yet.

- [ ] **Step 3: Implement**

Append to `app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkGeometry.kt` (add the import at the top too):

```kotlin
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.NormalizedPoint
import kotlin.math.sqrt
```

```kotlin
/** Whether [point] lies within [toleranceRadius] of any segment of [stroke], for tap-to-erase hit-testing. */
internal fun isPointNearStroke(point: NormalizedPoint, stroke: ConfidentialMarkStroke, toleranceRadius: Float): Boolean {
    if (stroke.points.size == 1) return distance(point, stroke.points[0]) <= toleranceRadius
    return stroke.points.zipWithNext().any { (a, b) -> distanceToSegment(point, a, b) <= toleranceRadius }
}

private fun distance(a: NormalizedPoint, b: NormalizedPoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

private fun distanceToSegment(p: NormalizedPoint, a: NormalizedPoint, b: NormalizedPoint): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lengthSquared = abx * abx + aby * aby
    if (lengthSquared == 0f) return distance(p, a)
    val t = (((p.x - a.x) * abx) + ((p.y - a.y) * aby)) / lengthSquared
    val clampedT = t.coerceIn(0f, 1f)
    val closest = NormalizedPoint(a.x + clampedT * abx, a.y + clampedT * aby)
    return distance(p, closest)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.pdf.ConfidentialMarkGeometryTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkGeometry.kt app/src/test/java/com/papr/scanner/pdf/ConfidentialMarkGeometryTest.kt
git commit -m "Add isPointNearStroke hit-testing for confidential-mark tap-to-erase"
```

---

### Task 5: `ConfidentialMarkRenderer`

**Files:**
- Create: `app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkRenderer.kt`

**Interfaces:**
- Consumes: `ConfidentialMarkStroke` (Task 1), `CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION` (Task 3).
- Produces: `class ConfidentialMarkRenderer { fun draw(canvas: android.graphics.Canvas, strokes: List<ConfidentialMarkStroke>, widthPx: Int, heightPx: Int) }`. Consumed by Task 6 (thumbnail regeneration, `PdfEngine`) and Task 7 (live overlay).

No TDD test-first step: pure `android.graphics` drawing code, same precedent as `SignatureStamper` (no meaningfully-testable-without-Robolectric logic beyond what Tasks 3/4 already extracted). Verified by compile + the manual checklist in Task 8. Every stroke reaching this renderer is guaranteed to have 2+ points — Task 7's drag handling only ever creates a stroke once a drag has moved, and a single-point tap is always interpreted as an erase-attempt instead (see Task 7), never appended as a new mark.

- [ ] **Step 1: Implement**

```kotlin
package com.papr.scanner.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.papr.scanner.domain.model.ConfidentialMarkStroke

class ConfidentialMarkRenderer {

    /** Draws [strokes] (points normalized 0f..1f) onto [canvas], scaled to a page whose drawn size is [widthPx]x[heightPx]. */
    fun draw(canvas: Canvas, strokes: List<ConfidentialMarkStroke>, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return

        val strokeWidthPx = widthPx * CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        val backgroundPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor(BACKGROUND_COLOR_HEX)
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = strokeWidthPx * TEXT_SIZE_FRACTION_OF_STROKE_WIDTH
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }

        for (stroke in strokes) {
            if (stroke.points.size < 2) continue

            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                val x = point.x * widthPx
                val y = point.y * heightPx
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            val fillPath = Path()
            strokePaint.getFillPath(path, fillPath)

            val bounds = RectF()
            fillPath.computeBounds(bounds, true)

            canvas.save()
            canvas.clipPath(fillPath)
            canvas.drawRect(bounds, backgroundPaint)
            drawTiledLabel(canvas, bounds, textPaint)
            canvas.restore()
        }
    }

    // Anchored to the canvas origin (not each stroke's own bounds) so rows line
    // up continuously across every stroke on the same page, not just within one.
    private fun drawTiledLabel(canvas: Canvas, bounds: RectF, textPaint: Paint) {
        val tileWidth = textPaint.measureText(LABEL)
        val lineHeight = textPaint.textSize * LINE_HEIGHT_FRACTION
        if (tileWidth <= 0f || lineHeight <= 0f) return

        var y = bounds.top - (bounds.top % lineHeight)
        while (y < bounds.bottom + lineHeight) {
            var x = bounds.left - (bounds.left % tileWidth)
            while (x < bounds.right) {
                canvas.drawText(LABEL, x, y, textPaint)
                x += tileWidth
            }
            y += lineHeight
        }
    }

    private companion object {
        const val LABEL = "CONFIDENTIAL   "
        const val BACKGROUND_COLOR_HEX = "#1A1A1A"
        const val TEXT_SIZE_FRACTION_OF_STROKE_WIDTH = 0.4f
        const val LINE_HEIGHT_FRACTION = 1.6f
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/papr/scanner/pdf/ConfidentialMarkRenderer.kt
git commit -m "Add ConfidentialMarkRenderer: stroke-clipped tiled CONFIDENTIAL text"
```

---

### Task 6: Wire marks through the data/view-model layer and PDF export

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/core/FileUtils.kt`
- Modify: `app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt`
- Modify: `app/src/main/java/com/papr/scanner/di/AppContainer.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailUiState.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt`

**Interfaces:**
- Consumes: `ConfidentialMarkStroke` (Task 1), `ConfidentialMarkRenderer` (Task 5).
- Produces: `FileUtils.generateThumbnail(context, documentId, sourcePageFile, confidentialMarks = emptyList())`; `PdfGenerationOptions.confidentialMarksByPageIndex: Map<Int, List<ConfidentialMarkStroke>>`; `DocumentDetailUiState.isMarkingConfidential: Boolean`; `DocumentDetailViewModel.toggleMarkingConfidential()`, `.addConfidentialMarkStroke(page: Page, stroke: ConfidentialMarkStroke)`, `.eraseConfidentialMarkStroke(page: Page, stroke: ConfidentialMarkStroke)`. Consumed by Task 7 (UI).

- [ ] **Step 1: Bake marks into thumbnail generation**

In `app/src/main/java/com/papr/scanner/core/FileUtils.kt`, add imports:

```kotlin
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.pdf.ConfidentialMarkRenderer
```

and change:

```kotlin
    /** Generates a small JPEG thumbnail from a page image, overwriting any previous one for this document. */
    suspend fun generateThumbnail(context: Context, documentId: Long, sourcePageFile: File): File =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePageFile.absolutePath, bounds)
            val sampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / THUMBNAIL_MAX_DIMENSION)
            val bitmap = BitmapFactory.decodeFile(
                sourcePageFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: throw IOException("Failed to decode bitmap: ${sourcePageFile.absolutePath}")
            val destination = thumbnailFile(context, documentId)
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()
            destination
        }
```

to:

```kotlin
    /**
     * Generates a small JPEG thumbnail from a page image, overwriting any
     * previous one for this document. [confidentialMarks] (if any) are baked
     * into this cached thumbnail so a marked page never shows unredacted in
     * the home grid preview - the live full-size page file itself is never
     * touched, only this derived cache artifact.
     */
    suspend fun generateThumbnail(
        context: Context,
        documentId: Long,
        sourcePageFile: File,
        confidentialMarks: List<ConfidentialMarkStroke> = emptyList(),
    ): File =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePageFile.absolutePath, bounds)
            val sampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / THUMBNAIL_MAX_DIMENSION)
            val bitmap = BitmapFactory.decodeFile(
                sourcePageFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: throw IOException("Failed to decode bitmap: ${sourcePageFile.absolutePath}")
            if (confidentialMarks.isNotEmpty()) {
                ConfidentialMarkRenderer().draw(Canvas(bitmap), confidentialMarks, bitmap.width, bitmap.height)
            }
            val destination = thumbnailFile(context, documentId)
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()
            destination
        }
```

Also add `import android.graphics.Canvas` to `FileUtils.kt`'s import block (alongside the existing `android.graphics.Bitmap`/`android.graphics.BitmapFactory`/`android.graphics.Matrix` imports).

- [ ] **Step 2: Wire the renderer into PDF export**

In `app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt`, add the import:

```kotlin
import com.papr.scanner.domain.model.ConfidentialMarkStroke
```

and change:

```kotlin
data class PdfGenerationOptions(
    val ocrEnabled: Boolean = false,
    val signature: SignaturePlacement? = null,
    val passwordProtection: PasswordProtection? = null,
    val targetSizeBytes: Long? = null,
)

class PdfEngine(
    private val bitmapPdfCompiler: BitmapPdfCompiler,
    private val ocrTextStamper: OcrTextStamper,
    private val signatureStamper: SignatureStamper,
    private val invisibleTextLayerInjector: InvisibleTextLayerInjector,
    private val pdfEncryptor: PdfEncryptor,
) {
```

to:

```kotlin
data class PdfGenerationOptions(
    val ocrEnabled: Boolean = false,
    val signature: SignaturePlacement? = null,
    val confidentialMarksByPageIndex: Map<Int, List<ConfidentialMarkStroke>> = emptyMap(),
    val passwordProtection: PasswordProtection? = null,
    val targetSizeBytes: Long? = null,
)

class PdfEngine(
    private val bitmapPdfCompiler: BitmapPdfCompiler,
    private val ocrTextStamper: OcrTextStamper,
    private val signatureStamper: SignatureStamper,
    private val confidentialMarkRenderer: ConfidentialMarkRenderer,
    private val invisibleTextLayerInjector: InvisibleTextLayerInjector,
    private val pdfEncryptor: PdfEncryptor,
) {
```

and change:

```kotlin
            options.signature?.let { placement ->
                if (placement.pageIndex == pageIndex) {
                    signatureStamper.stamp(canvas, placement.bitmap, bitmap.width, bitmap.height)
                }
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
            options.confidentialMarksByPageIndex[pageIndex]?.let { marks ->
                confidentialMarkRenderer.draw(canvas, marks, bitmap.width, bitmap.height)
            }
        }
```

- [ ] **Step 3: Update the `PdfEngine` construction site**

In `app/src/main/java/com/papr/scanner/di/AppContainer.kt`, add the import:

```kotlin
import com.papr.scanner.pdf.ConfidentialMarkRenderer
```

and change:

```kotlin
    val pdfEngine: PdfEngine by lazy {
        PdfEngine(
            BitmapPdfCompiler(),
            OcrTextStamper(),
            SignatureStamper(),
            InvisibleTextLayerInjector(),
            PdfEncryptor(),
        )
    }
```

to:

```kotlin
    val pdfEngine: PdfEngine by lazy {
        PdfEngine(
            BitmapPdfCompiler(),
            OcrTextStamper(),
            SignatureStamper(),
            ConfidentialMarkRenderer(),
            InvisibleTextLayerInjector(),
            PdfEncryptor(),
        )
    }
```

- [ ] **Step 4: Add `isMarkingConfidential` to the UI state**

In `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailUiState.kt`, change:

```kotlin
data class DocumentDetailUiState(
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val currentPageIndex: Int = 0,
    val pendingSignature: PendingSignature? = null,
    val isExporting: Boolean = false,
    val isDeleted: Boolean = false,
    // Bumped on every page-file mutation (rotate, etc). Page rows can be
    // written back with field-identical values (e.g. after a rotate, which
    // only touches the file on disk), and StateFlow dedupes equal values, so
    // without this the UI would silently miss the update until something
    // else happens to also change a real field.
    val revision: Int = 0,
)
```

to:

```kotlin
data class DocumentDetailUiState(
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val currentPageIndex: Int = 0,
    val pendingSignature: PendingSignature? = null,
    val isExporting: Boolean = false,
    val isDeleted: Boolean = false,
    // Bumped on every page-file mutation (rotate, etc). Page rows can be
    // written back with field-identical values (e.g. after a rotate, which
    // only touches the file on disk), and StateFlow dedupes equal values, so
    // without this the UI would silently miss the update until something
    // else happens to also change a real field.
    val revision: Int = 0,
    val isMarkingConfidential: Boolean = false,
)
```

- [ ] **Step 5: Wire the view model**

In `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt`, add the import:

```kotlin
import com.papr.scanner.domain.model.ConfidentialMarkStroke
```

Change:

```kotlin
    private val currentPageIndex = MutableStateFlow(0)
    private val pendingSignature = MutableStateFlow<PendingSignature?>(null)
    private val isExporting = MutableStateFlow(false)
    private val isDeleted = MutableStateFlow(false)
    private val refreshTrigger = MutableStateFlow(0)
```

to:

```kotlin
    private val currentPageIndex = MutableStateFlow(0)
    private val pendingSignature = MutableStateFlow<PendingSignature?>(null)
    private val isExporting = MutableStateFlow(false)
    private val isDeleted = MutableStateFlow(false)
    private val refreshTrigger = MutableStateFlow(0)
    private val isMarkingConfidential = MutableStateFlow(false)
```

Change:

```kotlin
    val uiState: StateFlow<DocumentDetailUiState> = combine(flowsSnapshot, refreshTrigger) { snapshot, revision ->
        DocumentDetailUiState(
            document = snapshot.document,
            pages = snapshot.pages,
            currentPageIndex = snapshot.currentPageIndex.coerceIn(0, (snapshot.pages.size - 1).coerceAtLeast(0)),
            pendingSignature = snapshot.pendingSignature,
            isExporting = snapshot.isExporting,
            isDeleted = isDeleted.value,
            revision = revision,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState())
```

to:

```kotlin
    val uiState: StateFlow<DocumentDetailUiState> = combine(
        flowsSnapshot,
        refreshTrigger,
        isMarkingConfidential,
    ) { snapshot, revision, marking ->
        DocumentDetailUiState(
            document = snapshot.document,
            pages = snapshot.pages,
            currentPageIndex = snapshot.currentPageIndex.coerceIn(0, (snapshot.pages.size - 1).coerceAtLeast(0)),
            pendingSignature = snapshot.pendingSignature,
            isExporting = snapshot.isExporting,
            isDeleted = isDeleted.value,
            revision = revision,
            isMarkingConfidential = marking,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState())
```

Change `rotatePage`:

```kotlin
    fun rotatePage(page: Page) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    FileUtils.rotateJpeg(File(page.imageUri), 90f)
                    // page.imageUri is already known to be correct here - no need to
                    // consult uiState.value.pages, which is reactive and can still
                    // reflect pre-mutation data at this exact point (see deletePage's
                    // history: reading it right after a DB write is a real race, not
                    // a hypothetical one).
                    if (page.pageIndex == 0) {
                        FileUtils.generateThumbnail(appContext, documentId, File(page.imageUri))
                    }
                    touchDocument()
                }
            }.onSuccess {
                refreshTrigger.value++
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to rotate page")
            }
        }
    }
```

to:

```kotlin
    fun rotatePage(page: Page) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    FileUtils.rotateJpeg(File(page.imageUri), 90f)
                    // page.imageUri is already known to be correct here - no need to
                    // consult uiState.value.pages, which is reactive and can still
                    // reflect pre-mutation data at this exact point (see deletePage's
                    // history: reading it right after a DB write is a real race, not
                    // a hypothetical one).
                    if (page.pageIndex == 0) {
                        FileUtils.generateThumbnail(appContext, documentId, File(page.imageUri), page.confidentialMarks)
                    }
                    touchDocument()
                }
            }.onSuccess {
                refreshTrigger.value++
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to rotate page")
            }
        }
    }
```

Change `movePage`:

```kotlin
    fun movePage(page: Page, direction: Int) {
        val pages = uiState.value.pages
        val currentIdx = pages.indexOfFirst { it.id == page.id }
        val targetIdx = currentIdx + direction
        if (currentIdx == -1 || targetIdx !in pages.indices) return
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    val other = pages[targetIdx]
                    documentRepository.updatePage(page.copy(pageIndex = other.pageIndex))
                    documentRepository.updatePage(other.copy(pageIndex = page.pageIndex))
                    // Whichever of the two swapped pages now sits at index 0 (computed
                    // from the swap we just made, not re-read from reactive state).
                    val newFirstPageFile = when (0) {
                        other.pageIndex -> page.imageUri
                        page.pageIndex -> other.imageUri
                        else -> null
                    }
                    newFirstPageFile?.let { FileUtils.generateThumbnail(appContext, documentId, File(it)) }
                    touchDocument()
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to reorder page")
            }
        }
    }
```

to:

```kotlin
    fun movePage(page: Page, direction: Int) {
        val pages = uiState.value.pages
        val currentIdx = pages.indexOfFirst { it.id == page.id }
        val targetIdx = currentIdx + direction
        if (currentIdx == -1 || targetIdx !in pages.indices) return
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    val other = pages[targetIdx]
                    documentRepository.updatePage(page.copy(pageIndex = other.pageIndex))
                    documentRepository.updatePage(other.copy(pageIndex = page.pageIndex))
                    // Whichever of the two swapped pages now sits at index 0 (computed
                    // from the swap we just made, not re-read from reactive state).
                    val newFirstPage = when (0) {
                        other.pageIndex -> page
                        page.pageIndex -> other
                        else -> null
                    }
                    newFirstPage?.let {
                        FileUtils.generateThumbnail(appContext, documentId, File(it.imageUri), it.confidentialMarks)
                    }
                    touchDocument()
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to reorder page")
            }
        }
    }
```

Change the last line of `deletePage`:

```kotlin
                    FileUtils.generateThumbnail(appContext, documentId, File(remaining.first().imageUri))
```

to:

```kotlin
                    FileUtils.generateThumbnail(
                        appContext,
                        documentId,
                        File(remaining.first().imageUri),
                        remaining.first().confidentialMarks,
                    )
```

Add three new methods, right after `clearPendingSignature()`:

```kotlin
    fun toggleMarkingConfidential() {
        isMarkingConfidential.value = !isMarkingConfidential.value
    }

    fun addConfidentialMarkStroke(page: Page, stroke: ConfidentialMarkStroke) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    val updated = page.copy(confidentialMarks = page.confidentialMarks + stroke)
                    documentRepository.updatePage(updated)
                    if (page.pageIndex == 0) {
                        FileUtils.generateThumbnail(appContext, documentId, File(page.imageUri), updated.confidentialMarks)
                    }
                    touchDocument()
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to add confidential mark")
            }
        }
    }

    fun eraseConfidentialMarkStroke(page: Page, stroke: ConfidentialMarkStroke) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    val updated = page.copy(confidentialMarks = page.confidentialMarks - stroke)
                    documentRepository.updatePage(updated)
                    if (page.pageIndex == 0) {
                        FileUtils.generateThumbnail(appContext, documentId, File(page.imageUri), updated.confidentialMarks)
                    }
                    touchDocument()
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to erase confidential mark")
            }
        }
    }
```

Finally, update `export()` to pass marks through to the PDF. Change:

```kotlin
                    val signature = state.pendingSignature
                        ?.takeIf { settings.signatureEnabled }
                        ?.let { pending -> SignaturePlacement(pending.bitmap, pending.pageIndex) }

                    pdfEngine.generatePdf(
                        pageFiles,
                        exportFile,
                        PdfGenerationOptions(
                            ocrEnabled = settings.ocrEnabled,
                            signature = signature,
                            passwordProtection = settings.password
                                .takeIf { settings.passwordEnabled && it.isNotBlank() }
                                ?.let { PasswordProtection(userPassword = it) },
                            targetSizeBytes = if (settings.compressToTargetSize) {
                                (settings.targetSizeMb * BYTES_PER_MB).toLong()
                            } else {
                                null
                            },
                        ),
                    )
```

to:

```kotlin
                    val signature = state.pendingSignature
                        ?.takeIf { settings.signatureEnabled }
                        ?.let { pending -> SignaturePlacement(pending.bitmap, pending.pageIndex) }

                    val confidentialMarksByPageIndex = state.pages
                        .filter { it.confidentialMarks.isNotEmpty() }
                        .associate { it.pageIndex to it.confidentialMarks }

                    pdfEngine.generatePdf(
                        pageFiles,
                        exportFile,
                        PdfGenerationOptions(
                            ocrEnabled = settings.ocrEnabled,
                            signature = signature,
                            confidentialMarksByPageIndex = confidentialMarksByPageIndex,
                            passwordProtection = settings.password
                                .takeIf { settings.passwordEnabled && it.isNotBlank() }
                                ?.let { PasswordProtection(userPassword = it) },
                            targetSizeBytes = if (settings.compressToTargetSize) {
                                (settings.targetSizeMb * BYTES_PER_MB).toLong()
                            } else {
                                null
                            },
                        ),
                    )
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, same as Task 2's run (no new tests in this task).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/papr/scanner/core/FileUtils.kt app/src/main/java/com/papr/scanner/pdf/PdfEngine.kt app/src/main/java/com/papr/scanner/di/AppContainer.kt app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailUiState.kt app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt
git commit -m "Wire confidential marks through DocumentDetailViewModel, thumbnails, and PDF export"
```

---

### Task 7: Drawing/erasing UI

**Files:**
- Create: `app/src/main/java/com/papr/scanner/presentation/detail/components/ConfidentialMarkOverlay.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/components/PagePager.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/components/PageThumbnailStrip.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailScreen.kt`

**Interfaces:**
- Consumes: `ConfidentialMarkStroke`, `NormalizedPoint` (Task 1); `FittedBounds`, `fittedImageBounds`, `isPointNearStroke`, `CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION` (Tasks 3/4); `ConfidentialMarkRenderer` (Task 5); `DocumentDetailUiState.isMarkingConfidential`, `DocumentDetailViewModel.toggleMarkingConfidential/addConfidentialMarkStroke/eraseConfidentialMarkStroke` (Task 6).

No TDD test-first step: Compose UI wiring with no new pure logic. Verified by compile + lint + the manual checklist in Task 8.

- [ ] **Step 1: Create the overlay composable**

```kotlin
package com.papr.scanner.presentation.detail.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.NormalizedPoint
import com.papr.scanner.pdf.CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION
import com.papr.scanner.pdf.ConfidentialMarkRenderer
import com.papr.scanner.pdf.FittedBounds
import com.papr.scanner.pdf.fittedImageBounds
import com.papr.scanner.pdf.isPointNearStroke
import java.io.File

private val renderer = ConfidentialMarkRenderer()

/**
 * Draws a page's confidential marks over its displayed image, aligned to the
 * image's actual fitted/cropped bounds within this composable's own size -
 * [crop] must match the content scale the page image itself is drawn with
 * (false for `ContentScale.Fit`, true for `ContentScale.Crop`). When
 * [editingEnabled], also turns drags into new marks and single taps on an
 * existing mark into an erase.
 */
@Composable
fun ConfidentialMarkOverlay(
    imageFile: File,
    marks: List<ConfidentialMarkStroke>,
    editingEnabled: Boolean,
    onStrokeAdded: (ConfidentialMarkStroke) -> Unit,
    onStrokeErased: (ConfidentialMarkStroke) -> Unit,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(imageFile) { mutableStateOf(IntSize.Zero) }
    var currentStroke by remember(imageFile) { mutableStateOf(listOf<Offset>()) }

    LaunchedEffect(imageFile) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        imageSize = IntSize(bounds.outWidth, bounds.outHeight)
    }

    val fitted = if (containerSize.width > 0 && containerSize.height > 0 && imageSize.width > 0 && imageSize.height > 0) {
        fittedImageBounds(
            containerWidth = containerSize.width.toFloat(),
            containerHeight = containerSize.height.toFloat(),
            imageWidth = imageSize.width.toFloat(),
            imageHeight = imageSize.height.toFloat(),
            crop = crop,
        )
    } else {
        null
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .pointerInput(editingEnabled, fitted, marks) {
                val bounds = fitted
                if (!editingEnabled || bounds == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset -> currentStroke = listOf(offset) },
                    onDrag = { change, _ -> currentStroke = currentStroke + change.position },
                    onDragEnd = {
                        val normalized = currentStroke.map { bounds.normalize(it) }
                        currentStroke = emptyList()
                        if (normalized.size >= 2) {
                            onStrokeAdded(ConfidentialMarkStroke(normalized))
                        } else if (normalized.size == 1) {
                            val tapped = marks.firstOrNull { stroke ->
                                isPointNearStroke(normalized[0], stroke, CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION)
                            }
                            tapped?.let(onStrokeErased)
                        }
                    },
                )
            },
    ) {
        val bounds = fitted ?: return@Canvas
        val liveStroke = currentStroke.takeIf { it.size >= 2 }
            ?.let { ConfidentialMarkStroke(it.map { offset -> bounds.normalize(offset) }) }
        val allMarks = if (liveStroke != null) marks + liveStroke else marks
        if (allMarks.isEmpty()) return@Canvas

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(bounds.left, bounds.top)
            renderer.draw(native, allMarks, bounds.width.toInt(), bounds.height.toInt())
            native.restore()
        }
    }
}

private fun FittedBounds.normalize(offset: Offset): NormalizedPoint = NormalizedPoint(
    x = ((offset.x - left) / width).coerceIn(0f, 1f),
    y = ((offset.y - top) / height).coerceIn(0f, 1f),
)
```

- [ ] **Step 2: Wire it into `PagePager`**

In `app/src/main/java/com/papr/scanner/presentation/detail/components/PagePager.kt`, add imports:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.papr.scanner.domain.model.ConfidentialMarkStroke
```

Change the function signature:

```kotlin
@Composable
fun PagePager(
    pages: List<Page>,
    currentPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
```

to:

```kotlin
@Composable
fun PagePager(
    pages: List<Page>,
    currentPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    isMarkingConfidential: Boolean,
    onMarkStrokeAdded: (Page, ConfidentialMarkStroke) -> Unit,
    onMarkStrokeErased: (Page, ConfidentialMarkStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Change the pager content:

```kotlin
    HorizontalPager(state = pagerState, modifier = modifier) { index ->
        PageImage(
            file = pages.getOrNull(index)?.let { File(it.imageUri) },
            contentDescription = "Page ${index + 1}",
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Fit,
        )
    }
```

to:

```kotlin
    HorizontalPager(state = pagerState, modifier = modifier) { index ->
        val page = pages.getOrNull(index)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PageImage(
                file = page?.let { File(it.imageUri) },
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (page != null) {
                ConfidentialMarkOverlay(
                    imageFile = File(page.imageUri),
                    marks = page.confidentialMarks,
                    editingEnabled = isMarkingConfidential && index == currentPageIndex,
                    onStrokeAdded = { stroke -> onMarkStrokeAdded(page, stroke) },
                    onStrokeErased = { stroke -> onMarkStrokeErased(page, stroke) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
```

- [ ] **Step 3: Show marks in the thumbnail strip**

In `app/src/main/java/com/papr/scanner/presentation/detail/components/PageThumbnailStrip.kt`, add the import:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
```

Change:

```kotlin
        items(pages, key = { it.id }) { page ->
            val isSelected = page.pageIndex == currentPageIndex
            PageImage(
                file = File(page.imageUri),
                contentDescription = "Page ${page.pageIndex + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(56.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        BorderStroke(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onPageSelected(page.pageIndex) },
            )
        }
```

to:

```kotlin
        items(pages, key = { it.id }) { page ->
            val isSelected = page.pageIndex == currentPageIndex
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        BorderStroke(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onPageSelected(page.pageIndex) },
            ) {
                PageImage(
                    file = File(page.imageUri),
                    contentDescription = "Page ${page.pageIndex + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (page.confidentialMarks.isNotEmpty()) {
                    ConfidentialMarkOverlay(
                        imageFile = File(page.imageUri),
                        marks = page.confidentialMarks,
                        editingEnabled = false,
                        crop = true,
                        onStrokeAdded = {},
                        onStrokeErased = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
```

(`ConfidentialMarkOverlay` needs no import here - it's in the same `presentation.detail.components` package as `PageThumbnailStrip`.)

- [ ] **Step 4: Add the toggle button and hint text**

In `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailScreen.kt`, add imports:

```kotlin
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.LocalContentColor
```

Change the page-action row (add the new button between Sign and Replace):

```kotlin
                IconButton(onClick = { showSignatureEditor = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Draw, contentDescription = "Sign page")
                }
                IconButton(
                    onClick = { currentPage?.let { onReplacePageClick(it.id, it.pageIndex) } },
                    enabled = currentPage != null,
                ) {
                    Icon(Icons.Filled.FindReplace, contentDescription = "Replace page")
                }
```

to:

```kotlin
                IconButton(onClick = { showSignatureEditor = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Draw, contentDescription = "Sign page")
                }
                IconButton(onClick = { viewModel.toggleMarkingConfidential() }, enabled = currentPage != null) {
                    Icon(
                        Icons.Filled.Brush,
                        contentDescription = "Mark confidential",
                        tint = if (uiState.isMarkingConfidential) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(
                    onClick = { currentPage?.let { onReplacePageClick(it.id, it.pageIndex) } },
                    enabled = currentPage != null,
                ) {
                    Icon(Icons.Filled.FindReplace, contentDescription = "Replace page")
                }
```

Change the `PagePager` call site:

```kotlin
            PagePager(
                pages = uiState.pages,
                currentPageIndex = uiState.currentPageIndex,
                onPageChanged = viewModel::selectPage,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
```

to:

```kotlin
            PagePager(
                pages = uiState.pages,
                currentPageIndex = uiState.currentPageIndex,
                onPageChanged = viewModel::selectPage,
                isMarkingConfidential = uiState.isMarkingConfidential,
                onMarkStrokeAdded = viewModel::addConfidentialMarkStroke,
                onMarkStrokeErased = viewModel::eraseConfidentialMarkStroke,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            if (uiState.isMarkingConfidential) {
                Text(
                    text = "Drag to cover content, tap a mark to remove it. Hidden text may still be found by " +
                        "search or by copying text from an exported PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
```

- [ ] **Step 5: Verify it compiles and lints**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:lintDebug`
Expected: same warning count as before this task (0 new errors, 0 new warnings) - check `app/build/reports/lint-results-debug.html` if the count changed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/detail/components/ConfidentialMarkOverlay.kt app/src/main/java/com/papr/scanner/presentation/detail/components/PagePager.kt app/src/main/java/com/papr/scanner/presentation/detail/components/PageThumbnailStrip.kt app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailScreen.kt
git commit -m "Add drag-to-mark/tap-to-erase confidential-marker UI"
```

---

### Task 8: Full verification + manual checklist

**Files:** none (verification only)

- [ ] **Step 1: Full build verification**

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Expected: all four succeed. `testDebugUnitTest` should show 18 new tests since the start of this plan (6 from Task 1, 4 + 8 = wait, re-check: Task 1 has 6 tests, Task 3 has 4 tests, Task 4 adds 4 more to the same file for 8 total in `ConfidentialMarkGeometryTest`, so 6 + 8 = 14 new tests total across this plan) beyond the pre-existing suite, all passing. `lintDebug`'s warning count should be unchanged from before this plan (no new warnings introduced).

- [ ] **Step 2: Confirm no stray changes to the two known pre-existing WIP files**

```bash
git status --short app/src/main/java/com/papr/scanner/billing/BillingManager.kt app/src/main/java/com/papr/scanner/scanner/DocumentScannerManager.kt
```

Expected: both still show as modified with exactly the same pre-existing diff they had before this plan started (nothing from this plan's work touched them).

- [ ] **Step 3: Hand off the manual on-device checklist**

No device/emulator is available in this environment - hand this checklist to the user:

- Open a document, tap the new brush icon on the page-action row: it highlights (active state) and a hint about search/copy-paste still finding hidden text appears below the page.
- Drag a finger across part of the page: that exact swiped path is covered with tiled "CONFIDENTIAL" text, fully opaque (no content visible through it).
- Draw a second stroke elsewhere on the same page: its tiled text rows line up with the first stroke's rows (not offset/misaligned).
- Tap directly on an existing mark (still in marking mode): it disappears.
- Tap the brush icon again to exit marking mode: dragging on the page no longer creates marks.
- Leave the document (back to Home) and reopen it: the mark(s) are still there, in the same place.
- A page with a mark shows the mark in the page thumbnail strip at the bottom of the screen.
- If the marked page is page 1: the home screen's grid thumbnail for this document also shows the mark.
- Rotate a marked page-1: the home screen thumbnail still shows the mark correctly positioned after rotation.
- Export the document (Share → PDF): the exported PDF shows the mark in the same place as the in-app view, fully opaque.
- A document with no marks exports and displays exactly as before (no regression).
- Marks on one page never appear on a different page.
- An existing document created before this update (if one is available) still opens correctly and its pages display normally (confirms the database migration didn't break existing data).

- [ ] **Step 4: Hand off to `superpowers:finishing-a-development-branch`**

Once the user confirms the checklist (or asks to proceed regardless), use the finishing-a-development-branch skill: verify tests pass (already done in Step 1), then ask the simplified push-vs-keep-local question this engagement has used throughout (working directly on `main`, already consented).
