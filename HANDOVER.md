# Handover (2026-11-16, second session)

Branch: `ccr-b9245ae8-inefuw` (continues `ccr-b4eccc90-m97k4j`). No pull request has been opened.

## Priorities (user's direction, latest first)
1. App features first; **WhatsApp work is paused** (built and tested, untouched since); it comes next.
2. **Gmail: done in 0.5.0** (see below). **Discord: a web-view tab since 0.5.16** (see below; the user
   had earlier chosen "build with a warning", then "pause on Discord", and has now asked for
   the web-view tab only, with no bridge). **Instagram: skipped** (ban risk, like Messenger).

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

## 0.5.29 (versionCode 37) - share to LightDecoder, edit a WhatsApp message
- **Share target.** `ui/share/ShareActivity` (exported) takes ACTION_SEND (text, image, video,
  audio) and SEND_MULTIPLE (image, video): pick a linked service and a conversation. One file
  (and any text) opens the thread with it in the composer (`RelayIntents.threadWithShared`,
  `ThreadActivity.takeShared`); several files are sent after one question. Shared files are copied
  to `cache/shared-in/` first (`SharedContent.copyIn`, kept a day), because the sharing app's
  grant ends with the share screen. Only other apps' `content:` uris are taken: a `file:` path or
  a uri of the app's own provider could name private files. `ViewerActivity`'s older "Open with
  LightDecoder" entry for pictures is still there, so pictures show two entries.
- **Direct share.** `chat/ShareShortcuts` pushes a long-lived shortcut for a conversation when the
  user sends to it from the thread or the share screen (`res/xml/shortcuts.xml`, meta-data on
  InboxActivity); Android puts them in the share sheet's top row and under the launcher icon.
  Removed on deleteConversation and wipeArchive. Messenger and Discord cannot be targets (no
  backend to send through).
