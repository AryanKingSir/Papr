# Contributing to Papr

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug or asking for a feature

Open an [issue](https://github.com/AryanKingSir/Papr/issues/new/choose) using the matching template. For bugs, the phone model, Android version, Papr version (Settings > About) and steps to reproduce help a lot. Please don't attach scans of real personal documents.

## Making a change

1. Fork the repo and create a branch from `main`.
2. Open the project in Android Studio, or build with `./gradlew assembleDebug` (JDK 17+).
3. Keep the change focused on one thing, and match the style of the code around it.
4. Add or update unit tests in `app/src/test` when you change logic.
5. Before pushing, make sure these pass with no new warnings:

   ```sh
   ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
   ```

   `assembleRelease` matters: the release build is minified by R8, and some crashes only show up there.
6. Try the change on a device or emulator, ideally on the release build too.
7. Open a pull request against `main` that says what changed and why, and include screenshots for anything visible.

## Commit messages

Write a short summary line in plain language ("Keep the page clear of the Back gesture's edge zones"), then a body explaining what was wrong and why the change fixes it.

## Ground rules

- Papr stays free, with no ads, tracking, analytics or accounts. Changes that add these won't be accepted.
- Documents never leave the phone unless the user shares them.
- By contributing, you agree that your contribution is licensed under the GPL-3.0, like the rest of the project.
