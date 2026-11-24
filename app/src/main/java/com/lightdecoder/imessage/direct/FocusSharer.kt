package com.lightdecoder.imessage.direct

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightdecoder.imessage.facetime.CallIds
import kotlinx.coroutines.CancellationException

/**
 * Sharing the user's own Focus (B6.4), in the ":imessage" process, off by default ("Share Focus
 * Status"). Only while [enabled] (signed in with iCloud's services, the setting on):
 * - Do Not Disturb turned on here: `statuskitShareStatus("dnd")`, and the people of the one-to-one
 *   chats the user wrote in lately ([invitees], [DirectWire.MAX_FOCUS_HANDLES] at most, from the main
 *   process) are let see it (`statuskitInvite`), from this phone's number ([sender]) only;
 * - turned off: `statuskitShareStatus(null)`;
 * - the setting turned off ([withdraw]): those let see it are taken off (`statuskitRemove`) and the
 *   keys rolled (`statuskitResetKeys`), so nobody sees it any more.
 * Nothing else: no mode name, no exemptions, nothing about the user's other devices.
 */
class FocusSharer(
    private val bridge: FeatureBridge,
    private val enabled: () -> Boolean,
    /** This phone's number as the user sends from it (a `tel:` handle); null when not known. */
    private val sender: () -> String?,
    /**
     * Where those let see it are kept (one of the account's files, so a sign-out deletes it after
     * [withdraw]): a later [withdraw], in a later run of this process, takes them off too. Null: kept
     * in memory only (tests).
     */
    private val file: java.io.File? = null,
) {

    private val lock = Any()
    private var wanted: List<String> = emptyList()
    private val invited = LinkedHashSet<String>()
    private var loaded = false
    private var sharedOn: Boolean? = null

    /** [invited], read from [file] the first time (under [lock]). */
    private fun invitedLocked(): LinkedHashSet<String> {
        if (!loaded) {
            loaded = true
            try {
                val f = file?.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }
                if (f != null) {
                    val a = org.json.JSONArray(f.readText())
                    for (i in 0 until minOf(a.length(), DirectWire.MAX_FOCUS_HANDLES * 4)) {
                        (a.opt(i) as? String)?.let(CallIds::handle)?.let { invited.add(CallIds.key(it)) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "those let see the user's Focus were not read (${e::class.java.simpleName})")
            }
        }
        return invited
    }

    private fun saveLocked() {
        val f = file ?: return
        try {
            if (invited.isEmpty()) {
                f.delete()
                return
            }
            f.parentFile?.mkdirs()
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(org.json.JSONArray(invited.toList()).toString())
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "those let see the user's Focus were not kept (${e::class.java.simpleName})")
        }
    }

    /** The people who may see the user's Focus: those the user wrote to lately (a full replacement). */
    fun invitees(handles: List<String>) = synchronized(lock) {
        wanted = handles.mapNotNull(CallIds::handle).distinctBy(CallIds::key).take(DirectWire.MAX_FOCUS_HANDLES)
    }

    /** Tests and the setting's page: who was let see it. */
    fun invitedKeys(): Set<String> = synchronized(lock) { invitedLocked().toSet() }

    /** Do Not Disturb is now [on] here. */
    suspend fun dndChanged(on: Boolean) {
        if (!enabled()) return
        try {
            if (synchronized(lock) { sharedOn } != on) {
                bridge.shareStatus(if (on) MODE else null)
                synchronized(lock) { sharedOn = on }
            }
            if (on) invite()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the user's Focus was not shared (${e::class.java.simpleName})")
        }
    }

    private suspend fun invite() {
        val from = sender()?.let(CallIds::handle)?.takeIf { it.startsWith("tel:") } ?: return
        val fresh = synchronized(lock) { invitedLocked().let { known -> wanted.filter { CallIds.key(it) !in known } } }
        if (fresh.isEmpty()) return
        // Kept first: should the process end right after, a later withdraw still takes them off.
        synchronized(lock) {
            fresh.mapTo(invitedLocked(), CallIds::key)
            while (invited.size > DirectWire.MAX_FOCUS_HANDLES * 4) invited.remove(invited.first())
            saveLocked()
        }
        bridge.statusInvite(from, fresh)
    }

    /** Whether anyone let see the user's Focus is still to be taken off (a [withdraw] that did not finish). */
    fun anyInvited(): Boolean = synchronized(lock) { invitedLocked().isNotEmpty() }

    /**
     * "Share Focus Status" turned off (or signed out): nobody sees it any more. Never a failure of what
     * asked. Those let see it are forgotten (here and in [file]) only once Apple took them off and the
     * keys were rolled; anyone not taken off, or everyone if the keys were not rolled, is kept for a
     * later [withdraw] (the next start, or a sign-out).
     */
    suspend fun withdraw() {
        val who = synchronized(lock) {
            sharedOn = null
            invitedLocked().toList()
        }
        val off = ArrayList<String>()
        var done = true
        try {
            bridge.shareStatus(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            done = false
            Log.w(TAG, "the user's Focus was not withdrawn (${e::class.java.simpleName})")
        }
        for (h in who) {
            try {
                bridge.statusRemove(h)
                off += h
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "someone let see the user's Focus was not taken off (${e::class.java.simpleName})")
            }
        }
        val rolled = try {
            bridge.statusResetKeys()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the keys of the user's Focus were not rolled (${e::class.java.simpleName})")
            false
        }
        if (!rolled) return
        synchronized(lock) {
            if (done) sharedOn = false
            if (invitedLocked().removeAll(off.toSet())) saveLocked()
        }
    }

    /**
     * Android's Do Not Disturb, watched while sharing is on: [onChange] with whether it is on now
     * (anything but "all notifications"). [U] whether a receiver registered at run time hears of it
     * without the "Do Not Disturb access" permission; the state is read once at [start] either way.
     */
    class Watcher(private val context: Context, private val onChange: (Boolean) -> Unit) {
        private var receiver: BroadcastReceiver? = null

        fun start() {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    try {
                        onChange(dndOn(context))
                    } catch (e: Throwable) {
                        Log.w(TAG, "Do Not Disturb not read (${e::class.java.simpleName})")
                    }
                }
            }
            ContextCompat.registerReceiver(context, r, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
            onChange(dndOn(context))
        }

        fun stop() {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
        }

        companion object {
            fun dndOn(context: Context): Boolean {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return false
                val f = nm.currentInterruptionFilter
                return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
        }
    }

    companion object {
        private const val TAG = "FocusSharer"
        private const val MAX_FILE_BYTES = 64 * 1024L
        const val MODE = "dnd"
    }
}
