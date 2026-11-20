# iMessage in LightDecoder via OpenBubbles' library (design, 2026-11-19)

Status: **built in 0.5.28 (see "What is built"); not yet tried on a phone.** The older
Flutter-based analysis further down applies to OpenBubbles 1.15.x only and is kept as history.

## iPhone-level iMessage in the Messages tab (0.5.58)
With the bundled library set up ("direct mode"), nothing hands off to OpenBubbles; every feature runs
here through the `:imessage` process (`DirectEngine`/`DirectService`) and the main process
(`DirectLink`), joined by `DirectIpc`/`DirectWire`. Archive schema v3 adds `edited_at` and `extra_json`
(`IMessageExtra`: parts, edits, effect, voice, link, app, sticker, unsent).
- **Receipts:** delivery acks (OpenBubbles' markCertified), read receipts out (setting, off by default),
  iPhone-style "Delivered" / "Read <time>" footer. Updates are acked to the library only after storage;
  undeliverable updates are skipped after capped retries instead of blocking the queue.
- **Edit / Unsend / tapbacks:** native, with iPhone windows (edit 15 min, 5 times; unsend 2 min, whole
  message); unsent messages leave "You unsent a message."; six tapbacks plus any emoji, both ways.
- **Typing** both ways (throttled; stops 5 s after the last keystroke or on send).
- **Attachments** both ways (MMCS upload/download in `:imessage`, 100 MB limit, sends serialized per
  chat through `DirectLink.inTurn`), Live Photo stills, stickers, video stills, voice notes.
  iPhone CAF voice messages are remuxed by `media/CafAudio` (Opus to Ogg, AAC to ADTS, AMR, LPCM to
  WAV); other codecs open in another app.
- **Groups:** renames, members, shrunk groups stay groups, read-only once the user is removed; "New
  iMessage group" in the Messages menu (every address checked with Apple first).
- **Effects** (incoming caption, invisible ink, outgoing picker on long-press of Send), link previews
  (web addresses only), app cards and moves.
- **Games:** `imessage/madrid/` hosts iMessage-app extensions through the BlueBubbles "Madrid" AIDL
  (copied unchanged, Apache-2.0, see NOTICE). The only registered extension is OpenPigeon
  (`com.openbubbles.openpigeon`, installed separately from Google Play, signing cert pinned and checked
  before every bind). LightDecoder carries the messages and contains no game logic. Setting "iMessage
  games" (on by default; file `imessage/games-off` when off) on the iMessage screen, with "Get games".
- **Not verified on a real phone/iPhone from the build environment:** everything above runs in JVM and
  Robolectric tests only (1015 tests).

## Current design (0.5.54): the library powers Blue Bubbles
LightDecoder 0.5.53 already had iMessage in the Messages tab ("Blue Bubbles": an iMessage archive
filled from OpenBubbles' notifications, sent through their Reply button, merged in blue into the
Messages conversation with the same person). 0.5.54 keeps all of that and, once the library is
registered, uses it underneath: `imessage/direct/DirectLink` writes the library's messages into the
same archive (reusing a person's chat; `me`, addresses without scheme), and `IMessageManager` sends,
retries and unsends through it. Without the library everything works as in 0.5.53. The sections
below describe the library parts (engine, bridge, keystore, registration); where they say "Messages
archive" or "imsg: in RelayManager", that was the 0.5.28 design and is replaced by this.

## Trusting OpenBubbles (notification mode)
Without the library, Blue Bubbles works through the OpenBubbles app, which is trusted by its
signing certificate, never by its package name (`imessage/OpenBubbles.trust`, checked like
OpenPigeon's: `hasSigningCertificate` on Android 9+, the `GET_SIGNATURES` digest before, cached
until the app is updated). The pin is the Play Store build's certificate, SHA-256
`54:33:6B:2F:36:F9:ED:C8:B9:02:74:15:F7:5A:D0:7B:06:05:4D:9B:4A:C9:FE:61:67:53:2C:12:86:90:1D:A2`
(Play App Signing, read with `apksigner verify --print-certs` from `com.openbubbles.messaging`
2.0.0, versionCode 20002293). Its test builds (`com.bluebubbles.messaging.alpha` / `.beta`) have
no known certificate and are taken by name in debug builds of LightDecoder only; the on-device
test's stand-in (`:fakebubbles`, installed as `.beta`) is one, so it is never to be installed on a
phone used for real: a release build ignores it. Only then are its notifications read (of this
user only, not another profile's), its Reply / Mark As Read / tap PendingIntents kept (and only
those whose `creatorPackage` is that OpenBubbles, checked again before each is sent), files
handed to it, and its saved pictures taken from the gallery. A copy signed by anyone else is
named on the screens and in the problem report as not used (`IMessageErrors.NOT_VERIFIED`).
An iMessage chat is linked into a Messages conversation by its number or email address (or by
hand), never by a name alone: a chat with no number of its own whose name matches is only listed
first in "Link an iMessage chat…", for the user to confirm. (Check iMessage still takes the reply in
such a chat, written since the check was sent, for its verdict; nothing is linked by it.)

## Inline replies (0.5.57)
A reply names the answered message's GUID (`NormalMessage.replyGuid`) and the part of it
(`replyPart`, "<part>:<start>:<end>" of that part's text, as OpenBubbles' `computeWireReplyPart`
writes it: "0:0:<length>" for a plain text, "0:0:0" when the text is unknown). `DirectLink.deliver`
works both out from the reply target's row (`imsg:<GUID>`; older rows Blue Bubbles stored from
notifications have no GUID, and a reply to one goes as a message of its own). Incoming replies are
stored with `replyToId = imsg:<GUID>`, so the thread shows them under the message they answer.

## Phone-number verification (0.5.55): SIM first, then SMS
OpenBubbles 2.0.0's order (`PhoneNumberPage.subscribeSubscription`, default `smsLess = true`):
1. **SIM ("SMS-less", `SimAuth`)**: needs the app-op `USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER` (user grants
   it from a shell, e.g. adb on a computer; on phones where it is not granted, step 2 is used). mccmnc, IMSI and IMEI of the SIM; subscriber =
   `0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org`; the library's `getEntitlements(config, conn,
   mccmnc, subscriber, imei, callback)` talks to the carrier's entitlement server (address in Apple's
   carrier bundle) and calls back with an EAP-AKA challenge; `EapAka.respond` asks the SIM
   (`getIccAuthentication(APPTYPE_USIM, AUTHTYPE_EAP_AKA, base64(len|RAND|len|AUTN))`), derives K_aut
   (MK = SHA-1(identity|IK|CK), FIPS 186-2 generator) and returns the signed EAP response. The library
   then gets the number + signature and returns the `IdsUser`.
2. **SMS (`SmsPhoneVerifier`)** when the library says "Carrier does not support ICC auth!", or the
   app-op is not granted: `REG-REQ?v=3;t=<push token hex>;r=<id>;` to the carrier's gateway, Apple's
   `REG-RESP?v=3;r=..;n=..;s=..` back (text or data SMS; can be pasted), then `authPhone`.
On carriers with an entitlement server Apple's SMS gateway may never answer (seen on the owner's
phone), so a silent gateway now points to step 1.

## What is built (0.5.28): iMessage inside the Messages tab
Owner's direction (2026-11-19): no separate tab; iMessage fully in the Messages tab, messages
arriving whether LightDecoder is open or not, in sync with OpenBubbles, and unsend working after a
restart.
- **In the Messages tab's archive** (`imessage/ImessageArchive`): iMessage conversations
  (`imsg:tel:+1...`, `imsg:mailto:...`, groups `imsg:group:<guid>`) and messages
  (`imsg:<GUID>`) are stored in the Messages archive beside the Google Messages ones (whose ids
  never start with `imsg:`), so the list, threads, search and notifications show them like texts.
- **Routing** (`relay/RelayManager`): every action on an `imsg:` conversation goes to
  `imessage/ImessageEngine` instead of the phone: send (local copy, replaced by the sent
  message once Apple's `SendConfirm` arrives, or failed with the reason), retry, discard, delete
  (here only), archive, mute, mark read (no read receipt is sent). Pictures and reactions over
  iMessage are not built yet: they fail with a message saying so (never fall back to SMS).
  `startConversation` with one person who is on iMessage (`validateTargets`) opens an iMessage
  conversation, as an iPhone would; otherwise SMS/RCS as before.
- **Sync**: incoming iMessages, copies of messages the user sent from their other devices
  (OpenBubbles included: Apple sends the user's own devices a copy, which is stored as outgoing),
  delivered/read receipts (ticks), edits and other people's unsends all update the archive.
  Attachments show as a line naming the file (not downloaded yet). Only messages from the
  registration on: older history would need an Apple Account sign-in and iCloud (not built).
- **Background** (`imessage/ImessageService`, foreground service type remoteMessaging): keeps
  the session connected once iMessage is set up; started after registration, with the app, and
  at boot/update (`relay/BootReceiver`). Events are acknowledged to the library only after the
  archive has them, so nothing is lost if the app dies in between.
- **Unsend** (thread: hold one of your iMessages, "Unsend (m:ss left)"): `ImessageEngine.unsend`
  reads who the message went to from the archive (its conversation's participants and group
  guid), so it works after a restart too. It waits for Apple's answer; too late, not connected,
  refused, or no answer in 45 s each give "Couldn't unsend: <reason>". Success removes it here
  and says it disappears for iOS 16+ only.
- **Setup** (`ui/imessage/ImessageActivity`, Messages tab menu > "iMessage..."): import
  OpenBubbles' files, enter the relay code, register; status green only when Apple says
  Registered.
- **Two processes** (branch `ccr-ffe9dbd4-xu00fq-isolated`; the main branch loads the library in
  the main process instead): OpenBubbles' library runs only in **":imessage"**
  (`imessage/ImessageProcess`; `LightDecoderApp.onCreate` returns early there): its
  `ImessageService` (foreground, keeps iMessage connected), `ImessageEngine`, the setup screen
  and the SMS verification. The Messages archive, the screens and the Google Messages link stay
  in the main process, which never loads the library, so a crash in it cannot take them down.
  They talk over two non-exported bound services with `Messenger` (`imessage/ImessageIpc`,
  format `imessage/ImessageWire`):
  - main -> ":imessage" (`ImessageLink` -> `ImessageService`): SEND (target built from the
    archive, text, reply GUID and part) -> GUID + time; UNSEND (target, GUID, part); REACH (address) ->
    the conversation if it is on iMessage. Each waits for Apple's answer; a dead or silent other
    process gives a clear error, never a hang.
  - ":imessage" -> main (`ImessageEngine.sink` -> `ImessageSinkService` -> `ImessageLink.apply`):
    each archive change, in Apple's order. The library's event is acknowledged only after the
    main process answered that it stored it; until then it stays in the library's journal and is
    retried (with backoff), also after a restart. Binding with BIND_AUTO_CREATE starts the main
    process if it is not running, so messages are stored and notified whether LightDecoder is
    open or not.
  - The "registered" flag is a file (`files/imessage/registered`), not SharedPreferences, which
    are cached per process.
- **Built in (0.5.29)**: a build machine that has the owner's copies puts them in
  `~/.lightdecoder/imessage-libs/<abi>/` (or `LIGHTDECODER_IMESSAGE_LIBS`); `bundleImessageLibs`
  checks each against `app/imessage-pins.txt` (a wrong file fails the build) and packs them into
  the APK's `lib/<abi>/`, so nothing has to be imported (the setup screen says so; they are
  loaded with `System.loadLibrary`). They are never committed: the repository is public. CI and
  other builds have no such folder and keep the import step. **An APK built with them is for the
  owner's devices and must not be published** (e.g. as a GitHub release).
- **Import** (`imessage/direct/DirectLibrary`, `ElfInfo`): a file is kept only when its SHA-256 is one
  of `PINS` for this phone's CPU type (arm64: `librust_bridge.so` b6dfe8ce..., `libheifdec.so`
  bdabf750...; armeabi-v7a: `librust_bridge.so` 7d628384... only); anything else is refused, whatever
  it exports (`ElfInfo` only words the refusal). Before `System.load` each imported file is hashed
  again; one that no longer matches its pin (changed, or kept by an earlier LightDecoder that took an
  unpinned `libheifdec.so`) is not loaded, is deleted, and the setup screen says why. Both pinned
  bridges name `libheifdec.so` in DT_NEEDED and bind at load (BIND_NOW), so a CPU type with no pinned
  `libheifdec.so` (armeabi-v7a, x86_64 today) cannot use the library at all: the setup screen says so
  instead of asking for files, until a pinned copy is added. `libuniffi_runtime.so` is not needed.
- **Bindings**: `app/src/main/java/uniffi/rust_bridge/rust_bridge.kt`, generated by
  `tools/uniffi-bindgen/gen.sh` from the pinned library's own metadata (uniffi 0.32.0, MPL-2.0),
  over JNA 5.17.0 (Apache-2.0). The library itself is never committed or bundled.
- **Keystore** (`imessage/ImessageKeystore`): Android Keystore, aliases prefixed `imessage:`.
- **Registration** (`ImessageEngine.register`, OpenBubbles 2.0.0's order): `doFirstTimeInit` +
  `setupKeystore`; `configFromRelay(code, host, token)` (default
  `https://registration-relay.beeper.com` with its public token) -> `newNgmIdentity` +
  `setIdentity` -> `setupPush` -> `makeAnisette` -> phone number by SMS (`SmsPhoneVerifier`,
  `PhoneRegSms`) -> `authPhone` -> `registerIds` -> `startSession`; after a restart
  `restoreSession`.
