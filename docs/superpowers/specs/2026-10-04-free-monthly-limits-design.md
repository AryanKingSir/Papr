# Free monthly limits + Pro — design

Approved by the user on 2026-10-04.

## Goal

Papr is free to use, with monthly limits on its heavier features; the existing one-time Pro purchase (`pro_lifetime_unlock`) removes every limit. Today Pro unlocks nothing - `isProUnlocked` is read only by the paywall.

## Plans

| Feature | Free | Pro |
|---|---|---|
| Scan, add/replace pages, plain PDF export, signing, in-app search | Unlimited | Unlimited |
| Export with searchable text (OCR) | 5 per month | Unlimited |
| Export with a password | 5 per month | Unlimited |
| Export compressed to a target size | 5 per month | Unlimited |
| Merge documents | 5 per month | Unlimited |
| Add confidential marks | Not available | Unlimited |

## Counting rules

- Limits are per feature. One export with OCR + password + compression uses one of each of those three.
- A use is recorded only when the export or merge **succeeds**. A failed or cancelled one costs nothing.
- The month is the calendar month in the device's local time; counts reset on the 1st.
- Pro users are never counted or blocked.

## Confidential marks on the free plan

Marks that already exist (made before this update, or carried in by a merge) always keep displaying and are always baked into exports - an export never silently drops a redaction. A free user can still remove a mark: the brush button enters marking mode when the current page has marks, where tapping a mark removes it, but drawing a new one opens the Pro sheet instead. On a page with no marks, the brush button opens the Pro sheet directly.

## What the user sees

- Export sheet: each limited option's subtitle says what's left, e.g. "3 of 5 left this month". With none left, the switch is off and locked; tapping the row opens the Pro sheet. Pro users see the normal subtitles.
- Merge screen: a line under the list says "2 of 5 free merges left this month". With none left, the Merge button reads "Go Pro to merge" and opens the Pro sheet.
- Pro sheet: says what Pro gives ("Unlimited searchable-text, password and compressed exports, unlimited merges, and confidential marks"), and, when opened because a limit was hit, why (e.g. "You've used this month's 5 free searchable-text exports."). It can now open from the detail and merge screens as well as Home.

## Architecture

- `MeteredFeature` enum: `OCR_EXPORT`, `PASSWORD_EXPORT`, `COMPRESSED_EXPORT`, `MERGE`.
- `UsageTracker` (one instance in `AppContainer`) is the single place every screen asks. It stores, in the existing `user_prefs` DataStore, the month the counts belong to and one count per feature. API:
  - `allowances: Flow<Map<MeteredFeature, Allowance>>` - `Allowance(remaining: Int?, limit: Int)`, `remaining == null` meaning unlimited (Pro).
  - `suspend fun canUse(features: Set<MeteredFeature>): Boolean`
  - `suspend fun recordUse(features: Set<MeteredFeature>)` - one atomic DataStore edit; if the stored month isn't the current one, counts start from zero first.
  - The month comes from an injectable clock (`() -> Long` millis), turned into a `year * 12 + month` key with `Calendar` (no `java.time`: minSdk is 24 and the build has no desugaring).
  - Pure helpers hold the logic (`countsForMonth`, `remainingUses`) so it can be unit tested without Android.
- `DocumentDetailViewModel.export()` checks `canUse` for the requested features before generating (guarding a stale sheet) and calls `recordUse` after the PDF is written. `addConfidentialMarkStroke` refuses (and asks for the Pro sheet) when not Pro.
- `MergeConfirmViewModel.merge()` checks `canUse(MERGE)` first and records the use after a successful merge.
- Screens learn "show the Pro sheet, with this reason" from a `SharedFlow` on their ViewModel, plus direct taps on locked controls.

## Known trade-offs

Counts live on the device. Moving the phone's date forward, or clearing app data (which also deletes every document), starts a fresh month. That is normal for an offline app with no accounts and not worth a backend.

## Testing

- Unit: counting, the monthly reset (including a year boundary), Pro bypass, several features in one use, `canUse` at the limit.
- On the moto g13 (debug, then release): use up OCR exports and merges to 0, check each locked path opens the Pro sheet with the right reason, roll the month over (by editing the stored month in the debug build) and see the counts reset, and check that existing marks still export and can be removed on the free plan.
