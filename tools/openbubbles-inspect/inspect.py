#!/usr/bin/env python3
"""Work out which OpenBubbles source matches an official OpenBubbles Android build.

Usage:
    python3 -I inspect.py <OpenBubbles .apk | .xapk | .apks | .apkm | folder of split APKs | librust_lib_bluebubbles.so>
    python3 -I inspect.py --self-test

Only reads the files: nothing in them is run.

What it reports, and why it matters for LightDecoder's iMessage module (docs/imessage-openbubbles.md):

* The flutter_rust_bridge content hash compiled into librust_lib_bluebubbles.so
  (exported as `frb_get_rust_content_hash`). The hash is SHA-1 over the sorted names
  of every bridged function. The app refuses to start a library whose hash differs
  from its own bindings, so it says exactly which binding set the library expects.
* Whether that hash matches a public OpenBubbles/openbubbles-app commit
  (public_hashes.tsv). A match means LightDecoder can generate matching bindings
  from that commit and drive this very library. No match means the build's source
  is not published, and the bindings would have to be recovered from the same APK's
  libapp.so (the compiled Dart side).
* The opaque Rust types the library exports (each has exported
  rust_arc_increment/decrement functions) compared with the newest public source:
  types that are new or gone show how far the build has drifted from it.
* flutter_rust_bridge version, the Dart snapshot hash in libapp.so (the Dart SDK
  that built it, which a decompiler for the Dart side needs), and the CPU type.
"""
import hashlib
import io
import os
import re
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RUST_LIB = "librust_lib_bluebubbles.so"
DART_LIB = "libapp.so"
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]
OPAQUE_RE = re.compile(rb"rust_arc_increment_strong_count_RustOpaque_flutter_rust_bridgefor_generatedRustAutoOpaqueInner([A-Za-z0-9]+)")


# ---------------------------------------------------------------- ELF reading

class Elf:
    """Just enough of ELF to read exported symbols and the bytes behind them."""

    def __init__(self, data):
        if data[:4] != b"\x7fELF":
            raise ValueError("not an ELF file")
        self.data = data
        self.is64 = data[4] == 2
        self.end = "<" if data[5] == 1 else ">"
        e = self.end
        if self.is64:
            (self.machine,) = struct.unpack_from(e + "H", data, 18)
            shoff, = struct.unpack_from(e + "Q", data, 40)
            phoff, = struct.unpack_from(e + "Q", data, 32)
            phentsize, phnum, shentsize, shnum = struct.unpack_from(e + "HHHH", data, 54)
        else:
            (self.machine,) = struct.unpack_from(e + "H", data, 18)
            phoff, shoff = struct.unpack_from(e + "II", data, 28)
            phentsize, phnum, shentsize, shnum = struct.unpack_from(e + "HHHH", data, 42)
        self.loads = []
        for i in range(phnum):
            o = phoff + i * phentsize
            if self.is64:
                ptype, _flags, off, vaddr, _pa, filesz = struct.unpack_from(e + "IIQQQQ", data, o)
            else:
                ptype, off, vaddr, _pa, filesz = struct.unpack_from(e + "IIIII", data, o)
            if ptype == 1:
                self.loads.append((vaddr, off, filesz))
        self.sections = []
        for i in range(shnum):
            o = shoff + i * shentsize
            if self.is64:
                name, stype, _f, addr, off, size, link, _info, _al, entsize = struct.unpack_from(e + "IIQQQQIIQQ", data, o)
            else:
                name, stype, _f, addr, off, size, link, _info, _al, entsize = struct.unpack_from(e + "IIIIIIIIII", data, o)
            self.sections.append((stype, off, size, link, entsize))

    def symbols(self):
        """Exported dynamic symbols: {name: (address, size)}."""
        out = {}
        for stype, off, size, link, entsize in self.sections:
            if stype != 11:  # SHT_DYNSYM
                continue
            stroff = self.sections[link][1]
            n = size // entsize if entsize else 0
            for i in range(n):
                o = off + i * entsize
                if self.is64:
                    nm, _info, _other, shndx, value, ssize = struct.unpack_from(self.end + "IBBHQQ", self.data, o)
                else:
                    nm, value, ssize, _info, _other, shndx = struct.unpack_from(self.end + "IIIBBH", self.data, o)
                if shndx == 0 or nm == 0:
                    continue
                endp = self.data.index(b"\0", stroff + nm)
                out[self.data[stroff + nm:endp].decode("latin-1")] = (value, ssize)
        return out

    def file_offset(self, vaddr):
        for va, off, filesz in self.loads:
            if va <= vaddr < va + filesz:
                return off + (vaddr - va)
        return None

    def cpu(self):
        return {183: "arm64", 40: "arm", 62: "x86_64", 3: "x86"}.get(self.machine, "machine %d" % self.machine)


