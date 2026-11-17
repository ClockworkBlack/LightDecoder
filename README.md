# LightDecoder

An Android app that decodes and displays the HEIC stickers and images that
iPhones send and that Google Messages often shows as a broken **"Can't load
image — this media format is not available on this device."**

It ships its own HEVC/HEIF decoder (libde265 + libheif, built in), so it does
**not** depend on whether a given phone has HEIC support. It opens a `.heic`
file or a sticker shared into it, decodes transparency and animation on-device,
and lets you view, save, and re-share it as a PNG.

Since 0.3.0 it can also, if you set it up, link to Google Messages on your
phone the way Messages for web does, and work as a local messaging client:
read, search, and send your texts, with HEIC stickers that arrive that way
decoded automatically. That part is optional, does nothing until you pair, and
has real costs; see "Google Messages link" below.

> Status: working. The decoder, the viewer, and an on-device sticker inbox that
> catches incoming stickers automatically all produce an installable APK, with a
> unit-test suite that decodes real HEIC files. See "Automatic sticker inbox"
> below for how the automatic catching works and stays within Android's rules.
>
> The Google Messages link is new in 0.3.0 and experimental. Its logic is
> covered by unit tests against a stand-in for the Go bridge, but pairing with a
> real phone and talking to Google's real servers are not covered by any
> automated test.

## Why this exists

iPhone stickers arrive on Android as HEIC. Android's HEIF support is **optional
per device** (Google lists it as optional in the CDD, and it depends on a
hardware HEVC decoder), so many phones can decode a plain HEIC photo but choke
on sticker files — which carry an **alpha auxiliary image** for transparency,
and sometimes an **image sequence** for animation. LightDecoder handles those
itself.

## What works today

- **Self-contained decoder.** libde265 (HEVC) + libheif, compiled into one
  `liblightdecoder.so`, linked statically, for `arm64-v8a`, `armeabi-v7a`, and
  `x86_64`. No reliance on the system image pipeline.
- **Transparency.** The alpha auxiliary image is decoded and composited over a
  checkerboard so you can see it.
- **Animation.** Image sequences play on the view's frame clock.
- **Receive / open.** Registers for `image/heic`, `image/heif`, their
  `-sequence` variants, and `.heic` / `.heif` / `.heics` by name, for both
  "open with" and "share into". From other apps it takes `content://` links
  only: `file://` paths, and links into LightDecoder's own storage, are
  refused.
- **Save / share.** Export the current frame to the gallery (scoped storage on
  Android 10+) or share it as a PNG via `FileProvider`.
- **Details.** Reports container brands, size, bit depth, alpha, colour profile,
  and frame count.

- **Sticker effects.** iPhones store a plain cut-out plus an XMP note
  (`stickerEffect:type="stroke"`) and draw the Outline/Comic/Puffy/Shiny effect
  themselves. LightDecoder reads that note, labels the effect, and draws the
  Outline effect (a white border following the alpha edge); a button toggles it.

Verified against real iPhone sticker files and against synthetic files whose
every pixel is known: still, opaque, transparent, animated (intra- and
inter-coded) all decode correctly — through the native probe, through the JVM
across the real JNI boundary (`tools/jni-smoke/`), and through the Android
`Bitmap` path under Robolectric (`app/src/test/`: 16 decoder tests, out of 263
unit tests in all; the Go bridge has another 64 of its own).

## Automatic sticker inbox

The home screen is an **inbox** that catches incoming stickers and decodes them
without you sharing each one. It uses Android's official **notification
listener** API — the same mechanism smartwatch and Android Auto companions use —
not any protocol emulation or account access:

- Android starts the listener only after you turn on **Notification access** for
  LightDecoder in system settings, and stops it when you turn it off.
- A messaging app posts a chat notification with `MessagingStyle`; a picture
  message carries a `content://` URI, and Android grants notification listeners
  read access to it while the notification shows. LightDecoder reads that
  picture, decodes it, and stores it.
- Only notifications from Google Messages and Samsung Messages are looked at.
  Everything caught stays in app-private storage; nothing is uploaded, and
  backup and device-to-device transfer are off, so the inbox is never copied to
  a cloud backup or another phone.
