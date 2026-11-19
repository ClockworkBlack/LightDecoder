package com.lightdecoder.imessage

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
 * under OpenBubbles' terms). Each imported file must be one of [PINS], byte for byte: the Kotlin
 * bindings in `uniffi.rust_bridge` were generated from exactly these builds, and a different build
 * would fail its own checksum check on load anyway, only later and less clearly.
 */
object ImessageLibrary {

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
        // libheifdec.so for 32-bit ARM: not checked yet (see HEIF_PIN_NOTE).
    )

    /**
     * Until a libheifdec.so of a CPU type is pinned, an import of it is accepted when it is a
     * library for this CPU that exports libheif's decoder (what librust_bridge.so links against),
     * and its SHA-256 is shown so it can be pinned.
     */
    const val HEIF_PIN_NOTE = "libheifdec.so is not pinned yet for this phone"

    /** The CPU type this process runs as, in Android's ABI names. */
    fun processAbi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull() ?: Build.SUPPORTED_ABIS.first()
    }

    fun dir(context: Context): File = File(context.filesDir, "imessage/lib/" + processAbi())

    /** The libraries still missing for this CPU type (empty: ready to load). */
    fun missing(context: Context): List<String> = REQUIRED.filter { !File(dir(context), it).isFile }

    fun isInstalled(context: Context): Boolean = missing(context).isEmpty()

    /** What an imported file turned out to be. */
    sealed class ImportResult {
        data class Installed(val name: String, val pinned: Boolean, val sha256: String) : ImportResult()
        data class Refused(val reason: String) : ImportResult()
    }

    /**
     * Decides what to do with a file the user picked, from its SHA-256 and ELF header only.
     * Pure, so the rules are tested without a phone.
     */
    fun classify(sha256: String, elf: ElfInfo?, abi: String): ImportResult {
        val pin = PINS.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }
        if (pin != null) {
            return if (pin.abi == abi) {
                ImportResult.Installed(pin.name, pinned = true, sha256 = sha256)
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
            if (PINS.none { it.name == HEIF && it.abi == abi } && elf.exportsHeifDecoder) {
                return ImportResult.Installed(HEIF, pinned = false, sha256 = sha256)
            }
            return ImportResult.Refused("This libheifdec.so is not the one from OpenBubbles 2.0.0.")
        }
        if (elf.exportsRustBridge) {
            return ImportResult.Refused(
                "This librust_bridge.so is a different OpenBubbles build than 2.0.0, which is the one " +
                    "LightDecoder was built for. Use the file from OpenBubbles 2.0.0.",
            )
        }
        return ImportResult.Refused("This is not one of the OpenBubbles files LightDecoder needs ($BRIDGE, $HEIF).")
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
     */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val dir = dir(context)
        check(isInstalled(context)) { "The OpenBubbles libraries are not imported yet." }
        System.load(File(dir, HEIF).absolutePath)
        System.setProperty("uniffi.component.rust_bridge.libraryOverride", File(dir, BRIDGE).absolutePath)
        loaded = true
    }

    @Volatile
    var loaded = false
        private set
}
