# iMessage in LightDecoder via OpenBubbles' library (design, 2026-11-19)

Status: **research done, inspection tool written; no app code yet.** Next step needs the
OpenBubbles 2.0.0 Android APK (see "What is needed next").

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

## Plan once a matching binding set exists
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
