package com.lightdecoder.messenger

import android.content.Context
import androidx.core.content.edit

/** The Messenger tab's few settings. */
object MessengerPrefs {
    private const val FILE = "messenger"
    private const val KEY_PAUSED = "paused"

    /** While paused, Messenger's notifications are not read at all. */
    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY_PAUSED, paused) }
}
