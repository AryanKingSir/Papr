# Confidential Marker — Design

## Context

Part of the wrap-up phase of the decomposed Papr backlog (see
`docs/superpowers/specs/2026-09-12-stability-crash-fixes-design.md` for the
full decomposition). The original request, from the very first message of
this engagement, was a single deferred line: "the 'confidential' marker i
asked you to add (this one can be stubbed for later tho)." It was never
touched until now. Five rounds of clarifying questions in this session
turned it into a real feature:

1. It's a **highlighter-style redaction tool**: drag over part of a page and
   that exact swiped path gets visually covered, tiled with repeated
   "CONFIDENTIAL" text — replacing the "strike it with a physical marker"
   workflow.
2. It is **visual-only, non-destructive** — explicitly not burned into the
   page image or the OCR text layer. The user initially assumed real
   physical markers (and this app's existing Sign feature) permanently
   destroy what's underneath; on learning that a "permanent" implementation
   would mean rasterizing into the stored page file, they chose the
   non-destructive route instead. Accepted trade-off: since the underlying
   OCR'd text is untouched, this app's own content search
   (`HomeViewModel.matchDocument`) and copy-paste from an exported PDF could
   still surface covered text. This is called out in the UI (see below)
   rather than solved here.
3. Marks are **saved permanently as part of the document** — present every
   time the document is reopened or re-exported, not a one-off,
   redraw-every-time overlay like the existing signature feature.
4. Rendering approach: **freeform stroke path** (matches "roll it over"),
   but instead of bending text along the curve, the stroke's thick outline
   acts as a clip mask over a simple repeating "CONFIDENTIAL" text pattern —
   full opacity, straight/legible rows, no gaps.

Current relevant state (verified by reading the code):

- **Signature precedent, and why it doesn't fit as-is:**
  `SignatureOverlayEditor` captures freeform strokes (`List<List<Offset>>`)
  in a blank `Canvas` dialog, rendered to a `Bitmap`. That bitmap is held as
  transient in-memory state (`DocumentDetailViewModel.pendingSignature`,
  never persisted to Room) and stamped onto the page **only at export time**
  by `SignatureStamper.stamp(canvas, signature, pageBitmapWidth,
  pageBitmapHeight)`, called from `PdfEngine.generatePdf`'s per-page
  callback. A confidential mark needs to (a) draw directly on top of the
  real page content, not a blank canvas, (b) support many marks across many
  pages, not one signature for the whole document, and (c) persist to Room,
  not live only in ViewModel state.
- **`Page` domain model / `PageEntity`** (`domain/model/Page.kt`,
  `data/local/db/PageEntity.kt`) are simple 1:1-mapped data classes:
  `id, documentId, pageIndex, imageUri, extractedText`.
  `DocumentRepositoryImpl` maps between them with plain `toDomain()`/
  `toEntity()` extensions, and `updatePage(page: Page)` already exists and
  is exactly the primitive `rotatePage`/`movePage` already use to persist a
  changed `Page` via `documentRepository.updatePage(page.copy(...))`.
- **`PdfEngine.generatePdf`** calls `bitmapPdfCompiler.compile(pageFiles,
  outputFile, targetSizeBytes) { canvas, bitmap, pageIndex, pageWidthPx,
  pageHeightPx -> ... }` — a per-page callback where the signature is
  currently stamped, using `bitmap.width`/`bitmap.height` as the scale
  reference. Because this callback runs after `BitmapPdfCompiler` has
  already decided the page's final pixel dimensions (including any
  compression-driven downscaling), anything drawn here in bitmap-pixel space
  automatically lands at the right place regardless of target-size
  compression — no separate scale math needed. This is the natural
  integration point for stamping confidential marks into the export too.
- **`PagePager`** shows the current page via `PageImage` (a Coil
  `AsyncImage`) with `ContentScale.Fit` inside a `fillMaxSize().padding(16.dp)`
  box — no zoom/pan. Because `Fit` letterboxes the image within its
  container when aspect ratios don't match, drawing/hit-testing needs to
  happen against the image's actual fitted bounds, not the full container.
- **`PaprDatabase`** is at `version = 1`, `exportSchema = true`, built via
  plain `Room.databaseBuilder(...).build()` in `AppContainer` — no
  `addMigrations`/`fallbackToDestructiveMigration` yet, so this is the
  project's first schema change and needs its first real `Migration`.

Same environment constraint as every prior sub-project: no device/emulator,
so interactive verification is handed off in a manual checklist.

## Architecture

### Data model

Rather than a new table, marks are stored as a new nullable column directly
on the page, since they're only ever read/written in the context of "this
page's marks," never queried independently:

```kotlin
// domain/model/Page.kt
data class Page(
    val id: Long,
    val documentId: Long,
    val pageIndex: Int,
    val imageUri: String,
    val extractedText: String?,
    val confidentialMarks: List<ConfidentialMarkStroke> = emptyList(),
)

// domain/model/ConfidentialMarkStroke.kt
data class ConfidentialMarkStroke(val points: List<NormalizedPoint>)
data class NormalizedPoint(val x: Float, val y: Float) // both in 0f..1f, relative to page image bounds
```

`PageEntity` stores the same list encoded into a single `TEXT` column
(`confidentialMarks: String? = null`) via pure, top-level encode/decode
functions — the project's established pattern for otherwise-untestable
logic embedded in an Android class:

```kotlin
internal fun encodeMarkStrokes(strokes: List<ConfidentialMarkStroke>): String
internal fun decodeMarkStrokes(raw: String?): List<ConfidentialMarkStroke>
```

Format: strokes joined by `|`, points within a stroke joined by `;`, each
point `"x,y"` (e.g. `"0.10,0.20;0.15,0.22|0.50,0.60"` for two strokes). No
JSON dependency needed for this shape.

