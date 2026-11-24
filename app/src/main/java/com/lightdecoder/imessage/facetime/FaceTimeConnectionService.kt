package com.lightdecoder.imessage.facetime

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.annotation.RequiresApi
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.Handles
import java.util.concurrent.Executor

/**
 * LightDecoder's own self-managed ConnectionService (SPEC2 B3.3), in the ":imessage" process where
 * the calls are. Only Telecom can bind it (it is exported, as Android requires, under
 * BIND_TELECOM_CONNECTION_SERVICE, which only the system holds). It makes Telecom's call for a
 * FaceTime call the call engine handed over and is waiting for ([TelecomCalls.attach]); any other
 * request gets a failed call. Every callback is Throwable-safe: nothing here may take the process down.
 */
class FaceTimeConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        create(request, incoming = true)

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        create(request, incoming = false)

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        runCatching { telecom()?.failed(guidOf(request)) }
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        runCatching { telecom()?.failed(guidOf(request)) }
    }

    private fun create(request: ConnectionRequest?, incoming: Boolean): Connection = try {
        val guid = guidOf(request)
        val calls = telecom()
        val connection = FaceTimeConnection(guid, calls)
        if (calls == null || guid == null || !calls.attach(guid, incoming, connection)) {
            Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        } else {
            connection.setUp(incoming, request)
            connection
        }
    } catch (e: Throwable) {
        FaceTimeLog.note("Telecom's call could not be made (${e::class.java.simpleName})")
        Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    }

    private fun telecom(): TelecomCalls? = FaceTimeCalls.peek()?.telecom

    companion object {
        /** The call's guid in the extras handed to Telecom (and back in its request). */
        const val EXTRA_GUID = "com.lightdecoder.facetime.extra.TELECOM_GUID"

        /** The call's guid a request of Telecom's carries, only a guid's form. */
        internal fun guidOf(request: ConnectionRequest?): String? = stringOf(request, EXTRA_GUID)?.takeIf(CallIds::isGuid)

        /** [key] of a request's extras (or of the ones nested in them, as Telecom may hand them back). */
        internal fun stringOf(request: ConnectionRequest?, key: String): String? {
            val extras = request?.extras ?: return null
            val nested = listOfNotNull(
                extras,
                runCatching { extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) }.getOrNull(),
                runCatching { extras.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) }.getOrNull(),
            )
            return nested.firstNotNullOfOrNull { b -> runCatching { b.getString(key) }.getOrNull()?.takeIf { it.length <= 512 } }
        }
    }
}

/** Telecom's call of one FaceTime call: what Telecom asks goes to [calls] ([TelecomCalls]); nothing here answers or places by itself. */
class FaceTimeConnection(private val guid: String?, private val calls: TelecomCalls?) : Connection(), TelecomCalls.Line {

