# Editor redesign, markup tools, organise pages, image export, home polish — design (2026-10-04)

## Context

The free limits + Pro work is done (main = bccc246). The user's feedback: the document editing screen
is cluttered. Its bottom is a thumbnail strip plus 7 unlabelled icons (move earlier, rotate, sign,
confidential, replace, delete, move later), and the top bar has 2 more unlabelled icons (add pages,
export), so nothing says what a button does. The app is also missing basic markup (pens,
highlighters). The confidential marker should move into a Markup section and stay Pro only.

The user chose all of the following (2026-10-04):
- **Toolbar:** grouped tabs. A bottom bar with 4 labelled buttons (**Markup, Sign, Pages, Export**).
  Tapping one swaps the bar to that group's labelled tools, with a Done button.
- **Markup:** pen, highlighter, eraser, undo/redo, **colour + thickness picker**, **text boxes**,
  and the confidential marker (with a lock icon for free users). The eraser removes **whole strokes**.
- **Paywall:** pen, highlighter, eraser and text are **free and unlimited**. Adding confidential
  marks stays Pro only, unchanged from today.
- **Signature:** keep **one saved signature** on the phone: "Use saved signature" or "Draw new".
- **Organise pages:** a page grid where you **drag to reorder**, plus **multi-select** to rotate or
  delete several pages at once. It replaces the move-earlier/later arrows.
- **Export as images:** the Export sheet gets a **PDF / Images** switch. Images = every page as a
  JPG with everything baked in, through the Android share sheet. Free and unlimited.
- **Home:** labelled controls, a **search bar with a Filter button** (sheet with date and folder,
  active filters as removable chips), nicer document cards, and a **labelled "Pro" pill**.
- **Colours:** **keep Material You wallpaper colours** (no theme change).

Settled decisions from earlier work that must still hold (memory: papr-product-decisions): the
signature is drag-to-place and saved per page. Confidential marks are baked into exported pixels and
drawn above everything else. Free limits are unchanged. Search by date stays a date-range picker.

## Design

### Data model (stored per page, editable, baked into the image on export)
- New `domain/model/PageAnnotation.kt`, a sealed type with normalized (0..1) coordinates like the
  existing marks:
  - `InkStroke(tool: PEN|HIGHLIGHTER, color: Int /*ARGB*/, width: Float, points: List<NormalizedPoint>)`
  - `TextNote(text, x, y, color, size, quarterTurns: Int)`, anchored at its top-left corner.
  - `width` and `size` are fractions of the page's **shorter side**, so a rotate doesn't change thickness.
  - `rotatedClockwise()` for both. Strokes reuse the point formula from
    `ConfidentialMarkStroke.rotatedClockwise()`. A text note moves its anchor and increments
    `quarterTurns`, so the text turns with the page content.
- `Page.annotations: List<PageAnnotation> = emptyList()`. Encode as JSON with `org.json`, which is
  built into Android. Add `testImplementation("org.json:json")` so unit tests get the real
  implementation. Text needs proper escaping, which the hand-rolled `|;,` format can't give.
- Room **v3 → v4**: `MIGRATION_3_4` = `ALTER TABLE pages ADD COLUMN annotations TEXT`. Export
  `schemas/4.json`. Map the field in `DocumentRepositoryImpl` like `confidentialMarks`.
- Confidential marks keep their own column and semantics. They are not migrated.
- `DocumentMerger` copies pages with `page.copy(...)`, so annotations carry over with no file copies needed.

### One renderer for every overlay (targeted refactor)
Right now three places draw a page's overlays, each in its own way: `PdfEngine.preparePage`,
`FileUtils.generateThumbnail`, and the Compose overlays. Image export would add a fourth. Add
`pdf/PageOverlayRenderer.kt` with `draw(canvas, overlays: PageOverlays, widthPx, heightPx)`, where
`PageOverlays(annotations, signature, confidentialMarks)` comes from `Page.overlays()`.
- **Layer order:** highlighter, pen ink, text notes, signature, then confidential marks on top, so a
  mark always hides everything under it.
