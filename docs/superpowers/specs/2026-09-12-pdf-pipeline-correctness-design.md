# PDF Pipeline Correctness — Design

## Context

Sub-project 2 of the decomposed Papr backlog (see
`docs/superpowers/specs/2026-09-12-stability-crash-fixes-design.md` for the
full decomposition and rationale for splitting this work up). Covers three
reported bugs in the PDF export pipeline (`app/src/main/java/com/papr/scanner/pdf/`
and `DocumentDetailViewModel.export`):

1. The signature a user draws and places is captured, but doesn't actually
   appear on the exported PDF page.
2. A multi-page scan of a single source (e.g. a book) produces PDF pages of
   inconsistent sizes.
3. "Compress to target size" doesn't hit the target — a ~16MB export with a
   1MB target still produced an ~11MB file.

As with sub-project 1, there's no crash-log/telemetry infrastructure and no
device/emulator available in this environment, so this pass is code-reasoning
and research-grounded rather than empirically measured; a manual on-device
verification checklist is handed off at the end, same as sub-project 1.

## Finding 1: Signature placement (root-caused, high confidence)

`DocumentDetailViewModel.buildSignaturePlacement` computes the signature's
`Rect` using the *original, full-resolution* file dimensions, read directly
off disk via `BitmapFactory.Options().apply { inJustDecodeBounds = true }`.

But `BitmapPdfCompiler.compile` never draws that original-resolution image.
It draws `decodeDownsampled(pageFile)` — capped at `MAX_PAGE_DIMENSION` (3000px
on the long side) — and, when target-size compression is on, a further-shrunk
version of it. The signature `Rect`, computed against dimensions that can be
meaningfully larger than the actual page canvas, ends up positioned (and
sized) outside where the real page content is. Any scan wider than 3000px on
its long side (common for modern phone cameras) triggers this. A PDF
viewer clips content outside the page's MediaBox, so the signature is either
not visible, or visible in the wrong place — consistent with "takes an input
but doesn't actually place it."

**Fix:** `SignaturePlacement` stops carrying a pre-computed `Rect`. It carries
just the signature `Bitmap` and target `pageIndex`. `BitmapPdfCompiler`
computes the actual placement `Rect` *inside* its per-page draw callback,
where `bitmap.width`/`bitmap.height` are the real, final dimensions for that
page (already downsampled and, if applicable, already compressed). Same
visual sizing as today (bottom-right corner, page-width/3 wide, 40px margin)
— just computed at the point where real dimensions are known instead of
guessed beforehand in the ViewModel. `DocumentDetailViewModel.
buildSignaturePlacement` and its now-unnecessary `BitmapFactory` bounds-decode
are removed.

## Finding 2: Page-size consistency (likely already correct — verify only)

`BitmapPdfCompiler.uniformPageSizePx` computes the max post-downsample-cap
width/height across every page in the document, and `compile` uses that same
`pageWidthPt`/`pageHeightPt` for every page's `PdfDocument.PageInfo`,
regardless of that individual page's own (possibly smaller) bitmap — which
gets centered within the shared size via a canvas translate. Reading this
code, it looks like it already produces uniform PDF page sizes correctly.

**No code change planned for this finding.** It gets re-verified as part of
the on-device checklist at the end of this sub-project. If it's still
inconsistent after the other two fixes land, that's a new finding requiring
its own investigation — not something to guess a fix for without evidence.

## Finding 3: Compress-to-target-size (root cause well-grounded, fix redesigns the mechanism)

Current behavior: `BitmapPdfCompiler.recompress` uses `CompressionIterator`
to step JPEG quality down (and, if quality alone doesn't get there, downscale
pixel dimensions) until a **JPEG-encoded byte count** is under the per-page
target. It then *decodes that JPEG back into a plain `Bitmap`*
(`BitmapFactory.decodeByteArray`) and draws it onto `PdfDocument`'s canvas via
`Canvas.drawBitmap`.

