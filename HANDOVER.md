# Handover (2026-11-16, second session)

Branch: `ccr-b9245ae8-inefuw` (continues `ccr-b4eccc90-m97k4j`). No pull request has been opened.

## Done in this session
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
