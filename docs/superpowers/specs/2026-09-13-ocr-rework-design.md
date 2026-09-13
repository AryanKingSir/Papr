# OCR Rework — Design

## Context

Sub-project 4 of the decomposed Papr backlog (see
`docs/superpowers/specs/2026-09-12-stability-crash-fixes-design.md` for the
full decomposition). The user's original complaint: "OCR should be available
as soon as pdf is scanned and processed currently it is only available as an
export option and that is honestly pretty trash it deals horribly with
slighly curved pages like books and even on flat pages the selection is wayy
of and not accurate." This breaks into three separate claims, investigated
independently below — one is a real, unbuilt feature; one is a hard limit
this app can't fix; one turned out to have a different (and more fixable)
root cause than the complaint's wording suggested.

Same environment constraints as prior sub-projects: no device/emulator, so
anything requiring visual confirmation on a real scan is handed off to the
user in a manual checklist. Two pieces of this investigation used external
research/data rather than pure code reading: confirming ML Kit's document
scanner's actual capabilities (for item 2), and fetching the real, published
Adobe Helvetica font metrics (for item 3's fix) rather than approximating
them from memory.

## Item 1: OCR at scan time

**Finding:** `Page.extractedText` (`app/src/main/java/com/papr/scanner/domain/model/Page.kt`)
already exists in the domain model, entity, and repository mapping, and a
comment in `InvisibleTextLayerInjector.kt` references it as if it's already
used for in-app search. In reality, every page is created with
`extractedText = null` (in `MainActivity.handleNewDocument`/
`handleAppendPages`) and nothing ever updates it afterward. The schema
anticipated this feature; it was never built.

**Fix:** A new small class wraps text extraction for a single page and
persists the result:

```kotlin
class PageTextExtractor(
    private val ocrTextStamper: OcrTextStamper,
    private val documentRepository: DocumentRepository,
) {
    suspend fun extractAndSave(page: Page)
}
```

It decodes the page's image file at a downsample cap (reusing/extracting the
same capped-decode logic `BitmapPdfCompiler` already has, to avoid
duplicating it — see Architecture below), runs ML Kit recognition via
`OcrTextStamper`, and writes the result through
`documentRepository.updatePage(page.copy(extractedText = ...))`.

Wired into `AppContainer` like the app's other dependencies, and called from
`MainActivity`'s three page-creation/replacement paths
(`handleNewDocument`, `handleAppendPages`, `handleReplacePage`) — replacing a
page must also re-run extraction, since the old text would otherwise be
stale and wrong for the new image. Each call is fired as its own
`lifecycleScope.launch` separate from the launch that saves the
document/navigates the user to it, so OCR runs in the background without
delaying the save-and-navigate flow the user already sees. Per the
user's choice, this is best-effort: no WorkManager, no new dependency. If
the app is closed mid-extraction, that page's `extractedText` just stays
`null` until something re-triggers it (a natural, expected follow-up for
the future search sub-project would be "OCR pending" documents get
retried, but that's out of scope here — search itself isn't part of this
sub-project).

## Item 2: Curved/book pages — out of scope, documented limitation

Researched what Papr's existing capture pipeline (`DocumentScannerManager`,
wrapping Google's `GmsDocumentScanning`) actually does: confirmed it
performs perspective correction and de-skewing (straightening a page
photographed at an angle — a linear, 4-corner transform), but not true
page-curl dewarping (correcting the physical curve of a page near a book's
spine, which needs nonlinear correction). That's active computer-vision
research territory (displacement-flow estimation, grid-regularization neural
networks) — not available in ML Kit's consumer API, and not something to
build from scratch here. Per the user's decision, no fix is attempted; this
is documented as a known limitation of on-device OCR generally.

## Item 3: Selection accuracy on flat pages

