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

    enum class Kind { REPLY, UNSEND, EDIT, REACT }

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
    )

    data class Result(val ok: Boolean, val stoppedAt: String?, val ms: Long)

    private const val PREFS = "wrapper"
    private const val KEY_ON = "on"

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
        if (IMessageReplies.open(context, chatGuid)) return true
        val pkg = OpenBubbles.installedPackage(context) ?: return false
        return OpenBubbles.start(context, OpenBubbles.shareToChatIntent(pkg, chatGuid, "", emptyList()))
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
                    else -> Unit
                }
            }
        }
        if (result.ok && job.kind == Kind.UNSEND && job.targetId != null) {
            runCatching {
                com.lightdecoder.imessage.IMessagePrefs.addDeletedMessage(app, job.targetId)
                IMessageManager.get(app).forgetSentElsewhere(job.targetId)
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
            else -> app.getString(R.string.wrapper_failed_unsend)
        }
        if (words != null) Handler(Looper.getMainLooper()).post { Toast.makeText(app, words, Toast.LENGTH_LONG).show() }
        listener?.invoke(job, result)
    }

    /** Told when a job ends (tests, and the screen that started it). */
    @Volatile
    var listener: ((Job, Result) -> Unit)? = null
}
