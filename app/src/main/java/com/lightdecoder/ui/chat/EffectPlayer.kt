package com.lightdecoder.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.VisibleForTesting
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.direct.IMessageExtra
import java.util.WeakHashMap

/**
 * Apple's message effects as this app plays them, with its own simple drawing: a bubble effect
 * moves the message's bubble, a screen effect draws over the whole thread for a few seconds.
 * [screen] says which kind each is; the id is the one stored with the message ([IMessageExtra.effect]).
 */
internal enum class EffectKind(val id: String, val screen: Boolean, val durationMs: Long) {
    SLAM("com.apple.MobileSMS.expressivesend.impact", false, 600),
    LOUD("com.apple.MobileSMS.expressivesend.loud", false, 1_100),
    GENTLE("com.apple.MobileSMS.expressivesend.gentle", false, 1_600),
    INK(IMessageExtra.INVISIBLE_INK, false, 1_200),
    ECHO("com.apple.messages.effect.CKEchoEffect", true, 2_600),
    SPOTLIGHT("com.apple.messages.effect.CKSpotlightEffect", true, 2_600),
    BALLOONS("com.apple.messages.effect.CKHappyBirthdayEffect", true, 2_800),
    CONFETTI("com.apple.messages.effect.CKConfettiEffect", true, 2_600),
    LOVE("com.apple.messages.effect.CKHeartEffect", true, 2_400),
    LASERS("com.apple.messages.effect.CKLasersEffect", true, 2_600),
    FIREWORKS("com.apple.messages.effect.CKFireworksEffect", true, 2_800),
    CELEBRATION("com.apple.messages.effect.CKSparklesEffect", true, 2_600),
    ;

    companion object {
        /** The effect [id] names, or null for none or one this app does not know. */
        fun of(id: String?): EffectKind? = if (id.isNullOrEmpty()) null else values().firstOrNull { it.id == id }
    }
}

/**
 * The messages whose effect has played by itself (sent or seen arriving), newest last, at most
 * [cap] of them: the oldest are forgotten first. Pure. Tested in EffectPlaybackTest.
 */
internal class PlayedEffects(ids: Collection<String> = emptyList(), private val cap: Int = CAP) {
    private val ids = LinkedHashSet<String>()

    init {
        ids.forEach(::add)
    }

    operator fun contains(id: String): Boolean = id in ids

    /** Remembers [id] as played; false when it already was. */
    fun add(id: String): Boolean {
        if (id.isEmpty() || id.contains('\n')) return false
        if (!ids.add(id)) return false
        while (ids.size > cap) ids.remove(ids.first())
        return true
    }

    val size: Int get() = ids.size

    fun toList(): List<String> = ids.toList()

    /** As stored: one id per line. */
    fun encode(): String = ids.joinToString("\n")

    companion object {
        const val CAP = 500

        /** Reads what [encode] wrote. */
        fun decode(stored: String?): PlayedEffects = PlayedEffects(stored.orEmpty().split('\n').filter { it.isNotEmpty() })
    }
}

/** [PlayedEffects] kept in a small preferences file, so a reopened thread does not play them again. */
internal class EffectPlays(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val played = PlayedEffects.decode(prefs.getString(KEY, null))

    operator fun contains(id: String): Boolean = id in played

    fun add(id: String) {
        if (played.add(id)) prefs.edit().putString(KEY, played.encode()).apply()
    }

    companion object {
        private const val PREFS = "effect_plays"
        private const val KEY = "played"
    }
}

/**
 * When an effect plays by itself in an open thread, as on an iPhone: the newest incoming message
 * with an effect when the thread opens (unread then, or just arrived), and one that arrives while it
 * is open. Each at most once ([played]). The user's own message plays when sent ([sentRow]), never
 * on a reload. Pure. Tested in EffectPlaybackTest.
 */
internal class EffectWatch {
    private var opened = false
    private var newestMs = Long.MIN_VALUE
    private val seen = HashSet<String>()

    /** A new conversation (or the same one opened afresh). */
    fun reset() {
        opened = false
        newestMs = Long.MIN_VALUE
        seen.clear()
    }

    /**
     * The incoming message among [rows] (a load of the thread, oldest first) whose effect should
     * play now: at the first load the newest incoming one with an effect, if nothing of the user's
     * came after it and the thread was unread ([unreadAtOpen]) or it is from the last [FRESH_MS];
     * later, the newest one with an effect not in an earlier load and not older than the newest
     * message then. Null for none or one already [played].
     */
    fun arrived(rows: List<MessageRow>, unreadAtOpen: Boolean, nowMs: Long, played: (String) -> Boolean): MessageRow? {
        val first = !opened
        val since = newestMs
        val before = HashSet(seen)
        opened = true
        rows.forEach { seen += it.id }
        rows.maxOfOrNull { it.timestampMs }?.let { if (it > newestMs) newestMs = it }
        if (first) {
            val lastOwn = rows.filter(::isOwn).maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE
            val newest = rows.filter { isIncoming(it) && effectOf(it) != null }.maxByOrNull { it.timestampMs } ?: return null
            if (newest.timestampMs < lastOwn) return null
            if (!unreadAtOpen && newest.timestampMs < nowMs - FRESH_MS) return null
            return newest.takeUnless { played(it.id) }
        }
        return rows.filter {
            isIncoming(it) && it.id !in before && it.timestampMs >= since - CLOCK_SLACK_MS && effectOf(it) != null && !played(it.id)
        }.maxByOrNull { it.timestampMs }
    }

