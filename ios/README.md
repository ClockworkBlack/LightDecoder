# LightDecoder for iPhone

A separate app from the Android one, on its own branch (`ios`), in this folder
only. It does not touch the Android project or its builds.

## What it is

iOS gives no app access to the iPhone's own texts or iMessages, so LightDecoder
on iPhone is a messaging app of its own, the way Signal and WhatsApp are. It
talks over **Matrix**, an open messaging network, on the free public server
`matrix.org` by default (any Matrix server works).

| Tab | What it does |
|---|---|
| **Chats** | Conversations with replies (swipe a message right, or hold it), emoji reactions, read status, typing, pictures, stickers, removing your own messages, invitations, starting a chat by Matrix name (`@anna:matrix.org`). |
| **Stickers** | Make a sticker from a photo: the iPhone's own subject finder cuts it out (tap to choose which person or thing when there are several), white outline with adjustable thickness, sharpen, title, white background. Edit later (the original picture is kept), save as new, share into any app. "Add to my stickers" on any picture received. |
| **Discord** | Discord's own website, unmodified, in a data store of its own (same rules as the Android tab: nothing injected, nothing read). |
| **Settings** | Your Matrix name, display name, notifications, read receipts, typing, sign out. |

## What it does not do (yet)

- **No end-to-end encryption.** Messages are protected on their way (HTTPS) and
  stored on the Matrix server. Encrypted conversations made by other apps
  (Element starts them encrypted) show as "Encrypted message". Start
  conversations from LightDecoder so they are readable on both sides.
- **No instant notifications when the app is closed.** iOS only wakes a closed
  app through Apple's push service, which needs a small relay server holding the
  app's Apple push key (Matrix calls it a push gateway; `sygnal` is the standard
  one). Until that exists, notifications arrive while the app is open or
  recently used, and now and then when iOS lets it check (background refresh).
- No WhatsApp or Google Messages link (the Android app's bridges are not ported).
- No voice or video calls, no file or video sending (they show as a line of text).

## How it is built

- `LightDecoderCore/` is a Swift package with everything that needs no screen:
  the Matrix client (`MatrixAPI`), what the server sends turned into chat state
  (`SyncReducer`), and the text rules. Plain Foundation, so its tests run on
  Linux and macOS: `cd ios/LightDecoderCore && swift test` (19 tests, one of
  them against the real matrix.org when it is reachable).
- `App/` is the SwiftUI app (iOS 17 and later). `project.yml` is the project:
  `brew install xcodegen && cd ios && xcodegen generate` makes
  `LightDecoder.xcodeproj`, which is not committed.
- `.github/workflows/ios.yml` builds on GitHub's Macs when started by hand
  (see `HANDOVER.md`): core tests, a simulator build with a launch test, and an
  unsigned `.ipa` as an artifact. **It has not yet completed a run**: the app's
  screens have not been compiled.

## Installing on an iPhone with a paid Apple developer account

The workflow's `.ipa` is unsigned, so an iPhone will not install it as it is.
Either:

1. **From a Mac with Xcode:** generate the project (above), open it, choose your
   team under Signing & Capabilities (the bundle id is `com.lightdecoder.ios`;
   change it if it is taken), then Product > Archive > Distribute App >
   TestFlight (to invite friends by email) or Ad Hoc.
2. **From GitHub, no Mac needed (not set up yet):** add an App Store Connect API
   key and a distribution certificate as repository secrets, and a signing step
   to the workflow that uploads to TestFlight. Ask for this when the account is
   ready; the secrets never go in the repository.

## Getting started with friends

1. Each person makes a free account on the sign-in page the app opens (or at
   app.element.io), and gets a name like `@anna:matrix.org`.
2. In Chats, tap the pencil and type a friend's name. They get an invitation,
   accept it, and the conversation is open on both sides.
3. On Android, until LightDecoder for Android has the same chat tab, use any
   Matrix app (Element, FluffyChat) with the same kind of account. Let the
   iPhone side start the conversation, so it is not encrypted.