def to_i32(v):
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v & 0x80000000 else v


def decode_return_constant(elf, addr):
    """The i32 a tiny `fn() -> i32 { CONST }` returns, read from its machine code."""
    off = elf.file_offset(addr)
    if off is None:
        return None
    code = elf.data[off:off + 64]
    if elf.machine == 183:  # arm64: mov/movn w0 then optional movk w0, lsl 16; ret
        val = None
        for i in range(0, len(code) - 3, 4):
            (ins,) = struct.unpack_from("<I", code, i)
            if ins == 0xD65F03C0:  # ret
                return to_i32(val) if val is not None else None
            rd, imm16, hw = ins & 0x1F, (ins >> 5) & 0xFFFF, (ins >> 21) & 3
            op = ins & 0xFF800000
            if rd != 0:
                continue
            if op == 0x52800000:      # movz w0
                val = imm16 << (16 * hw)
            elif op == 0x12800000:    # movn w0
                val = ~(imm16 << (16 * hw)) & 0xFFFFFFFF
            elif op == 0x72800000 and val is not None:  # movk w0
                val = (val & ~(0xFFFF << (16 * hw))) | (imm16 << (16 * hw))
        return None
    if elf.machine in (62, 3):  # x86: mov eax, imm32 ; ret
        i = code.find(b"\xb8")
        if i >= 0 and i + 5 < len(code):
            return to_i32(struct.unpack_from("<I", code, i + 1)[0])
        return None
    if elf.machine == 40:  # arm32: movw r0 / movt r0 (ARM mode)
        lo = hi = None
        for i in range(0, len(code) - 3, 4):
            (ins,) = struct.unpack_from("<I", code, i)
            if (ins & 0x0FF0F000) == 0x03000000:
                lo = ((ins >> 4) & 0xF000) | (ins & 0xFFF)
            elif (ins & 0x0FF0F000) == 0x03400000:
                hi = ((ins >> 4) & 0xF000) | (ins & 0xFFF)
            elif ins == 0xE12FFF1E:
                break
        if lo is not None:
            return to_i32(lo | ((hi or 0) << 16))
    return None


def dart_snapshot_hash(elf):
    """The 32-character snapshot hash after the Dart VM snapshot magic."""
    syms = elf.symbols()
    for name in ("_kDartVmSnapshotData", "_kDartIsolateSnapshotData"):
        if name in syms:
            off = elf.file_offset(syms[name][0])
            if off is None:
                continue
            blob = elf.data[off:off + 256]
            if blob[:4] == b"\xf5\xf5\xdc\xdc":
                h = blob[20:52]
                if re.fullmatch(rb"[0-9a-f]{32}", h):
                    feats = blob[52:].split(b"\0")[0].decode("latin-1", "replace")
                    return h.decode(), feats
    m = re.search(rb"\xf5\xf5\xdc\xdc.{16}([0-9a-f]{32})", elf.data, re.S)
    return (m.group(1).decode(), "") if m else (None, "")


# ---------------------------------------------------------------- finding the libraries

def nested_zips(data, depth=0):
    """Yield (path, ZipFile) for an APK and every APK inside it (XAPK/APKS bundles)."""
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return
    yield "", z
    if depth > 1:
        return
    for info in z.infolist():
        if info.filename.lower().endswith((".apk", ".apks", ".xapk", ".apkm")):
            for sub, zz in nested_zips(z.read(info), depth + 1):
                yield info.filename + ("!" + sub if sub else ""), zz


