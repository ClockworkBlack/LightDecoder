package com.lightdecoder.wrapper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.lightdecoder.R
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessageReplies
import com.lightdecoder.imessage.OpenBubbles

/**
 * The OpenBubbles wrapper, as LightDecoder uses it: the setting, and the
 * jobs [WrapperService] does. Experimental: off unless the user turns it on
 * and allows the accessibility service.
 */
object Wrapper {

    enum class Kind {
        REPLY, UNSEND, EDIT, REACT,

        /** Pictures, a voice message, stickers, or a text to a chat with no Reply button: sent from OpenBubbles' composer. */
        SEND,

        /** Only a look at the chat: what OpenBubbles says under the newest message (Delivered, Read). */
        STATUS,
    }

    /**
     * One thing to do in OpenBubbles: an inline reply with [text] to the message reading [targetText],
     * or Undo Send of it, in [chatGuid]; then back to [returnTo]. [localId] is LightDecoder's own row
     * for the reply, shown while it goes.
     */
    data class Job(
        val kind: Kind,
        val chatGuid: String,
        val targetText: String,
        val text: String,
        val returnTo: Intent,
        val localId: String? = null,
        /** For Undo Send: LightDecoder's row of the message, removed here once it is undone. */
        val targetId: String? = null,
        /** For [Kind.SEND]: OpenBubbles' composer for the chat with the files and text in it. */
        val open: Intent? = null,
    )

    data class Result(val ok: Boolean, val stoppedAt: String?, val ms: Long)

    private const val PREFS = "wrapper"
    private const val KEY_ON = "on"
    private const val KEY_INLINE = "inline_replies"

    /** Replies too (inline, through OpenBubbles' screen)? Off: replies go straight through OpenBubbles' Reply button. */
    fun inlineReplies(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INLINE, false)

    fun setInlineReplies(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_INLINE, on).apply()
        IMessageLog.add(context, "Wrapper: inline replies ${if (on) "on" else "off"}")
    }

    private const val KEY_RECEIPTS = "receipts"

    /** Read receipts: a quick look in OpenBubbles when a conversation opens (on by default with the wrapper). */
    fun receipts(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECEIPTS, true)

    fun setReceipts(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECEIPTS, on).apply()
    }

    /** Chats looked at for receipts, and when: at most every [RECEIPT_EVERY_MS] each. */
    private val lookedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RECEIPT_EVERY_MS = 90_000L

    /**
     * Looks in OpenBubbles for whether the user's newest iMessage in [chatGuid] was delivered or read, when it
     * is worth it (receipts on, one not yet read, not looked at lately). Silent: behind a still of the screen.
     */
    fun checkReceipts(context: Context, chatGuid: String, returnTo: Intent) {
        if (!available(context) || !receipts(context) || WrapperService.isBusy()) return
        val now = System.currentTimeMillis()
        if ((lookedAt[chatGuid] ?: 0L) > now - RECEIPT_EVERY_MS) return
        val newest = runCatching { IMessageManager.get(context).newestOwnIMessage(chatGuid) }.getOrNull() ?: return
        if (newest.status == IMessageManager.STATUS_READ || now - newest.timestampMs > RECEIPT_LOOK_BACK_MS) return
        lookedAt[chatGuid] = now
        start(context, Job(Kind.STATUS, chatGuid, "", "", returnTo))
    }

    private const val RECEIPT_LOOK_BACK_MS = 48 * 60 * 60 * 1000L

    /** What the wrapper read under the newest message of [chatGuid]. */
    internal fun receipt(context: Context, chatGuid: String, receipt: WrapperNodes.Receipt) {
        runCatching { IMessageManager.get(context.applicationContext).recordReceipt(chatGuid, receipt.name) }
    }

    /** Whether a reply to an iMessage goes inline through the wrapper. */
    fun repliesInline(context: Context): Boolean = available(context) && inlineReplies(context)

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        IMessageLog.add(context, "Wrapper: ${if (on) "turned on" else "turned off"}")
    }

    /** Whether the service is allowed and running now. */
    fun serviceOn(): Boolean = WrapperService.running != null || runner != null

    /** Stands in for the service in tests. */
    @Volatile
    internal var runner: ((Context, Job) -> Unit)? = null

    /** On, allowed, and OpenBubbles is there: inline replies and Undo Send go through it. */
    fun available(context: Context): Boolean = enabled(context) && serviceOn() && OpenBubbles.isInstalled(context)

    /** Android's accessibility settings, where the user allows the service. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts [job]; false if the service is not running. */
    fun start(context: Context, job: Job): Boolean {
        runner?.let {
            it(context, job)
            return true
        }
        val service = WrapperService.running ?: return false
        IMessageLog.add(context, "Wrapper ${job.kind.name.lowercase()} ${IMessageLog.chatRef(job.chatGuid)}: started")
        service.run(job)
        return true
    }

    /** OpenBubbles on [chatGuid]: its own notification's "open" intent, else its share screen for the chat. */
    internal fun openChat(context: Context, chatGuid: String): Boolean {
        if (IMessageReplies.open(context, chatGuid, quiet = true)) return true
        val pkg = OpenBubbles.installedPackage(context) ?: return false
        val intent = OpenBubbles.shareToChatIntent(pkg, chatGuid, "", emptyList()).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return try {
            context.startActivity(intent, android.app.ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** After a job: a reply shown as sent stays; one that did not go is taken back and left for the user in OpenBubbles. */
    internal fun finish(context: Context, job: Job, result: Result) {
        val app = context.applicationContext
        val local = job.localId
        // Shown here as done: OpenBubbles notifies none of these.
        if (result.ok && job.targetId != null) {
            runCatching {
                when (job.kind) {
                    Kind.EDIT -> IMessageManager.get(app).recordEdited(job.targetId, job.text)
                    Kind.REACT -> IMessageManager.get(app).recordOwnReaction(job.targetId, job.text)
                    Kind.UNSEND -> IMessageManager.get(app).recordUnsent(job.targetId)
                    else -> Unit
                }
            }
        }
        if (!result.ok) {
            if (local != null) runCatching { IMessageManager.get(app).forgetSentElsewhere(local) }
            if (job.kind == Kind.REPLY || job.kind == Kind.EDIT) {
                app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("reply", job.text))
            }
        }
        val words = when {
            result.ok -> null
            job.kind == Kind.REPLY -> app.getString(R.string.wrapper_failed_reply)
            job.kind == Kind.EDIT -> app.getString(R.string.wrapper_failed_edit)
            job.kind == Kind.REACT -> app.getString(R.string.wrapper_failed_react, job.text)
            job.kind == Kind.SEND -> app.getString(R.string.wrapper_failed_send)
            // A look for receipts that did not work says nothing.
            job.kind == Kind.STATUS -> null
            else -> app.getString(R.string.wrapper_failed_unsend)
        }
        if (words != null) Handler(Looper.getMainLooper()).post { Toast.makeText(app, words, Toast.LENGTH_LONG).show() }
        listener?.invoke(job, result)
    }

    /** Told when a job ends (tests, and the screen that started it). */
    @Volatile
    var listener: ((Job, Result) -> Unit)? = null
}
