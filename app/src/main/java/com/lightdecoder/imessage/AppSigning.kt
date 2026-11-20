package com.lightdecoder.imessage

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Whether an installed app is signed with a pinned certificate: what LightDecoder trusts the other
 * apps it works with by (OpenBubbles, [OpenBubbles]; OpenPigeon,
 * [com.lightdecoder.imessage.madrid.MadridApps]), never their package name alone, which any app
 * can take. A pin is the SHA-256 of the signing certificate, as "AA:BB:…" (what `apksigner verify
 * --print-certs` prints, with colons).
 */
object AppSigning {

    /** "AA:BB:…" as bytes; null when it is not colon-separated hex. */
    fun hexToBytes(hex: String): ByteArray? {
        val parts = hex.split(':')
        if (parts.any { it.length != 2 }) return null
        return try {
            ByteArray(parts.size) { parts[it].toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** The SHA-256 of [cert] (a certificate's encoded bytes), as "AA:BB:…". */
    fun fingerprint(cert: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** Whether one of [certs] (certificates' encoded bytes) is the one [pin] names. */
    fun matches(pin: String, certs: List<ByteArray>): Boolean = certs.any { fingerprint(it).equals(pin, ignoreCase = true) }

    /**
     * Whether [pkg] is signed with the certificate [pin] names: on Android 9+ by the package
     * manager's own check (which follows a rotated key's lineage), before that by the certificates
     * it lists (Android 8: GET_SIGNATURES, each one's SHA-256). Before Android 9 it throws
     * [PackageManager.NameNotFoundException] when [pkg] is not installed; callers look it up first.
     */
    fun isSignedWith(pm: PackageManager, pkg: String, pin: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bytes = hexToBytes(pin)?.takeIf { it.size == 32 } ?: return false
            return pm.hasSigningCertificate(pkg, bytes, PackageManager.CERT_INPUT_SHA256)
        }
        @Suppress("DEPRECATION")
        val signatures = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        return matches(pin, signatures.map { it.toByteArray() })
    }

    /**
     * Checks made, by package: the app's last update time when it was checked, and the answer.
     * A check holds until the app is updated (or reinstalled, which also changes the time).
     */
    class Cache {
        private val checked = HashMap<String, Pair<Long, Boolean>>()

        /** The answer for [pkg] last updated at [updated], or null when it was not checked since. */
        fun get(pkg: String, updated: Long): Boolean? = synchronized(checked) { checked[pkg]?.takeIf { it.first == updated }?.second }

        fun put(pkg: String, updated: Long, ok: Boolean) {
            synchronized(checked) { checked[pkg] = updated to ok }
        }

        fun clear() = synchronized(checked) { checked.clear() }
    }
}
