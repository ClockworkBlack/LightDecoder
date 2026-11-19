package com.lightdecoder.ui.chat

import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.lightdecoder.R
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.databinding.DialogReplyThreadBinding

/**
 * The thread view of iPhone-style chats, opened by a tap on a reply, on a message others answered
 * or on "N Replies" (as an iPhone opens it from "N Replies" or a reply's faded copy): over the whole chat, which is blurred (Android 12 and later) and veiled behind it,
 * the first message of the thread ([rootId]) and every reply in it, drawn as in the chat and
 * joined by a line, with a field at the bottom whose message goes into the thread ([send]). A tap
 * beside the messages, or Back, closes it. [update] shows it as the chat now is (new replies
 * arrive in it while it is open).
 *
 * The bubbles are the chat's own ([MessagesAdapter], set up by [configure]); a long press opens
 * the same actions (tapbacks, effects, ...) as in the chat ([callbacks]).
 */
class ReplyThreadPanel(
    private val activity: AppCompatActivity,
    /** What is behind it: blurred while it is open. */
    private val behind: View,
    media: MediaLoader,
    callbacks: MessagesAdapter.Callbacks,
    val rootId: String,
    configure: (MessagesAdapter) -> Unit,
    /** Sends [text] into the thread; true when it went (the field is then emptied). */
    private val send: (String) -> Boolean,
    private val onClosed: (ReplyThreadPanel) -> Unit,
) {
    val binding: DialogReplyThreadBinding = DialogReplyThreadBinding.inflate(activity.layoutInflater)
    val dialog = AppCompatDialog(activity, R.style.Theme_LightDecoder_ThreadPanel)

    // In the thread view, "Reply" (TalkBack's action on a bubble) is the field at the bottom.
    private val panelCallbacks = object : MessagesAdapter.Callbacks by callbacks {
        override fun onReply(row: MessageRow) {
            binding.threadInput.requestFocus()
        }

        override fun onOpenThread(rootId: String) = Unit
    }

    val adapter = MessagesAdapter(media, panelCallbacks).also {
        configure(it)
        it.phoneStyle = true
        // Already in the thread: a tap on a bubble opens nothing.
        it.threadTaps = false
    }
    private val layoutManager = LinearLayoutManager(activity).apply { stackFromEnd = true }

    /** The messages shown, by id: a new one scrolls the list down to it. */
    private var shownIds: Set<String> = emptySet()

    init {
        val b = binding
        dialog.setContentView(b.root)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            unblur()
            onClosed(this)
        }
        dialog.window?.let { window ->
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        ViewCompat.setAccessibilityPaneTitle(b.root, activity.getString(R.string.imessage_thread_title))
        ViewCompat.setOnApplyWindowInsetsListener(b.threadContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        // A veil over the chat: lighter where the chat is blurred under it.
        val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        b.threadScrim.setBackgroundColor(ContextCompat.getColor(activity, if (blurs) R.color.phone_thread_scrim else R.color.phone_thread_scrim_solid))
        b.threadScrim.setOnClickListener { dismiss() }

        b.threadMessages.layoutManager = layoutManager
        b.threadMessages.adapter = adapter
        (b.threadMessages.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        b.threadMessages.addItemDecoration(ReplyLines(activity))
        // A tap beside the bubbles (on the list, not on a message) closes it, as on an iPhone.
        val taps = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean = true
        })
        b.threadMessages.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (taps.onTouchEvent(e) && rv.findChildViewUnder(e.x, e.y)?.let { onBubbleArea(it, e) } != true) {
                    dismiss()
                    return true
                }
                return false
            }
        })

        // The field: an iPhone's pill, "Reply" in it, and the round blue arrow once something is typed.
        val density = activity.resources.displayMetrics.density
        b.threadInput.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = FIELD_RADIUS_DP * density
            setColor(ContextCompat.getColor(activity, R.color.phone_screen))
            setStroke(maxOf(1, density.toInt()), ContextCompat.getColor(activity, R.color.phone_field_stroke))
        }
        b.threadInput.setTextColor(ContextCompat.getColor(activity, R.color.phone_on_incoming))
        b.threadInput.setHintTextColor(ContextCompat.getColor(activity, R.color.phone_meta))
        b.threadSend.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.phone_blue))
        b.threadInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderSend()
        })
        b.threadSend.setOnClickListener {
            val text = b.threadInput.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && send(text)) b.threadInput.setText("")
        }
        renderSend()
    }

    /** Whether a tap at [e] on the row [row] is on its bubble (or what is drawn with it), not beside it. */
    private fun onBubbleArea(row: View, e: MotionEvent): Boolean {
        val column = row.findViewById<View>(R.id.column) ?: return true
        val x = e.x - row.left - row.translationX
        val y = e.y - row.top - row.translationY
        return x >= column.left && x <= column.right && y >= column.top && y <= column.bottom
    }

    private fun renderSend() {
        val has = !binding.threadInput.text.isNullOrBlank()
        binding.threadSend.isEnabled = has
        binding.threadSend.alpha = if (has) 1f else DISABLED_ALPHA
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun show() {
        blur()
        dialog.show()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss() else unblur()
    }

    /**
     * Shows [items] (the thread's messages as [ThreadItems.build] makes them for it). A message that
     * was not there before (a new reply) scrolls the list down to the newest.
     */
    fun update(items: List<ThreadItem>) {
        val ids = items.mapNotNullTo(HashSet()) { (it as? ThreadItem.Message)?.row?.id }
        val grew = shownIds.isNotEmpty() && !shownIds.containsAll(ids)
        val first = shownIds.isEmpty()
        shownIds = ids
        adapter.submitList(items) {
            if ((first || grew) && items.isNotEmpty()) binding.threadMessages.scrollToPosition(items.lastIndex)
        }
    }

    private fun blur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = BLUR_DP * activity.resources.displayMetrics.density
            behind.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }

    private fun unblur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) behind.setRenderEffect(null)
    }

    private companion object {
        const val BLUR_DP = 18f
        const val FIELD_RADIUS_DP = 18f
        const val DISABLED_ALPHA = 0.4f
    }
}
