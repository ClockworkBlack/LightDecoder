package com.lightdecoder.imessage.direct

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * OpenBubbles' native libraries, which the user imports from their own copy of the OpenBubbles
 * app. LightDecoder never ships them (docs/imessage-openbubbles.md: they hold Apple keys and are
 * under OpenBubbles' terms). Each imported file must be one of [PINS] for this phone's CPU type,
 * byte for byte: anything else is refused, and the SHA-256 of each file is checked again right
 * before it is loaded ([load]). The Kotlin bindings in `uniffi.rust_bridge` were generated from
 * exactly these builds; a library that is not pinned never runs in the ":imessage" process.
 *
 * librust_bridge.so cannot be loaded without libheifdec.so: both pinned builds name it in
 * DT_NEEDED and bind every symbol at load (BIND_NOW). So a CPU type whose libheifdec.so is not
 * pinned ([unpinned]) cannot use the library until a pinned copy is added here.
 */
object DirectLibrary {

    /** One known file: which library it is, for which CPU, and its SHA-256. */
    data class Pin(val name: String, val abi: String, val sha256: String)

    const val BRIDGE = "librust_bridge.so"
    const val HEIF = "libheifdec.so"

    /** The libraries the bridge needs, in load order: libheifdec first (librust_bridge links to it). */
    val REQUIRED = listOf(HEIF, BRIDGE)

    /** OpenBubbles 2.0.0 (Play Store, 2026-11). */
    val PINS = listOf(
        Pin(BRIDGE, "arm64-v8a", "b6dfe8ce44395ca48e041657dc990a534fdaa7acfda1c1d8f2e269d8e332c997"),
        Pin(BRIDGE, "armeabi-v7a", "7d62838458723fa2b6fa2a65e48b55f95455cca2fec7ab47ca765daed998e05e"),
        Pin(HEIF, "arm64-v8a", "bdabf750465540846ca40b032e64666fb9a5b96b554795e1d809dcfbcb5cb17c"),
        // libheifdec.so for 32-bit ARM is not pinned (no copy checked yet): 32-bit phones cannot
        // use the library until it is ([unpinned]), as librust_bridge.so needs it to load.
    )

    /** The pinned build of [name] for [abi], or null when there is none. */
    fun pinFor(name: String, abi: String): Pin? = PINS.firstOrNull { it.name == name && it.abi == abi }

    /**
     * The libraries with no pinned build for [abi]: empty where everything can be imported.
     * Otherwise the library cannot be used on that CPU type at all (librust_bridge.so does not
     * load without libheifdec.so), and the setup screen says so instead of asking for files.
     */
    fun unpinned(abi: String): List<String> = REQUIRED.filter { pinFor(it, abi) == null }

    /** Why a library was not loaded: it is not the pinned build any more (or never was). */
    class NotPinned(message: String) : DirectEngine.ImessageError(message)

    /** The CPU type this process runs as, in Android's ABI names. */
    fun processAbi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull() ?: Build.SUPPORTED_ABIS.first()
    }

    fun dir(context: Context): File = File(context.filesDir, "imessage/lib/" + processAbi())

    /**
     * The CPU types this APK carries OpenBubbles' libraries for (built in from the build machine,
     * see bundleImessageLibs in app/build.gradle.kts; checked against the same pins). Empty for a
     * build without them, which asks the user to import the files instead.
     */
    fun bundledAbis(context: Context): Set<String> = try {
        context.assets.open("imessage/bundled_abis.txt").bufferedReader().use { r ->
            r.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    } catch (e: IOException) {
        emptySet()
    }

    /** Whether this APK carries the libraries for this phone: then nothing needs importing. */
    fun isBundled(context: Context): Boolean = processAbi() in bundledAbis(context)

    /**
     * The libraries still missing for this CPU type (empty: ready to load). Whether the files there
     * are the pinned builds is checked when they are loaded ([load], [verify]), which refuses (and
     * removes) any that is not.
     */
    fun missing(context: Context): List<String> =
        if (isBundled(context)) emptyList() else REQUIRED.filter { !File(dir(context), it).isFile }

    fun isInstalled(context: Context): Boolean = missing(context).isEmpty()

    /** What an imported file turned out to be. */
    sealed class ImportResult {
        /** One of [PINS] for this phone, byte for byte: kept as [name]. */
        data class Installed(val name: String, val sha256: String) : ImportResult()
        data class Refused(val reason: String) : ImportResult()
    }

    /**
     * Decides what to do with a file the user picked, from its SHA-256 (and, only to say why a
     * file is refused, its ELF header). Only a pinned build for [abi] is ever accepted. Pure, so
     * the rules are tested without a phone.
     */
    fun classify(sha256: String, elf: ElfInfo?, abi: String): ImportResult {
        val pin = PINS.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }
        if (pin != null) {
            return if (pin.abi == abi) {
                ImportResult.Installed(pin.name, sha256 = sha256)
            } else {
                ImportResult.Refused(
                    "This is OpenBubbles' ${pin.name} for ${pin.abi}, but this phone needs the $abi one " +
                        "(in the APK's config.${abi.replace('-', '_')} part).",
                )
            }
        }
        if (elf == null) return ImportResult.Refused("This is not an Android library (.so) file.")
        if (elf.abi != abi) {
            return ImportResult.Refused("This library is for ${elf.abi}; this phone needs $abi.")
        }
        if (elf.soname == HEIF || (elf.soname == null && elf.exportsHeifDecoder)) {
            if (pinFor(HEIF, abi) == null) return ImportResult.Refused(notPinnedForAbi(HEIF, abi))
            return ImportResult.Refused("This libheifdec.so is not the one from OpenBubbles 2.0.0.")
        }
        if (elf.exportsRustBridge) {
            if (pinFor(BRIDGE, abi) == null) return ImportResult.Refused(notPinnedForAbi(BRIDGE, abi))
            return ImportResult.Refused(
                "This librust_bridge.so is a different OpenBubbles build than 2.0.0, which is the one " +
                    "LightDecoder was built for. Use the file from OpenBubbles 2.0.0.",
            )
        }
        return ImportResult.Refused("This is not one of the OpenBubbles files LightDecoder needs ($BRIDGE, $HEIF).")
    }

    /** Why [name] for [abi] is refused whatever copy it is: no build of it is pinned for that CPU type. */
    fun notPinnedForAbi(name: String, abi: String): String =
        "LightDecoder has no pinned $name for $abi phones yet, so it cannot use OpenBubbles' library on this phone. " +
            "Only files whose SHA-256 is known are loaded."

    /**
     * Whether the library files in [dir] are the pinned builds for [abi], read again now: null when
     * they are, else why not. A file that is there but is not the pinned build (or has no pinned
     * build for [abi] at all, as an earlier version could leave) is deleted, so it is asked for
     * again and never loaded by anything. Never loads anything itself.
     */
    internal fun verify(dir: File, abi: String, pins: List<Pin> = PINS): String? {
        for (name in REQUIRED) {
            val file = File(dir, name)
            val pin = pins.firstOrNull { it.name == name && it.abi == abi }
            if (pin == null) {
                if (file.exists()) {
                    file.setWritable(true)
                    file.delete()
                }
                return notPinnedForAbi(name, abi)
            }
            if (!file.isFile) return "$name is not imported yet."
            val sha = try {
                sha256(file)
            } catch (e: IOException) {
                return "$name could not be read: ${e.message}"
            }
            if (!sha.equals(pin.sha256, ignoreCase = true)) {
                file.setWritable(true)
                file.delete()
                return "$name is not the pinned OpenBubbles 2.0.0 build (SHA-256 $sha), so it was not loaded and was removed. " +
                    "Import the file from OpenBubbles 2.0.0 again."
            }
        }
        return null
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies a picked file in, if [classify] accepts it. Never keeps a refused or partial file. */
    fun import(context: Context, uri: Uri): ImportResult {
        val tmp = File(context.cacheDir, "imessage-import.tmp")
        try {
            val sha = context.contentResolver.openInputStream(uri)?.use { copyHashing(it, tmp) }
                ?: return ImportResult.Refused("The file could not be opened.")
            val result = classify(sha, ElfInfo.read(tmp), processAbi())
            if (result is ImportResult.Installed) {
                val dest = File(dir(context), result.name)
                dest.parentFile?.mkdirs()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                }
                dest.setReadOnly()
            }
            return result
        } catch (e: IOException) {
            return ImportResult.Refused("The file could not be read: ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    /** Removes the imported libraries (the registration state is kept). */
    fun remove(context: Context) {
        dir(context).listFiles()?.forEach { it.setWritable(true); it.delete() }
    }

    internal fun copyHashing(input: InputStream, dest: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        dest.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Loads the libraries into this process, once. libheifdec.so goes first so that the Android
     * linker finds it (by its name) when librust_bridge.so asks for it; then the bindings are told
     * where librust_bridge.so is. Must run before anything in `uniffi.rust_bridge` is touched.
     *
     * Imported files are hashed again first ([verify]), and neither is loaded unless both are the
     * pinned builds: a file changed or left by an older version is refused here with
     * [NotPinned], which the setup screen shows. Both are loaded right after that check, so the
     * bindings get the library already in memory.
     */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        if (isBundled(context)) {
            // In the APK (whose signature covers them; checked against the same pins when it was
            // built): loaded by name, libheifdec first. The bindings then ask for "rust_bridge"
            // by name too, and get the library already loaded.
            System.loadLibrary("heifdec")
            System.loadLibrary("rust_bridge")
            loaded = true
            return
        }
        val dir = dir(context)
        verify(dir, processAbi())?.let { throw NotPinned(it) }
        System.load(File(dir, HEIF).absolutePath)
        System.load(File(dir, BRIDGE).absolutePath)
        System.setProperty("uniffi.component.rust_bridge.libraryOverride", File(dir, BRIDGE).absolutePath)
        loaded = true
    }

    @Volatile
    var loaded = false
        private set
}