- Known limits: unpairing Google Messages with "delete archive", or another phone's archive
  wipe, also deletes the iMessage conversations (one archive). The Messages tab must be paired
  for its composer to be enabled.
- Tests: `imessage/ImessageArchiveTest` (ids, mapping of every event kind, groups, sending
  data), `imessage/ImessageWireTest` (everything that crosses processes survives the trip),
  `imessage/ImessageIpcTest` (request/reply over a bound Messenger, errors, timeouts),
  `imessage/ImessageLogicTest` (unsend window, SMS format, addresses, import rules),
  `imessage/ElfInfoTest`.

### Keystore contract (read from OpenBubbles 2.0.0's own implementation)
Public keys: X.509 SubjectPublicKeyInfo DER. ECDH (`derive`): peer key in the same form, raw
secret out, "ECDH" on AndroidKeyStore. AES-GCM: 12-byte IV + ciphertext + 16-byte tag.
RSA: PKCS#1 v1.5 or OAEP (digest + MGF1 digest). Signatures: `<SHA..>with<RSA|ECDSA>`.
`importKey`: `WrappedKeyEntry` under an RSA-2048 PURPOSE_WRAP_KEY key (OAEP, SHA-256) whose
public key `getImportWrapKey` returns. Key purposes from `KeystoreAccessRules`.

