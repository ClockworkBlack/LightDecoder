package com.lightdecoder.wrapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lightdecoder.R
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.OpenBubbles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The OpenBubbles wrapper (experimental, off unless the user turns it on): an
 * accessibility service that only acts on OpenBubbles' screens (its packages
 * are the only ones in the service's config). It does in OpenBubbles what
 * OpenBubbles' notification cannot: an inline reply to one message, and
 * Undo Send. It opens the chat, finds the message, long-presses it, picks the
 * action, types, sends, and brings LightDecoder back, behind a cover of
 * LightDecoder's own so OpenBubbles is not seen working. Every step and its
 * time are in the iMessage log; when anything is not found LightDecoder is
 * brought back all the same, offering to try again or do it in OpenBubbles.
 */
class WrapperService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cover: View? = null

    override fun onServiceConnected() {
        running = this
        IMessageLog.add(this, "Wrapper: accessibility service on")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (running === this) running = null
        IMessageLog.add(this, "Wrapper: accessibility service off")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (running === this) running = null
        scope.cancel()
        unfreeze()
        super.onDestroy()
    }

    /** Jobs waiting for the one under way (main thread only). */
    private val queue = ArrayDeque<Wrapper.Job>()

    /** The job under way, if any. */
    @Volatile
    private var current: Wrapper.Job? = null

    /** Set to stop the job under way (a look for receipts the user's own action or a tap overrides). */
    @Volatile
    private var stop = false

    /**
     * Runs [job], one at a time. The user's own jobs come first: a look for receipts under way is
     * stopped for them, and others wait their turn (they no longer fail as "busy"). A look for receipts
     * while anything is under way or waiting is dropped.
     */
    fun run(job: Wrapper.Job) {
        scope.launch {
            val now = current
            if (now != null) {
                if (job.kind == Wrapper.Kind.STATUS) return@launch
                if (now.kind == Wrapper.Kind.STATUS) stop = true
                queue.removeAll { it.kind == Wrapper.Kind.STATUS }
                queue.addLast(job)
                return@launch
            }
            if (job.kind == Wrapper.Kind.STATUS && queue.isNotEmpty()) return@launch
            go(job)
        }
    }

    private suspend fun go(first: Wrapper.Job) {
        var job = first
        // Silent: a still of the screen as it is now stays in front while OpenBubbles works underneath.
        current = job
        stop = false
        busy = true
        freeze(touchStops = job.kind == Wrapper.Kind.STATUS)
        while (true) {
            val started = SystemClock.elapsedRealtime()
            val steps = ArrayList<String>()
            var stepAt = started
            fun mark(name: String) {
                val now = SystemClock.elapsedRealtime()
                steps += "$name ${now - stepAt}ms"
                stepAt = now
            }
            val failedAt: String? = try {
                // Reading OpenBubbles' screen is many calls to Android: off LightDecoder's main thread,
                // so LightDecoder itself never stutters while the wrapper works.
                withContext(Dispatchers.Default) { perform(job, ::mark) }
            } catch (e: Exception) {
                "error ${e.javaClass.simpleName}"
            }.let { if (it != null && stop) "stopped for you" else it }
            val total = SystemClock.elapsedRealtime() - started
            IMessageLog.add(
                this@WrapperService,
                "Wrapper ${job.kind.name.lowercase()} ${IMessageLog.chatRef(job.chatGuid)}: " +
                    (if (failedAt == null) "done" else "stopped at $failedAt") + " in ${total}ms (${steps.joinToString(", ")})",
            )
            val next = queue.removeFirstOrNull()
            if (next == null) {
                // Always back to LightDecoder, done or not: the user is never left in OpenBubbles.
                bringBack(job)
                // LightDecoder is drawn again behind the still before it goes: no flash.
                delay(RETURN_MS)
                current = null
                busy = false
                unfreeze()
                withContext(Dispatchers.IO) { Wrapper.finish(this@WrapperService, job, Wrapper.Result(failedAt == null, failedAt, total)) }
                return
            }
            // The next one goes on behind the same still, with no trip back to LightDecoder between.
            withContext(Dispatchers.IO) { Wrapper.finish(this@WrapperService, job, Wrapper.Result(failedAt == null, failedAt, total)) }
            job = next
            current = job
            stop = false
            touchStops = false
        }
    }

    @Volatile
    internal var busy = false

    /** The steps; null when all went, else the step it stopped at. */
    private suspend fun perform(job: Wrapper.Job, mark: (String) -> Unit): String? {
        val failed = when (job.kind) {
            Wrapper.Kind.SEND -> send(job, mark)
            Wrapper.Kind.STATUS -> status(job, mark)
            else -> act(job, mark)
        }
        // Whatever was done, OpenBubbles' chat is on screen: what it says under the newest message.
        if (failed == null && job.kind != Wrapper.Kind.STATUS) {
            root()?.let(WrapperNodes::receipt)?.let { Wrapper.receipt(this, job.chatGuid, it) }
        }
        return failed
    }

    /** Only a look: the chat opened, the receipt under the newest message read. */
    private suspend fun status(job: Wrapper.Job, mark: (String) -> Unit): String? {
        if (!Wrapper.openChat(this, job.chatGuid)) return "open"
        waitFor(OPEN_MS) { root -> WrapperNodes.field(root) } ?: return "chat"
        mark("chat")
        val receipt = waitFor(RECEIPT_MS) { root -> if (WrapperNodes.receipt(root) != null) root else null }
            ?.let(WrapperNodes::receipt) ?: return "no receipt shown"
        Wrapper.receipt(this, job.chatGuid, receipt)
        mark("receipt ${receipt.name.lowercase()}")
        return null
    }

    private suspend fun act(job: Wrapper.Job, mark: (String) -> Unit): String? {
        if (!Wrapper.openChat(this, job.chatGuid)) return "open"
        // 1. The chat is open: its message field is there.
        waitFor(OPEN_MS) { root -> WrapperNodes.field(root) } ?: return "chat"
        mark("chat")
        // 2. The message replied to (or to unsend), scrolling back to it if need be.
        val mine = job.kind != Wrapper.Kind.REPLY && job.kind != Wrapper.Kind.REACT
        var bubble = waitFor(SHORT_MS) { root -> WrapperNodes.message(root, job.targetText, mine) }
        var scrolls = 0
        // Undo Send (2 min) and Edit (15 min) are for recent messages, near the bottom: no long hunt.
        val maxScrolls = if (job.kind == Wrapper.Kind.UNSEND || job.kind == Wrapper.Kind.EDIT) RECENT_SCROLLS else MAX_SCROLLS
        while (bubble == null && scrolls < maxScrolls && !stop) {
            val list = root()?.let(WrapperNodes::scrollable) ?: break
            act(list, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            scrolls++
            bubble = waitFor(SCROLL_MS) { root -> WrapperNodes.message(root, job.targetText, mine) }
        }
        bubble ?: return "message"
        mark(if (scrolls > 0) "message (scrolled $scrolls)" else "message")
        // 3. Its menu: a long press.
        val press = WrapperNodes.longPressTarget(bubble)
        if (press == null || !act(press, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            if (!gesture(bubble.bounds, long = true)) return "long-press"
        }
        // A reaction: tap its emoji in the reaction row at the top of the menu (scrolling along it if need be).
        if (job.kind == Wrapper.Kind.REACT) {
            var emoji = waitFor(MENU_MS) { root -> WrapperNodes.reaction(root, job.text) }
            var along = 0
            while (emoji == null && along < 3) {
                val row = root()?.let(WrapperNodes::rows)?.firstOrNull() ?: break
                act(row, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                along++
                emoji = waitFor(SCROLL_MS) { root -> WrapperNodes.reaction(root, job.text) }
            }
            emoji ?: return "reaction not offered"
            mark("menu")
            if (!click(emoji)) return "reaction"
            delay(SETTLE_MS)
            mark("reacted")
            return null
        }
        val titles = when (job.kind) {
            Wrapper.Kind.UNSEND -> UNSEND_TITLES
            Wrapper.Kind.EDIT -> EDIT_TITLES
            else -> REPLY_TITLES
        }
        val findItem: (UiNode) -> UiNode? = { root -> WrapperNodes.action(root, *titles) ?: WrapperNodes.more(root) }
        var found = waitFor(MENU_MS, findItem)
        if (found == null && !stop) {
            // No menu: the accessibility long press may not have opened it; a real one.
            mark("menu not open, long-press again")
            gesture(bubble.bounds.takeUnless { it.isEmpty } ?: press?.bounds ?: Rect(), long = true)
            found = waitFor(MENU_MS, findItem)
        }
        if (found == null) {
            IMessageLog.add(this, "Wrapper: no ${titles.first()} in OpenBubbles' menu; buttons there: ${root()?.let { WrapperNodes.buttonNames(it) } ?: "no OpenBubbles window"}")
            return "menu"
        }
        var item: UiNode = found
        if (WrapperNodes.action(root() ?: found, *titles) == null) {
            // Behind the menu's "more" button.
            click(found)
            item = waitFor(MENU_MS) { root -> WrapperNodes.action(root, *titles) } ?: run {
                IMessageLog.add(this, "Wrapper: no ${titles.first()} behind \"more\"; buttons there: ${root()?.let { WrapperNodes.buttonNames(it) } ?: "none"}")
                return "menu item"
            }
        }
        mark("menu")
        val unsentBefore = if (job.kind == Wrapper.Kind.UNSEND) root()?.let(WrapperNodes::unsentCount) ?: 0 else 0
        if (!click(item)) return "menu item"
        if (job.kind == Wrapper.Kind.UNSEND) {
            // Done when OpenBubbles shows one more "You unsent a message".
            waitFor(UNSENT_MS) { root -> if (WrapperNodes.unsentCount(root) > unsentBefore) root else null }
                ?: return "undo send not shown"
            mark("undo send")
            return null
        }
        if (job.kind == Wrapper.Kind.EDIT) {
            // The message becomes a field ("Edited Message") with a send arrow to its right.
            val editing = waitFor(SHORT_MS) { root -> WrapperNodes.editField(root) } ?: return "edit field"
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.text) }
            if (!act(editing, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "type"
            mark("typed")
            val confirm = waitFor(SHORT_MS) { root -> WrapperNodes.editField(root)?.let { f -> WrapperNodes.send(root, f) } } ?: return "edit send"
            if (!click(confirm)) return "edit send"
            waitFor(SHORT_MS) { root -> if (WrapperNodes.editField(root) == null) root else null } ?: return "edited?"
            mark("edited")
            return null
        }
        // 4. Reply mode: type into the field.
        val field = waitFor(SHORT_MS) { root -> WrapperNodes.field(root) } ?: return "field"
        act(field, AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.text) }
        if (!act(field, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "type"
        mark("typed")
        // 5. Send (it appears once there is text), and see the field empty again.
        val send = waitFor(SHORT_MS) { root -> WrapperNodes.field(root)?.let { f -> WrapperNodes.send(root, f) } } ?: return "send button"
        if (!click(send)) return "send"
        waitFor(SHORT_MS) { root -> WrapperNodes.field(root)?.takeIf { it.text.isBlank() || it.text == it.hint } } ?: return "sent?"
        mark("sent")
        return null
    }

    /** OpenBubbles' composer opened with the files (and text) in it: press send. */
    private suspend fun send(job: Wrapper.Job, mark: (String) -> Unit): String? {
        val intent = job.open ?: return "open"
        val started = runCatching {
            startActivity(
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
            )
        }.isSuccess
        if (!started) return "open"
        waitFor(OPEN_MS) { root -> WrapperNodes.field(root) } ?: return "chat"
        mark("chat")
        // The shared files load into the composer; the send button shows once they have.
        delay(ATTACH_MS)
        val button = waitFor(SHORT_MS) { root -> WrapperNodes.field(root)?.let { f -> WrapperNodes.send(root, f) } } ?: return "send button"
        if (!click(button)) return "send"
        delay(SETTLE_MS)
        mark("sent")
        return null
    }

    // --- OpenBubbles' screen ---------------------------------------------------------------

    /** OpenBubbles' window, wherever it is in the window list (LightDecoder's cover is above it). */
    private fun root(): AccNode? {
        val fromWindows = runCatching { windows }.getOrNull().orEmpty()
            .mapNotNull { it.root }
            .firstOrNull { OpenBubbles.isOpenBubbles(it.packageName?.toString()) }
        val info = fromWindows ?: rootInActiveWindow?.takeIf { OpenBubbles.isOpenBubbles(it.packageName?.toString()) } ?: return null
        return AccNode(info, null)
    }

    /** Looks every [POLL_MS] until [find] has something, at most [timeoutMs]. */
    private suspend fun waitFor(timeoutMs: Long, find: (UiNode) -> UiNode?): UiNode? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (stop) return null
            root()?.let { r -> find(r)?.let { return it } }
            if (SystemClock.elapsedRealtime() > deadline) return null
            delay(POLL_MS)
        }
    }

    private fun act(node: UiNode, action: Int, args: Bundle? = null): Boolean =
        (node as? AccNode)?.info?.performAction(action, args) == true

    private suspend fun click(node: UiNode): Boolean {
        val target = WrapperNodes.clickTarget(node) ?: node
        if (act(target, AccessibilityNodeInfo.ACTION_CLICK)) return true
        return gesture(node.bounds, long = false)
    }

    /** A tap or long press at the middle of [bounds], for a node that does not take the action itself. */
    private suspend fun gesture(bounds: Rect, long: Boolean): Boolean {
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (long) LONG_PRESS_MS else 50)
        withContext(Dispatchers.Main) { letTouchesThrough(true) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        if (ok) delay(if (long) LONG_PRESS_MS + 100 else 120)
        withContext(Dispatchers.Main) { letTouchesThrough(false) }
        return ok
    }

    /** Back to the LightDecoder conversation it was started from. */
    private fun bringBack(job: Wrapper.Job) {
        runCatching {
            startActivity(
                job.returnTo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
            )
        }
    }

    // --- the still ----------------------------------------------------------------------------

    private var params: WindowManager.LayoutParams? = null

    /**
     * Puts a still picture of the screen (Android 11+: the service's own screenshot) in front of
     * everything, taking touches, so nothing is seen or tapped while OpenBubbles works. Without a
     * screenshot, a plain cover of LightDecoder's own colour.
     */
    /** A look for receipts: a tap on the still stops it and LightDecoder is back at once. */
    @Volatile
    private var touchStops = false

    private suspend fun freeze(touchStops: Boolean) {
        this.touchStops = touchStops
        if (cover != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val still = screenshot()
        val view: View = if (still != null) {
            android.widget.ImageView(this).apply {
                setImageBitmap(still)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            }
        } else {
            FrameLayout(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@WrapperService, R.color.wrapper_cover))
                addView(ProgressBar(this@WrapperService), FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                ))
            }
        }
        // Touches stop here (they would land in OpenBubbles).
        view.setOnTouchListener { _, e ->
            if (this.touchStops && e.actionMasked == android.view.MotionEvent.ACTION_DOWN) stop = true
            true
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
            windowAnimations = 0
        }
        runCatching {
            wm.addView(view, p)
            cover = view
            params = p
        }
    }

    private fun unfreeze() {
        val view = cover ?: return
        cover = null
        params = null
        runCatching { getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    /** While a gesture is made, touches go through the still (to OpenBubbles under it). */
    private fun letTouchesThrough(through: Boolean) {
        val view = cover ?: return
        val p = params ?: return
        p.flags = if (through) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { getSystemService(WindowManager::class.java)?.updateViewLayout(view, p) }
    }

    /** The screen as it is now (Android 11+), or null. */
    private suspend fun screenshot(): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        return kotlinx.coroutines.withTimeoutOrNull(SHOT_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = runCatching {
                                android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            runCatching { result.hardwareBuffer.close() }
                            if (cont.isActive) cont.resumeWith(Result.success(bitmap))
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resumeWith(Result.success(null))
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            }
        }
    }

    /** An accessibility node as a [UiNode]. */
    private class AccNode(val info: AccessibilityNodeInfo, override val parent: UiNode?) : UiNode {
        override val text: String get() = info.text?.toString().orEmpty()
        override val description: String get() = info.contentDescription?.toString().orEmpty()
        override val tooltip: String
            get() = if (android.os.Build.VERSION.SDK_INT >= 28) info.tooltipText?.toString().orEmpty() else ""
        override val hint: String get() = info.hintText?.toString().orEmpty()
        override val className: String get() = info.className?.toString().orEmpty()
        override val bounds: Rect by lazy { Rect().also(info::getBoundsInScreen) }
        override val clickable: Boolean get() = info.isClickable
        override val longClickable: Boolean get() = info.isLongClickable
        override val editable: Boolean get() = info.isEditable
        override val scrollable: Boolean get() = info.isScrollable
        override val children: List<UiNode> by lazy {
            (0 until info.childCount).mapNotNull { i -> info.getChild(i)?.let { AccNode(it, this) } }
        }
    }

    companion object {
        @Volatile
        var running: WrapperService? = null
            private set

        private const val POLL_MS = 40L
        private const val OPEN_MS = 5_000L
        private const val SHORT_MS = 2_000L
        private const val MENU_MS = 1_500L
        private const val SCROLL_MS = 600L
        private const val SETTLE_MS = 350L
        private const val LONG_PRESS_MS = 600L
        private const val MAX_SCROLLS = 8
        private const val RECENT_SCROLLS = 1
        private const val SHOT_MS = 700L
        private const val ATTACH_MS = 600L
        private const val RECEIPT_MS = 900L
        private const val UNSENT_MS = 2_500L
        private val UNSEND_TITLES = arrayOf("Undo Send", "Unsend", "Undo send message", "Unsend message")
        private val EDIT_TITLES = arrayOf("Edit", "Edit message")
        private val REPLY_TITLES = arrayOf("Reply", "Reply in thread")

        /** A job under way (a look for receipts is not started then). */
        fun isBusy(): Boolean = running?.busy == true

        private const val RETURN_MS = 250L
    }
}