    companion object {
        /** On opening a thread, an effect this recent plays though the thread was read already. */
        const val FRESH_MS = 60 * 60_000L

        /** How far behind the newest message an arrival may be stamped (the sender's clock) and still be new. */
        const val CLOCK_SLACK_MS = 2 * 60_000L

        fun isOwn(row: MessageRow): Boolean = row.isOutgoing || row.direction == "out"

        fun isIncoming(row: MessageRow): Boolean = !isOwn(row) && row.direction != "system"

        /** The id of the effect [row] was sent with, when this app can play it; else null. */
        fun effectOf(row: MessageRow): String? {
            if (row.extra.isEmpty() || !row.extra.contains("effect")) return null
            return IMessageExtra.parse(row.extra).effect?.takeIf { EffectKind.of(it) != null }
        }

        /** The user's own message just sent with [effect] (stamped from [sinceMs] on), newest first; null for none yet. */
        fun sentRow(rows: List<MessageRow>, effect: String, sinceMs: Long, played: (String) -> Boolean): MessageRow? =
            rows.filter { isOwn(it) && it.timestampMs >= sinceMs && effectOf(it) == effect && !played(it.id) }.maxByOrNull { it.timestampMs }
    }
}

/**
 * Plays message effects in [activity]: a bubble effect on the message's bubble, a screen effect in
 * an [EffectOverlayView] over the activity's content, gone when done. Nothing moves when the system
 * says to remove animations ([animationsOff]); every play is kept in [plays] all the same.
 */