### Not verified (no phone or relay in the build environment)
Loading the real library on the Fold 7, registration, sending and unsending. The pass the
owner set: registration completes and the status turns green; if that fails with this
official library, the relay is the problem.

## Goal (owner's direction)
- iMessage inside LightDecoder, built from open-source tools that need no paid licence.
- Use the official `librust_lib_bluebubbles.so` from the start (not a build with
  placeholder certificates), registering with the owner's **relay code**.
- Pass = registration completes and iMessage status goes green. If that fails with the
  official library, the relay is the problem, not the build.
- Unsend: `sendImessage(...)` returns the sent message's GUID; `unsend(guid, part)`
  retracts it. LightDecoder stores only those GUIDs, never message content. The Unsend
  button works for about 2 minutes after sending; the recipient needs iOS 16+ for it to
  disappear on their side; a retract that does not go through shows a clear error.

## Result 2026-11-19: 2.0.0 library inspected, route decided
Files inspected (owner's own download, not committed): OpenBubbles 2.0.0 base APK and the
`config.armeabi_v7a` `librust_bridge.so` (sha256 7d628384...e05e, 23 MB, uniffi_core 0.32.0).
- **It exposes everything needed**, with these Kotlin shapes (generated; see below):
  `configFromRelay(code, host, token?) -> JoinedOsConfig`, `validateRelay(config) -> String?`,
  `newNgmIdentity()`, `setupPush(config, identity, state?, statePath) -> SetupPushResult`,
  `registerIds(path, config, aps, identity, users) -> RegisterResult`,
  `startSession(path, config, conn, anisette, account?, users, identity, circleSession?, pipeline)
  -> SharedPushStateHandle`, `sendMessage(session, MessageInst) -> Boolean`, `doReregister(session)`.
- **GUID**: `MessageInst.id` is a String the caller sets, so `sendImessage` makes a UUID, sends,
  and returns that id (only if `sendMessage` returned true).
- **Unsend**: `PushChatMessage` has an unsend variant carrying `UnsendMessage(tuuid: String,
  editPart: ULong)`: `unsend(guid, part)` = `sendMessage` of that, to the same conversation.
- **Bindings are generated from the library itself**, not copied from OpenBubbles' app:
  `tools/uniffi-bindgen/gen.sh <librust_bridge.so> <out>` (uniffi-bindgen 0.32.0, MPL-2.0, plus a
  3-line patch because stripped Android libraries keep metadata only in the dynamic symbol
  table). Output (32,948 lines, package `uniffi.rust_bridge`, uses JNA) is made on the
  machine and not committed. Same bindings come from any ABI's library.
- **Needed on the phone**: the arm64 `librust_bridge.so` and `libheifdec.so` (the v7a files
  cannot run on the Fold 7; `libuniffi_runtime.so` turned out not to be needed), imported by the
  user and accepted only when their SHA-256 is pinned (see "Import" above: nothing unpinned is
  loaded).
- **arm64 files received (2026-11-19), the ones LightDecoder will pin on import:**
  `librust_bridge.so` sha256 `b6dfe8ce44395ca48e041657dc990a534fdaa7acfda1c1d8f2e269d8e332c997`
  (33,123,024 bytes), `libuniffi_runtime.so` sha256
  `dfd4f36f8e452df7d811cafa00a9e4a0a3ff0ad7026a368f342287ea5be776d7`. uniffi contract version
  30; all 228 call checksums decoded (e.g. send_message 18695, register_ids 886,
  config_from_relay 61018) and they equal the ones in the generated bindings, which are
  byte-identical to those made from the v7a library.
- Still open: how 2.0.0's own app registers (the sequence around `setupPush` ->
  `registerIds` -> `startSession`, and the Android keystore callback `NativeKeystore`
  the library needs); read from the APK's `classes*.dex`.

