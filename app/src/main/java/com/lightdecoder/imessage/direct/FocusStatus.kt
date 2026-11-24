package com.lightdecoder.imessage.direct

import android.util.Log
import com.lightdecoder.imessage.facetime.CallIds
import kotlinx.coroutines.CancellationException
import uniffi.rust_bridge.StatusKitUpdate

/**
 * Other people's Focus status (B6.3), in the ":imessage" process: the handles the main process asks
 * about ([subscribe]: one-to-one chats opened lately, [DirectWire.MAX_FOCUS_HANDLES] at most) are
 * handed to `statuskitSubscribe`, and what their phones tell ([onUpdate]) goes to the main process,
 * only for a handle asked about. Nothing is done unless [enabled] (signed in with iCloud's services,
 * Focus status on). Should Apple refuse it (without the iCloud Keychain, "StatusKit is unavailable
 * without a keychain"), the feature is marked not available ([unavailable]) and asks nothing more.
 */
class FocusStatus(
    private val bridge: FeatureBridge,
    private val enabled: () -> Boolean,
    private val unavailable: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var subscribed: Set<String> = emptySet()
    private var lastAskedMs = 0L
    private var active = false

    /** The handles whose status is taken now (by [CallIds.key]). */
    fun subscribedKeys(): Set<String> = synchronized(lock) { subscribed }

    /**
     * Asks for the Focus status of [handles] (a full replacement). The same set again within
     * [MIN_INTERVAL_MS] is not asked again. True when Apple took it.
     */
    suspend fun subscribe(handles: List<String>): Boolean {
        if (!enabled()) return false
        val clean = handles.mapNotNull(CallIds::handle).distinctBy(CallIds::key).take(DirectWire.MAX_FOCUS_HANDLES)
        val keys = clean.mapTo(HashSet(), CallIds::key)
        synchronized(lock) {
            if (keys == subscribed && active && now() - lastAskedMs in 0 until MIN_INTERVAL_MS) return true
        }
        return try {
            if (clean.isEmpty()) {
                if (synchronized(lock) { active }) bridge.statusUnsubscribe()
            } else {
                bridge.statusSubscribe(clean)
            }
            synchronized(lock) {
                subscribed = keys
                lastAskedMs = now()
                active = clean.isNotEmpty()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Focus status was not asked for (${e::class.java.simpleName}); not available")
            synchronized(lock) {
                subscribed = emptySet()
                active = false
            }
            unavailable()
            false
        }
    }

    /** What someone's phone told of their Focus: an update for the main process, or null (not asked about, or off). */
    fun onUpdate(update: StatusKitUpdate): DirectArchive.Update.Focus? {
        if (!enabled()) return null
        val handle = CallIds.handle(update.user) ?: return null
        if (CallIds.key(handle) !in subscribedKeys()) return null
        return DirectArchive.Update.Focus(handle, silenced = !update.allowed, atMs = now())
    }

    /** Stops asking (turned off, signed out). Never a failure of what asked. */
    suspend fun stop() {
        val was = synchronized(lock) {
            val a = active
            active = false
            subscribed = emptySet()
            a
        }
        if (!was) return
        try {
            bridge.statusUnsubscribe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Focus status was not stopped (${e::class.java.simpleName})")
        }
    }

    companion object {
        private const val TAG = "FocusStatus"
        const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
