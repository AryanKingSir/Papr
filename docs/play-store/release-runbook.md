# Release runbook — Papr on Google Play

Your developer account is **personal and was created after Nov 2023**. Google requires a **closed test with at least 12 testers who stay opted in for 14 days in a row** before you can apply for production access. Plan for about 3 weeks from first upload to public launch.

## 0. Keep the upload key safe (do this first)

- Key file: `~/.android/papr-upload.jks` (alias `papr-upload`)
- Passwords: in `keystore.properties` in the project root (gitignored, never committed)
- **Back up both** to somewhere other than this Mac (a password manager plus an encrypted cloud drive). With Play App Signing, a lost upload key can be reset through Play support, but that takes days.

## 1. Build the bundle

```
./gradlew :app:bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`, signed with the upload key, with the R8 mapping file inside (so Play's crash reports show readable stack traces).

For every new upload, bump `versionCode` (and usually `versionName`) in `app/build.gradle.kts`. Play rejects a versionCode it has already seen.

## 2. Create the app in Play Console

1. Play Console > **Create app**: name "Papr - Document Scanner", default language English, **App**, **Free** (free with in-app purchases is still "Free"), accept the declarations.
2. Fill **Policy > App content** using `console-answers.md`.
3. Fill **Store presence > Main store listing** using `listing.md` and the files in `graphics/`.
4. Setup > **App signing**: keep the default, "Use Google-generated key" (Play App Signing).

## 3. Internal testing (just you, same day)

1. Testing > **Internal testing** > Testers: create an email list with your own Google account.
2. **Create release** > upload the `.aab` > release notes > Save > **Start rollout**.
3. Open the opt-in link on your phone, accept, and install Papr from the Play Store.
4. Monetize > Products > In-app products: create and **activate** `pro_lifetime_unlock` (see `console-answers.md`). In-app products can only be created after the first bundle with the BILLING permission is uploaded.
5. Setup > **License testing**: add your Google account so purchases are free test purchases.

**Purchase checks (only possible from a Play-installed build):**
- Open the Pro sheet: price shows. Buy: the limits disappear on the open screen immediately (checklist 10.7).
- Settings > Restore purchase on a second device or after reinstalling: Pro comes back.
- Refund the test order (Order management) and reopen Papr later: Pro goes away (new in this release).
- Turn on airplane mode, then open the Pro sheet: it shows the offline "Try again" state, and Pro is **not** removed.

## 4. Closed testing (the 14-day clock)

1. Testing > **Closed testing** > Create track (or use "Alpha") > Testers: a Google Group or email list with **12 or more** people.
2. Promote the internal release to this track, or upload the same `.aab`.
3. Send testers the opt-in link. They must **opt in and keep the app installed for 14 consecutive days**. Have a few spares, because the count must not drop below 12.
4. Ask testers for feedback. Google asks about it when you apply for production.

## 5. Apply for production

After 14 days: Dashboard > **Apply for production**. You answer questions about the test (how you recruited testers, what feedback you got, what you changed). Review usually takes up to 7 days.

## 6. Production release

1. Production > Create release > promote the tested release > Countries > **Start rollout**. A staged rollout (for example 20%) is sensible for v1.
2. Watch **Android vitals** (crashes/ANRs) for the first days. There's no crash reporting in the app by design, so vitals is the only crash signal.

## After launch: every update

`versionCode` +1 → `./gradlew :app:bundleRelease` → internal track → test → promote to production.