## Update 2026-11-19: 2.0.0 is not a Flutter app any more
The owner opened the 2.0.0 XAPK (`config.armeabi_v7a` split, lib/armeabi-v7a). It has
**no `librust_lib_bluebubbles.so`, no `libflutter.so`, no `libapp.so`**; instead
`librust_bridge.so` (23 MB on 32-bit ARM) and `libuniffi_runtime.so`. So 2.0.0 is a native
Android app whose Rust core is reached through **uniffi**, i.e. generated Kotlin bindings.
If that holds, most of the "Route" section above no longer applies:
- uniffi calls are exported by name (`uniffi_<crate>_fn_func_*`, `_fn_method_*`) with a
  checksum per call and a contract version, and the matching Kotlin bindings ship in the
  same APK's `classes*.dex` (readable with jadx, Apache-2.0).
- LightDecoder can then call the official 2.0.0 library straight from Kotlin in its
  `:imessage` process: no Flutter, no Dart, no reverse-engineered byte formats. The
  import check pins the library's SHA-256 and the per-call checksums.
- `tools/openbubbles-inspect` now reports uniffi libraries (calls, checksums, contract
  version, likely register/relay/send/unsend calls, and the uniffi packages in the dex).
- Still needed: the arm64 `librust_bridge.so` + `libuniffi_runtime.so` and the base APK
  (for its dex), to read the real call list.

