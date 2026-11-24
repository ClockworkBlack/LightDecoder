package com.lightdecoder.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * This phone's location, once, for "Send My Current Location" (SPEC2 B4.4): only while the user
 * waits for it on screen, only with the permission they gave for it, never kept and never logged.
 * A fix from the fused provider (Android 12+) or else GPS or the network, [TIMEOUT_MS] at most; a
 * last known one [FRESH_MS] old at most stands in when no fix comes.
 */
object CurrentLocation {
    const val TIMEOUT_MS = 10_000L
    const val FRESH_MS = 2 * 60_000L

    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Whether the user let the app have a location (coarse is enough). */
    fun permitted(context: Context): Boolean = PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** The provider asked for a fix (pure): fused on Android 12+ where there is one, else GPS, else the network; null with none on. */
    fun providerFor(sdk: Int, enabled: Set<String>, all: Set<String>): String? = when {
        sdk >= 31 && LocationManager.FUSED_PROVIDER in all && LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
        LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
        LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
        else -> null
    }

    /** Whether a fix taken at [fixMs] (wall clock) is recent enough to send at [nowMs]: [FRESH_MS] at most, never from the future. */
    fun fresh(fixMs: Long, nowMs: Long): Boolean = fixMs in (nowMs - FRESH_MS)..nowMs

    /** The coordinates now, or null (no permission, no provider on, nothing in time). */
    @SuppressLint("MissingPermission") // checked with permitted()
    suspend fun get(context: Context): Pair<Double, Double>? {
        if (!permitted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = try {
            manager.getProviders(true).toSet()
        } catch (e: RuntimeException) {
            emptySet()
        }
        val all = try {
            manager.allProviders.toSet()
        } catch (e: RuntimeException) {
            emptySet()
        }
        val provider = providerFor(Build.VERSION.SDK_INT, enabled, all) ?: return null
        val fix = withTimeoutOrNull(TIMEOUT_MS) { current(context, manager, provider) }
        val location = fix ?: lastKnown(manager, enabled)
        return location?.let { it.latitude to it.longitude }?.takeIf { (la, lo) -> la.isFinite() && lo.isFinite() && la in -90.0..90.0 && lo in -180.0..180.0 }
    }

    /** One fix on every Android version (a single update request before Android 11), dropped when the wait ends. */
    @SuppressLint("MissingPermission")
    private suspend fun current(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { runCatching { signal.cancel() } }
            try {
                androidx.core.location.LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context),
                ) { location -> if (cont.isActive) cont.resume(location) }
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(manager: LocationManager, enabled: Set<String>): Location? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        enabled.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .filter { fresh(it.time, now) }
            .maxByOrNull { it.time }
    }
}
