# Handover (2026-11-16, second session)

Branch: `ccr-b9245ae8-inefuw` (continues `ccr-b4eccc90-m97k4j`). No pull request has been opened.

## Priorities (user's direction, latest first)
1. App features first; **WhatsApp work is paused** (built and tested, untouched since); it comes next.
2. **Gmail: done in 0.5.0** (see below). **Discord: paused** by the user (they chose "build with a
   warning", then "pause on Discord"). **Instagram: skipped** (ban risk, like Messenger).

## Releases and signing
- Latest: **0.4.2** (versionCode 6), signed with the permanent release key (CN=LightDecoder,
  SHA-256 63:b0:da:cf:...:36:9f:d1). The user holds the keystore and its password; it is NOT in the
  repository. To sign a build, set LIGHTDECODER_KEYSTORE / _KEYSTORE_PASSWORD / _KEY_ALIAS
  (alias `lightdecoder`), or ask the user to add the secrets to the GitHub "release" environment.
  Never sign a build for the user with any other key: it would not install as an update.

## 0.4.1 / 0.4.2
- **Picture sends (hotfix):** in SMS/MMS conversations (anything not RCS) pictures are shrunk to at
  most 1600 px and 800 KB before sending (`AttachmentPrep.MMS`); carriers refuse larger MMS and the
  phone then keeps the picture, and every later message to that person, "sending". RCS keeps
  `AttachmentPrep.LARGE` (4096 px / 8 MB). Not verified on a real phone.
- **Sticker maker Fine-tune:** Erase / Keep brushes with adjustable size, two-finger zoom and pan,
  undo per stroke (`ui/sticker/RefineView`, `StickerMaker.Stroke/Edits/cutOut`).

## 0.5.2
- Thread top bar: the conversation's avatar next to the name, and a call button for one-to-one
  conversations with a phone number (opens the dialer with the number; no call permission).
- Sticker maker: outline thickness slider (2-40 px of the 512 px sticker, default 12).
- Made stickers are also copied to the gallery (Pictures/LightDecoder Stickers, Android 10+);
  "Add from gallery (restore stickers)" in the sticker panel brings them (or any picture) back after
  a reinstall. App-private storage already survives updates signed with the release key.

## 0.5.1 (hotfix)
- **Gmail paused** (user's request): `chat/Features.GMAIL = false` hides the tab and stops the
  background check; the code and tests stay.
- **Pictures:** a picture and its text are now sent as two messages (picture first). The phone's
  echo of a picture+caption send had come back with the text only.
- **Problem report** (home menu and Google Messages link settings > "Report a problem"): app and
  phone versions, link states and settings, the send queue, the latest outgoing messages' states,
  and the activity logs, shared as text. Content-free (tested). New log lines: attachment queued
  (type, size before/after, RCS or SMS/MMS), Google accepted it, the phone's echo (with
  "THE PICTURE IS MISSING" when it is), and outgoing picture status changes.
- **Pairing:** the bridge is loaded when setup opens; the start of pairing is retried once by
  itself on pairing_init_timeout / pairing_in_progress / connection_closed / internal; Go's start
  watchdog is 35 s (was 60).
- **Duplicate devices:** pairing again now revokes the pairing it replaces with Google (Go
  `revokeReplaced`), so the phone lists LightDecoder once. Entries left by earlier installs (keys
  gone with the uninstall) can only be removed on the phone.

## 0.5.0: the Gmail tab
- A third service, `ChatService.GMAIL`: IMAP (imap.gmail.com:993) to read and SMTP
  (smtp.gmail.com:465) to send, with the user's Gmail address and an **app password** (the user's
  choice; needs 2-Step Verification). Library: Jakarta Mail for Android 1.6.7 (EPL-2.0 or
  GPL-2.0 + CPE; licence in assets/licenses/41-jakarta-mail.txt and NOTICE).
- `mail/`: `MailBridge` (+ `ImapMailBridge`), `MailManager` (the ChatBackend), `MailIndex`
  (server locations and headers), `MailVault` (encrypted with the same Keystore key as the
  Google Messages session), `MailPrefs`, `MailText`, `MailCheckJob` (JobScheduler, ~15 min).
- Threads are built from Message-ID / In-Reply-To / References (the library has no Gmail X-GM
  extensions). A conversation id is a hash of the thread's first Message-ID. A message sent in a
  thread is a reply-all to its latest email; emails this app sends carry `X-LightDecoder-Tmp` so
  their copy in Sent replaces the local one.
