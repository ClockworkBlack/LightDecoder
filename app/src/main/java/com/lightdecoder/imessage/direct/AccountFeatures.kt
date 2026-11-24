package com.lightdecoder.imessage.direct

import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.rust_bridge.StatusKitUpdate

/**
 * What the optional Apple Account unlocks (B6), in the ":imessage" process: Name & Photo
 * ([profiles]), Focus status in ([focus]) and out ([sharer]), and Messages in iCloud ([join],
 * [cloud]). Owned by [DirectEngine]; the settings pages and the main process's requests reach it.
 *
 * Inert without an account: every entry point looks at [on] first (signed in, not needing a sign-in,
 * with iCloud's services, as [AppleAccount.state] has it in memory) and does nothing at all otherwise:
 * no library call, no file, no network, nothing told. Each feature has its own switch besides, kept
 * in the account's flag (`apple-account.json`, removed by a sign-out): receiving names and photos and
 * Focus status on, sharing them off, Messages in iCloud off, until the user turns them.
 */
class AccountFeatures internal constructor(
    private val account: AppleAccount,
    private val files: AccountFiles,
    private val bridge: FeatureBridge,
    private val lock: Mutex,
    private val busy: () -> Boolean,
    /** This phone's number as the user sends from it. */
    private val sender: () -> String?,
    /** Hands an update to the main process; true once stored. */
    private val deliver: suspend (DirectArchive.Update) -> Boolean,
    joinSteps: () -> KeychainJoin.Steps,
    /** Keeps the keychain's code, encrypted. */
    keepCode: (String) -> Unit,
    /** Android's Do Not Disturb, watched while sharing is on (null in tests). */
    private val watcher: ((Boolean) -> Unit) -> FocusSharer.Watcher? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** The account's flag as kept (its features' settings). */
    fun flag(): AccountFlag = files.readFlag()

    /** Whether the features may be used at all: signed in (not needing a sign-in) with iCloud's services. */
    fun on(): Boolean {
        val s = account.state.value
        return s.features && flag().state == FlagState.SIGNED_IN
    }

    /** A feature's call failed: when Apple no longer takes the sign-in, the account needs attention (once; [AppleAccount.authFailed]). */
    private fun failedWith(e: Throwable) {
        if (e is CancellationException) return
        if (AppleAccountRules.isAuthFailure(com.lightdecoder.imessage.Redact.secrets(DirectEngine.readable(e)))) account.authFailed()
    }

    val profiles = Profiles(bridge, files.myProfileFile, enabled = ::on, sharing = { flag().profileShare }, failed = ::failedWith)

    val focus = FocusStatus(bridge, enabled = { on() && flag().let { it.focusIn && !it.focusUnavailable } }, unavailable = {
        scope.launch { setFlag { it.copy(focusUnavailable = true) } }
    }, now = now, failed = ::failedWith)

    val sharer = FocusSharer(bridge, enabled = { on() && flag().focusShare }, sender = sender, file = files.focusInvitedFile)

    val cloud = CloudImport(
        bridge, files.cloudDir,
        enabled = { on() && flag().history == AccountFlag.HISTORY_ON && account.inClique },
        deliver = { b -> deliver(b) },
        adp = {
            Log.w(TAG, "Messages in iCloud: Advanced Data Protection is on; turned off")
            scope.launch { setFlag { it.copy(history = AccountFlag.HISTORY_OFF) } }
        },
        now = now,
        failed = ::failedWith,
    )

    val join = KeychainJoin(
        joinSteps(), files, lock, busy,
        // Not for a phone in the keychain already (Messages in iCloud turned off, then on again: [turnOnAgain]).
        allowed = { on() && flag().history != AccountFlag.HISTORY_ON && !account.inClique },
        keepCode = keepCode,
        changed = {
            // Joined: the import may run from now on, in this process too.
            if (flag().history == AccountFlag.HISTORY_ON) account.inClique = true
            account.featuresChanged()
        },
        touched = { account.liveSessionTouched() },
        now = now,
    )

    private var dnd: FocusSharer.Watcher? = null

    /**
     * Whether "Also remove imported messages" was ticked for a sign-out: kept on disk
     * ([AccountFiles.removeImportedMarker]) until the main process was told of the sign-out with it
     * ([importedRemovalTold]), or the next sign-in.
     */
    var removeImported: Boolean
        get() = files.removeImportedMarker.isFile
        set(value) {
            try {
                files.mark(files.removeImportedMarker, value)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "the choice to remove imported messages was not kept (${e::class.java.simpleName})")
            }
        }

    /** The main process was told where the account stands ([signedIn]): a removal asked for was told with a sign-out, or a sign-in makes it moot. */
    fun importedRemovalTold(signedIn: Boolean) {
        if (signedIn || removeImported) removeImported = false
    }

    // --- the engine's moments ---------------------------------------------------------------------

    /**
     * The session came up at a start (or was rebuilt): whether this phone is in the iCloud Keychain,
     * the import's run once per start while Messages in iCloud is on, Do Not Disturb watched while
     * sharing is on. Nothing at all without an account.
     */
    suspend fun sessionUp() {
        if (!on()) return
        try {
            account.inClique = try {
                bridge.isInClique()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            account.featuresChanged()
            val f = flag()
            if (f.history == AccountFlag.HISTORY_ON) cloud.start(CloudImport.sinceOf(f.window, now()))
            // Sharing turned off, but someone was not taken off then: tried again.
            if (!f.focusShare && sharer.anyInvited()) sharer.withdraw()
            watchDnd()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "the account's features did not start (${e::class.java.simpleName})")
        }
    }

    /** At a start, once the session was restored or not (under the engine's lock): a keychain join cut short. */
    suspend fun atStartLocked(restored: Boolean) {
        if (flag().history != AccountFlag.HISTORY_JOINING && !files.joinMarker.isFile) return
        try {
            join.atStartLocked(restored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "a keychain join cut short was not put right (${e::class.java.simpleName})")
        }
    }

    /** Whether [update] (a name and photo offered) is to go to the main process: only with the feature on. */
    fun takes(update: DirectArchive.Update.ProfileOffer): Boolean = on() && flag().profilesIn

    /** Someone's Focus status, from Apple: to the main process, only for someone asked about. */
    suspend fun onStatusKit(update: StatusKitUpdate) {
        val u = focus.onUpdate(update) ?: return
        deliver(u)
    }

    /** A sign-out's first step: everything stops (what the account's files hold goes with them). */
    suspend fun stopForSignOut() {
        cloud.forget()
        focus.stop()
        // Also those left from a withdraw that did not finish.
        if (flag().focusShare || sharer.anyInvited()) sharer.withdraw()
        dnd?.stop()
        dnd = null
        account.inClique = false
    }

    // --- the main process's requests --------------------------------------------------------------

    /** [DirectWire.PROFILE_FETCH]: a name and photo the main process took an offer of. Null when it is off. */
    suspend fun fetchProfile(r: DirectWire.ProfileFetchRequest): DirectWire.ProfileReply? {
        if (!on() || !flag().profilesIn) return null
        return profiles.fetch(r.descriptor, File(r.imagePath))
    }

    /** [DirectWire.FOCUS_SUBSCRIBE]: who to ask about, and who may see the user's own Focus. */
    suspend fun focusRequest(handles: List<String>, invite: List<String>) {
        if (!on()) return
        sharer.invitees(invite)
        focus.subscribe(handles)
    }

    // --- the settings ---------------------------------------------------------------------------

    suspend fun setProfilesIn(on: Boolean) = setFlag { it.copy(profilesIn = on) }

    suspend fun setProfileShare(on: Boolean) = setFlag { it.copy(profileShare = on) }

    suspend fun setFocusIn(enabled: Boolean) {
        if (!enabled) focus.stop()
        // Turned on again: Apple is asked again.
        setFlag { it.copy(focusIn = enabled, focusUnavailable = if (enabled) false else it.focusUnavailable) }
    }

    suspend fun setFocusShare(enabled: Boolean) {
        val was = flag().focusShare
        setFlag { it.copy(focusShare = enabled) }
        if (was && !enabled) {
            dnd?.stop()
            dnd = null
            sharer.withdraw()
        } else if (enabled) {
            watchDnd()
        }
    }

    suspend fun setWindow(window: String) = setFlag { it.copy(window = window) }

    /** "Import Now". */
    fun importNow(): Boolean {
        val f = flag()
        return cloud.start(CloudImport.sinceOf(f.window, now()))
    }

    /** Whether this phone is in the account's iCloud Keychain already (as asked at this start, or joined since). */
    fun joined(): Boolean = on() && account.inClique

    /**
     * Messages in iCloud on again on a phone in the keychain already ([joined]): only the setting and
     * an import, with no keychain join (nothing rebuilt, no new escrow record). False when not.
     */
    suspend fun turnOnAgain(): Boolean {
        if (!joined() || flag().history == AccountFlag.HISTORY_JOINING) return false
        setFlag { it.copy(history = AccountFlag.HISTORY_ON) }
        importNow()
        return flag().history == AccountFlag.HISTORY_ON
    }

    /**
     * Runs [block] (a step a settings page asked for) in these features' own scope: the page going
     * (Back, a rotation) never cuts it short; it only stops waiting for it.
     */
    suspend fun <T> work(block: suspend () -> T): T = scope.async { block() }.await()

    /** Messages in iCloud off: the import stops and its tokens go; what was imported stays. */
    suspend fun historyOff() {
        cloud.forget()
        setFlag { it.copy(history = AccountFlag.HISTORY_OFF) }
    }

    private fun watchDnd() {
        if (!on() || !flag().focusShare || dnd != null) return
        dnd = watcher { onDnd -> scope.launch { sharer.dndChanged(onDnd) } }
        try {
            dnd?.start()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Do Not Disturb is not watched (${e::class.java.simpleName})")
            dnd = null
        }
    }

    /** Changes the account's flag (only while signed in: there is nothing to change otherwise). */
    private suspend fun setFlag(change: (AccountFlag) -> AccountFlag) {
        lock.withLock {
            val f = files.readFlag()
            if (f.state == FlagState.NONE) return
            files.writeFlag(change(f))
        }
        account.featuresChanged()
    }

    companion object {
        private const val TAG = "AccountFeatures"

        /** Tests: the features the screens use instead of the engine's. */
        @Volatile
        internal var testInstance: AccountFeatures? = null

        /** The engine's (":imessage" process only). */
        fun of(context: android.content.Context): AccountFeatures = testInstance ?: DirectEngine.get(context).features
    }
}