    fun setUp(incoming: Boolean, request: ConnectionRequest?) {
        connectionProperties = PROPERTY_SELF_MANAGED
        // No holding (a FaceTime call cannot be held: Telecom's hold ends it) and no conference.
        connectionCapabilities = 0
        audioModeIsVoip = true
        val video = request?.videoState?.let { VideoProfile.isVideo(it) } == true
        videoState = if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        // A call handed over unnamed (a group call placed here) shows no address, whatever placeCall's needed URI was.
        val unnamed = FaceTimeConnectionService.stringOf(request, TelecomAccount.EXTRA_UNNAMED) != null
        request?.address?.takeIf { !unnamed }?.let { setAddress(it, TelecomManager.PRESENTATION_ALLOWED) }
            ?: setAddress(null, TelecomManager.PRESENTATION_RESTRICTED)
        val name = FaceTimeConnectionService.stringOf(request, TelecomAccount.EXTRA_NAME)?.let(CallIds::name)
        if (name.isNullOrBlank()) setCallerDisplayName(null, TelecomManager.PRESENTATION_RESTRICTED) else setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        if (incoming) setRinging() else setDialing()
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            FaceTimeLog.note("a Telecom callback failed (${e::class.java.simpleName})")
        }
    }

    // --- Telecom asks ----------------------------------------------------------------------------

    override fun onShowIncomingCallUi() {
        // LightDecoder's own ringing notice is up already (posted as the call began to ring).
    }

    override fun onAnswer() = safely { calls?.answered(guid) }

    override fun onAnswer(videoState: Int) = safely { calls?.answered(guid) }

    override fun onReject() = safely { calls?.rejected(guid) }

    override fun onReject(rejectReason: Int) = safely { calls?.rejected(guid) }

    override fun onDisconnect() = safely { calls?.disconnected(guid) }

    override fun onAbort() = safely { calls?.disconnected(guid) }

    override fun onHold() = safely { calls?.held(guid) }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState?) = safely {
        if (state == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@safely
        calls?.audioChanged(guid, TelecomAccount.routesOf(state.supportedRouteMask), TelecomAccount.routeOf(state.route))
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(endpoints: MutableList<CallEndpoint>) = safely {
        available = endpoints.toList()
        calls?.audioChanged(guid, available.mapNotNull { TelecomAccount.routeOfEndpoint(it.endpointType) }.distinct(), current?.let { TelecomAccount.routeOfEndpoint(it.endpointType) })
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(endpoint: CallEndpoint) = safely {
        current = endpoint
        calls?.audioChanged(guid, available.mapNotNull { TelecomAccount.routeOfEndpoint(it.endpointType) }.distinct(), TelecomAccount.routeOfEndpoint(endpoint.endpointType))
    }

    @Volatile
    private var available: List<CallEndpoint> = emptyList()

    @Volatile
    private var current: CallEndpoint? = null

    // --- the call engine tells ---------------------------------------------------------------------

    override fun markActive() {
        setActive()
    }

    override fun end(cause: TelecomCalls.EndCause) {
        setDisconnected(DisconnectCause(TelecomAccount.causeCode(cause)))
        destroy()
    }

    override fun moveTo(route: Route): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val endpoint = available.firstOrNull { TelecomAccount.routeOfEndpoint(it.endpointType) == route } ?: return false
            requestCallEndpointChange(endpoint, Executor { it.run() }) { }
            return true
        }
        @Suppress("DEPRECATION")
        setAudioRoute(TelecomAccount.audioRouteOf(route))
        return true
    }
}

/**
 * LightDecoder's calling account with Telecom ("LightDecoder", self-managed, video calling), and the
 * real [TelecomCalls.Platform]. Registered in the ":imessage" process while FaceTime and "Use with
 * Bluetooth and car" are both on; unregistered when either is turned off (the kill switch). The
 * system's call log is never asked to keep these calls.
 */
object TelecomAccount {
    const val EXTRA_NAME = "com.lightdecoder.facetime.extra.TELECOM_NAME"

    /** Present when the call is handed over with no address (a group call placed here): Telecom's call shows none. */
    const val EXTRA_UNNAMED = "com.lightdecoder.facetime.extra.TELECOM_UNNAMED"

    /** placeCall needs a URI: a group call placed here gets this one, which names nobody. */
    internal const val UNNAMED_PART = "group"
    private const val ID = "facetime"