- Screens: Gmail tab (third tab), `ui/mail/MailSetupActivity` (add account / settings),
  `ui/mail/MailComposeActivity` (the Gmail tab's + button). No reactions or quote-reply for email.
- Deleting or archiving here is local only (Gmail is not changed); reading marks \Seen in Gmail.
- **Not yet tested against real Gmail** (the build container cannot reach IMAP/SMTP); all tests
  use `FakeMailBridge`.

## 0.4.3
- Catching stickers from other apps' notifications is switched off: the listener is not declared
  in the manifest and the home screen card and its menu options are gone.

## Done in the previous session
- **WhatsApp as a second service: finished.**
  - `WaService`: the foreground service (notification on the "WhatsApp link" channel with Disconnect; stops
    itself without a link, without "stay connected", or while its notification cannot be seen).
  - Wiring: `BootReceiver` (WhatsApp's own "start automatically"), `ReconnectReceiver`
    (`ACTION_WA_RECONNECT`), `NotificationBlockReceiver`, and `LightDecoderApp` (screen shown /
    app in background, for reconnects and for not staying "online"). The WhatsApp manager is only
    created there when the user wants WhatsApp connected.
  - `WaSetupActivity` (+ `WaSetupModel`): disclosure (unofficial client, ban risk, what is stored),
    the user's number, the 8-character pairing code (large, copyable, kept out of screenshots), or a
    QR code (ZXing core, Apache-2.0, added to the licence bundle and NOTICE). Linking only finishes
    when the user confirms on their phone; leaving the screen abandons an unfinished attempt.
  - `WaStatusActivity`: state, account, device name, Stay connected / Start automatically / Notify,
    battery exemption, Disconnect, Unlink (keep or delete the archive), delete a kept archive, log.
- **Pictures:** tapping a picture in a thread opens `PictureGalleryActivity`: full screen and dark,
  pinch / double-tap zoom and pan (`ZoomImageView`), swipe between all pictures of the conversation,
  tap to hide the bars, Share, and "Save, details and effects" (the old `ViewerActivity`).
  Videos and other files still open in another app.
- **Sending pictures:** `chat/AttachmentPrep` runs before an attachment is queued (both services):
  HEIC/HEIF becomes JPEG (PNG when transparent; animated HEIC is left as it is), and photos over
  4096 px or 8 MB are scaled down. A screen test sends a picture with text from the thread end to end.
- **Swipe to reply** works like Google Messages: the bubble follows the finger a short way, springs
  back into its place, and the reply bar opens on release (before, a completed swipe left the
  message off screen). The keyboard opens for the reply.
- Version 0.4.0 (versionCode 4).
- **Message notifications are on by default** for both links (each has its switch in its link
  settings and its own channel; Reply and Mark as read). They arrive while the link is connected.
- **Search fix:** opening search in the conversation list no longer rebuilds the toolbar menu, which
  collapsed the search field straight away (`ConversationsActivity.refreshMenu`).
- Fixed tests from the previous session that were written against placeholders, or that drove
  screens in ways Android never does (`ServiceTabsTest`).
- Tests added: `WaRuntimeTest`, `WaServiceTest`, `WhatsAppLinkScreensTest`, `PictureGalleryTest`.

## How to build here
The cloud environment "LightDecoder" allows `dl.google.com` (Android SDK) and `gcp.mirrors.maven.org`
(Robolectric's runtime jars). Then:
```
# Android SDK in /opt/android-sdk: cmdline-tools, then
sdkmanager "ndk;28.2.13676358" "cmake;3.31.6" "platforms;android-36" "build-tools;35.0.0"
git submodule update --init --depth 1
export ANDROID_HOME=/opt/android-sdk GOTOOLCHAIN=go1.26.11
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintDebug
cd gmbridge && go test -race ./...
```
Never build with `-x :app:buildGoBridge`.

## Hard product rules (both services)
1. Linking is never automated: the user confirms on their own phone.
2. A visible foreground notification with Disconnect is shown while connected; Unpair/Unlink is always available.
3. Everything stays local and is excluded from backup.
4. Logs never contain message text, names, numbers, JIDs, codes or keys.

## Known open points
- Nothing has been tested on a real phone against real Google or WhatsApp servers.
- Using WhatsApp and Google Messages through unofficial clients is against their terms; accounts can be banned.
- The binding contracts of the multi-service work are kept in `docs/handover/`.
