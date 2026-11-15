# LightDecoder

An Android app that decodes and displays the HEIC stickers and images that
iPhones send and that Google Messages often shows as a broken **"Can't load
image — this media format is not available on this device."**

It ships its own HEVC/HEIF decoder (libde265 + libheif, built in), so it does
**not** depend on whether a given phone has HEIC support. It opens a `.heic`
file or a sticker shared into it, decodes transparency and animation on-device,
and lets you view, save, and re-share it as a PNG.

> Status: early. The decoder core and the viewer are working and produce an
> installable APK. The Google Messages pairing module (below) is planned, not
> yet built.

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
  "open with" and "share into".
- **Save / share.** Export the current frame to the gallery (scoped storage on
  Android 10+) or share it as a PNG via `FileProvider`.
- **Details.** Reports container brands, size, bit depth, alpha, colour profile,
  and frame count.

Verified against real iPhone sticker files: five sample stickers decode to the
correct dimensions with alpha preserved, both through the native command-line
probe and through the JVM across the real JNI boundary (see
`tools/jni-smoke/`).

## Planned: Google Messages pairing

To read and send messages (including RCS) from inside LightDecoder, the plan is
the **Beeper method**: pair as a "Messages for web" client to the user's own
Google Messages app using the open-source
[`libgm`](https://github.com/mautrix/gmessages) library (compiled for Android
with gomobile). The user's phone remains the relay and does the actual RCS;
LightDecoder reads and sends through it.

Important consequences, by design:

- **The phone is the relay.** Google Messages stays installed and default, and
  online. This is not a native carrier RCS stack — Android exposes no such API
  to third-party apps.
- **One-time consent.** Pairing uses Google's own device-pairing flow: the user
  signs into their own Google account and confirms a matching emoji on the
  phone, **once**. The app then persists the session and keeps it warm (token
  refresh + a periodic phone ping via a foreground service) so the emoji
  confirmation is not needed again under normal use. There is deliberately **no
  bypass of that first on-device confirmation** — it is the gate that proves the
  phone is the user's own.
- **Unofficial and fragile.** It emulates a web client; Google can change the
  protocol and break it.

## Licence

**GNU AGPL-3.0-or-later** (see [`LICENSE`](LICENSE)). The planned pairing module
links `libgm`, which is AGPL-3.0, so the whole app is AGPL and its source stays
available. Bundled decoder libraries libde265 and libheif are each LGPL-3.0 and
are used unmodified as git submodules under `third_party/`.

## Building

Requirements: Android SDK with NDK r28, CMake 3.31+, JDK 17.

```sh
git clone --recurse-submodules https://github.com/ClockworkBlack/lightdecoder
cd lightdecoder
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

If you cloned without `--recurse-submodules`:

```sh
git submodule update --init --depth 1
```

## Layout

```
app/src/main/cpp/        native decoder + JNI (ld_core, ld_jni, ld_probe)
app/src/main/java/       Kotlin: NativeDecoder binding, HeifCodec, viewer UI
third_party/libde265     HEVC decoder (submodule, pinned)
third_party/libheif      HEIF container (submodule, pinned)
tools/jni-smoke/         host-side decode/JNI verification harness
```
