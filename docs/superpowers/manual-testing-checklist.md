# Papr — Manual Testing Checklist

One consolidated reference for the on-device checklists produced across every sub-project of this engagement (each is also still in its own plan doc under `docs/superpowers/plans/`, in full context — this file just gathers them in one place to actually run through). Items marked **✓ emulator** were run on the `Pixel_6_Pro_12` emulator (Android 12) on 2026-10-02, after upgrading from the previous release's database. A second emulator pass on 2026-10-03 (**✓ emulator 2026-10-03**) covered everything that doesn't need the camera, real OCR, or billing, and fixed the bugs it found (commit af226e8). Everything else still needs a real device - in particular anything involving the camera scanner, real OCR quality, or Google Play billing.

A real-phone pass on a **moto g13** (Android 14, 720×1600, 4 GB) on 2026-10-04 (**✓ moto g13 2026-10-04**) ran everything that doesn't need the camera pointed at paper, a purchase, or a printer, on the debug build and then the release build. Scans went through the ML Kit scanner's "Import from gallery" using generated page photos, so detection, cropping, saving and real OCR were exercised; only the camera capture itself wasn't. It found and fixed: rotate not showing until the document was reopened (55d019b), the OCR text layer landing upside down (6bf5fe9), compress-to-target overshooting when OCR or a password is on (4b25aac), stale exports left behind after a rename (eaed638), long titles growing the top bar (ca3b538), a trailing space breaking search (612e88e), and OCR text missing beyond page 8 (e05eaab).

**Setup:** `./gradlew :app:installDebug`

Work through sections in order if convenient (later sections build on documents/pages created in earlier ones), but they don't strictly depend on each other. For each numbered item: report pass/fail and anything unexpected. Don't guess a fix for a surprise — just report it as a finding.

## 1. Core scanning & document lifecycle

