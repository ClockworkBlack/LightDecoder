# iOS handover (2026-11-17)

Branch: `ios` (from `main` at 0.5.22). Everything iOS is in `ios/` and
`.github/workflows/ios.yml`; nothing of the Android app is changed on this branch.

## Decisions (the user's)
- iPhone version for iPhone-only friends. iOS cannot read or send the iPhone's
  own texts, so it is a messaging app of its own on **Matrix**, on the free
  public server (matrix.org by default).
- The user has a **paid Apple developer account**.
- "Most capabilities possible in line with the Android version."

## State
- `ios/LightDecoderCore` (Matrix client, sync reducer, text rules): **compiled
  and tested** on Linux with Swift 6.0.3: 19 tests pass, one against the real
  matrix.org (it offers m.login.password, m.login.sso, m.login.token).
- `ios/App` (SwiftUI app) and `ios/UITests`: **written but never compiled.**
  No Mac was available: this environment is Linux, and GitHub's macOS runners
  did not pick up any job for this repository on 2026-11-17 (macos-15 and
  macos-26 jobs stayed queued until GitHub failed them after 15 minutes with no
  logs; macos-14 is rejected at once; Linux jobs start in seconds; GitHub's
  status page said all systems operational). The account is new, and GitHub
  limits macOS runners for new accounts, which is the likely cause.
- Expect compile errors on the first real build. Fix them from the build log;
  the code was reviewed by reading only.

## To get a build
Any one of:
1. Start the "Build iOS" workflow again later, in case the queue clears or the
   account's limit lifts. It is manual (`workflow_dispatch`), and GitHub's
   website shows no "Run workflow" button for a workflow file that is not on
   `main`: start it through the API (`run_workflow`, `ios.yml`, ref `ios`;
   that worked on 2026-11-17) or `gh workflow run ios.yml --ref ios`.
2. A Mac with Xcode 16 or later: `brew install xcodegen && cd ios && xcodegen generate`,
   open `LightDecoder.xcodeproj`, build.
3. A CI service with Macs that needs no Mac of one's own (Codemagic, Bitrise,
   Xcode Cloud); with the Apple developer account it can also sign and send
   the app to TestFlight.

## Next
1. First compile; fix what it finds; run the launch test.
2. Sign and distribute (TestFlight) with the developer account. Secrets go in
   the CI's secret store, never in the repository.
3. The same Matrix chat as a tab in the Android app (`ChatBackend`), so both
   sides are LightDecoder. Until then the Android side can use any Matrix app;
   the iPhone side should start the conversation (not encrypted).
4. Push notifications: a `sygnal` push gateway with the app's APNs key, and a
   pusher registered from the app.
5. End-to-end encryption (matrix-rust-sdk's crypto) if wanted.
