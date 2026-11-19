package com.lightdecoder.imessage.direct

import android.content.Context
import java.io.File

/**
 * iMessage settings that both processes read. "Registered" is a file, not SharedPreferences:
 * SharedPreferences are cached per process, and the ":imessage" process sets it while the main
 * process reads it (to start the service at boot and on app start).
 */
object DirectPrefs {
    private const val FILE = "imessage"
    const val KEY_CODE = "relay_code"
    const val KEY_HOST = "relay_host"

    /** Only used by the setup screen (in the ":imessage" process). */
    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun marker(context: Context) = File(context.filesDir, "imessage/registered")

    private fun shownMarker(context: Context) = File(context.filesDir, "imessage/blue-bubbles-turned-on")

    /** Whether Blue Bubbles was already turned on for the library's chats (done once). */
    fun blueBubblesTurnedOn(context: Context): Boolean = shownMarker(context).isFile

    fun setBlueBubblesTurnedOn(context: Context) {
        shownMarker(context).apply { parentFile?.mkdirs() }.writeText("1")
    }

    /** Set once Apple reported a registration; the background service runs while it is. */
    fun registered(context: Context): Boolean = marker(context).isFile

    fun setRegistered(context: Context, value: Boolean) {
        val f = marker(context)
        if (value) {
            f.parentFile?.mkdirs()
            if (!f.isFile) f.writeText("1")
        } else {
            f.delete()
        }
    }
}
