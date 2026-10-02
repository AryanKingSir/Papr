# Papr — Manual Testing Checklist

One consolidated reference for the on-device checklists produced across every sub-project of this engagement (each is also still in its own plan doc under `docs/superpowers/plans/`, in full context — this file just gathers them in one place to actually run through). Items marked **✓ emulator** were run on the `Pixel_6_Pro_12` emulator (Android 12) on 2026-10-02, after upgrading from the previous release's database. Everything else still needs a real device - in particular anything involving the camera scanner, real OCR quality, or Google Play billing.

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
10. Rotate the screen (or turn on "Don't keep activities" in developer options) while a just-finished scan is saving — the new document still opens, and no empty, thumbnail-less document is left in the grid.
11. Turn on airplane mode, scan, and confirm the scan still saves. ✓ emulator (the OCR-model-missing path: pages are saved, OCR is deferred and retried on next launch).

## 2. Signature, PDF export, compression

1. Tap the signature (pen) icon, draw, tap "Use signature" — the signature appears on the page straight away in the bottom-right, outlined, with a ✕ and a resize handle, and a "Drag the signature to move it…" hint with Done. ✓ emulator
2. Drag the signature somewhere else, and drag its corner handle to resize it — both stick after release, and the page can't be swiped while placing. ✓ emulator
3. Tap Done — the outline/handles go away. Tap the signature again — placement mode comes back. ✓ emulator (Done)
4. Tap ✕ while placing — the signature is removed.
5. Leave the document and reopen it — the signature is still there, where you left it.
6. Rotate a signed page — the signature moves with the page and stays upright and on the page.
7. Export — the signature appears in the PDF exactly where it was placed on screen (there is no longer an "Include signature" switch). ✓ emulator
8. Export a multi-page document whose pages were photographed at different distances — every page is the same size in the PDF and its content fills the page the same way. ✓ emulator (pages of 1600×2200 and 1200×1650 px both fill a 576×792 pt page)
9. Export with "compress to target size" well below the natural size — the file is at or under the target, pages keep their shape, and the paper size is unchanged. ✓ emulator (1.27 MB → 0.2 MB target → 209,405 bytes)
10. Repeat 9 with a high-resolution scan (over ~3000px on its long side) and with a document that has a signature and confidential marks.

## 3. OCR & text layer

1. Scan a new multi-page document — the app navigates to it immediately, without waiting for OCR. A few seconds later `extractedText` should be populated for its pages (check via a device DB inspector, or ask for a temporary debug log statement).
1a. On a fresh install, try exporting with "Searchable text (OCR)" before the text-recognition model has downloaded — you get a "Searchable text isn't ready yet…" message rather than a generic failure. ✓ emulator (before the message existed, this showed the underlying ML Kit error)
2. Append pages to an existing document, and separately replace a page — both get `extractedText` populated, and replacing a page doesn't leave the old page's stale text under the new image.
3. Export a flat (non-curved) page with OCR enabled — open the PDF and select text across a full line; the selection should track the real printed words closely, including near the right edge of long lines.
4. If the scan has accented characters (café, naïve, résumé, or similar), confirm they select correctly too.
5. Export with OCR a page that has a confidential mark — the covered words can't be selected or found in the PDF.

## 4. Search & filtering

1. Search by a document's exact title — it shows up, no "Matched in scanned text" badge.
2. Search by a word that only appears in a scanned page's OCR'd text, not in any title — the document shows up, tagged with the badge. ✓ emulator
3. Pick a query that matches one document's title and a different document's content only — the title match sorts above the content match.
4. Tap the calendar icon in the top bar — the date-range picker opens; picking a range filters the grid to documents scanned within it, including ones scanned late on the last day. Picking a single day (start only) filters to that day. The date under each grid item is the scan date.
5. The active date range shows as a chip below the search bar; tapping it clears the filter.
6. The date filter combines sensibly with an active text search and/or folder filter (narrows further, doesn't fight with them).
7. A search with no results says "No matching documents", not "No documents yet". ✓ emulator
8. On launch, the grid doesn't flash "No documents yet" before documents appear.

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
12. Merging documents that have confidential marks and signatures keeps them on the merged pages. ✓ emulator

## 6. Confidential marker

1. Open a document, tap the brush icon on the page-action row — it highlights (active state), the page can't be swiped, and a hint (with Done) says covered text can still be found by searching in Papr.
2. Drag a finger across part of the page — that exact swiped path is covered with tiled "CONFIDENTIAL" text, fully opaque.
3. Draw a second stroke elsewhere on the same page — its tiled text rows line up with the first stroke's rows.
4. Tap directly on an existing mark (still in marking mode) — it disappears.
5. Tap the brush icon again to exit marking mode — dragging no longer creates marks.
6. Leave the document and reopen it — the mark(s) are still there, in the same place.
7. A page with a mark shows the mark in the page thumbnail strip.
8. If the marked page is page 1: the home screen's grid thumbnail for the document also shows the mark.
9. Rotate a marked page — the mark turns with the page and keeps covering the same words, on screen, in the thumbnail strip, and (for page 1) on the home thumbnail. ✓ emulator (and four rotations return the mark to its exact original position)
10. Export the document — the PDF shows the mark in the same place, fully opaque.
11. A document with no marks exports and displays exactly as before (no regression).
12. Marks on one page never appear on a different page.
13. An existing document created before this update (if available) still opens correctly (confirms the database migration didn't break existing data). ✓ emulator (v2 → v3 upgrade with existing documents and marks)
14. Export a marked page, open the PDF in a PDF editor, and try to delete the mark — it's part of the page image, so nothing underneath is revealed.

## 7. Visual polish & platform details

1. Home grid shows "1 Page" / "N Pages", not "1p"/"4p".
2. The app icon (launcher, recents/overview) has no visible white border/ring, and the logo looks appropriately zoomed in.
3. The home screen's top bar shows a small paper icon immediately before "Papr", visible and correctly colored in both light and dark theme.
4. General smoke test after the targetSdk 37 bump: nothing looks clipped or misaligned around the status/navigation bars.
5. Export filename: export a document you've renamed in-app (e.g. to "Bio"), save the shared PDF somewhere, and check the actual resulting filename matches the in-app title. ✓ emulator (the exported file is `7sept.pdf` for a document titled "7sept"; the old `export_<timestamp>.pdf` naming was replaced on Sep 12)
6. Open the Pro sheet with no internet — it says it couldn't reach Google Play and offers "Try again", instead of spinning forever.