Research (see conversation) into how `android.graphics.pdf.PdfDocument`
embeds canvas-drawn bitmaps converges on: the final PDF's embedded image size
tracks the bitmap's **pixel dimensions**, not any JPEG quality setting —
`PdfDocument`/its Skia PDF backend does not preserve or respect the JPEG
quality used before drawing. So the code's stopping condition ("the JPEG
proxy is small enough") is measuring something that has little relationship
to what actually ends up in the file. Quality reduction shrinks the JPEG
proxy (fooling the loop into stopping) without shrinking what's actually
embedded, which is consistent with the reported 16MB→11MB result: only the
incidental downscaling (triggered when quality alone couldn't hit the
JPEG-byte proxy target) helped at all.

**Fix — measure the real output, iterate:**
- Build the whole PDF at a given global pixel scale (starting at the
  existing downsample cap), write it to disk, check its **actual file size**.
- If over target and attempts remain: compute the next scale factor from
  real measured feedback — raster size scales roughly with pixel area, so
  `nextScale = sqrt(targetSizeBytes / actualSizeBytes)` applied uniformly to
  every page's dimensions — then fully rebuild (re-decode each page file at
  the new scale, redraw, rewrite) and re-measure.
- Bounded to a fixed number of attempts (reusing the existing convention of
  6). If still over target after the last attempt, keep the smallest
  attempt produced rather than fail the export outright — same "best effort"
  philosophy the current code already has for its own downscale floor.
- This is correct by construction regardless of the exact internal mechanism
  `PdfDocument` uses, since it reacts to real measured output rather than a
  proxy.
- `CompressionIterator`'s JPEG-quality-stepping role becomes unused by this
  redesign and is removed (its own change causes this, so removing it is in
  scope — not a pre-existing-dead-code removal).
- The per-page JPEG round-trip (`Bitmap.compress` → `BitmapFactory.
  decodeByteArray`) that existed purely to produce the "is it small enough"
  proxy also goes away — pages are scaled directly via
  `Bitmap.createScaledBitmap` to the attempt's pixel budget, with no
  pointless encode/decode/quality-loss cycle that wasn't buying anything.

## Testing approach

No device/emulator is available in this environment, and the PDF pipeline
uses real `android.graphics`/`android.graphics.pdf.PdfDocument` classes that
don't run under plain JVM unit tests (this project has no Robolectric — only
pure-Kotlin-logic unit tests, per `FileUtilsTest.kt`/`CompressionIteratorTest.
kt`). So:
- Any genuinely pure logic introduced by this work (e.g. the
  `sqrt(target/actual)` scale-factor calculation) gets a real JUnit test,
  same pattern as the existing `CompressionIteratorTest.kt`.
- The rest is verified by code reasoning during implementation, plus a
  manual on-device checklist handed to the user at the end (sign a page and
  export, check the signature appears in the right place; scan a multi-page
  document and check all pages render at the same size; export with a small
  target size and check the actual output file size against it).
- `CompressionIteratorTest.kt` gets deleted along with `CompressionIterator`
  itself, since the class it tests no longer exists after this fix.

## Success Criteria

- `./gradlew :app:compileDebugKotlin`, `:app:assembleDebug`,
  `:app:testDebugUnitTest`, and `:app:lintDebug` all pass.
- Signature placement: code-verified that the Rect is computed against the
  same bitmap actually drawn, in all cases (with and without target-size
  compression active).
- Compress-to-target: code-verified that the loop measures the real output
  file size and only stops when at/under target or attempts are exhausted.
- Page-size consistency: no regression from today's (already-correct-looking)
  behavior.
- Manual on-device checklist (signature placement, page-size consistency,
  target-size accuracy) handed to the user, same pattern as sub-project 1's
  Task 4.

## Out of scope

OCR accuracy/timing, search, export filename, branding/UI, build warnings,
merge feature, confidential-marker stub — all separate, already-identified
sub-projects. Any further PDF-pipeline issue not one of these three specific
reports (e.g. password protection correctness, OCR text-layer alignment) is
not addressed here unless it's incidentally touched by one of these three
fixes.
