# Finish Pass — Design

## Context

An audit (2026-10-02) of the user's original backlog against the code found
every sub-project committed, but four things not actually finished:

1. **Page sizes** — the PDF pipeline spec marked this "verify only", so no
   code changed. Every PDF page shares one outer size, but each scan is drawn
   at its own native pixel size, centered. A page photographed from slightly
   further away still renders visibly smaller — the reported symptom. There is
   also a sample-size mismatch: `downsampleFactorFor` computes a non-power-of-2
   `inSampleSize`, the decoder rounds it down to a power of 2, so a very large
   scan decodes bigger than the page box and gets cropped.
2. **Signature** — export placement was fixed, but the app never shows the
   signature on the page, it's lost when leaving the document, and it only
   appears in the export behind an opt-in toggle. User decision: **drag to
   place** — visible on the page immediately, drag to move, resize, persisted,
   exported where placed.
3. **Stability** — the first stability audit predates OCR/marks/merge. A
   re-audit of all ~4,600 lines found new crash, data-loss, and glitch bugs
   (listed below).
4. **Code quality as a whole** — never done as its own pass.

User instruction: proceed without asking for approval; pick the best option
at each decision point. Same environment constraints as before: JVM unit
tests for pure logic, full builds + lint, and the consolidated on-device
checklist for everything visual.

## 1. Uniform page sizing

- Page box stays "largest post-cap page dimensions across the document"
  (scaled by the compression scale).
- Each page bitmap is **fit-scaled into the page box** (aspect preserved,
  centered) via the canvas transform — no extra bitmap memory, the image is
  embedded at native resolution and drawn scaled.
- The compiler hands callbacks a `PagePlacement(offsetX, offsetY, scale)`
  instead of raw page dimensions. Canvas-drawn overlays (signature, marks)
  draw in bitmap pixel space inside the transform; the raw-operator OCR text
  layer applies the same placement by hand (`x * scale + offsetX`).
- One shared `sampleSizeFor(longestSide, maxDimension)` helper returning
  `Integer.highestOneBit(longest / max)` — exactly what the decoder actually
  uses — replaces the three divergent copies (`BitmapPdfCompiler`,
  `FileUtils.rotateJpeg`, `FileUtils.decodeDownsampled`). Because placement
  is computed from the *decoded* bitmap's real size, nothing can be cropped
  even if a decoder rounds differently.

## 2. Signature drag-to-place

**Model.** `PageSignature(imagePath, x, y, width, aspectRatio)` — `x`/`y` the
top-left and `width` the width, all as fractions of the page image;
`aspectRatio` = height/width of the signature image, so layout never needs
to decode it. One signature per page (signing a page again replaces it).
Stored as a nullable Room `@Embedded(prefix = "signature_")` on
`PageEntity`; migration 2→3 adds five nullable columns.

**Image.** The drawn strokes are cropped to their bounding box (+ small
padding) and saved as a transparent PNG `signature_<uuid>.png` in the
document's folder. Replaced/removed signatures delete their file; deleting
the document already deletes the folder.

**Flow.** Sign → draw dialog → "Use signature" → saved on the current page at
the default spot (bottom-right, ⅓ page width) and **placement mode** turns
on: the signature gets an outline, a corner resize handle, and a remove (✕)
button; dragging the body moves it, dragging the handle resizes with aspect
locked, pager swiping is disabled. Changes persist on gesture end. Tapping
the signature (outside marking mode) re-enters placement mode; a "Done"
action exits it. Placement and marking modes are mutually exclusive.

**Everywhere else.** Shown in the page pager and thumbnail strip, baked into
the home thumbnail (like marks), always exported where placed — the export
sheet's "Include signature" toggle is removed (what you see is what you
export). Merge copies the signature file and placement. Replace-page keeps
it. Rotate-page moves it with the page: the signature's center rotates with
the page, the signature stays upright and keeps its width as a fraction of
the new page width, clamped inside the page.

## 3. Stability fixes

Each item names the failure it fixes.

1. **Background OCR crash.** `startTextExtraction` launches in
   `lifecycleScope` with no error handling — a deleted page file or ML Kit
   error crashes the app. Move to an app-lifetime `applicationScope`
   (`SupervisorJob` + `Dispatchers.Default` + logging handler) in
   `AppContainer`; `PageTextExtractor` catches per page and skips.
2. **OCR silently reverting edits.** OCR writes back a whole `Page` snapshot
   taken before recognition, undoing any rotate/move/mark/signature made in
   the meantime. Replace with a targeted
   `UPDATE pages SET extractedText = :text WHERE id = :id`.
3. **OCR cancelled on rotation/recreation** — fixed by (1)'s scope.
4. **Scan import tied to the Activity.** Import runs in `lifecycleScope`
   inside `MainActivity`; recreation mid-import cancels it and a new-document
   failure leaves an empty, thumbnail-less document in the grid. Move import
   into a `ScanImporter` (data layer, application scope) that deletes the
   partial document on any failure, and reports "open this document" /
   "failed" through a buffered `Channel` the activity collects (survives
   recreation, delivered once).
