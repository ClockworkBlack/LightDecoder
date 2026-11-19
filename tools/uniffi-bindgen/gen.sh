#!/bin/sh
# Generates Kotlin bindings for a uniffi library (the OpenBubbles 2.0.0 librust_bridge.so you
# imported yourself) with the open-source uniffi-bindgen. Nothing is downloaded from OpenBubbles
# and the output is not committed: it is made on your machine from your copy of the library.
#   tools/uniffi-bindgen/gen.sh /path/to/librust_bridge.so /path/to/out-dir
# Needs cargo and network access to crates.io. Any ABI's library gives the same bindings.
set -eu
lib=$(realpath "$1"); mkdir -p "$2"; out=$(realpath "$2")
here=$(cd "$(dirname "$0")" && pwd)
work=${LD_BINDGEN_WORK:-$here/.work}
mkdir -p "$work/src"
cp "$here/Cargo.toml" "$work/Cargo.toml"; cp "$here/src/main.rs" "$work/src/main.rs"
if [ ! -d "$work/vendor-ub" ]; then
  (cd "$work" && cargo fetch -q)
  src=$(ls -d "$HOME"/.cargo/registry/src/*/uniffi_bindgen-0.32.0 | head -1)
  cp -r "$src" "$work/vendor-ub"
  (cd "$work/vendor-ub" && patch -p1 < "$here/read-dynsym.patch")
fi
printf '\n[patch.crates-io]\nuniffi_bindgen = { path = "vendor-ub" }\n' >> "$work/Cargo.toml"
(cd "$work" && cargo build --release -q)
# library mode runs `cargo metadata` in the current directory, so run from the work dir
cd "$work" && ./target/release/ld-uniffi-bindgen generate --no-format --library "$lib" --language kotlin --out-dir "$out"