A new `Migration(1, 2)` adds the column
(`ALTER TABLE pages ADD COLUMN confidentialMarks TEXT`), registered via
`.addMigrations(...)` on the existing `Room.databaseBuilder` call in
`AppContainer` — additive only, no existing column changes shape.

### Drawing & erasing

A new toolbar icon in `DocumentDetailScreen`'s existing page-action row
(alongside Rotate/Sign/Replace/Delete) toggles "mark confidential" mode for
the current page. While active, the real page image is shown (not a blank
canvas) with a `pointerInput(detectDragGestures)` overlay sized and
positioned to exactly match the image's fitted bounds within its container
(computed the same way `ContentScale.Fit` does, from the container size and
the decoded image's aspect ratio — needed so touch coordinates convert
correctly to the 0f..1f normalized space regardless of screen size).

- **Drag** anywhere on the page starts a new stroke; on drag end, the
  stroke's points are normalized and appended to that page's
  `confidentialMarks`, and `documentRepository.updatePage(page.copy(...))`
  persists it immediately — no separate save step, matching how a real
  marker works ("mark it and it's marked").
- **Tap** directly on an existing stroke (hit-tested against its normalized
  path, using the same tolerance as its render width) removes just that
  stroke and persists the update. No confirmation dialog — nothing is
  actually destroyed, so removal is cheap and reversible by just redrawing.
- A small text hint in the mode's toolbar/header states that hidden content
  may still be found by search or by copying text from an exported PDF
  (the accepted trade-off from the Context section).
- Exiting the mode is just toggling the icon again; there's nothing to
  discard since every stroke already saved itself.

### Rendering

One shared, non-Compose renderer does the actual drawing so the on-screen
preview and the exported PDF are pixel-for-pixel the same visual:

```kotlin
// pdf/ConfidentialMarkRenderer.kt
class ConfidentialMarkRenderer {
    fun draw(canvas: android.graphics.Canvas, strokes: List<ConfidentialMarkStroke>, widthPx: Int, heightPx: Int)
}
```

For each stroke: build an `android.graphics.Path` from its normalized
points scaled by `widthPx`/`heightPx`; stroke it at a fixed fraction of page
width (around 4%, rounded caps/joins) to get the highlighter-width coverage
band;
convert that stroked outline to a fill region (`Paint.getFillPath`); clip
the canvas to that region and fill it with repeating rows of "CONFIDENTIAL"
text over a solid dark background, fully opaque, tiled to cover the whole
stroke's bounding box (so rows line up continuously across the page rather
than restarting per stroke).

This same class is used from two call sites:

- **Live view** (`PagePager` and `PageThumbnailStrip`, both already
  rendering `Page`s from `DocumentDetailViewModel`'s existing `uiState`):
  a small Compose wrapper calls `drawIntoCanvas { it.nativeCanvas }` to hand
  the real `android.graphics.Canvas` to `ConfidentialMarkRenderer`, using
  the same fitted-bounds math as the drag overlay so the rendered marks line
  up with the drag gestures that created them.
- **Export** (`PdfEngine.generatePdf`'s existing per-page callback): a new
  call next to the existing signature stamp, `confidentialMarkRenderer.draw(
  canvas, page.confidentialMarks, bitmap.width, bitmap.height)`, sourced
  from the page list `DocumentDetailViewModel.export()` already has in hand
  — no new "pending" state needed, since marks are always read from
  whatever is currently persisted.

### Home screen thumbnail

The home grid's thumbnail is a separately cached, statically-generated JPEG
(`FileUtils.generateThumbnail`, regenerated today whenever page 0 changes —
e.g. on rotate/reorder/delete). Since marks should never leak in the
library view either, thumbnail generation also composites in
`ConfidentialMarkRenderer` output for that page, and a mark add/remove on
page 0 triggers thumbnail regeneration the same way rotate already does.
The live full-size page image file itself is never touched — only this
derived cache artifact gets the marks baked in, consistent with "visual
overlay, not destructive."

## Testing approach

Same constraint as every prior sub-project: no device/emulator, no
Robolectric. Pure logic extracted into top-level functions gets real JUnit
tests, following this project's established pattern:

- `encodeMarkStrokes` / `decodeMarkStrokes` round-trip (including empty
  list, single point, multiple strokes).
- Hit-testing for tap-to-erase (a point-near-path-within-tolerance function).
- The fitted-bounds calculation (container size + image aspect ratio →
  displayed image rect), since both the drag overlay's touch mapping and the
  live-view renderer depend on it being correct and consistent.

Everything else (actual drawing UI, PDF export visual output, thumbnail
regeneration) is verified by code reasoning during implementation plus a
manual on-device checklist at the end, same pattern as every prior
sub-project.

## Success Criteria

- `./gradlew :app:compileDebugKotlin`, `:app:assembleDebug`,
  `:app:lintDebug`, `:app:testDebugUnitTest` all pass.
- New unit tests cover stroke encode/decode, tap-to-erase hit-testing, and
  the fitted-bounds calculation.
- Manual on-device checklist: entering mark mode and dragging over page
  content covers it with tiled "CONFIDENTIAL" text; the mark is still there
  after leaving and reopening the document; tapping an existing mark removes
  it; a page with a mark shows it in the thumbnail strip and the home grid
  thumbnail; exporting a marked document produces a PDF where the mark is
  visually present in the same place; marks on one page don't appear on
  other pages; an existing document created before this change still opens
  correctly (migration doesn't break existing data).

## Out of scope

Making the mark actually strip the underlying OCR text/search index (the
accepted visual-only trade-off). Freehand color/size customization of the
mark. An "undo" for erase (redrawing is the undo). Any change to the
existing signature feature's behavior.
