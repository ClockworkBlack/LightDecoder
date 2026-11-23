package com.lightdecoder.ui.chat

import android.content.Context
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.FaceTimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The green "Touch to return to call" bar at the top of the chats and of a chat while a FaceTime call
 * holds the line (as iPhone's), with the call's time once it is on: a tap opens the call screen. It
 * shows what the ":imessage" process says where FaceTime stands ([DirectLink.faceTime]); main process.
 */
class ReturnToCallBar private constructor(private val activity: AppCompatActivity, val view: TextView) {

    private var ticker: Job? = null

    /** What the bar shows now (for tests). */
    @Volatile
    var shown: FaceTimeState = FaceTimeState.NONE
        private set

    private fun start() {
        view.setOnClickListener { open() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DirectLink.get(activity.applicationContext).faceTime.collect { show(it) }
            }
        }
    }

    private fun show(state: FaceTimeState) {
        shown = state
        ticker?.cancel()
        if (!state.live) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.contentDescription = activity.getString(R.string.facetime_return_description, state.title)
        ticker = activity.lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                view.text = text(activity, state, now)
                if (state.sinceMs <= 0) break
                delay(1000 - (now % 1000).coerceIn(0, 999))
            }
        }
    }

    /** The call screen, back in front (the call's own process; the same app). */
    private fun open() {
        try {
            activity.startActivity(com.lightdecoder.ui.imessage.FaceTimeCallActivity.showIntent(activity))
        } catch (e: RuntimeException) {
            // Nothing to open it with.
        }
    }

    companion object {
        /**
         * Puts the bar into [parent] at [index] (the top of a screen's column), hidden until a call
         * holds the line; it follows where FaceTime stands while the screen is in sight.
         */
        fun attach(activity: AppCompatActivity, parent: LinearLayout, index: Int): ReturnToCallBar {
            val density = activity.resources.displayMetrics.density
            val view = TextView(activity).apply {
                id = R.id.returnToCall
                gravity = Gravity.CENTER
                setBackgroundColor(ContextCompat.getColor(activity, R.color.call_accept))
                setTextColor(ContextCompat.getColor(activity, R.color.call_on_control))
                textSize = 14f
                minHeight = (32 * density).toInt()
                val pad = (6 * density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                isClickable = true
                isFocusable = true
                visibility = View.GONE
            }
            parent.addView(view, index.coerceIn(0, parent.childCount), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return ReturnToCallBar(activity, view).also { it.start() }
        }

        /** "Touch to return to call · 01:23" once the call is on ([FaceTimeState.sinceMs]), without the time before. Pure but for the strings. */
        fun text(context: Context, state: FaceTimeState, nowMs: Long): String =
            if (state.sinceMs <= 0) {
                context.getString(R.string.facetime_return)
            } else {
                context.getString(R.string.facetime_return_time, DateUtils.formatElapsedTime(((nowMs - state.sinceMs) / 1000).coerceAtLeast(0)))
            }
    }
}