    /** Whether this phone has Telecom's calling accounts for apps (connection services) at all. */
    fun supported(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)
    }.getOrDefault(false)

    /** Whether calls are to be handed to Telecom now: both settings on, and Telecom there. */
    fun wanted(context: Context): Boolean = DirectPrefs.faceTimeOn(context) && DirectPrefs.telecomOn(context) && supported(context)

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, FaceTimeConnectionService::class.java), ID)

    private fun manager(context: Context): TelecomManager? = runCatching { context.getSystemService(TelecomManager::class.java) }.getOrNull()

    /** Registers the account (the settings on) or takes it away (either off). Never throws. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val tm = manager(app) ?: return
        try {
            if (wanted(app)) {
                val account = PhoneAccount.builder(handle(app), app.getString(R.string.facetime_telecom_account))
                    .setCapabilities(
                        PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING or PhoneAccount.CAPABILITY_VIDEO_CALLING,
                    )
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
                    .build()
                tm.registerPhoneAccount(account)
            } else {
                tm.unregisterPhoneAccount(handle(app))
            }
        } catch (e: Throwable) {
            FaceTimeLog.note("LightDecoder's calling account could not be ${if (wanted(app)) "registered" else "taken away"} with Telecom (${e::class.java.simpleName})")
        }
    }

    /** What Telecom is given of a call's address: a phone number as one, an email address as a SIP address (Telecom's only scheme for it). */
    fun uriOf(address: String?): Uri? {
        val h = CallIds.handle(address) ?: return null
        return if (h.startsWith("tel:")) Uri.fromParts(PhoneAccount.SCHEME_TEL, Handles.display(h), null) else Uri.fromParts(PhoneAccount.SCHEME_SIP, Handles.display(h), null)
    }

    /** The real platform: Telecom's own calls, on LightDecoder's account. */
    class Real(context: Context) : TelecomCalls.Platform {
        private val app = context.applicationContext

        private fun extras(ask: TelecomCalls.Ask): Bundle = Bundle().apply {
            putString(FaceTimeConnectionService.EXTRA_GUID, ask.guid)
            ask.name?.let { putString(EXTRA_NAME, it) }
            if (ask.address == null) putString(EXTRA_UNNAMED, "1")
        }

        override fun addIncoming(ask: TelecomCalls.Ask): Boolean {
            val tm = manager(app) ?: return false
            sync(app)
            val extras = extras(ask).apply {
                uriOf(ask.address)?.let { putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, it) }
                putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, if (ask.video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
            }
            tm.addNewIncomingCall(handle(app), extras)
            return true
        }

        @android.annotation.SuppressLint("MissingPermission") // MANAGE_OWN_CALLS is declared: enough for a self-managed account's own calls
        override fun placeOutgoing(ask: TelecomCalls.Ask): Boolean {
            val tm = manager(app) ?: return false
            val uri = if (ask.address == null) Uri.fromParts(PhoneAccount.SCHEME_SIP, UNNAMED_PART, null) else uriOf(ask.address) ?: return false
            sync(app)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(app))
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, extras(ask))
                putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (ask.video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
            }
            tm.placeCall(uri, extras)
            return true
        }
    }

    // --- routes and causes, as Telecom has them --------------------------------------------------

    fun routeOf(audioRoute: Int): Route? = when (audioRoute) {
        CallAudioState.ROUTE_EARPIECE -> Route.EARPIECE
        CallAudioState.ROUTE_SPEAKER -> Route.SPEAKER
        CallAudioState.ROUTE_WIRED_HEADSET -> Route.WIRED
        CallAudioState.ROUTE_BLUETOOTH -> Route.BLUETOOTH
        else -> null
    }

    fun routesOf(mask: Int): List<Route> = listOf(
        CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_WIRED_HEADSET, CallAudioState.ROUTE_BLUETOOTH,
    ).filter { mask and it != 0 }.mapNotNull(::routeOf)

    fun audioRouteOf(route: Route): Int = when (route) {
        Route.EARPIECE -> CallAudioState.ROUTE_EARPIECE
        Route.SPEAKER -> CallAudioState.ROUTE_SPEAKER
        Route.WIRED -> CallAudioState.ROUTE_WIRED_HEADSET
        Route.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
    }

    /** [CallEndpoint]'s types (Android 14), by value: earpiece 1, Bluetooth 2, wired 3, speaker 4. */
    fun routeOfEndpoint(type: Int): Route? = when (type) {
        1 -> Route.EARPIECE
        2 -> Route.BLUETOOTH
        3 -> Route.WIRED
        4 -> Route.SPEAKER
        else -> null
    }

    fun causeCode(cause: TelecomCalls.EndCause): Int = when (cause) {
        TelecomCalls.EndCause.LOCAL -> DisconnectCause.LOCAL
        TelecomCalls.EndCause.REMOTE -> DisconnectCause.REMOTE
        TelecomCalls.EndCause.MISSED -> DisconnectCause.MISSED
        TelecomCalls.EndCause.REJECTED -> DisconnectCause.REJECTED
        TelecomCalls.EndCause.CANCELED -> DisconnectCause.CANCELED
        TelecomCalls.EndCause.ANSWERED_ELSEWHERE -> DisconnectCause.ANSWERED_ELSEWHERE
        TelecomCalls.EndCause.ERROR -> DisconnectCause.ERROR
    }
}