- By default only HEIC/HEIF attachments (the ones Messages can't show) are kept;
  an option keeps every picture. An activity log records only kinds and
  outcomes ("picture attachment, image/heic, saved") — never message text,
  names, or conversations.

This is **Option A / Pathway 1** from `docs/rcs-integration.md`: on-device,
Play-Store-compliant, zero protocol emulation. It works on its own; it does not
need the Google Messages link.

### What the listener cannot see

The listener does not pair with, sign into, or emulate Google Messages, and it
never touches account credentials. In exchange it only sees message
attachments **from the moment they arrive** (no history), and Android does not
expose end-to-end-encrypted RCS content this way — so some RCS stickers may not
appear. The optional Google Messages link below can fill that gap, at a price.

## Google Messages link (optional)

LightDecoder can pair with Google Messages on your phone as a "Messages for
web" client. It does this with **libgm**, the protocol library of
[mautrix-gmessages](https://github.com/mautrix/gmessages), compiled into the app
with gomobile. This is **Pathway 2** in `docs/rcs-integration.md`, which earlier
versions of this README said was deliberately not built. It is now built, as an
opt-in second source next to the notification listener, under the rules
described below. Until you go through its setup, it makes no network
connection and loads nothing.

### How pairing works

1. Setup first says plainly what the link is: that it emulates Messages for web
   over Google's private protocol, against Google's Terms of Service; that your
   data stays on the phone; that you confirm it on your phone; and how to stop
   it. You tick a box to continue. Setup goes on only once the link's
   notification can be shown, because that notification is how you see and
   stop the connection: on Android 13+ it asks for notification permission,
   and if notifications are off it explains why and opens the settings. On a
   debug build it also warns that adb can read what the link stores, so use a
   test account there.
2. You sign in to the Google account that Google Messages on your phone uses,
   in a sign-in page inside the app. It is there only to obtain the cookies
   Messages for web uses (`SID`, `HSID`, `SSID`, `APISID`, `SAPISID`, `OSID`);
   only Google's own sites load in it, and its cookies and storage are wiped
   before and after (if the app is killed during sign-in, they are wiped the
   next time it starts). Google sometimes refuses sign-in inside an embedded
   page; then you can paste cookies copied from your own browser instead (as
   JSON, a `Cookie:` header, or a "Copy as cURL" command), and the app takes
   them off the clipboard afterwards if they are still there. These are the
   only two places cookies ever come from.
3. The app shows an emoji. Nothing is linked until **you** tap the same emoji in
   Google Messages on your phone. The app has no way to answer that prompt and
   does not try; it waits up to three minutes, and a wrong emoji or a timeout
   just means trying again. (This is Google-account pairing; QR-code pairing is
   not offered.) Your phone then lists the link as "LightDecoder" under Device
   pairing; a link made with an earlier 0.3.0 build shows as "libgm" until you
   pair again.

### What it does once paired

- **Conversations and threads.** Inbox, archived and spam folders; messages with
  sender names, delivery ticks, reply quotes, reactions, a typing indicator, and
  pictures (HEIC included, through the app's own decoder). Pictures are
  downloaded when shown. While disconnected that keeps working as long as the
  sign-in token from the last connection is valid (up to about a day); after
  that a picture shows "Connect to load" and loads once the link is back.
- **Catching up.** Messages that arrived while the link was down are fetched
  when it reconnects, newest first, up to 500 per conversation; anything older
  is left to the history import or to scrolling up.
- **Sending and managing.** Text and attachments (up to 100 MB), replies,
  reactions, deleting messages, archiving, muting and deleting conversations,
  and new conversations and groups. Sends go into an on-device queue that
  survives restarts and waits for the connection. A send whose fate is
  unclear (Google took it but the phone's answer never came, the app was
  killed in the middle of it, or the phone never confirmed it within an hour,
  even when asked) is never sent again by itself. It shows "Not confirmed. It
  may have been sent.", and tapping it asks first: send it again (which may
  deliver it twice), discard it, or leave it. A send that certainly did not
  go out shows "Not sent. Tap to try again." and goes again with one tap.
- **Read and typing.** Opening a thread marks it read on the phone; typing sends
  the "is typing" signal.
- **Search.** Full-text search over everything in the local archive.
- **History import.** After pairing, the 25 newest conversations with 50
  messages each by default; "deep" imports 200 conversations with 1000 messages
  each, or it can be off. Scrolling up in a thread fetches older messages on demand.
- **Stickers.** HEIC stickers that arrive through the link (live, or fetched
  when catching up) go into the sticker inbox like the ones from
  notifications, without notification access. A sticker caught both ways is
  kept once.
- **Notifications for new messages** with Reply and Mark as read, off by default
  because Google Messages on the phone already notifies.

### Staying visible and revocable

- While connected, a notification is always shown, saying what the link is
  doing, with a one-tap **Disconnect**. The app only connects while that
  foreground service runs and its notification can be seen: if you turn the
  app's notifications (or the link's notification channel) off, the link
  disconnects and does not connect until they are back on; the status
  screen says "Notifications are off" and opens the settings for you. On Android
  14+, where the notification can be swiped away, it comes straight back while
  connected.
- **Disconnect** closes the connection and keeps the pairing. **Unpair**, in the
  link's status screen, revokes the pairing on the phone, deletes the session,
  and optionally deletes the local archive; it stays available even after
  Google has signed the link out. If the sign-in token expired while
  disconnected, the revoke needs a short connection of its own, made while the
  link's notification (or the unpair in progress on screen) is showing. If the
  phone could not be told (no network, Google unreachable), the app says so
  and asks you to remove "LightDecoder" under Device pairing in Google
  Messages yourself. You can also remove the link there at any time; once
  Google reports that, the app stops and tells you to pair again.
- After the phone restarts, the link reconnects only if you left "stay
  connected" and "start automatically" on, and the same goes for opening the
  app while the link is not running, except that a start you asked for which
  Android refused is tried again then. If Google signs the link out or rejects
  the cookies, it stops and tells you to pair again. It never pairs again by
  itself.

### What it costs

- **It emulates a Google client over a private protocol.** The Messages for web
  protocol is undocumented, and using it from another app is against Google's
  Terms of Service. Google could stop it working at any time, or act on the
  account used.
- **It breaks when Google changes the protocol**, until libgm is updated and the
  app is rebuilt.
- **It needs your phone.** Messages for web relays through the phone, so the
  phone has to be on and online. Only one web session is active at a time:
  opening messages.google.com somewhere else takes over, and the app offers
  "Use here" to take it back.
- **Network and battery.** The app now holds the `INTERNET` permission; before
  0.3.0 it had none. While connected it keeps a long-poll connection to Google
  open and the phone is pinged about once a minute, which costs battery on both
  devices. Battery optimisation can pause it; the status screen links to the
  setting.
- **Size.** The Go library (`libgojni.so`) is 9.8 MB for `armeabi-v7a`, 9.9 MB
  for `arm64-v8a` and 10.6 MB for `x86_64`, stored uncompressed. The APK carries
  all three, so about 30 MB of the release APK's 38 MB is the link.
- **Licence.** libgm is AGPL-3.0; see "Licence".

### Privacy

- **Local only.** There are no LightDecoder servers, no analytics, and no crash
  reporting. The link talks only to Google, as Messages for web does.
- **Session encrypted.** The session (Google cookies, pairing keys, tokens) is
  stored encrypted with AES-256-GCM under a key held in the Android Keystore
  (`files/relay/session.bin`). The key is not tied to the lock screen, so the
  link can run while the phone is locked.
- **Archive app-private.** Messages are kept in an SQLite database in the app's
  private storage (`databases/relay-archive.db`), with downloaded media under
  `files/relay-media/`. The app does not encrypt the archive itself beyond
  Android's own storage encryption. It is excluded from cloud backup and from
  device-to-device transfer, like everything else the app stores.
- **One phone per archive.** Conversation ids only mean something on the phone
  they came from, so if you link a different phone or Google account, the
  archive kept from the previous one is deleted before anything of the new one
  is stored or sent.
- **Logs record kinds only.** The link's activity log (on its status screen)
  records kinds and outcomes ("State: connected", "Send of a message failed:
  phone_not_responding"), never message text, names, numbers, the pairing emoji,
  cookies or keys. The Go side logs nothing: libgm's logger is turned off,
  because at its more detailed levels it logs decrypted data.
- **Unpair cleans up.** It deletes the session, and with the checkbox the
  archive and its media too. An archive you kept can be deleted later without
  pairing again ("Delete local message archive" on the link's status screen,
  or in the conversations menu); without a pairing, deleting a message or a
  conversation removes it from this device only. None of this touches the
  sticker inbox.

How the link is built, and how to maintain it: `docs/google-messages-link.md`.

### Pictures and your own stickers

Pictures in a conversation load by themselves and show at full size in the
bubble (up to 25 MB each; a video shows its still). If the phone only has a
thumbnail so far, the app asks it for the original without a tap.

The sticker button next to the attachment button opens your own stickers. "Make
a sticker" turns any picture into one, on the phone: optionally cut away a plain
background, clip it to a rounded square or circle, add the white outline, and
save. Tapping a sticker sends it as a 512×512 PNG, which an iPhone shows as a
picture in the conversation. It is not an iMessage sticker (those exist only
inside iMessage), and messages still arrive as green RCS/SMS bubbles there:
blue means iMessage, which only Apple devices can send. If a carrier turns the
picture into a JPEG (MMS), transparency is lost; the "White background" switch
is for that.

## Messenger tab

The conversation list has a **Messenger** tab. It has two halves that do not
touch each other:

- **An inbox from Messenger's notifications.** After you turn on Notification
  access, LightDecoder lists the messages the Messenger app notifies you about
  and sends your replies through that notification's own Reply button, so the
  Messenger app does the receiving and the sending. Only Messenger's
  notifications are read, what they say stays on the phone, and it holds only
  what arrived as a notification: no history, and pictures usually as a line
  of text.
- **"Full history / New chat"**: Facebook's own web messaging page
  (`facebook.com/messages`; messenger.com was retired in April 2026) in a web
  view with its own process and web data folder. Nothing is injected into the
  page and nothing is read out of it. It is asked for its computer layout, the
  way a browser's "Desktop site" switch does, because Facebook sends phone
  browsers to the Play Store.

There is no Messenger bridge in the app. `docs/messenger-hybrid.md` has the
boundary, the fact-check of the plan it follows, and what has not yet been
tried on a phone.

## Discord tab

The conversation list has a **Discord** tab beside Messages and WhatsApp. It
shows Discord's own website in a web view, unmodified: no injected script, no
reading of requests, cookies or the session token, no user-agent spoofing, and
nothing typed for you. There is no Discord bridge in the app. "Open Discord
app" is always one tap away, and is the more reliable route, because Discord
does not support mobile browsers and sign-in or CAPTCHA may not finish in a web
view.

The tab runs in a process of its own with its own web data folder, so Discord's
cookies and the other tabs' data cannot meet: the Google sign-in page of the
Messages link never sees a Discord cookie, and clearing that sign-in does not
sign Discord out. The tab needs Android 9 or later.

Discord's terms neither authorize nor forbid embedding its website, so treat
this as a grey area rather than an approved integration.
`docs/discord-web-view.md` has the container, the boundary and the reasoning.

## Licence

**GNU AGPL-3.0-or-later** (see [`LICENSE`](LICENSE)). The Google Messages link
links `libgm` (`go.mau.fi/mautrix-gmessages`), which is AGPL-3.0, so the whole
app is AGPL and its source stays available. Bundled decoder libraries libde265
and libheif are each LGPL-3.0 and are used unmodified as git submodules under
`third_party/`. Every third-party component and its licence is listed in
[`NOTICE`](NOTICE). The app ships the full texts too: About > Licences shows
NOTICE and the licence of everything compiled into the APK (the app, the
decoder libraries, each Go module and the Go runtime, and the Apache-2.0
AndroidX, Material and Kotlin libraries), which the build collects into the
APK's assets.

## Building

Requirements: Android SDK with NDK r28 (`28.2.13676358`) and SDK platform 36,
CMake 3.31+, JDK 17, and Go.

- **Go.** `gmbridge/go.mod` pins `toolchain go1.26.11`, and the build uses
  exactly that toolchain: any Go 1.21 or newer on `PATH` downloads it by
  itself, and the build checks the result. If Gradle does not see your Go, or
  you want another one, pass `-Pgmbridge.go=/path/to/go` (or set `gmbridge.go`
  in `~/.gradle/gradle.properties`); it may name any Go 1.21+ binary or a
  golang.org/dl wrapper such as `go1.26.11`, and gomobile then runs exactly
  that toolchain.
- **SDK location.** Set `ANDROID_HOME` (or `sdk.dir` in `local.properties`).
  The Go build passes it on to gomobile, which needs the NDK and an SDK
  platform of at least 26.
- **No gomobile setup.** Gradle builds `gomobile` and `gobind` from the
  `golang.org/x/mobile` version pinned in `gmbridge/go.mod`
  (`:app:buildGomobileTools`), then binds `gmbridge/` into
  `app/libs/gmbridge.aar` (`:app:buildGoBridge`; the aar is a build output and
  is not committed). No `gomobile init` and no global `go install` are needed.
  The first build downloads Go modules from proxy.golang.org.
- **Unit tests** also need the Go bridge (the app does not compile without the
  aar), and they build the native decoder for the build machine, so they need
  `cmake` and a C++20 compiler as well.
- **16 KB pages.** Both native libraries are linked with 16 KB-aligned
  segments and the APK is zip-aligned for 16 KB pages, as Android 15+ devices
  with 16 KB pages need.
- **Licences.** The build writes NOTICE and the licence texts into the APK's
  assets (`bundleLicenses`). It fails if a Go module linked into the app has no
  licence file, or an Android library outside the known Apache-2.0 ones is
  added.

```sh
git clone --recurse-submodules https://github.com/ClockworkBlack/lightdecoder
cd lightdecoder
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk (install this one)

./gradlew :app:testDebugUnitTest                 # Robolectric unit tests
(cd gmbridge && go vet ./... && go test -race -count=1 ./...)   # Go bridge tests (-race needs cgo)
```

Release signing: set `LIGHTDECODER_KEYSTORE`, `LIGHTDECODER_KEYSTORE_PASSWORD`,
`LIGHTDECODER_KEY_ALIAS` and optionally `LIGHTDECODER_KEY_PASSWORD` (or the
Gradle properties `lightdecoder.keystore`, `lightdecoder.keystorePassword`,
`lightdecoder.keyAlias`, `lightdecoder.keyPassword`). Without them the release
APK is signed with the local debug key; it is still not debuggable.

CI (`.github/workflows/android.yml`) runs the Go checks with `-race`, the unit
tests, and checks on the release APK (not debuggable, signer, 16 KB
alignment, licence files). Every run uploads it as `lightdecoder-preview`,
signed with the CI runner's throwaway debug key, so it cannot update an
installed release. On pushes to `main` that APK is also re-signed with the
release key, in a separate job that alone reads its secrets (they belong in
the repository's `release` environment, limited to `main`); that artifact is
`lightdecoder`. Without the key, `main` gets only the preview, with a
warning. What the repository owner has to set up on GitHub is listed under
"CI" in `docs/google-messages-link.md`.

Debug builds (`assembleDebug`, installed side by side as
`com.lightdecoder.debug`) are debuggable: anyone with adb on the phone can read
their message archive and Google Messages session. Use them only for
development, with test accounts; the pairing screen of a debug build says so
too.

If you cloned without `--recurse-submodules`:

```sh
git submodule update --init --depth 1
```

## Layout

```
app/src/main/cpp/        native decoder + JNI (ld_core, ld_jni, ld_probe)
app/src/main/java/
  .../heif/              NativeDecoder binding, HeifCodec, Media, ImageSniffer, StickerEffect
  .../inbox/             notification listener, on-device store, settings
  .../capture/           StickerCapture: received bytes → sticker inbox item (listener and link)
  .../relay/             the Google Messages link at runtime: GoRelayBridge, RelayManager,
                         RelayService, outbox, history import, encrypted session vault
  .../archive/           local SQLite message archive with FTS4 search, media cache
  .../ui/                inbox, viewer and licence screens
  .../ui/relay/          link setup (disclosure, sign-in, emoji) and status screens
  .../ui/chat/           conversations, thread, new conversation
  .../discord/           the Discord tab's container, address helpers, hand-off to the Discord app
  .../messenger/         the Messenger tab: notification listener, on-device inbox, reply buttons, web container
  .../ui/messenger/      the Messenger tab's screens (the web page in its own process)
  .../ui/discord/        the Discord tab's screen (its own process)
app/src/test/            Robolectric unit tests: decoder, archive, link runtime (against a
                         fake bridge), screens
gmbridge/                Go module wrapping libgm; gomobile binds it into app/libs/gmbridge.aar
third_party/libde265     HEVC decoder (submodule, pinned)
third_party/libheif      HEIF container (submodule, pinned)
third_party/licenses/    the Apache-2.0 text shipped for the Android libraries
tools/jni-smoke/         host-side decode/JNI verification harness
tools/make-test-media/   generator of the unit tests' synthetic HEIC files
docs/rcs-integration.md  the five RCS access pathways, which two LightDecoder uses, and why
docs/google-messages-link.md  how the Google Messages link is built, tested and maintained
docs/discord-web-view.md what the Discord tab does and refuses to do, and how it is kept apart
docs/messenger-hybrid.md what the Messenger tab does and refuses to do, and the fact-check behind it
NOTICE                   third-party components and their licences
```
