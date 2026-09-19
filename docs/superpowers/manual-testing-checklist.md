# Papr — Manual Testing Checklist

One consolidated reference for the on-device checklists produced across every sub-project of this engagement (each is also still in its own plan doc under `docs/superpowers/plans/`, in full context — this file just gathers them in one place to actually run through). No device/emulator was available in the dev environment, so none of these have been confirmed run yet.

**Setup:** `./gradlew :app:installDebug`

Work through sections in order if convenient (later sections build on documents/pages created in earlier ones), but they don't strictly depend on each other. For each numbered item: report pass/fail and anything unexpected. Don't guess a fix for a surprise — just report it as a finding.

## 1. Core scanning & document lifecycle

1. Scan a new multi-page document → saves and opens the detail screen.
2. Home screen: scroll the document grid — thumbnails load, scrolling doesn't stutter.
3. Open a saved document → detail screen renders pages correctly (no black screen).
4. Rotate a page, move a page earlier/later — both update instantly.
5. Add pages to a saved document.
6. Replace a page in a saved document.
7. Delete a single page from a multi-page document.
8. Delete a whole document, from both the home screen and the detail screen.
9. Background the app mid-scan — confirm it recovers correctly rather than crashing or losing the scan target.

## 2. PDF export, signature, compression

1. Sign a page and export with no compression target — the signature appears in the bottom-right corner of the correct page, fully visible, not cut off or offset. Repeat with a high-resolution scan (over ~3000px on its long side).
2. Scan a multi-page document from a single source and export it — every page renders at the same size in the PDF (none zoomed in/out relative to the others).
3. Export with "compress to target size" enabled, targeting clearly smaller than the natural export size — check the actual resulting file size is at or reasonably close to the target.
4. Combine signature + compress-to-target-size in the same export — the signature is still correctly positioned after the page is shrunk.

## 3. OCR & text layer

1. Scan a new multi-page document — the app navigates to it immediately, without waiting for OCR. A few seconds later `extractedText` should be populated for its pages (check via a device DB inspector, or ask for a temporary debug log statement).
2. Append pages to an existing document, and separately replace a page — both get `extractedText` populated, and replacing a page doesn't leave the old page's stale text under the new image.
3. Export a flat (non-curved) page with OCR enabled — open the PDF and select text across a full line; the selection should track the real printed words closely, including near the right edge of long lines.
4. If the scan has accented characters (café, naïve, résumé, or similar), confirm they select correctly too.

## 4. Search & filtering

1. Search by a document's exact title — it shows up, no "Matched in scanned text" badge.
2. Search by a word that only appears in a scanned page's OCR'd text, not in any title — the document shows up, tagged with the badge.
3. Pick a query that matches one document's title and a different document's content only — the title match sorts above the content match.
4. Tap the calendar icon in the top bar — the date-range picker opens; picking a range filters the grid to documents scanned within it.
5. The active date range shows as a chip below the search bar; tapping it clears the filter.
6. The date filter combines sensibly with an active text search and/or folder filter (narrows further, doesn't fight with them).

## 5. Multi-select, bulk delete, merge

1. Long-press a document — selection mode starts, that document is checked, the top bar shows "1 selected" (Merge disabled at 1 selected).
2. Tap other documents — they toggle selection, the count updates, Merge enables at 2+.
3. Tap the close (✕) action — selection mode exits, grid returns to normal.
4. Select 1 document, tap Delete — singular confirmation wording ("Delete this document?"), confirming deletes it.
5. Select 2+ documents, tap Delete — plural wording ("Delete N documents?"), confirming deletes all of them and the originals are gone.
6. Select 2+ documents in a specific tap order, tap Merge — the confirm screen lists them in that same order, each with a thumbnail.
7. On the confirm screen: move an item up/down (order changes, buttons disable at the ends), remove an item (Merge disables below 2 remaining), edit the pre-filled title.
8. Tap Merge — a new document opens directly (not the confirm screen), containing the source documents' pages concatenated in the shown order, with a correct page count and thumbnail.
9. Go back from the newly merged document — lands on Home, not the confirm screen.
10. The original source documents are still present in the home grid, unchanged.
11. In the merged document, rotate or delete a page, then check an original source document is unaffected (proves pages were really copied, not shared).

## 6. Confidential marker

1. Open a document, tap the brush icon on the page-action row — it highlights (active state) and a hint about search/copy-paste still finding hidden text appears below the page.
2. Drag a finger across part of the page — that exact swiped path is covered with tiled "CONFIDENTIAL" text, fully opaque.
3. Draw a second stroke elsewhere on the same page — its tiled text rows line up with the first stroke's rows.
4. Tap directly on an existing mark (still in marking mode) — it disappears.
5. Tap the brush icon again to exit marking mode — dragging no longer creates marks.
6. Leave the document and reopen it — the mark(s) are still there, in the same place.
7. A page with a mark shows the mark in the page thumbnail strip.
8. If the marked page is page 1: the home screen's grid thumbnail for the document also shows the mark.
9. Rotate a marked page 1 — the home thumbnail still shows the mark correctly positioned afterward.
10. Export the document — the PDF shows the mark in the same place, fully opaque.
11. A document with no marks exports and displays exactly as before (no regression).
12. Marks on one page never appear on a different page.
13. An existing document created before this update (if available) still opens correctly (confirms the database migration didn't break existing data).

## 7. Visual polish & platform details

1. Home grid shows "1 Page" / "N Pages", not "1p"/"4p".
2. The app icon (launcher, recents/overview) has no visible white border/ring, and the logo looks appropriately zoomed in.
3. The home screen's top bar shows a small paper icon immediately before "Papr", visible and correctly colored in both light and dark theme.
4. General smoke test after the targetSdk 37 bump: nothing looks clipped or misaligned around the status/navigation bars.
5. Export filename: export a document you've renamed in-app (e.g. to "Bio"), save the shared PDF somewhere, and check the actual resulting filename matches the in-app title.
