package com.lightdecoder.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

/**
 * Starts the app afresh after a restore: the launcher screen in a new task, then this process
 * ends, so nothing keeps the archive that was replaced open. (The iMessage process never opens
 * an archive and is left alone.)
 */
object AppRestart {

    /** How long the "restored" message may wait for the user before the app restarts anyway. */
    const val FALLBACK_MS = 30_000L

    /** What a restore that replaced the chats in this process brought back (null: none did). */
    data class Restored(val conversations: Int, val messages: Int)

    private val restoredState = kotlinx.coroutines.flow.MutableStateFlow<Restored?>(null)

    /**
     * Set once a restore replaced the chats in this process: from then on only a restart is safe.
     * Kept here rather than on a screen, so a screen made afresh still knows it.
     */
    val restored: kotlinx.coroutines.flow.StateFlow<Restored?> get() = restoredState

    fun markRestored(conversations: Int, messages: Int) {
        restoredState.value = Restored(conversations, messages)
    }

    /** Tests only. */
    internal fun resetForTesting() {
        restoredState.value = null
    }

    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) runCatching { context.startActivity(launch) }
        if (context is Activity) runCatching { context.finishAffinity() }
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    /**
     * Restarts from a thread of its own after [FALLBACK_MS], in case the screen that should ask
     * the user never gets to (something in this process waits on the archive that was closed).
     */
    fun restartLater(context: Context) {
        val app = context.applicationContext
        Thread({
            try {
                Thread.sleep(FALLBACK_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            restart(app)
        }, "restore-restart").apply { isDaemon = true }.start()
    }
}
