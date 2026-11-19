# openbubbles-inspect

Tells which public OpenBubbles source (if any) matches an official OpenBubbles
Android build, without running anything from it. Background and the plan it feeds:
`docs/imessage-openbubbles.md`.

```
python3 -I inspect.py OpenBubbles.xapk          # or .apk / .apks / a folder of split APKs / the .so
python3 -I inspect.py --self-test                # checks the decoders on libraries built with clang
```

Getting the APK from a phone that has OpenBubbles from the Play Store:

```
adb shell pm path com.openbubbles.messaging       # lists base.apk and split_config.arm64_v8a.apk
adb pull <each path> openbubbles/                 # then: python3 -I inspect.py openbubbles/
```

Files:
- `public_hashes.tsv`: flutter_rust_bridge content hash of every public
  `OpenBubbles/openbubbles-app` commit that changed the bridge (branch `rustpush` and
  the release tags), newest first. Regenerate from a clone with the loop in the doc.
- `public_opaque_types.txt`: opaque Rust types bridged by the newest public commit.

Exit code 0 = a public commit matches, 1 = no match, 2 = no library found.
