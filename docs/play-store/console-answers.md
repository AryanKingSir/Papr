# Play Console answers — Papr

Answers for every "App content" form (Play Console > Policy > App content), based on what the code actually does as of v1.0.0. You're responsible for the final answers. If the app changes (analytics, accounts, ads), update them.

## Privacy policy
`https://funnykid7.github.io/papr-legal/`

## Ads
**No, my app does not contain ads.**

## App access
**All functionality is available without special access.** (No login. Pro features need a purchase, and reviewers don't need them unlocked. Optionally add a note: "Pro features are a one-time in-app purchase; all core features are free.")

## Content rating (IARC questionnaire)
- Category: **All other app types** (utility/productivity)
- Violence, sexuality, language, controlled substances, gambling: **No** to all
- Users can interact or exchange content with each other: **No** (sharing goes through Android's share sheet to other apps; Papr has no messaging or user-to-user features)
- Shares user location: **No**
- Allows purchases of digital goods: **Yes** (one-time Pro unlock)
- Expected result: **Everyone / PEGI 3**

## Target audience and content
- Target age groups: **18 and over** (you may also tick 13–17; do **not** tick any under-13 group, or the app falls under the Families policy and its extra requirements)
- Appeals to children: **No**

## News app
**No.**

## Government app
**No.**

## Financial features
**My app doesn't provide any financial features.**

## Health
**My app does not have any health features.**

## Advertising ID
**No**, the app does not use an advertising ID. (The merged manifest has no `AD_ID` permission.)

## Data safety

The app itself sends nothing to you (it has no server). Two Google libraries inside it do send data, and Google's guidance is that you declare what they collect.

**Does your app collect or share any of the required user data types?** → **Yes**
**Is all of the user data collected by your app encrypted in transit?** → **Yes**
**Do you provide a way for users to request that their data is deleted?** → **No** (nothing is stored off-device by you; on-device data is deleted by uninstalling). If the form insists on a method, you can answer that users can email papr.support@gmail.com.

Data types to tick:

| Category > type | Collected | Shared | Ephemeral? | Required/optional | Purposes | Source |
|---|---|---|---|---|---|---|
| App info and performance > **Diagnostics** | Yes | No | No | Required | Analytics, App functionality | ML Kit (performance metrics, error codes) |
| App info and performance > **Other app performance data** | Yes | No | No | Required | Analytics | ML Kit (API config, input/output sizes) |
| Device or other IDs > **Device or other IDs** | Yes | No | No | Required | Analytics | ML Kit (per-installation identifier) |
| Financial info > **Purchase history** | Yes | No | No | Optional | App functionality | Google Play Billing (checks whether Pro is owned) |

Do **not** tick: Photos/videos, Files and docs, Personal info, Location, Contacts, Messages, Audio, Calendar, Web browsing, App activity. Page images and documents never leave the device, because scanning and OCR run on-device.

Source: [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure). Purchase history is ticked conservatively: the app only reads ownership locally from Google Play, but declaring it is the safe reading of Google's guidance for apps that use Play Billing.

## In-app product

Monetize > Products > In-app products > **Create product**
- Product ID: **`pro_lifetime_unlock`** (must match exactly; it's hard-coded in the app)
- Name: **Papr Pro**
- Description: **Removes all monthly limits and unlocks confidential marking. One-time purchase.**
- Price: your choice. Set a default price and let Play convert it for other countries.
- **Activate** it. An inactive product makes the Pro sheet show "unavailable".

## Countries
Any. The app is English-only, which doesn't block other countries.
