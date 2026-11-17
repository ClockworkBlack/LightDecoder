# LightDecoder for iOS

This branch (`ios-app`) is where the iOS app is developed. It starts from the
Android app at 0.5.24; nothing iOS-specific exists yet. The Android app stays
on `main` and its own branches.

## What carries over from the Android app

| Part | On iOS | Notes |
|---|---|---|
| Google Messages link (`gmbridge/`, Go, libgm) | Likely reusable | gomobile can bind the same Go packages for iOS (`gomobile bind -target=ios` produces an `.xcframework`). Needs macOS with Xcode; not tried yet. |
| WhatsApp link (`gmbridge/wa/`, Go, whatsmeow) | Likely reusable | Same as above, in the same framework. |
| HEIC stickers (libheif + libde265) | Not needed | iOS decodes HEIC, including stickers with transparency, through ImageIO. |
| Archive, outbox, reaction times (Kotlin + SQLite) | Rewrite | Same design, in Swift (SQLite or SwiftData). The behaviour is pinned down by the Android tests in `app/src/test/`. |
| Discord tab, Messenger "Full history" page (Android WebView in their own process) | Rewrite | `WKWebView`, each with its own `WKWebsiteDataStore(forIdentifier:)` (iOS 17+) to keep their cookies apart, which is what the separate processes do on Android. |
| Gmail tab (IMAP/SMTP) | Rewrite | Through a Swift IMAP library, or the Go side. |
| Messenger inbox and sticker catching from notifications | **Not possible** | iOS does not let an app read other apps' notifications (Android's NotificationListenerService has no iOS counterpart). Messenger on iOS would be the web page only. |
| Sticker maker (ONNX models) | Rewrite | ONNX Runtime has an iOS package; Apple's Vision framework can also cut out subjects. |

## Before writing code

- **A Mac with Xcode** is needed to build and sign anything for iOS, and an
  Apple Developer account to install on a phone outside Xcode.
- **App Store review** looks harder at unofficial clients of other services
  than Google Play does; the Google Messages and WhatsApp links may not be
  accepted there. Sideloading or TestFlight is the realistic first target.
- **Release signing** is separate from Android: the Android release key does
  not apply.

Nothing in this file has been tried on iOS yet; treat the "Likely" rows as
things to check first.
