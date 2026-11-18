package com.lightdecoder.imessage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The OpenBubbles app (open source; iMessage on Android with the help of an
 * Apple device), which does the iMessage part of "Blue Bubbles". LightDecoder
 * never talks to Apple: what it knows of an iMessage is what OpenBubbles'
 * notification showed, and what it sends goes through OpenBubbles.
 *
 * What is used of OpenBubbles is only what it offers every app and the
 * system: its notifications (read by the notification listener, answered
 * through their Reply and Mark As Read buttons) and the screens it opens
 * for others (a new chat with text filled in, a chat with something to
 * share). It has no other way in; none is needed.
 */
object OpenBubbles {
    /** The Play Store app, then its test builds. */
    val PACKAGES = listOf("com.openbubbles.messaging", "com.bluebubbles.messaging.alpha", "com.bluebubbles.messaging.beta")

    /** Where the app is offered (the Play Store). */
    const val STORE_URL = "https://play.google.com/store/apps/details?id=com.openbubbles.messaging"

    /** The tag of OpenBubbles' message notifications (its group summary has the same tag, id 0). */
    const val MESSAGE_TAG = "com.bluebubbles.messaging.NEW_MESSAGE_NOTIFICATION"

    /** Extras its message notifications carry. */
    const val EXTRA_CHAT_GUID = "chatGuid"
    const val EXTRA_MESSAGE_GUID = "messageGuid"

    fun isOpenBubbles(packageName: String?): Boolean = packageName != null && packageName in PACKAGES

    /** The OpenBubbles installed here, or null. Needs the manifest's <queries> entry. */
    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * OpenBubbles' new-chat screen with [address] (a phone number or an email
     * address) and [body] filled in. The user taps send there.
     */
    fun newChatIntent(packageName: String, address: String, body: String): Intent {
        val text = if (body.isEmpty()) "" else "?body=" + Uri.encode(body)
        return Intent(Intent.ACTION_VIEW, Uri.parse("imessage://" + address.trim() + text))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Opens the chat [chatGuid] in OpenBubbles with [text] and the files in
     * [streams] (content uris of this app's, with their types, readable by
     * OpenBubbles while it needs them) ready to send. The user taps send there.
     */
    fun shareToChatIntent(packageName: String, chatGuid: String, text: String, streams: List<Pair<Uri, String>>): Intent {
        val intent = when {
            streams.isEmpty() -> Intent(Intent.ACTION_SEND).setType("text/plain")
            streams.size == 1 -> Intent(Intent.ACTION_SEND)
                .setType(streams[0].second.ifBlank { "application/octet-stream" })
                .putExtra(Intent.EXTRA_STREAM, streams[0].first)
            else -> Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(if (streams.all { it.second.startsWith("image/") }) "image/*" else "*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams.map { it.first }))
        }
        // Android moves the streams into the intent's ClipData when it starts it, and the grant goes with them.
        if (streams.isNotEmpty()) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text)
        return intent
            .setPackage(packageName)
            .putExtra(Intent.EXTRA_SHORTCUT_ID, chatGuid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** False if OpenBubbles is not installed or would not take [intent]. */
    fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** OpenBubbles' main screen. */
    fun launch(context: Context): Boolean {
        val pkg = installedPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return start(context, intent)
    }

    /** OpenBubbles' page in the Play Store. */
    fun storeIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