def find_libs(path):
    """{(abi, libname): (where, bytes)} for the two libraries, from any input form."""
    found = {}

    def take(where, name, read):
        parts = name.split("/")
        if len(parts) >= 3 and parts[-3] == "lib" and parts[-1] in (RUST_LIB, DART_LIB):
            found.setdefault((parts[-2], parts[-1]), (where, read()))

    if os.path.isdir(path):
        for root, _d, files in os.walk(path):
            for f in files:
                p = os.path.join(root, f)
                if f in (RUST_LIB, DART_LIB):
                    abi = os.path.basename(root)
                    found.setdefault((abi if abi in ABIS else "?", f), (p, open(p, "rb").read()))
                elif f.lower().endswith((".apk", ".apks", ".xapk", ".apkm")):
                    for sub, z in nested_zips(open(p, "rb").read()):
                        for n in z.namelist():
                            take(p + ("!" + sub if sub else "") + "!" + n, n, lambda z=z, n=n: z.read(n))
        return found
    data = open(path, "rb").read()
    if data[:4] == b"\x7fELF":
        is_dart = os.path.basename(path) == DART_LIB
        found[("?", DART_LIB if is_dart else RUST_LIB)] = (path, data)
        return found
    for sub, z in nested_zips(data):
        for n in z.namelist():
            take(path + ("!" + sub if sub else "") + "!" + n, n, lambda z=z, n=n: z.read(n))
    return found


def load_public():
    rows = []
    with open(os.path.join(HERE, "public_hashes.tsv")) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            h, commit, date, version = line.rstrip("\n").split("\t")
            rows.append((int(h), commit, date, version))
    with open(os.path.join(HERE, "public_opaque_types.txt")) as f:
        opaque = {l.strip() for l in f if l.strip()}
    return rows, opaque


# ---------------------------------------------------------------- report

def report(path, out=sys.stdout):
    libs = find_libs(path)
    if not any(k[1] == RUST_LIB for k in libs):
        print("No %s found in %s." % (RUST_LIB, path), file=out)
        print("For a Play Store install, use base.apk plus split_config.arm64_v8a.apk (or the whole .xapk).", file=out)
        return 2
    public, public_opaque = load_public()
    newest = public[0] if public else None
    verdict = 1
    for (abi, name), (where, data) in sorted(libs.items()):
        if name != RUST_LIB:
            continue
        elf = Elf(data)
        syms = elf.symbols()
        print("== %s (%s, %s)" % (name, abi, elf.cpu()), file=out)
        print("   from    %s" % where, file=out)
        print("   sha256  %s" % hashlib.sha256(data).hexdigest(), file=out)
        frb = sorted(set(m.decode() for m in re.findall(rb"flutter_rust_bridge-(\d+\.\d+\.\d+)", data)))
        print("   flutter_rust_bridge %s" % (", ".join(frb) or "not found"), file=out)
        dispatch = sorted(s for s in syms if "pde_ffi_dispatcher" in s)
        print("   dispatcher %s" % (", ".join(dispatch) or "none (not a flutter_rust_bridge 2.x PDE build)"), file=out)
        uniffi = sorted(s for s in syms if s.startswith("uniffi_") and "_fn_func_" in s)
        print("   uniffi functions (callable from Kotlin): %d" % len(uniffi), file=out)
        for s in uniffi:
            print("      " + s.split("_fn_func_", 1)[1], file=out)
        h = None
        if "frb_get_rust_content_hash" in syms:
            h = decode_return_constant(elf, syms["frb_get_rust_content_hash"][0])
        print("   content hash %s" % (h if h is not None else "could not be read"), file=out)
        matches = [r for r in public if r[0] == h] if h is not None else []
        if matches:
            verdict = 0
            print("   MATCH: same bridged functions as public source:", file=out)
            for r in matches:
                print("      %s  %s  %s" % (r[1][:12], r[2], r[3]), file=out)
            print("   -> generate LightDecoder's bindings from the newest of these commits.", file=out)
        elif h is not None:
            print("   NO MATCH with any public commit (newest public: %s %s, hash %d)." % (
                newest[2], newest[3], newest[0]) if newest else "   NO MATCH.", file=out)
            print("   -> its source is not published; the bindings must be recovered from libapp.so.", file=out)
        types = {m.decode() for m in OPAQUE_RE.findall(data)}
        if types:
            added, gone = sorted(types - public_opaque), sorted(public_opaque - types)
            print("   opaque types: %d (public newest: %d); new %d, gone %d" % (
                len(types), len(public_opaque), len(added), len(gone)), file=out)
            for sign, names in (("+", added), ("-", gone)):
                for t in names[:25]:
                    print("      %s %s" % (sign, t), file=out)
                if len(names) > 25:
                    print("      %s ... %d more" % (sign, len(names) - 25), file=out)
    for (abi, name), (where, data) in sorted(libs.items()):
        if name != DART_LIB:
            continue
        elf = Elf(data)
        snap, feats = dart_snapshot_hash(elf)
        print("== %s (%s, %s)" % (name, abi, elf.cpu()), file=out)
        print("   from    %s" % where, file=out)
        print("   Dart snapshot hash %s" % (snap or "not found"), file=out)
        if feats:
            print("   snapshot features  %s" % feats, file=out)
    return verdict