- Highlighter: one `Path` per stroke, translucent colour (alpha ≈ 0.35), so its own overlaps don't
  darken. Pen: opaque, round caps and joins. Text: `Paint` sized from `size`, with the canvas
  rotated around the anchor by `quarterTurns`.
- It delegates to the existing `SignatureStamper` and `ConfidentialMarkRenderer`.
- `PdfGenerationOptions` replaces `signaturesByPageIndex` and `confidentialMarksByPageIndex` with
  `overlaysByPageIndex: Map<Int, PageOverlays>`. OCR still runs on the drawn image, which keeps
  confidential text out of the text layer.
- In-app display: generalize `ConfidentialMarkOverlay` into `PageOverlayLayer`. It draws through
  the same renderer in the fitted image bounds (`fittedImageBounds`) and routes input by the active
  tool (below). `PagePager` and `PageThumbnailStrip` switch to it.

### Editor screen layout (`DocumentDetailScreen`)
- **Top bar:** back, the title with a small pencil icon (makes tap-to-rename visible), nothing else.
  Add pages and Export move to the bottom.
- **Bottom main bar:** 4 labelled `ToolButton`s (icon + text, 48dp+ touch target):
  Markup · Sign · Pages · Export. Export opens the existing export sheet directly.
- **Sub-bars:** each has a leading **Done** and labelled tools:
  - **Pages:** Rotate · Organise · Replace · Add · Delete. Delete keeps the existing
    "last page deletes the document" dialog.
  - **Sign:** Use saved (only if one exists) · Draw new · Remove (only if the page has one).
    Placing works as today: drag to move, corner to resize.
  - **Markup row 1:** Pen · Highlighter · Text · Eraser · Confidential (lock badge when not Pro).
    **Row 2** holds options for the selected tool: colour swatches (pen and text: black, blue, red,
    green; highlighter: yellow, green, pink, blue), 3 sizes (thin, medium, thick), and Undo / Redo
    at the end. For Confidential, row 2 shows the existing hint text instead.
- While the Markup sub-bar is open, the thumbnail strip hides (more room for the page) and page
  swiping is off. The existing `HintRow` stays only for the confidential and signature hints, and
  its own Done is gone because the sub-bar has one.
- **Landscape:** the main tabs become a labelled vertical rail on the right. The sub-tools stack
  vertically and scroll, the same way the existing landscape branch works.
- **State:** `LocalState` replaces `isMarkingConfidential` and `isPlacingSignature` with
  `mode: EditorMode {NONE, MARKUP, SIGN, PAGES}`, `markupTool`, `penStyle`, `highlighterStyle` and
  `textStyle` (colour + size, held for the session only), and `isPlacingSignature` (inside SIGN).
  Switching pages ends signature placement (existing rule).

### Markup behaviour
- **Pen / Highlighter:** a drag draws a stroke, saved on drag end through `updatePage` (existing
  mutex + thumbnail refresh).
- **Eraser:** dragging over items hides them live. On release, one edit removes them all. Hit-tests
  reuse `isPointNearStroke` (ConfidentialMarkGeometry) with each stroke's own width, plus a text
  note's measured bounds. It also erases confidential marks, which matches the existing rule that
  free users can remove marks.
- **Text:** tapping empty space opens a small dialog to type the text, and it's placed at the tap.
  Tapping a note opens the same dialog to edit or delete it. Dragging a note moves it.
- **Confidential:** today's gating unchanged (`startMarkingConfidential(pageHasMarks)` /
  `addConfidentialMarkStroke` emit `MARKS_NEED_PRO`). Its tap-to-remove behaviour also stays.
- **Undo / Redo:** a new `presentation/detail/markup/MarkupHistory.kt`, a pure class with no
  Android dependencies, so it's unit-testable. Each edit records a snapshot
  `(pageId, before: annotations + confidentialMarks, after)`. Undo writes `before` and redo writes
  `after`, both through the page update path. History lasts for one Markup session: it clears on
  Done and survives page switches inside the session. Undoing an erased confidential mark restores
  it even for free users, because restoring isn't adding.
