#!/usr/bin/env bash
# Builds the decoder for the build machine (not Android) and drives the real JNI
# entry points over sample HEIC files, checking size and alpha against the
# native probe's ground truth. This verifies the C++ core *and* the JNI
# marshalling without a device or emulator.
#
# Usage: tools/jni-smoke/run.sh <file.heic> [more.heic ...]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
build="$repo/build/host"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <file.heic> [more.heic ...]" >&2
  echo "sample iPhone stickers: github.com/ReagentX/imessage-exporter" \
       "(imessage-database/test_data/stickers)" >&2
  exit 2
fi

cmake -G Ninja -DLD_HOST_BUILD=ON -DCMAKE_BUILD_TYPE=Release \
  -S "$repo/app/src/main/cpp" -B "$build" >/dev/null
ninja -C "$build" lightdecoder ldprobe >/dev/null

javac -d "$build/jni-classes" "$here"/com/lightdecoder/heif/NativeDecoder.java "$here"/JniSmoke.java
java -Djava.library.path="$build" -cp "$build/jni-classes" JniSmoke "$@"