**What it's not:** the position math converting OCR-detected coordinates
into PDF space (in `PdfEngine.kt`'s `onPageDrawn` callback) was re-derived
by hand against Android's actual documented `PdfDocument.Page` canvas
semantics (confirmed via research: the canvas uses standard Android
top-left-origin, y-down coordinates matching the page's point dimensions,
with Skia handling the flip to real bottom-up PDF space internally). The
existing Y-flip and centering-offset math checks out exactly against that
model — it is not the bug.

**What it actually is** — two real, fixable issues in
`InvisibleTextLayerInjector.kt`:

1. **Wrong font encoding for accented characters.** The invisible text's
   font dictionary (`<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>`)
   never sets `/Encoding`, so PDF readers fall back to the font's built-in
   `StandardEncoding`. The text bytes themselves are written using
   ISO-8859-1 (Latin-1) (`InvisibleTextLayerInjector`'s existing `latin1`
   charset). `StandardEncoding` and Latin-1 agree on ASCII (32-126) but
   *disagree* on the accented-character range (128-255) — so any OCR'd word
   containing an accent (café, naïve, résumé, ...) selects against entirely
   the wrong glyph codes. Fix: add `/Encoding /WinAnsiEncoding` to the font
   dictionary — WinAnsiEncoding matches Latin-1 for the range that actually
   matters here (the common accented-Latin block), unlike StandardEncoding.

2. **No horizontal scaling to match the real word's width.** The invisible
   layer always renders in Helvetica at a size matching the OCR-detected
   bounding box's *height*, but never adjusts its rendered *width* to match
   that same box's actual width. Since the real printed font on a scanned
   page is essentially never Helvetica, the invisible text's natural width
   at that font size diverges from the real word's width — and that error
   compounds across a line, so selection drifts further off the further
   right you go, worse on longer lines. Fix: PDF's `Tz` (horizontal scaling)
   operator, emitted per text element, computed from
   `(detectedWidth / naturalHelveticaWidth) * 100`. `naturalHelveticaWidth`
   is computed from real Adobe Helvetica character widths — fetched during
   this brainstorm from the authoritative AFM font metrics file (not
   approximated from memory) — for the full printable ASCII range (32-126,
   95 characters, covering the large majority of real-world OCR'd English
   text), with a single averaged fallback width (526/1000 em, itself the
   real average of those 95 verified widths) used for any character outside
   that range. This needs `OcrTextElement` to carry a `width` field
   (currently only position + font size), threaded through the same
   `PdfEngine` scaling `.map{}` that already handles `x`/`y`/`fontSize`
   (`box.width()` scaled the same way, no offset needed since width is a
   relative distance, not an absolute position).

## Architecture

- `OcrTextElement` (in `OcrTextStamper.kt`) gains a `width: Float` field.
- `OcrTextStamper.recognize()` populates it from `box.width()`, alongside
  the existing `x`/`y`/`fontSize` fields.
- `PdfEngine.kt`'s OCR `.map {}` scales `width` by the same
  `POINTS_PER_PIXEL` factor already applied to `fontSize` (no offset needed).
- `InvisibleTextLayerInjector.kt` gets a small Helvetica-width lookup
  (verified real data, not approximated) and emits `Tz` per element in
  `buildContentStream`, plus the `/Encoding /WinAnsiEncoding` addition to
  the font dictionary.
- New `PageTextExtractor` class (package `com.papr.scanner.pdf`, alongside
  `OcrTextStamper`) for item 1, wired into `AppContainer`, called from
  `MainActivity`.
- `BitmapPdfCompiler`'s existing capped-decode logic (`decodeDownsampled`/
  `decodeBounds`/`downsampleFactorFor`/`MAX_PAGE_DIMENSION`) is reused for
  `PageTextExtractor` rather than duplicated — exact extraction shape (a
  shared utility vs. exposing existing private members) is a plan-level
  decision, not a design-level one, since it's a mechanical DRY choice with
  only one reasonable shape once actually implemented.

## Testing approach

Same constraint as prior sub-projects: no device/emulator, no Robolectric.
The Helvetica-width-to-`Tz` computation is pure logic (given a string, a
font size, and a target width, compute a scaling percentage) and gets real
JUnit tests, same pattern as `BitmapPdfCompilerScaleTest.kt`. Everything
else (does OCR actually run after a scan, does the invisible text actually
select accurately on a real page, does WinAnsiEncoding actually fix accented
characters) is verified by code reasoning during implementation plus a
manual on-device checklist handed to the user at the end.

## Success Criteria

- `./gradlew :app:compileDebugKotlin`, `:app:assembleDebug`,
  `:app:lintDebug`, `:app:testDebugUnitTest` all pass.
- New unit tests cover the Helvetica width lookup and the `Tz` percentage
  computation (known strings/widths → expected scaling).
- `Page.extractedText` is code-verified to actually get populated after
  scanning a new document, appending pages, and replacing a page.
- Manual on-device checklist: scan a document, confirm `extractedText`
  eventually appears (can be checked indirectly once the search sub-project
  exists, or via a temporary debug check during this sub-project's
  verification); export a flat-page document with OCR enabled and confirm
  selecting text in a PDF viewer tracks the real words much more closely
  than before, including any accented characters present.

## Out of scope

Curved/book-page dewarping (documented limitation, no fix). Search itself
(a separate, not-yet-started sub-project that will consume
`Page.extractedText` once it exists). File merge and the confidential-marker
stub — unrelated, already-identified sub-projects.