# ---------------------------------------------------------------- self test

def self_test():
    """Checks the decoders on small libraries built here with clang (if available)."""
    import shutil
    import subprocess
    import tempfile
    clang = shutil.which("clang")
    if not clang:
        print("clang not found; skipping")
        return 0
    want = -1267927268
    src = ("int frb_get_rust_content_hash(void){return %d;}\n"
           "void frbgen_bluebubbles_pde_ffi_dispatcher_primary(void){}\n"
           "void frbgen_bluebubbles_rust_arc_increment_strong_count_RustOpaque_flutter_rust_bridgefor_generatedRustAutoOpaqueInnerArcIMClient(void){}\n"
           "void frbgen_bluebubbles_rust_arc_increment_strong_count_RustOpaque_flutter_rust_bridgefor_generatedRustAutoOpaqueInnerBrandNewThing(void){}\n"
           "void uniffi_rust_lib_bluebubbles_fn_func_init_native(void){}\n"
           "const char *frbv = \"flutter_rust_bridge-2.3.0/src/lib.rs\";\n") % want
    fails = 0
    with tempfile.TemporaryDirectory() as d:
        c = os.path.join(d, "s.c")
        open(c, "w").write(src)
        for target in ("aarch64-linux-android21", "armv7a-linux-androideabi21", "x86_64-linux-android21"):
            so = os.path.join(d, target, "lib", "x", RUST_LIB)
            os.makedirs(os.path.dirname(so))
            r = subprocess.run([clang, "--target=" + target, "-O2", "-fPIC", "-shared", "-nostdlib",
                                "-fuse-ld=lld", "-marm", c, "-o", so], capture_output=True, text=True)
            if r.returncode != 0:
                r = subprocess.run([clang, "--target=" + target, "-O2", "-fPIC", "-shared", "-nostdlib",
                                    "-fuse-ld=lld", c, "-o", so], capture_output=True, text=True)
            if r.returncode != 0:
                print("%-28s skipped (%s)" % (target, r.stderr.strip().splitlines()[-1:]))
                continue
            elf = Elf(open(so, "rb").read())
            got = decode_return_constant(elf, elf.symbols()["frb_get_rust_content_hash"][0])
            ok = got == want
            fails += not ok
            print("%-28s %s (%s)" % (target, "ok" if ok else "FAIL", got))
        # Whole report on an APK-shaped zip holding the arm64 build.
        arm = os.path.join(d, "aarch64-linux-android21", "lib", "x", RUST_LIB)
        if os.path.exists(arm):
            apk = os.path.join(d, "split.apk")
            with zipfile.ZipFile(apk, "w") as z:
                z.write(arm, "lib/arm64-v8a/" + RUST_LIB)
            bundle = os.path.join(d, "bundle.xapk")
            with zipfile.ZipFile(bundle, "w") as z:
                z.write(apk, "config.arm64_v8a.apk")
            buf = io.StringIO()
            code = report(bundle, buf)
            text = buf.getvalue()
            checks = [code == 0, "MATCH" in text, "+ BrandNewThing" in text, "init_native" in text,
                      "flutter_rust_bridge 2.3.0" in text]
            ok = all(checks)
            fails += not ok
            print("%-28s %s" % ("xapk report", "ok" if ok else "FAIL %s\n%s" % (checks, text)))
    print("self-test:", "passed" if not fails else "%d failed" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        sys.exit(self_test())
    if len(sys.argv) != 2:
        print(__doc__.strip().split("\n\n")[1])
        sys.exit(2)
    sys.exit(report(sys.argv[1]))