1. Scan a new multi-page document → saves and opens the detail screen. ✓ moto g13 2026-10-04 (via the scanner's gallery import; a camera capture is still to test)
2. Home screen: scroll the document grid — thumbnails load, scrolling doesn't stutter. ✓ emulator 2026-10-03
3. Open a saved document → detail screen renders pages correctly (no black screen). ✓ emulator 2026-10-03
4. Rotate a page, move a page earlier/later — both update instantly. ✓ emulator 2026-10-03
5. Add pages to a saved document. ✓ moto g13 2026-10-04
6. Replace a page in a saved document. ✓ moto g13 2026-10-04 (old file deleted, OCR text replaced, signature kept, home thumbnail updated)
7. Delete a single page from a multi-page document. ✓ emulator 2026-10-03
8. Delete a whole document, from both the home screen and the detail screen. ✗ emulator 2026-10-03 — from Home only (multi-select → Delete). The detail screen has no "delete document" action; a document is only removed there by deleting its last page. Decide whether to add one.
9. Background the app mid-scan — confirm it recovers correctly rather than crashing or losing the scan target. ✓ moto g13 2026-10-04 (process killed while the scanner was open; the pages still went to the right document)
10. Rotate the screen (or turn on "Don't keep activities" in developer options) while a just-finished scan is saving — the new document still opens, and no empty, thumbnail-less document is left in the grid. ✓ moto g13 2026-10-04 ("Don't keep activities"; the rotate variant wasn't run)
11. Turn on airplane mode, scan, and confirm the scan still saves. ✓ emulator (the OCR-model-missing path: pages are saved, OCR is deferred and retried on next launch).

## 2. Signature, PDF export, compression

1. Tap the signature (pen) icon, draw, tap "Use signature" — the signature appears on the page straight away in the bottom-right, outlined, with a ✕ and a resize handle, and a "Drag the signature to move it…" hint with Done. ✓ emulator
2. Drag the signature somewhere else, and drag its corner handle to resize it — both stick after release, and the page can't be swiped while placing. ✓ emulator
3. Tap Done — the outline/handles go away. Tap the signature again — placement mode comes back. ✓ emulator (Done)
4. Tap ✕ while placing — the signature is removed. ✓ emulator 2026-10-03
5. Leave the document and reopen it — the signature is still there, where you left it. ✓ emulator 2026-10-03
6. Rotate a signed page — the signature moves with the page and stays upright and on the page. ✓ moto g13 2026-10-04 (rotate of an unmarked, unsigned page didn't redraw until reopened - fixed in 55d019b) ✓ emulator 2026-10-03 (after fixing af226e8: it used to grow ~1.4× and drift on each turn)
7. Export — the signature appears in the PDF exactly where it was placed on screen (there is no longer an "Include signature" switch). ✓ emulator
8. Export a multi-page document whose pages were photographed at different distances — every page is the same size in the PDF and its content fills the page the same way. ✓ emulator (pages of 1600×2200 and 1200×1650 px both fill a 576×792 pt page)
9. Export with "compress to target size" well below the natural size — the file is at or under the target, pages keep their shape, and the paper size is unchanged. ✓ emulator (1.27 MB → 0.2 MB target → 209,405 bytes)
10. Repeat 9 with a high-resolution scan (over ~3000px on its long side) and with a document that has a signature and confidential marks. ✓ emulator 2026-10-03 (2400×3200 page with a signature and a mark: 241 KB → 0.2 MB target → 209 KB, same 864×1152 pt pages)

## 3. OCR & text layer

1. Scan a new multi-page document — the app navigates to it immediately, without waiting for OCR. A few seconds later `extractedText` should be populated for its pages (check via a device DB inspector, or ask for a temporary debug log statement). ✓ moto g13 2026-10-04 (text within ~3 s of saving; a blank page gets empty text, so it isn't retried)
1a. On a fresh install, try exporting with "Searchable text (OCR)" before the text-recognition model has downloaded — you get a "Searchable text isn't ready yet…" message rather than a generic failure. ✓ emulator (before the message existed, this showed the underlying ML Kit error)
2. Append pages to an existing document, and separately replace a page — both get `extractedText` populated, and replacing a page doesn't leave the old page's stale text under the new image. ✓ moto g13 2026-10-04
3. Export a flat (non-curved) page with OCR enabled — open the PDF and select text across a full line; the selection should track the real printed words closely, including near the right edge of long lines. ✓ moto g13 2026-10-04 (was mirrored into the wrong half of the page on Android 14 - fixed in 6bf5fe9; pages past 8 had no text layer - fixed in e05eaab)
4. If the scan has accented characters (café, naïve, résumé, or similar), confirm they select correctly too. ✓ moto g13 2026-10-04 (naïve, résumé, Zürich, señor, crème brûlée, façade all fine; ML Kit sometimes misreads a letter, e.g. "café" as "cafč", and words with letters outside Latin-1 are left out of the text layer by design)
5. Export with OCR a page that has a confidential mark — the covered words can't be selected or found in the PDF. ✓ moto g13 2026-10-04 (debug and release, with and without a password)

## 4. Search & filtering

1. Search by a document's exact title — it shows up, no "Matched in scanned text" badge. ✓ emulator 2026-10-03
2. Search by a word that only appears in a scanned page's OCR'd text, not in any title — the document shows up, tagged with the badge. ✓ emulator
3. Pick a query that matches one document's title and a different document's content only — the title match sorts above the content match. ✓ emulator 2026-10-03
4. Tap the calendar icon in the top bar — the date-range picker opens; picking a range filters the grid to documents scanned within it, including ones scanned late on the last day. Picking a single day (start only) filters to that day. The date under each grid item is the scan date. ✓ emulator 2026-10-03 (incl. a document at 23:59 on the last day)
5. The active date range shows as a chip below the search bar; tapping it clears the filter. ✓ emulator 2026-10-03
6. The date filter combines sensibly with an active text search and/or folder filter (narrows further, doesn't fight with them). ✓ emulator 2026-10-03
7. A search with no results says "No matching documents", not "No documents yet". ✓ emulator
8. On launch, the grid doesn't flash "No documents yet" before documents appear. ✓ emulator 2026-10-03

## 5. Multi-select, bulk delete, merge

1. Long-press a document — selection mode starts, that document is checked, the top bar shows "1 selected" (Merge disabled at 1 selected). ✓ emulator 2026-10-03
2. Tap other documents — they toggle selection, the count updates, Merge enables at 2+. ✓ emulator 2026-10-03
3. Tap the close (✕) action — selection mode exits, grid returns to normal. ✓ emulator 2026-10-03
4. Select 1 document, tap Delete — singular confirmation wording ("Delete this document?"), confirming deletes it. ✓ emulator 2026-10-03
5. Select 2+ documents, tap Delete — plural wording ("Delete N documents?"), confirming deletes all of them and the originals are gone. ✓ emulator 2026-10-03
6. Select 2+ documents in a specific tap order, tap Merge — the confirm screen lists them in that same order, each with a thumbnail. ✓ emulator 2026-10-03
7. On the confirm screen: move an item up/down (order changes, buttons disable at the ends), remove an item (Merge disables below 2 remaining), edit the pre-filled title. ✓ emulator 2026-10-03
8. Tap Merge — a new document opens directly (not the confirm screen), containing the source documents' pages concatenated in the shown order, with a correct page count and thumbnail. ✓ emulator 2026-10-03
9. Go back from the newly merged document — lands on Home, not the confirm screen. ✓ emulator 2026-10-03
10. The original source documents are still present in the home grid, unchanged. ✓ emulator 2026-10-03
11. In the merged document, rotate or delete a page, then check an original source document is unaffected (proves pages were really copied, not shared). ✓ emulator 2026-10-03
12. Merging documents that have confidential marks and signatures keeps them on the merged pages. ✓ emulator

## 6. Confidential marker

1. Open a document, tap the brush icon on the page-action row — it highlights (active state), the page can't be swiped, and a hint (with Done) says covered text can still be found by searching in Papr. ✓ emulator 2026-10-03
2. Drag a finger across part of the page — that exact swiped path is covered with tiled "CONFIDENTIAL" text, fully opaque. ✓ emulator 2026-10-03
3. Draw a second stroke elsewhere on the same page — its tiled text rows line up with the first stroke's rows. ✓ emulator 2026-10-03
4. Tap directly on an existing mark (still in marking mode) — it disappears. ✓ emulator 2026-10-03
5. Tap the brush icon again to exit marking mode — dragging no longer creates marks. ✓ emulator 2026-10-03
6. Leave the document and reopen it — the mark(s) are still there, in the same place. ✓ emulator 2026-10-03
7. A page with a mark shows the mark in the page thumbnail strip. ✓ emulator 2026-10-03
8. If the marked page is page 1: the home screen's grid thumbnail for the document also shows the mark. ✓ emulator 2026-10-03
9. Rotate a marked page — the mark turns with the page and keeps covering the same words, on screen, in the thumbnail strip, and (for page 1) on the home thumbnail. ✓ emulator (and four rotations return the mark to its exact original position)
10. Export the document — the PDF shows the mark in the same place, fully opaque. ✓ emulator 2026-10-03
11. A document with no marks exports and displays exactly as before (no regression). ✓ emulator 2026-10-03
12. Marks on one page never appear on a different page. ✓ emulator 2026-10-03
13. An existing document created before this update (if available) still opens correctly (confirms the database migration didn't break existing data). ✓ emulator (v2 → v3 upgrade with existing documents and marks)
14. Export a marked page, open the PDF in a PDF editor, and try to delete the mark — it's part of the page image, so nothing underneath is revealed. ✓ emulator 2026-10-03 (each PDF page is one image; no annotations, drawings or text layer)

## 7. Visual polish & platform details

1. Home grid shows "1 Page" / "N Pages", not "1p"/"4p". ✓ emulator 2026-10-03
2. The app icon (launcher, recents/overview) has no visible white border/ring, and the logo looks appropriately zoomed in. ✓ emulator 2026-10-03 (no ring; the paper now fits inside the round mask, 7480aac)
3. The home screen's top bar shows a small paper icon immediately before "Papr", visible and correctly colored in both light and dark theme. ✓ emulator 2026-10-03 (light and dark)
4. General smoke test after the targetSdk 37 bump: nothing looks clipped or misaligned around the status/navigation bars. ✓ emulator 2026-10-03
5. Export filename: export a document you've renamed in-app (e.g. to "Bio"), save the shared PDF somewhere, and check the actual resulting filename matches the in-app title. ✓ emulator (the exported file is `7sept.pdf` for a document titled "7sept"; the old `export_<timestamp>.pdf` naming was replaced on Sep 12)
6. Open the Pro sheet with no internet — it says it couldn't reach Google Play and offers "Try again", instead of spinning forever. Not verifiable on the moto g13 2026-10-04: a sideloaded build has no Play product, so the sheet says "Unavailable right now" online and offline alike (Play returns OK with no products in both cases). Needs a build installed from a Play testing track. ✓ emulator 2026-10-03 (no Play Store on the emulator; still worth checking with airplane mode on a phone)

## 8. Release build & robustness

Run these on the **release** build - it's what ships, and R8 minification can break things the debug build never shows (it did: the release build crashed on launch until 2b319d2). To install it on a phone signed with your local debug key: `./gradlew :app:assembleRelease`, then `zipalign -p 4` and `apksigner sign --ks ~/.android/debug.keystore` (password `android`) the `app-release-unsigned.apk` from `app/build/outputs/apk/release/`, and `adb install -r` it.

1. The release build launches, and scanning, OCR, signing, marking, export (with compression, password and OCR), merge, delete and the Pro sheet all work. ✓ moto g13 2026-10-04 (cold start 0.8-1.2 s vs 3.3 s debug; 0.2-0.5% janky frames paging a 14-page document and scrolling home) ✓ emulator 2026-10-03 (everything except the camera scan and real OCR, which this emulator can't do)
2. Background the app, let Android kill it (or `adb shell am kill com.papr.scanner`), then return - it reopens on the same screen; on the merge screen, your order and title are kept. ✓ moto g13 2026-10-04 (reopens the same document, but on page 1 rather than the page you were on) ✓ emulator 2026-10-03
3. With "Don't keep activities" on: export and come back from the share sheet, and leave the app mid-signature - nothing is lost or crashes. ✓ emulator 2026-10-03
4. Landscape: the home grid shows more columns, the detail screen's tools move to a side rail, the date picker fits, and every export option (including Export itself) is reachable. ✓ moto g13 2026-10-04 ✓ emulator 2026-10-03
5. Largest font size: nothing overlaps, and the export sheet still scrolls to Export. ✓ moto g13 2026-10-04 ✓ emulator 2026-10-03
6. A fresh install (no documents): "No documents yet" with a Scan button; the Pro sheet without Google Play says it couldn't reach it, with Try again. ✓ emulator 2026-10-03
7. Random-input stress test (`adb shell monkey -p com.papr.scanner ...`) - no crashes or freezes. ✓ moto g13 2026-10-04 (5,000 events) ✓ emulator 2026-10-03 (20,000 events across debug and release)

## 9. Android 7 (API 24, the minSdk) - emulator pass 2026-10-04

Run on the `Medium_Phone_Android_7` AVD (Android 7.0, 1080x2400, 420dpi) with 14 seeded documents, on both the debug and the R8 release build. That emulator's Google Play services is 20.24 (2020) and can't update without an account, so the scanner and OCR can't run there - which doubles as a test of an old phone with outdated Play services.

1. Launch, home grid, search (title / scanned text / no results), date filter (single day incl. 23:59, range, combined with search, chip clears it), detail screen, rotate, reorder, delete page, sign (draw, place, move, resize, persist, rotate x4), confidential marks (draw, tap to remove, Done), export (plain, compressed under target, password), rename + export filename, merge (tap order, reorder, process death on the confirm screen, signature/marks copied), single and bulk delete (files and thumbnails removed), Pro sheet ("couldn't reach Google Play" + Try again), landscape, largest font + display size, "Don't keep activities", 30,000 monkey events. ✓ emulator 2026-10-04 (debug and release)
2. Outdated Play services: Scan / Add pages / Replace page show "Couldn't open the scanner. Update Google Play services and try again." instead of crashing; OCR export shows the "Searchable text isn't ready yet" message. ✓ emulator 2026-10-04
3. Bugs found and fixed on branch `android7-pass` (merged into main 2026-10-04): moving and resizing a signature undid each other; Back in multi-select closed the app; the scan button covered the last grid item; clearing a filter or search left the grid mid-list with newer documents hidden above; the page tools squashed "Move page later" to a sliver at the largest display size (and on 320dp phones); the keyboard's Done key didn't save a rename. Each re-verified on the emulator after the fix.

## 10. Free limits and Pro

The free plan gets 5 searchable-text (OCR), 5 password and 5 compressed exports and 5 merges a month. Adding confidential marks needs Pro, and Pro removes every limit (spec: `specs/2026-10-04-free-monthly-limits-design.md`). The counts live in `files/datastore/user_prefs.preferences_pb` (`usage_month` = year × 12 + month, where January is 0, plus `usage_<feature>`). To test Pro, or another month, without a purchase: force-stop the app, rewrite that file (e.g. `is_pro_unlocked`=true, or `usage_month` set to last month), push it back, `chown` it to the app's uid, and relaunch.

1. Export sheet: each option says "N of 5 left this month". An export with compression + password uses one of each and leaves OCR alone. With none left the switch becomes a lock, and tapping the row opens the Pro sheet saying which limit was hit. ✓ emulator 2026-10-04 (debug and release)
2. A failed export costs nothing (OCR export when text recognition isn't ready stays at 5 of 5). ✓ emulator 2026-10-04
3. Merge screen: "N of 5 free merges left this month" drops after a merge. With none left the button reads "Go Pro to merge" and opens the Pro sheet with the reason. ✓ emulator 2026-10-04
4. Marks on the free plan: the Confidential tool (Markup, with a lock) on a page without marks opens the Pro sheet. On a page with marks it selects the tool ("Tap a mark to remove it. Adding marks needs Papr Pro."): tapping a mark removes it, and a drawn stroke is dropped and opens the Pro sheet. Existing marks are still baked into a free plain export. ✓ emulator 2026-10-04
5. New month: with the stored month set to last month, every count is back to 5 of 5, and the first use writes the current month. ✓ emulator 2026-10-04 (debug and release)
6. Pro: no counts on the export sheet or merge screen, nothing is locked, uses aren't counted, and marking shows the full hint. ✓ emulator 2026-10-04 (by setting `is_pro_unlocked`)
7. A real purchase from the Pro sheet removes the limits on the open screen right away, with no restart. Needs a Play testing track build.

## 11. Editor & markup (redesign 2026-10-04)

The editor's bottom bar is four labelled groups - Markup, Sign, Pages, Export - each opening its own labelled tools with Done (spec: `specs/2026-10-04-editor-markup-redesign-design.md`). Markup is free; only adding confidential marks needs Pro.

1. Every button on the editor and home screen has a text label; the title shows a pencil and tapping it renames. Back steps out of a tool group before it leaves the document. ✓ emulator 2026-10-04
2. Pen and highlighter: each of the four colours and three sizes draws as previewed; a highlighter crossing itself doesn't get darker; markup shows on the page, its thumbnail and the home card. ✓ emulator 2026-10-04
3. Text: tap to add (dialog), tap the text to edit or delete it, drag it to move it; line breaks and accented characters survive. ✓ emulator 2026-10-04
4. Eraser: dragging across lines, highlights, text or confidential marks removes them whole (the signature is never erased). Undo/Redo step through every change in the session, across pages; they reset when Markup is closed. ✓ emulator 2026-10-04
5. Rotate a page with markup: the lines, text (turned with the page) and marks stay on the same content, at the same thickness. ✓ emulator 2026-10-04
6. Export a PDF and images: markup, signature and confidential marks are baked in, confidential marks on top. A document exported before this update comes out byte-identical. ✓ emulator 2026-10-04 (debug and release)
7. Sign: with nothing saved, "Remember this signature" (on by default) saves it; "Use saved" puts it on another page or document at the right proportions. (Replacing or removing the saved signature moved to Settings - see section 12.) ✓ emulator 2026-10-04
8. Pages > Organize: hold and drag a page to reorder (the editor and thumbnail follow); Select pages to rotate or delete them together; deleting every page warns that the document goes too; tapping a page opens it. ✓ emulator 2026-10-04 (debug and release)
9. Export > Images: every page as a JPG through the share sheet; free, even with PDF options left on. ✓ emulator 2026-10-04 (debug and release)
10. Home: Filter button (with a count) opens date and folder filters; filters in use are removable chips; Pro button hidden for Pro owners; selecting shows labelled Delete / Merge above the navigation bar; the scan button reads "Scan". ✓ emulator 2026-10-04
11. Merging documents with markup keeps it, and the signatures. ✓ emulator 2026-10-04 (release)
12. Upgrading from the previous version (database v3 to v4) keeps every document, mark and signature. ✓ emulator 2026-10-04
13. Landscape (tools on a side rail, markup options under the page) and the largest font size (tool rows scroll sideways). ✓ emulator 2026-10-04
14. Drawing, erasing and dragging text with a finger on a real phone feel right - no lag, strokes follow the finger, a stray touch doesn't swipe pages while marking up.

## 12. Settings (2026-10-04)

Opened from the gear on the home screen (spec: `specs/2026-10-04-settings-design.md`).

1. Theme System / Light / Dark applies at once, the status and navigation bar icons follow it, and it survives a restart. Material You off uses Papr's blue; on Android 11 and below the switch is disabled with "Needs Android 12 or later". ✓ emulator 2026-10-04 (Android 12 and 7)
2. Saved signature: Settings shows it with Draw new signature (replaces it, preview updates) and Remove (asks first; signed pages keep theirs), or Add a signature when none is saved. ✓ emulator 2026-10-04
3. Editor, signature saved: Sign offers Use saved, Draw new (a one-off - no Remember box, the saved one is untouched) and Remove from page; no Forget. With nothing saved, Draw new offers Remember. ✓ emulator 2026-10-04
4. Merge: "Delete the originals" starts at the Settings default; when on the button reads "Merge and delete originals", and after merging the originals, their files and thumbnails are gone. Off keeps them. ✓ emulator 2026-10-04
5. "Export starts with" Images opens the export sheet on Images. ✓ emulator 2026-10-04
6. Restore purchase: without Google Play it says it couldn't reach it; on a Play-installed build with Pro bought on the account it restores Pro. ✓ emulator 2026-10-04 (no-Play case only; the restore itself needs a Play testing track build)
7. The home screen logo matches the app icon in light and dark; the select box on Home and in Organize pages is a single squircle that shows on white and coloured pages. ✓ emulator 2026-10-04
8. Settings at the largest font size and in landscape: nothing overlaps (the signature buttons wrap). ✓ emulator 2026-10-04 (release)