## What was found
1. **Why placeholders exist.** Public `OpenBubbles/rustpush` (SSPL-1.0, updated
   2026-10-12) leaves out `certs/fairplay/*` (Apple FairPlay keys used in
   `src/activation.rs` to activate the device for push) and `certs/proxy/*` (test only).
   A source build cannot activate without them; the official library has them built in.
   With a relay code the iPhone supplies validation data, but activation still needs
   the FairPlay keys, so the relay does not remove the need for them.
2. **The Android library only ships in the Play Store app.** GitHub releases of
   `openbubbles-app` are Linux builds only. Play is at **2.0.0** (updated 2026-11-01).
3. **The library has no Kotlin calls for register, send or unsend.** Its uniffi
   exports (`rust/src/native.rs`) only cover the background loop, keychain, FaceTime
   decline and the like. Register (`config_from_relay`, `setup_push`, `register_ids`,
   `make_imclient`), `send` and unsend (`Message::Unsend(UnsendMessage{tuuid, edit_part})`)
   are **flutter_rust_bridge 2.3 functions**, reached through one exported dispatcher
   (`frbgen_bluebubbles_pde_ffi_dispatcher_primary`) by **number**, with arguments in
   frb's SSE byte format. The numbers and layouts are not exported; they live in the
   generated Dart bindings.