- Rotate (`rotatePage`) also rotates annotations, and deletes `annotations` along with the page row.

### Saved signature
- `filesDir/signature/saved_signature.png`, stored only on the phone, with helpers on `FileUtils`.
  The draw dialog (`SignatureOverlayEditor`) gets a "Remember this signature" checkbox, checked by
  default.
- "Use saved" copies the PNG into the document's folder first, because document folders must stay
  self-contained (delete and merge rely on that). Then it calls the existing `setSignature` path
  (refactor it to take a file or bitmap) and enters placing mode.
- The saved signature's chip in the Sign bar has a "Forget" action.

### Organise pages (full-screen mode inside the detail screen, shares its ViewModel)
- A `LazyVerticalGrid` of page cards with page numbers. Opened from Pages → Organise, closed with
  back or Done (`BackHandler`).
- **Drag to reorder:** long-press then drag, using `sh.calvin.reorderable` (latest stable). It
  handles auto-scroll and animation, which would otherwise be a lot of custom gesture code.
- **Select:** a "Select" button in the top bar enters selection mode. Tapping a card toggles it, and
  a labelled bottom bar offers **Rotate** and **Delete**. Deleting every page triggers the existing
  "delete this document?" wording.
- ViewModel: `reorderPages(orderedIds)` writes contiguous `pageIndex` values in one Room
  transaction (new repository/DAO method) and regenerates the thumbnail if the first page changed.
  `rotatePages(ids)` and `deletePages(ids)` reuse the per-page logic under one `fileMutex` lock.
  `movePage` and the arrows are removed.

### Export as images
- `ExportOptionsSheet`: a `SingleChoiceSegmentedButtonRow` with **PDF | Images** at the top. Images
  hides OCR, compress and password, and the button reads "Share images".
- `pdf/PageImageExporter.kt`: for each page, decode it (capped with `FileUtils.decodeDownsampled`,
  one page at a time), draw `PageOverlayRenderer`, and write a JPG (quality ~90) to
  `files/documents/<id>/images/<title>-<n>.jpg`. The folder is cleared before each export. It sits
  inside the existing FileProvider `documents/` path, and deleting the document removes it.
- Share with `ACTION_SEND_MULTIPLE` (`ACTION_SEND` for one page), `image/jpeg`, with read-grant
  flags. Not metered, and doesn't touch `pdfUri`.

### Home polish (`HomeScreen` + components)
- **Top bar:** logo + "Papr", then a labelled **Pro** pill (`FilledTonalButton`, star + "Pro").
  It's hidden for Pro owners, which needs `isPro` from `UsageTracker` in `HomeViewModel`.
- **Search row:** the search field plus a labelled **Filter** button with a count badge. Filter
  opens a `ModalBottomSheet` with a Date section (reuses `DateRangeFilterButton`'s
  `DatePickerDialog` logic, refactored into a reusable picker) and a Folder section (chips).
  Active filters appear below as one row of removable `InputChip`s, replacing today's separate date
  chip and always-on folder row.
- **Cards:** `DocumentGridItem` becomes an `OutlinedCard` with the thumbnail on top, the title, and
  one meta line "Mar 3 · 3 pages" (scan date, unchanged). The "Matched in scanned text" badge stays.
- **Selection mode:** a labelled bottom bar with **Delete** and **Merge** (Merge enabled at 2 or more).
- **FAB:** `ExtendedFloatingActionButton` "Scan".

## Implementation order (commit each step on main after its gates pass; push only when asked)

0. Save this design as `docs/superpowers/specs/2026-10-04-editor-markup-redesign-design.md` and commit.
   Invoke `frontend-design` before the first UI step, and use TDD for the model, renderer geometry,
   history and ViewModel logic.
1. Annotation model, JSON codec, rotation, Room v4 migration. Tests: codec round-trip including
   unicode, quotes and newlines in text; rotate.
