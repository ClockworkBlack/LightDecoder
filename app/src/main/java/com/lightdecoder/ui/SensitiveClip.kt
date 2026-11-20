package com.lightdecoder.ui

import android.content.ClipData
import android.os.PersistableBundle

/**
 * Clipboard text that is someone's personal detail (a phone number, an email address): marked
 * sensitive, so Android 13+ does not show it in its copy preview and keyboards that read the flag
 * keep it out of their clipboard history and suggestions.
 */
object SensitiveClip {

    /** ClipDescription.EXTRA_IS_SENSITIVE (Android 13+); by its name, which some keyboards read on older Androids too. */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun of(label: CharSequence, text: CharSequence): ClipData = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
}