- **Edit (WhatsApp only).** `WABridge.EditText` (whatsmeow `BuildEdit`) -> `WaBridge.editText` ->
  `WaManager.editMessage`; `Capability.EDIT`; "Edit" in the message sheet when `EditRule.canEdit`
  (own, text only, under 15 minutes; the bridge allows whatsmeow's 20). The archive has no
  "edited" mark yet. **Unsend already existed** for WhatsApp: Delete -> "Delete for everyone".
- **Google Messages cannot edit or unsend**: libgm v0.2610.0 has no such action in its protocol
  (ActionType has DELETE_MESSAGE only, which deletes on the user's own phone). Not something to
  add without the protocol.
- None of this was tried on a phone. Next version: 0.5.30 / 38.

## 0.5.28 (versionCode 36) - Smart cut: the full U-2-Net
- The owner chose "Model B": the full U-2-Net (Apache-2.0, 168 MB, sha256 8d10d2f3...2b491, from
  rembg's release assets) now finds objects instead of U-2-Net-p. It is over GitHub's 100 MB file
  limit, so it ships as `assets/models/u2net.onnx.0` + `.1`; `SubjectCutter.fullObjectsModel` joins
  them once into `noBackupFilesDir/models/u2net.onnx` and the runtime loads it from that file
  (mapped, not held in the Java heap). U-2-Net-p stays as the fallback. APK about 320 MB.
- **AUTO still picks the people model when people cover 2% of the picture**, so photos of people
  (and statues of them) are cut by RVM as before; the new model changes Objects / Both and what
  people hold. Changing that rule is the next thing to try if cuts of people still look ragged.
- BiRefNet-lite ("Model A") was cleaner but needs about 7 GB of memory at 1024 px: not for a phone.
- Not trained here: no GPU or dataset in this environment. Next version: 0.5.29 / 37.

## 0.5.27 (versionCode 35) - when a message was read
- Holding one of the user's own messages that shows the read tick now says when it was read, at
  the top of the actions sheet (`sheet_message_actions.readInfo`, `ui/chat/ReadInfo`).
- Google Messages gives no time with "read", so `reactions/ReadTimes` (read_times.db, cleared with
  the archive, not created until needed) notes when the link sees a message turn to read:
  `RelayManager.onMessage` (where `wasUnread` is worked out) and `WaManager.onReceipt`. Same
  three wordings as reaction times: seen live, "at or before" when caught up, not known when it
  was already read on first load. Test: `ReadTimesTest`.

## 0.5.26 (versionCode 34) - Turn button; a calmer archive wipe
- **Sticker maker "Turn"** (beside "Back to original picture"): the picture a quarter turn
  clockwise per tap, for one that still comes in sideways (a file without its camera's rotation
  note, or a sticker whose original was kept sideways before 0.5.25). It starts over like a new
  picture (no cut, brushing or taps); a sticker being edited keeps the turn when saved
  (`StickerMakerActivity.turnPicture/usePicture`, test in `StickerEditTest`).
- **`ReactionTimes` no longer creates its database just to clear or read it.** `clear` runs inside
  `RelayManager.wipeArchive`, which a test gives only 200 ms when another phone's archive goes;
  CI failed once there (`RelayManagerTest.anotherPhonesArchive...NotDeletedLate`, "timed out
  waiting for the connect", run 44143117327). Not reproduced locally (22 passes, also under load
  and with a 50 ms budget), so this removes the work my reaction feature added to that step
  rather than proving the cause. `RelayTestEnv.eventually` now prints the end of the link's log
  when it times out, so a repeat says what the link did instead.
- `OutboxTest.aSentMessageWhoseEchoWasLostIsSettledByLookingItUp` waited for the outbox entry to
  go and then asserted at once that the unconfirmed list was empty, which is written a moment
  later; it failed once on an overloaded machine. It now waits for that too.
- **Smart cut, not changed yet:** tried on the owner's statue photo, BiRefNet-lite (MIT, 224 MB,
  1024 px, 7 s on the build machine) cuts far cleaner than U-2-Net-p; full U-2-Net (Apache-2.0,
  176 MB, 0.3 s) is in between. Waiting for the owner's choice (APK size, speed on the phone).

## 0.5.25 (versionCode 33) - hotfix: camera photos sideways
- BitmapFactory ignores a JPEG's EXIF orientation, so portrait camera photos came out sideways
  in the sticker maker (and library import), the viewer, the conversations, and when a photo was
  shrunk before sending (re-encoded without the note, so it was *sent* sideways too).
- `heif/ExifOrientation` (androidx.exifinterface) turns non-HEIF decodes upright in
  `Media.decode`, `Media.decodeFirstFrame` and `MediaLoader.decodePreview` (AttachmentPrep goes
  through the latter). HEIF is left alone: libheif / ImageDecoder apply its rotation already.
- Stickers made before this keep a sideways original (saved without the note): pick the
  picture again (or use Turn, 0.5.26). Test: `ExifOrientationTest`.

## 0.5.24 (versionCode 32) - hotfix: messages in a row went out slowly
- **Cause:** each Google Messages send returned only when the phone had answered (seconds), and
  the outbox sends one at a time to keep order, so a burst went out one phone round trip apart.
- **Fix:** libgm patch g (`Client.SendMessageAsync`, see third_party/.../PATCHES.md); bridge
  `StartText` / `FinishSend` (gmbridge/sendpipe.go); `RelayBridge.startText` returning a
  `PendingSend`. `Outbox.drain` starts each text once Google accepted the previous one and
  collects the phone's answers alongside, at most `MAX_TEXTS_IN_FLIGHT` (6). Attachments still
  go alone, after the texts ahead of them. Lookups of unconfirmed sends now run after the queue
  (interrupted entries still before it), so they never hold up new messages.
- Trade-off: a text the phone answers with a transient failure (FAILURE_2/3) is retried after
  the ones behind it went out, so it can land after them. Tests: `OutboxPipelineTest`,
  gmbridge `sendpipe_test.go`. WhatsApp was not changed (its sends wait only for WhatsApp's
  server).

## 0.5.23 (versionCode 31)
- Messenger tab: "Sent a photo / video" lines get an icon and open the conversation in the
  Messenger app (or Facebook's page without it); `messenger/MessengerMedia`. The notification
  never carries the file itself.
- Facebook sign-in on the Messenger page: password-manager tip, and when Facebook asks for a
  code, "Approve in the Facebook app" (`MessengerWebState.CHECK`).

## 0.5.22 (versionCode 30, on main)
- **Everything merged into main** (PR #1): this branch line up to 0.5.21, plus from session
  `ccr-4fee5e9f-jrrlhu`: the **Messenger tab** (inbox from Messenger's notifications, replies
  through their Reply button, Facebook's page in its own `:messenger` process; listed before
  WhatsApp in the switcher; `docs/messenger-hybrid.md`) and **reaction times** (hold a reaction:
  who and when; `reactions/ReactionTimes`, `ui/chat/ReactionDetails`).
- **CI fixes on main:** setup-android installs `platform-tools` only (Google dropped the `tools`
  package it asked for by default), and both jobs use **JDK 21** (Robolectric on sdk=36 refuses
  Java 17). Merge main into a branch before its next PR, or its CI fails the same two ways.

## 0.5.21
- **App switcher replaces the tab row** (`view_app_switcher.xml`, `ServiceTabStrip.attachSwitcher`):
  the toolbar title is "Messages ▾" / "Discord ▾"; a tap lists the apps with unread counts; the
  red count beside the switcher is the *other* apps' unread (fixes the badge covering "Messages").
  The `services` TabLayout is kept but GONE: it is still the model (selection, badges) the screens
  and tests use. While searching, the SearchView takes the toolbar (switcher detached).
- **Discord top bar: one 44 dp row**, no back arrow. Switching apps fades (160 ms, `res/anim`),
  and Discord -> list uses SINGLE_TOP so the list is brought forward, not rebuilt.
- **Discord speed**: `DiscordWarmup` service in ":discord" (engine only, no page or network,
  only once the tab has been used), `offscreenPreRaster`, renderer priority IMPORTANT.
- Tests: `AppSwitcherTest`, updated `ServiceTabsTest`, `DiscordManifestTest`, `DiscordFitTest`.

## 0.5.20
- **Tab order: Messages, Discord, WhatsApp** (Discord is added after the first service, in the
  conversation list and on the Discord screen).
- **Discord tab made "firmer"** (`ui/discord/DiscordPage.kt`): the page view is kept alive in
  the Discord process between visits (paused, timers stopped) instead of reloading each time;
  `FirmWebView` has no overscroll, no zoom, no sideways drag of the whole page; surface-colour
  background (no white flash); `onRenderProcessGone` recreates the page instead of crashing.
  Tests: `DiscordPageTest`. Not tried on a phone.

## 0.5.19
- The user signed in to Discord in the tab on their phone (Galaxy Z Fold 7); the web view works.
- **Discord tab fit** (`discord/DiscordFit`, `DiscordActivity.applyPageWidth`): the page is laid
  out at a CSS width and the view scaled to fill the screen. "Page size" menu, remembered
  separately for narrow (< 600 dp, folded) and wide (unfolded) screens; Automatic = natural size
  when narrow, at least 940 CSS px when wide (Discord's side-by-side layout). Larger sizes use
  textZoom. Fold/unfold handled in place (`density` added to configChanges).
- **Discord top bar is one row**: the tab strip and arrow sit inside the toolbar; no title or
  address subtitle. Tests: `DiscordFitTest`.
- Not verified on the Fold: how the scaled page looks unfolded, and whether Discord's website
  goes side-by-side at 940 px.

## 0.5.18
- **Discord: sign in with a QR code** (`discord/DiscordQr`, `DiscordActivity` "Sign in with QR
  code" in the sign-in banner and menu). Gives the page a computer-size window scaled to fit so
  Discord's sign-in QR appears, reads it off the screen (PixelCopy + zxing, only
  `https://discord.com/ra/<code>` accepted), and offers "Approve in the Discord app" (VIEW intent
  to com.discord) or the code drawn large for another device. Back to phone width once signed in.
  **Passkeys cannot work in the WebView** (see docs/discord-web-view.md); the QR route lets the
  user sign in to the Discord app with a passkey and approve the tab there.
  Unverified (discord.com is blocked from the build environment): that the QR shows at 1100 CSS
  px in a WebView, and that the Discord app acts on an opened /ra/ link. Tests: `DiscordQrTest`.

## 0.5.17
- Merged `origin/discord-tab` (0.5.16, fast-forward) after reading it; first compiled and tested
  here: full suite (510 then 511 tests) and lint pass. Its "not compiled" note below is resolved.
- **Service tab strip** (conversation list and the Discord screen): `TabLayout` in scrollable mode
  (scrolls sideways when the tabs do not fit) beside a ▾ button (`servicesMenu`, tinted
  colorOnSurface: white in dark mode) that opens a list of every tab with the current one ticked
  (`ui/chat/ServiceTabStrip`). Test: `ServiceTabsTest.theTabsScrollAndTheArrowListsThemAll`.
- Discord tab still not tried on a phone (WebView sign-in, CAPTCHA, the ":discord" process).

## 0.5.16
- **Discord tab** (`ui/discord/DiscordActivity`, `discord/`): a tab after WhatsApp in the
  conversation list (`Features.DISCORD`; not a `ChatService`, so no backend, archive, badge or
  notifications). It opens Discord's website, unmodified, in a WebView. No bridge, no token, no
  injected script, no user-agent override; "Open Discord app" hands the conversation on screen to
  the official app. Rules and the fact-check behind them: `docs/discord-web-view.md`.
- **Its own container:** `android:process=":discord"` plus `WebView.setDataDirectorySuffix`
  (`DiscordProcess.enterIfDiscordProcess`, first line of `LightDecoderApp.onCreate`, which then
  returns: none of the links' managers exist in that process). Needed because `SignInWebData`
  deletes the main process's whole WebView folder after a Google sign-in. Android 9+ only; the
  tab is hidden below that. Do not move the screen into the main process or add a WebView to it
  before the suffix is set.
- **Not compiled, not run on a phone:** this was written in a session with no Android SDK. Only
  the plain JVM tests ran (`discord/Discord*Test`, 11 tests). `ServiceTabsTest` and
  `MailScreensTest` were edited for the extra tab and have NOT been run. First thing next
  session: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.

## 0.5.15
- **"Read" notifications** (`relay/ReadNotifier.kt`: `ReadNotifier`, `ReadNotifyPrefs`,
  `AppVisibility`). When one of the user's messages turns OUTGOING_DISPLAYED (Google Messages:
  `RelayManager.onMessage` compares with the stored row before the upsert; WhatsApp:
  `WaManager.onReceipt` on READ/PLAYED receipts), a notification "<name> read your message"
  with the message's start goes on its own channel "Read receipts" (IMPORTANCE_DEFAULT), one per
  conversation (tag "read:" + the messages notification's tag, so no WhatsApp ids), replaced by
  the next, cancelled when the conversation is opened. Rules: on for everyone by default; only
  while no LightDecoder screen is started unless "Also while LightDecoder is open"; off per
  conversation (Settings > "Choose who to be notified about", or the conversation's menu "Notify
  me when they read my messages"); never for a message read over 3 days after it was sent.
  Settings section `section_read_notify.xml` / `ui/relay/ReadNotifySection` sits under
  "Message notifications" in both the Google Messages and WhatsApp link settings (the prefs are
  shared; the people list shows that service's conversations). Not for Gmail.

## 0.5.14
- **iPhone tapbacks shown as reactions** (`ui/chat/Tapbacks`, applied in `ThreadActivity.submit`
  before `ThreadItems.build`; display only, nothing stored changes). Reads the English texts an
  iPhone sends to non-iPhones: Loved / Liked / Disliked / Laughed at / Emphasized / Questioned,
  "Reacted <emoji> to", and "Removed a heart from" etc., on a quoted text (cut-short quotes
  ending in "…" match by start) or on "an image / a photo / a sticker / a GIF / a video /
  an audio message / an attachment". The latest earlier message that fits gets the emoji (as a
  ReactionDto from the sender); one tapback per person per message, as on an iPhone; the text
  is hidden. Unmatched texts (target not loaded) stay as text. Not yet applied to the inbox
  preview or notifications, which still say "Loved an image".

## 0.5.13
- Fix: tapping Remove on something and then Keep on it again left it removed (removes always
  beat keeps). `TapCutter.combine(found, steps)` now applies taps in the order made: start
  empty if any tap is a Keep (else from what Smart cut found), then each Keep adds and each
  Remove takes out, so a later tap on the same thing undoes an earlier one.

## 0.5.12
- **A tap finds the thing under it** (`sticker/TapCutter`): EfficientSAM ViT-T
  (`assets/models/efficient_sam_vitt_encoder.onnx` 25 MB + `_decoder.onnx` 17 MB, Apache-2.0,
  from github.com/labelmeai/efficient-sam release onnx-models-20231225). The picture is encoded
  once when the first tap is made (about 1.2 s on the build machine), each tap decoded in about
  50 ms; the first of the three masks (the whole thing) is taken, made in-or-out, fitted to the
  picture's edges, tidied, and crumbs under 5% of its main piece dropped.
  New tap rule (`TapCutter.combine`): keep taps define what stays (the union of what is under
  them, found by the other models or not: a held bottle); with no keep tap, what Smart cut
  found; remove taps take their thing out (grown a little, slivers opened away).
  If the tap model cannot run, the older `SubjectPick` piece-choosing is used.
  Tried on the user's picture: man + bottle, man alone, woman (two taps: her and her hoodie).
- APK is now about 157 MB. GitHub repository files (raw/LFS) are blocked from this
  environment; only release assets download, which is why these model sources were chosen.

## 0.5.10
- **Choose who or what to keep** (sticker maker, under Smart cut): `PickView` shows the picture
  with what the cut drops dimmed; taps are Keep or Remove marks (`StickerProject.Edits.keepTaps /
  removeTaps`, original's pixels). `sticker/SubjectPick.apply` narrows the mask on a 256 px grid:
  keep taps keep their pieces and drop untapped ones, remove taps drop theirs, and a piece with
  both (people touching) is divided by cheapest path with a colour-change cost, so the line
  falls on the edge between them. Touching people in similar colours may need a second tap.
- **Auto keeps what people hold** (`Subjects.peopleHolding`): each connected piece of the objects
  mask that touches the people mask is added to it, unless the part beyond the people is more
  than half their area (then it is taken for furniture or the room). Only as good as the small
  objects model: what U-2-Net-p does not see (a clear glass, say) is not added.
- **Smart cut tuning** (tried on the user's picture and samples): two working sizes, a mirrored
  second pass and a contrast boost made the masks no better or worse, so the models run once
  as before. What helped: `SubjectMask.cleaned()` (small holes filled, specks dropped with their
  faint rim, edge one pixel in against background fringe), the held-object rule only trusting
  objects confidence above 190, and a **Sharpen** slider (`Options.sharpen`, unsharp mask that
  ignores transparent neighbours) for soft sources such as video frames.
- **Send several stickers**: "Send several" in the sticker panel marks tapped stickers (up to
  10) and sends them one after the other in the order tapped (`ThreadActivity.sendStickers`),
  stopping at the first failure.

## 0.5.9
- **Smart cut keeps whole people.** U-2-Net-p alone lost bodies (dark clothes, dim rooms), and so
  did bigger general models (silueta, isnet-general-use; tried on the user's picture). Added a
  people model: Robust Video Matting, MobileNetV3 (`assets/models/rvm_mobilenetv3.onnx`, 15 MB,
  GPL-3.0, sha256 88d45312...), run on one frame with empty memory at about 256 px
  (`downsample_ratio`), which is where it judges whole bodies best. `SubjectCutter` now returns
  `Subjects(people, objects)`; `Options.smartKind` (AUTO = people if they cover 2% of the
  picture, else objects; PEOPLE; OBJECTS; BOTH) picks the mask, and `Options.smartKeep` (0..100)
  moves the line between kept and cut (`SubjectMask` holds raw confidence now).
- **Smart cut is a toggle button beside "Choose picture"** (`smartCut`); its choices (kind, keep
  less/more) appear under the tool buttons. The "Cut away a plain background" switch is the
  flood fill only, and the two are mutually exclusive. Saved projects still use
  removeBackground + smart.
- Tests run both real models (`pictures/astronaut.jpg`, NASA public domain; `coffee.jpg`).

## 0.5.8
- **Smart cut-out** (sticker maker, "Remove background" > "Smart cut-out", on by default for new
  stickers): `sticker/SubjectCutter` runs U-2-Net-p (`assets/models/u2netp.onnx`, Apache-2.0,
  sha256 309c8469...) with ONNX Runtime 1.22.0 (MIT) on the phone, then fits the 320 px answer to
  the picture's edges with a guided filter (`SubjectMask`). `StickerMaker.cutOut/make(subject =)`;
  without a mask (`Options.smart` off, model failed, no subject found) the old flood fill is used.
  The mask is not saved with a project; it is found again when the sticker is edited.
  **Do not use ML Kit / Google Play services for this**: they are proprietary and cannot ship in
  this AGPL app (the `bundleLicenses` gate rejects them). The runtime is left out for x86_64
  (APK size); unit tests use the desktop `onnxruntime` jar and run the real model on
  `test/resources/pictures/coffee.jpg` (CC0, scikit-image). APK is now about 100 MB.
- Traced cuts follow a smooth curve through the finger's points (`StickerProject.outlineOf`).
  Stickers traced before this come out very slightly different when edited and saved again.
- **Title under a sticker**: `Options.caption` (max 40 characters), white with a thin dark edge,
  in a 64 px band taken from the picture's height.
- **Save as new** while editing: adds a sticker beside the one being edited ("Save changes"
  replaces it, as before).
- Not verified on a real phone (model speed and memory there especially).

## 0.5.7
- **Stickers others send** (iPhone or Android): "Add to my stickers" in a message's touch-and-hold
  menu (any picture or sticker), in the picture gallery's menu and in the picture viewer's top bar
  (also the sticker inbox). `StickerLibrary.addPicture(bytes)` decodes the first frame (HEIC,
  WebP, GIF, PNG...), keeps it as a library sticker and saves an editing project with no outline,
  so it can be edited like one the user made. Downloads the full picture first when needed.
  Tests: `sticker/ReceivedStickerTest`.

## 0.5.6
- Fine-tune: "Redo stroke"; "Clear brushing" is undoable (Redo brings the strokes back in order);
  "Redo cut-out" after "Undo cut-out".
- Thread top bar: no phone number under the name (only typing/online/last seen, or a group's size).
- Known flaky under full-suite load: `LinkRound2Test.aLoadThatOutlivesAThreadSwitch...` (it steps
  the main looper one task at a time); passes alone. Worth making deterministic.

## 0.5.5
- Tracing takes on the first touch: the sticker maker's page (`DrawingScrollView`) is locked while
  tracing or brushing (a settling fling used to swallow the first touch), the picture is scrolled
  into view, fast strokes use every batched point, and a trace is judged by its size (24 dp), with
  a hint when it is too small instead of silently dropping it.
- The thread's toolbar avatar is drawn only when it changes, before the title (redrawing it on
  every refresh disturbed the thread-switch ordering LinkRound2Test checks).

## 0.5.4
- The sticker panel has an "Edit stickers" button (pencil): while on, a tapped sticker opens in
  the editor instead of being sent; a hint line says touch and hold edits or deletes.

## 0.5.3
- Editing made stickers: touch and hold a sticker in the sticker panel > Edit (or Delete). The maker
  opens with how it was made (`sticker/StickerProject`: the original picture plus options, traced
  outline and brush strokes as points, kept in filesDir/my-stickers/projects/<name>/); Save
  replaces the sticker in place (and its gallery copy); "Back to original picture" undoes every
  edit. Stickers made before 0.5.3 have no project and are edited as they are.

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
