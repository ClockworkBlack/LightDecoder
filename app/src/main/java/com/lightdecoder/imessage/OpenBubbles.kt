package com.lightdecoder.imessage

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.lightdecoder.BuildConfig

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
 *
 * OpenBubbles is known by its signing certificate, never by its package name alone, which any app
 * can take ([isTrusted]): only then are its notifications read, its buttons pressed (and only those
 * it made itself, [madeBy]), files handed to it, or its saved pictures taken. The Play Store's
 * build is pinned ([PLAY_CERT_SHA256]); its test builds' certificates are not known, so they are
 * taken by name in debug builds of LightDecoder only (the on-device test's stand-in is one).
 */
object OpenBubbles {
    /** The Play Store app. */
    const val PLAY_PACKAGE = "com.openbubbles.messaging"

    /**
     * The SHA-256 of the certificate Google Play signs [PLAY_PACKAGE] with (Play App Signing,
     * "CN=Android, OU=Android, O=Google Inc."), read with `apksigner verify --print-certs` from
     * OpenBubbles 2.0.0 (versionCode 20002293) from the Play Store. A copy built or signed by anyone
     * else is not used.
     */
    const val PLAY_CERT_SHA256 = "54:33:6B:2F:36:F9:ED:C8:B9:02:74:15:F7:5A:D0:7B:06:05:4D:9B:4A:C9:FE:61:67:53:2C:12:86:90:1D:A2"

    /** OpenBubbles' (BlueBubbles') test builds: no certificate known for them, so debug builds only. */
    val TEST_PACKAGES = listOf("com.bluebubbles.messaging.alpha", "com.bluebubbles.messaging.beta")

    /** The Play Store app, then (in a debug build of LightDecoder only) its test builds. */
    val PACKAGES: List<String> = listOf(PLAY_PACKAGE) + if (BuildConfig.DEBUG) TEST_PACKAGES else emptyList()

    /** Where the app is offered (the Play Store). */
    const val STORE_URL = "https://play.google.com/store/apps/details?id=com.openbubbles.messaging"

    /** The tag of OpenBubbles' message notifications (its group summary has the same tag, id 0). */
    const val MESSAGE_TAG = "com.bluebubbles.messaging.NEW_MESSAGE_NOTIFICATION"

    /** Extras its message notifications carry. */
    const val EXTRA_CHAT_GUID = "chatGuid"
    const val EXTRA_MESSAGE_GUID = "messageGuid"

    /** By name only: a first look (which notifications to read further). What is trusted is [isTrusted]. */
    fun isOpenBubbles(packageName: String?): Boolean = packageName != null && packageName in PACKAGES

    /** What is known of an app with one of OpenBubbles' package names. */
    enum class Trust {
        /** Installed, and OpenBubbles (signed as pinned; a test build: by name, in a debug build). */
        VERIFIED,

        /** Installed under OpenBubbles' name, but not signed as OpenBubbles is: never used. */
        UNVERIFIED,
        NOT_INSTALLED,
    }

    /** The certificate [pkg] must be signed with; null for a test build ([TEST_PACKAGES], debug builds only). */
    private fun pinOf(pkg: String): String? = if (pkg == PLAY_PACKAGE) PLAY_CERT_SHA256 else null

    private val checked = AppSigning.Cache()

    /** How a package's certificate is checked ([AppSigning.isSignedWith]); tests replace it (Robolectric fakes no signing certificate). */
    @Volatile
    internal var verifier: (PackageManager, String, String) -> Boolean = AppSigning::isSignedWith

    /**
     * Whether [pkg] is OpenBubbles here: installed and signed with its pinned certificate. The check
     * holds until the app is updated (or replaced). Needs the manifest's <queries> entry.
     */
    fun trust(context: Context, pkg: String): Trust {
        if (pkg !in PACKAGES) return Trust.UNVERIFIED
        val pm = context.packageManager
        val updated = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            return Trust.NOT_INSTALLED
        }
        val pin = pinOf(pkg) ?: return Trust.VERIFIED
        checked.get(pkg, updated)?.let { return if (it) Trust.VERIFIED else Trust.UNVERIFIED }
        val ok = try {
            verifier(pm, pkg, pin)
        } catch (e: PackageManager.NameNotFoundException) {
            return Trust.NOT_INSTALLED
        } catch (e: RuntimeException) {
            false
        }
        checked.put(pkg, updated, ok)
        return if (ok) Trust.VERIFIED else Trust.UNVERIFIED
    }

    /** Whether [packageName] is OpenBubbles, installed here and signed as it should be ([trust]). */
    fun isTrusted(context: Context, packageName: String?): Boolean =
        packageName != null && trust(context, packageName) == Trust.VERIFIED

    /**
     * Whether [intent] (one of a notification's buttons, or its tap) was made by [packageName] itself,
     * the trusted OpenBubbles that posted it: a notification can carry another app's PendingIntent,
     * and pressing one sends the user's text, or lends the right to open a screen, to its maker.
     */
    fun madeBy(intent: PendingIntent?, packageName: String?): Boolean =
        intent != null && packageName != null && intent.creatorPackage == packageName

    /** The OpenBubbles installed here and verified ([trust]), or null. Needs the manifest's <queries> entry. */
    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { trust(context, it) == Trust.VERIFIED }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /** An app under OpenBubbles' name that is not signed as OpenBubbles (a copy built by someone else), if one is installed and no real one is. */
    fun impostor(context: Context): String? =
        if (installedPackage(context) != null) null else PACKAGES.firstOrNull { trust(context, it) == Trust.UNVERIFIED }

    /** Tests: forget the checks made, and check certificates for real again. */
    internal fun resetForTesting() {
        checked.clear()
        verifier = AppSigning::isSignedWith
    }

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
     * [packageName] must be a verified OpenBubbles ([installedPackage]): the read grant goes to it.
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

    /** How [openNewChat] got OpenBubbles to a new chat. */
    enum class NewChat {
        /** A new chat with the person, the text filled in. */
        CHAT_OPENED,

        /** Its share screen with the text: the user picks or starts the chat there. */
        SHARE_OPENED,

        /** Only its main screen (the number is on the clipboard for the user). */
        APP_OPENED,
        NOT_INSTALLED,
        FAILED,
    }

    /**
     * Opens OpenBubbles on a new chat with [address] and [body], trying each
     * way it may accept one: imessage:, smsto: and sms: links (which ones its
     * version takes is not documented), then its share screen, then its main
     * screen. Each attempt is in the iMessage log.
     */
    fun openNewChat(context: Context, address: String, body: String): NewChat {
        val pkg = installedPackage(context) ?: return NewChat.NOT_INSTALLED.also {
            val fake = impostor(context)
            IMessageLog.add(
                context,
                if (fake != null) "New chat: the OpenBubbles installed ($fake) is not signed as OpenBubbles from the Play Store, so it is not used"
                else "New chat: OpenBubbles is not installed (looked for ${PACKAGES.joinToString()})",
            )
        }
        val to = address.trim()
        val query = if (body.isEmpty()) "" else "?body=" + Uri.encode(body)
        val links = listOf(
            "imessage" to Intent(Intent.ACTION_VIEW, Uri.parse("imessage://$to$query")),
            "smsto" to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to))),
            "sms" to Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + Uri.encode(to) + query)),
        )
        for ((name, intent) in links) {
            if (body.isNotEmpty()) intent.putExtra("sms_body", body).putExtra(Intent.EXTRA_TEXT, body)
            intent.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, intent, "New chat by $name: link")) return NewChat.CHAT_OPENED
        }
        if (body.isNotEmpty()) {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, share, "New chat by share screen")) return NewChat.SHARE_OPENED
        }
        val main = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (main != null && tryStart(context, main, "New chat: main screen")) return NewChat.APP_OPENED
        return NewChat.FAILED
    }

    private fun tryStart(context: Context, intent: Intent, what: String): Boolean {
        val ok = start(context, intent)
        IMessageLog.add(context, "$what ${if (ok) "opened" else "not taken"} (${intent.`package`})")
        return ok
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