2. `PageOverlayRenderer` + `PageOverlays`, wired into PdfEngine, the thumbnail and a
   `PageOverlayLayer` that's display-only for now. Exports stay pixel-identical for existing docs
   (compare an exported PDF before and after).
3. Toolbar restructure: main bar + Pages and Sign sub-bars + Export tab, moving the existing actions
   only. Remove the old icon row.
4. Markup: pen, highlighter, eraser, colours, sizes, `MarkupHistory` undo/redo, Confidential tool
   with lock.
5. Text boxes.
6. Saved signature.
7. Organise pages grid: reorder library, multi-select rotate and delete, remove the arrows.
8. Export as images.
9. Home polish.
10. Add an "Editor & markup" section to `docs/superpowers/manual-testing-checklist.md` and record
    the new decisions in memory.

## Files to change (main ones)
- `presentation/detail/DocumentDetailScreen.kt`, `DocumentDetailViewModel.kt`, `DocumentDetailUiState.kt`
- `presentation/detail/components/`: `PagePager.kt`, `PageThumbnailStrip.kt`,
  `ConfidentialMarkOverlay.kt` → `PageOverlayLayer.kt`, `SignatureOverlayEditor.kt`,
  `ExportOptionsSheet.kt`, plus new `EditorToolbar.kt`, `MarkupToolbar.kt`, `OrganizePagesGrid.kt`,
  `TextNoteDialog.kt`
- New: `presentation/detail/markup/MarkupHistory.kt`, `domain/model/PageAnnotation.kt`,
  `pdf/PageOverlayRenderer.kt`, `pdf/PageImageExporter.kt`
- `domain/model/Page.kt`, `data/local/db/PageEntity.kt`, `PaprDatabase.kt` (v4),
  `PaprDatabaseMigrations.kt`, `PageDao.kt`, `data/repository/DocumentRepository(Impl).kt`
- `pdf/PdfEngine.kt`, `core/FileUtils.kt` (thumbnail, saved signature), `di/AppContainer.kt`
- `presentation/home/HomeScreen.kt`, `HomeViewModel.kt`, `components/DocumentGridItem.kt`,
  `SearchBar.kt`, `FilterChipsRow.kt`, `DateRangeFilterButton.kt`
- `gradle/libs.versions.toml`, `app/build.gradle.kts`: add reorderable and org.json (test)

## Verification
- **Gates for every step:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`.
  All tests pass (94 today, plus the new ones), 0 compiler warnings, lint clean. Use
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- **New unit tests:** annotation codec and rotation, eraser hit-testing, `MarkupHistory` (undo,
  redo, a new edit clears redo, page switches), `reorderPages` index writing, the
  `meteredFeaturesFor` image format (no metered features), and ViewModel mode transitions and
  confidential gating.
- **Migration:** build bccc246 in a temp worktree, install it, and seed a doc with a mark and a
  signature. Then `adb install -r` the new build and confirm the data survives and the page opens.
- **Emulator** (Pixel_6_Pro_Android_12, per the papr-emulator-testing memory), debug **and** a
  signed release build:
  - Every tab and sub-tool is labelled.
  - Draw pen, highlighter and text, erase, undo and redo.
  - Rotate a page with markup and check the markup follows.
  - Export a PDF and render it with pymupdf to confirm the markup is baked in and confidential
    stays on top.
  - Export images and check the share sheet gets N JPGs.
  - Reorder by drag, then multi-select rotate and delete.
  - Use the saved signature on a second document.
  - Merge a doc with markup.
  - Home filter sheet and chips, and the selection bar.
  - Landscape and font_scale 2.0 layouts.
  - Toggle the Pro flag through DataStore to check the free-user lock on Confidential.
- **Android 7 AVD smoke:** draw with `input swipe` and check the editor renders at minSdk.
- **Real phone (moto g13):** offer a pass following the papr-real-phone-testing memory once the
  emulator pass is done.