4. **Bindings must match the exact build.** frb's Dart side refuses a library whose
   content hash (SHA-1 of the sorted bridged function names, exported as
   `frb_get_rust_content_hash`) differs from its own.
5. **Public app source lags the Play build.** Newest public bridge: commit `c30641f4e`
   (2026-03-16, hash -1267927268, pubspec 1.15.0+207; branch head `eed1b63` 1.15.0+227,
   2026-04-14). Nothing public is close to 2.0.0, so 2.0.0's hash is expected **not** to
   match, but this must be checked on the real file: `tools/openbubbles-inspect`.
6. **Licences.** rustpush: SSPL-1.0, free, no fee, not OSI-approved; its exception
   lets only OpenBubbles relicense. openbubbles-app: Apache-2.0. LightDecoder: AGPL-3.0
   (and it carries AGPL libgm). Owner's decision: **accept, keep it separate**: the
   iMessage part is its own module, off unless turned on, the official library is
   **never committed or bundled**. The user imports it from their own OpenBubbles install,
   and NOTICE explains the SSPL terms.

## Route, decided by what the inspector reports on the 2.0.0 APK
- **MATCH** (a public commit has the same hash): generate Dart bindings from that commit
  and drive this very library (plan below). Best case.
- **NO MATCH** (expected): the bindings for 2.0.0 have to be recovered from the same
  APK's `libapp.so` (the compiled Dart side) with an open-source Flutter AOT decompiler
  (blutter, which needs the Dart snapshot hash the inspector prints). That only has to
  recover the handful of calls listed in point 3 and the types they take, but it is
  reverse-engineering, and it has to be redone for every OpenBubbles update that changes
  the bridge. The fallback is pinning to an older Play build whose hash *does* match
  public source (1.15.0 builds; the inspector names which one).

## Plan once a matching binding set exists (1.15.x analysis, superseded)
- **`:imessage` process** (like `:discord`): loads the imported
  `librust_lib_bluebubbles.so` from app-private storage after checking its SHA-256 and
  content hash against the pinned values; nothing else runs there.
- **Headless Dart engine** (Flutter add-to-app, BSD; no UI) running a small Dart
  entrypoint built against the matched bindings. It does what OpenBubbles' setup does
  for a relay code (`lib/app/layouts/setup/pages/rustpush/hw_inp.dart`:
  `configFromRelay(code, host)` → `setupPush` → `registerIds` → `makeImclient`
  → watcher loop) and exposes over a MethodChannel:
  `register(relayCode)`, `status()`, `sendImessage(handle, text) -> guid`,
  `unsend(guid, part)`, and incoming-message events.
- **Kotlin side** (`imessage/`): `ImessageBridge` bound service, a "Messages ▾" switcher
  entry (Features flag, off by default), the relay-code screen, and a status row
  (green only after `registerIds` succeeds and the IM client is up).
- **Unsend**: `imessage/SentGuids` keeps `(guid, part, sentAt)` only, deleted after
  15 minutes; the Unsend action shows only for the user's own message and only while
  `now - sentAt < 2 min`. A failed retract (send error, not registered, window passed)
  shows "Couldn't unsend: <reason>". Apple gives no confirmation from the recipient, so
  the screen also says it may still show on iOS 15 or older; that cannot be detected.
- **Test**: unit tests for the GUID store, window and error mapping; the import check
  against a library built by the inspector's self-test; on a phone: register with the
  relay code, status green, send, unsend within 2 minutes to an iOS 16+ device.

## What is needed next
- The OpenBubbles **2.0.0** APK files. Downloading from APK mirrors was blocked in the
  build session. From a phone with OpenBubbles installed:
  `adb shell pm path com.openbubbles.messaging`, pull `base.apk` and
  `split_config.arm64_v8a.apk`, put them in the session (or repo branch, not main), and run
  `python3 -I tools/openbubbles-inspect/inspect.py <folder>`.
- Its result picks MATCH / NO MATCH above.

## Regenerating the public hash table
```
git clone --filter=blob:none --no-checkout https://github.com/OpenBubbles/openbubbles-app.git ob
cd ob && git fetch origin rustpush --tags
git log origin/rustpush --format='%H %cs' -- rust/src/frb_generated.rs | while read h d; do
  echo "$(git show $h:rust/src/frb_generated.rs | grep -o 'CONTENT_HASH: i32 = -\?[0-9]*' | grep -o -- '-\?[0-9]*$') $h $d"; done
```
