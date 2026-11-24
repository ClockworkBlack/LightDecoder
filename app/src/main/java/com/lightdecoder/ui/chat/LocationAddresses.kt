package com.lightdecoder.ui.chat

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/**
 * The address of a location someone sent, for "Show addresses for locations" (off by default:
 * Android's Geocoder sends the coordinates to its service). Only ever called with that setting on;
 * one line, [MAX_CHARS] at most; null when there is none. Never logs the coordinates.
 */
object LocationAddresses {
    const val MAX_CHARS = 120

    @Suppress("DEPRECATION") // The listener form is Android 13+; this runs off the main thread.
    fun lookUp(context: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ")
            }?.let(::oneLine)
        }
    } catch (e: Exception) {
        null
    }

    /** [text] as one short line: no control characters, [MAX_CHARS] at most. */
    fun oneLine(text: String): String? =
        text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(MAX_CHARS).ifBlank { null }
}
