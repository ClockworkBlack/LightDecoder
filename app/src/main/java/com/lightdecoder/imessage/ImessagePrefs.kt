package com.lightdecoder.imessage

import android.content.Context

/** iMessage settings that must be known without loading the library (at boot, on app start). */
object ImessagePrefs {
    private const val FILE = "imessage"
    private const val KEY_REGISTERED = "registered"
    const val KEY_CODE = "relay_code"
    const val KEY_HOST = "relay_host"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Set once Apple reported a registration; the background service runs while it is. */
    fun registered(context: Context): Boolean = prefs(context).getBoolean(KEY_REGISTERED, false)

    fun setRegistered(context: Context, value: Boolean) {
        if (registered(context) != value) prefs(context).edit().putBoolean(KEY_REGISTERED, value).apply()
    }
}
