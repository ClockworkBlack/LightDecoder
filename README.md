# LightDecoder

An Android app that decodes and displays the HEIC stickers and images that
iPhones send and that Google Messages often shows as a broken **"Can't load
image — this media format is not available on this device."**

It ships its own HEVC/HEIF decoder (libde265 + libheif, built in), so it does
**not** depend on whether a given phone has HEIC support. It opens a `.heic`
file or a sticker shared into it, decodes transparency and animation on-device,
and lets you view, save, and re-share it as a PNG.

> Status: working. The decoder, the viewer, and an on-device sticker inbox that
> catches incoming stickers automatically all produce an installable APK, with a
> unit-test suite that decodes real HEIC files. See "Automatic sticker inbox"
> below for how the automatic catching works and stays within Android's rules.

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

- **Sticker effects.** iPhones store a plain cut-out plus an XMP note
  (`stickerEffect:type="stroke"`) and draw the Outline/Comic/Puffy/Shiny effect
  themselves. LightDecoder reads that note, labels the effect, and draws the
  Outline effect (a white border following the alpha edge); a button toggles it.

Verified against real iPhone sticker files and against synthetic files whose
every pixel is known: still, opaque, transparent, animated (intra- and
inter-coded) all decode correctly — through the native probe, through the JVM
across the real JNI boundary (`tools/jni-smoke/`), and through the Android
`Bitmap` path under Robolectric (`app/src/test/`, 16 tests).

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
  `allowBackup` is off so the inbox is never copied to a cloud backup.
- By default only HEIC/HEIF attachments (the ones Messages can't show) are kept;
  an option keeps every picture. An activity log records only kinds and
  outcomes ("picture attachment, image/heic, saved") — never message text,
  names, or conversations.

This is **Option A / Pathway 1** from `docs/rcs-integration.md`: on-device,
Play-Store-compliant, zero protocol emulation.

### What this does not do

It does not pair with, sign into, or emulate Google Messages, and it does not
capture account credentials. A notification listener only sees message
attachments **from the moment they arrive** (no history), and Android does not
expose end-to-end-encrypted RCS content this way — so some RCS stickers may not
appear. The alternative (a reverse-engineered web-relay such as `libgm`) is
deliberately **not** built here: it violates Google's terms, breaks whenever the
protocol changes, and — if its one-time on-device confirmation were ever
automated away — becomes a covert interception vector. The on-device listener
gets stickers through the front door instead.

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
app/src/main/java/
  .../heif/              NativeDecoder binding, HeifCodec, Media, ImageSniffer, StickerEffect
  .../inbox/             notification listener, on-device store, settings
  .../ui/                inbox + viewer screens
app/src/test/            unit tests that decode real HEIC through the Bitmap path
third_party/libde265     HEVC decoder (submodule, pinned)
third_party/libheif      HEIF container (submodule, pinned)
tools/jni-smoke/         host-side decode/JNI verification harness
tools/make-test-media/   generator of the unit tests' synthetic HEIC files
docs/rcs-integration.md  the five RCS access pathways and why this uses Pathway 1
```