5. **Replace-page ordering.** The old file is deleted *before* the DB points
   at the new one — a failure in between leaves a page with no image (black
   page). Update DB first, then delete; clear stale `extractedText`;
   regenerate the page-1 thumbnail *with* marks/signature (it currently drops
   marks, exposing hidden content in the home grid).
6. **Scanner start failure is silent** (button does nothing) — show a toast.
7. **Stale page snapshots in edits.** Rotate/move/delete/mark/sign operate on
   the `Page` object the UI captured; two quick edits lose the first (e.g.
   two fast mark strokes keep only one). Every page mutation re-reads the
   current row inside `fileMutex` (new one-shot `getPage`/`getPages`
   repository calls).
8. **Rotating a page leaves its confidential marks behind**, uncovering the
   hidden content. Rotate mark points with the page — `(x, y) → (1 − y, x)`
   for 90° clockwise — and the signature per §2.
9. **Black/blank detail screen.** `isDeleted` is read inside `combine` but
   isn't one of its sources, so the "go back" signal can be missed and the
   screen stays on a null document. Add it as a source; add a loading state;
   if the document is confirmed missing, go back instead of rendering empty.
10. **Main-thread disk I/O while swiping.** `ConfidentialMarkOverlay` decodes
    image bounds on the main thread for every page and thumbnail, and caches
    the size per *path* — so after a rotate the cached width/height are
    swapped and marks render misaligned. Decode on IO, keyed on the file's
    last-modified time.
11. **Tap-to-erase never fires.** Erase is detected inside
    `detectDragGestures`, which only reports after touch slop — a tap is never
    a drag. Use `detectTapGestures` for erase.
12. **Thumbnail strip highlights the wrong page** when `pageIndex` and list
    position diverge — use list position.
13. **Home search jank.** Matching every page's OCR text runs on the main
    thread on every keystroke and every page update. Run it on
    `Dispatchers.Default`.
14. **Home empty-state flash / misleading empty state.** The initial empty
    list shows "No documents yet" before data loads, and the same message
    shows when a search simply has no results. Add a loading state and a
    "No matching documents" state.
15. **Date filter drops the selected days.** The picker returns UTC-midnight
    millis; the filter uses them as an exact `start..end` range, so the end
    day (and a single-day pick) matches nothing, and timezones shift days.
    Convert to the local-day inclusive range; a single picked day filters to
    that day. The grid shows `createdAt` (the date the filter uses) instead of
    `updatedAt`, so the date you see is the date you filter by.
16. **Unhandled exceptions → crash / stuck UI** in bulk delete (home) and
    merge (stuck on "Merging…"). Catch and surface errors; the merger deletes
    its partial document on failure or cancellation, and copies marks and
    signatures (currently dropped — a merged copy of a redacted document is
    unredacted).
17. **Paywall infinite spinner** when billing can't connect (offline / no
    Play Store) — show "unavailable, try again" for `Disconnected`, and catch
    connect errors.
18. **Double export** — Export stays tappable while an export runs; disable it.
    Blank titles are rejected on rename.

## 4. Code quality pass

- `MainActivity` shrinks to UI wiring (import logic → `ScanImporter`).
- One `DocumentDeleter` replaces the duplicated "delete row + folder +
  thumbnail" code in `HomeViewModel` and `DocumentDetailViewModel`, and is
  reused by import/merge cleanup. Unused `DocumentDetailViewModel.deleteDocument()`
  is removed (deleting the last page still deletes the document).
- One `ThumbnailRenderer` path for "page image + marks + signature" so every
  thumbnail regeneration site produces the same result.
- `PdfEngine`'s hand-rolled OCR offset math goes through `PagePlacement`.
- Fix all compiler warnings (4 deprecated icons → AutoMirrored) and the
  actionable lint warnings (`UseKtx` ×2, `ConstantLocale`, unused
  `drawable/ic_launcher.xml`).
- Commit Room schema JSON (`app/schemas/` is currently git-ignored despite
  `exportSchema = true`), so migrations have a reviewable history.
- Out of scope, noted only: dependency version bumps, a monochrome themed
  launcher icon (needs a purpose-drawn glyph), moving UI strings to
  `strings.xml`.

## Testing

New pure logic gets JVM unit tests: `sampleSizeFor`, page fit placement,
mark-point rotation, signature-placement rotation/clamping/default placement,
signature stroke-bounds cropping, local-day date-range conversion. Each task
runs `testDebugUnitTest` + `assembleDebug`; the final pass also runs
`assembleRelease` and `lintDebug` (0 errors, no new warnings) and the
migration is checked against the exported schema. The manual checklist gains
the new signature and stability items.