internal class EffectPlayer(
    private val activity: Activity,
    private val list: View,
    private val animationsOff: () -> Boolean = { animationsOff(activity) },
) {
    enum class Reason { SENT, RECEIVED, REPLAY }

    /** One effect played: on [messageId]'s bubble or over the screen; [animated] false when animations are off. */
    data class Play(val messageId: String, val effect: String, val screen: Boolean, val reason: Reason, val animated: Boolean)

    /** Every play, oldest first (for tests and logs). */
    val plays: List<Play> get() = played
    private val played = ArrayList<Play>()

    /** Told of each play as it starts. */
    @VisibleForTesting
    var onPlay: ((Play) -> Unit)? = null

    /** How many screen effects (and invisible ink shimmers) were drawn over the screen. */
    @VisibleForTesting
    var overlaysShown = 0
        private set

    private var overlay: EffectOverlayView? = null
    private val running = ArrayList<Animator>()

    /**
     * Plays [effect] for message [messageId], whose bubble is [bubble] (null: not on screen; a
     * screen effect then starts from the middle) and whose text is [text] (Echo flies it). Null when
     * this app does not know the effect.
     */
    fun play(messageId: String, effect: String, reason: Reason, bubble: View?, text: String): Play? {
        val kind = EffectKind.of(effect) ?: return null
        val animated = !animationsOff()
        val play = Play(messageId, effect, kind.screen, reason, animated)
        played += play
        onPlay?.invoke(play)
        if (!animated) return play
        when (kind) {
            EffectKind.SLAM -> bubble?.let(::slam)
            EffectKind.LOUD -> bubble?.let(::loud)
            EffectKind.GENTLE -> bubble?.let(::gentle)
            else -> showOverlay(kind, bubble, text)
        }
        return play
    }

    /**
     * Plays the text effects (iOS 18's Big, Shake, ...) of message [messageId] in its bubble's text
     * [view]: recorded as a play of [TEXT], nothing moving with animations off.
     */
    fun playText(messageId: String, view: TextEffectView, reason: Reason): Play {
        val animated = !animationsOff()
        val play = Play(messageId, TEXT, false, reason, animated)
        played += play
        onPlay?.invoke(play)
        if (!animated) return play
        view.play()?.let { animator ->
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    running.remove(animation)
                }
            })
            running += animator
        }
        return play
    }

    /** Stops whatever plays and puts everything back (leaving the thread). */
    fun cancel() {
        running.toList().forEach { it.cancel() }
        running.clear()
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        list.translationY = 0f
    }

    // --- bubble effects ------------------------------------------------------------------

    /** Drops in large from above and slams down; the thread shakes. */
    private fun slam(bubble: View) {
        val px = bubble.resources.displayMetrics.density
        val drop = ObjectAnimator.ofPropertyValuesHolder(
            bubble,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 2.4f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 2.4f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -bubble.height * 1.6f - 40 * px, 0f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
        ).apply {
            duration = 260
            interpolator = AccelerateInterpolator(1.6f)
        }
        val shake = ObjectAnimator.ofFloat(list, View.TRANSLATION_Y, 0f, 10 * px, -6 * px, 4 * px, -2 * px, 0f).apply { duration = 320 }
        runOnBubble(bubble, AnimatorSet().apply { playSequentially(drop, shake) })
    }

    /** Grows big and shakes, then settles. */
    private fun loud(bubble: View) {
        val grow = ObjectAnimator.ofPropertyValuesHolder(
            bubble,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.9f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.9f),
        ).apply {
            duration = 220
            interpolator = OvershootInterpolator()
        }
        val shake = ObjectAnimator.ofFloat(bubble, View.ROTATION, 0f, -5f, 5f, -5f, 5f, -3f, 3f, 0f).apply { duration = 560 }
        val settle = ObjectAnimator.ofPropertyValuesHolder(
            bubble,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.9f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.9f, 1f),
        ).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
        }
        runOnBubble(bubble, AnimatorSet().apply { playSequentially(grow, shake, settle) })
    }

    /** Starts small and slowly grows to its size. */
    private fun gentle(bubble: View) {
        val grow = ObjectAnimator.ofPropertyValuesHolder(
            bubble,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.35f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.35f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 1f),
        ).apply {
            duration = EffectKind.GENTLE.durationMs
            interpolator = DecelerateInterpolator(2f)
        }
        runOnBubble(bubble, grow)
    }

    /**
     * Runs [animator] on [bubble]: drawn above its neighbours and not cut off by its row while it
     * plays, and put back as it was when it ends (or its row is reused, [settle]).
     */
    private fun runOnBubble(bubble: View, animator: Animator) {
        settle(bubble)
        val unclipped = ArrayList<ViewGroup>()
        var parent = bubble.parent
        while (parent is ViewGroup && parent !== list) {
            if (parent.clipChildren) {
                parent.clipChildren = false
                unclipped += parent
            }
            parent = parent.parent
        }
        val row = unclipped.lastOrNull() ?: bubble
        bubble.pivotX = bubble.width / 2f
        bubble.pivotY = bubble.height / 2f
        row.translationZ = 8 * bubble.resources.displayMetrics.density
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                running.remove(animation)
                bubbles.remove(bubble)
                unclipped.forEach { it.clipChildren = true }
                row.translationZ = 0f
                reset(bubble)
                list.translationY = 0f
            }
        })
        running += animator
        bubbles[bubble] = animator
        animator.start()
    }

    // --- screen effects ------------------------------------------------------------------

    private fun showOverlay(kind: EffectKind, bubble: View?, text: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        overlay?.let { old ->
            old.stop()
            root.removeView(old)
        }
        val view = EffectOverlayView(activity)
        overlaysShown++
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = view
        val anchor = bubble?.takeIf { it.isAttachedToWindow }?.let { b ->
            val at = IntArray(2)
            val origin = IntArray(2)
            b.getLocationInWindow(at)
            root.getLocationInWindow(origin)
            val left = (at[0] - origin[0]).toFloat()
            val top = (at[1] - origin[1]).toFloat()
            RectF(left, top, left + b.width, top + b.height)
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = kind.durationMs }
        animator.addUpdateListener { view.progress = it.animatedValue as Float }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                running.remove(animation)
                (view.parent as? ViewGroup)?.removeView(view)
                if (overlay === view) overlay = null
            }
        })
        view.start(kind, anchor, text, animator)
        running += animator
        animator.start()
    }

    companion object {
        /** What a play of a message's text effects is recorded as ([Play.effect]). */
        const val TEXT = "text"

        /**
         * The key a message's text effects are remembered by once played by themselves: the message's
         * own, or its local copy's (the sent message and the copy shown while it went are one).
         */
        fun textKey(row: MessageRow): String = (if (row.tmpId.isNotEmpty()) MessageRow.tmpMessageId(row.tmpId) else row.id) + "#text"

        /** The animations running on bubbles, so that a row reused meanwhile is put back ([settle]). */
        private val bubbles = WeakHashMap<View, Animator>()

        /** Whether the system's "Remove animations" (animator duration scale 0) is on. */
        fun animationsOff(context: Context): Boolean =
            runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f

        /** Whether [effect] plays over the whole screen (true), on the bubble (false), or not at all (null: unknown). */
        fun isScreen(effect: String?): Boolean? = EffectKind.of(effect)?.screen

        /** Stops an effect playing on [bubble] (its row is about to show another message) and puts it back as it was. */
        fun settle(bubble: View) {
            bubbles.remove(bubble)?.cancel()
            reset(bubble)
        }

        private fun reset(bubble: View) {
            bubble.scaleX = 1f
            bubble.scaleY = 1f
            bubble.translationY = 0f
            bubble.rotation = 0f
            bubble.alpha = 1f
        }
    }
}
