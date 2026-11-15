package com.lightdecoder.inbox

import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** The user's choices for the sticker inbox, plus whether Android has granted access. */
object InboxPrefs {
    private const val FILE = "inbox"
    private const val KEY_PAUSED = "paused"
    private const val KEY_ALL_IMAGES = "all_images"
    private const val KEY_ALERTS = "alerts"

    /** Messaging apps whose notifications are looked at. Everything else is ignored. */
    val MESSAGING_PACKAGES = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The user can pause catching without revoking notification access. */
    fun isPaused(context: Context) = prefs(context).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) =
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()

    /**
     * Off (the default): only HEIC/HEIF attachments are kept, the ones Messages
     * cannot show. On: every picture attachment is kept.
     */
    fun catchAllImages(context: Context) = prefs(context).getBoolean(KEY_ALL_IMAGES, false)

    fun setCatchAllImages(context: Context, all: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALL_IMAGES, all).apply()

    /** Whether a caught sticker is also shown as a notification of its own. On by default. */
    fun alertsEnabled(context: Context) = prefs(context).getBoolean(KEY_ALERTS, true)

    fun setAlertsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALERTS, enabled).apply()

    /** True once the user has turned on notification access for LightDecoder in system settings. */
    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun listenerComponent(context: Context) =
        ComponentName(context, StickerNotificationListener::class.java)
}
