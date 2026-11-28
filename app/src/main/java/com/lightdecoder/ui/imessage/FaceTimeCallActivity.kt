package com.lightdecoder.ui.imessage

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Rational
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityFacetimeCallBinding
import com.lightdecoder.imessage.direct.DirectPaths
import com.lightdecoder.imessage.direct.DirectService
import com.lightdecoder.imessage.direct.Handles
import com.lightdecoder.imessage.facetime.Call
import com.lightdecoder.imessage.facetime.CallIds
import com.lightdecoder.imessage.facetime.CallLabels
import com.lightdecoder.imessage.facetime.CallMessage
import com.lightdecoder.imessage.facetime.CallSurfaces
import com.lightdecoder.imessage.facetime.CallerInfo
import com.lightdecoder.imessage.facetime.FaceTimeActionReceiver
import com.lightdecoder.imessage.facetime.FaceTimeCalls
import com.lightdecoder.imessage.facetime.FaceTimeCamera
import com.lightdecoder.imessage.facetime.FaceTimeLog
import com.lightdecoder.imessage.facetime.FaceTimeNotices
import com.lightdecoder.imessage.facetime.Media
import com.lightdecoder.imessage.facetime.MAX_PEOPLE
import com.lightdecoder.imessage.facetime.Phase
import com.lightdecoder.imessage.facetime.PipParams
import com.lightdecoder.imessage.facetime.Refusal
import com.lightdecoder.imessage.facetime.Route
import com.lightdecoder.imessage.facetime.TileLayout
import com.lightdecoder.imessage.facetime.Unanswered
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.applySystemBarPadding
import com.lightdecoder.ui.chat.Avatars
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The FaceTime call screen, in the ":imessage" process (where the call is): a call that rings
 * here (Accept, Decline, and "Audio only" for a video call), one the user placed ("Calling…"),
 * one being joined, one that is on (the other side's picture full screen, the user's own camera in
 * a small rounded view, Mute, Camera, Flip, Speaker, End, the time), and a placed one nobody took
 * ("No answer", Call Again). It shows what [FaceTimeCalls] says and hands it the user's taps;
 * nothing of a call is decided here.
 *
 * - Only the user's tap places or answers a call, and the microphone (always) and the camera (for
 *   video) are asked for first: without the microphone nothing is placed or answered, and it says
 *   so; without the camera the call is audio only, and it says so. An intent to place or answer is
 *   acted on once, when it is new (never one the system brings back from the recent screens).
 * - It shows over the lock screen and turns the screen on, as a phone call does. It shows the call
 *   only: "Message" (the chat with this person) asks for the unlock first, and so does everything
 *   that places a call ("Call Again" too): a ringing call can be answered on a locked phone, none placed.
 * - While the screen is not in sight (another app, Home, the phone locked and dark) the camera does
 *   not send this phone's picture; it does again when the screen is back, if it was on.
 * - Leaving the screen (Back, the arrow) leaves the call on, under its notice; it comes back from there.
 *   A video call that is on, with a picture of the other side, floats on in a picture-in-picture
 *   window instead (Home, Back, "Message"), with Mute, Camera Off and End: its camera goes on
 *   sending there (as iPhone's), but stops at once when the screen goes off.
 * - Someone's shared screen is shown whole, as the big picture; the other side's camera goes small.
 * - A group call's people each have a tile (their camera, or their name): a tile tapped is big. Only
 *   those who joined as Apple vouches for are named as the user knows them (and pictured); anyone
 *   else is shown by the address the call reports, marked as not verified. The call's name (its
 *   caller's, "· Group FaceTime", for one rung here) opens who is in it and who was invited, each of
 *   those rung again with a tap of theirs; nobody can be added (a new call with everyone is).
 * - A group ring from someone the user does not know offers Ignore (nothing is sent) and is
 *   answered without the camera.
 *
 * Not exported; started by the chat screens (main process), the call notices, and nothing else.
 */
class FaceTimeCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFacetimeCallBinding
    private val calls: FaceTimeCalls by lazy { FaceTimeCalls.get(this) }

    /** What waits for the permissions' answer. */
    private sealed class Pending {
        data class Place(
            val peer: String,
            val name: String,
            val video: Boolean,
            val from: String?,
            val conversationId: String?,
            val photo: String?,
            val peers: List<String> = listOf(peer),
        ) : Pending()
        data class Answer(val guid: String, val withCamera: Boolean) : Pending()
    }

    /** In a picture-in-picture window now. */
    @VisibleForTesting
    internal var inPip = false
        private set

    /** The screen went off while it floated: its camera was paused for it ([screenOff]). */
    private var pausedForScreenOff = false
    private var screenOff: BroadcastReceiver? = null

    /** What the window's buttons and shape were last set to ([updatePip]), so they are set again only when they change. */
    private var pipSet: Any? = null

    /** The group call's tiles, by participant id: their views, and which is big ([focused]). */
    private val tileViews = LinkedHashMap<Long, Tile>()
    private var focused: Long? = null
    private var screenSurface: Surface? = null

    /** The participant sheet, while it is open. */
    @VisibleForTesting
    internal var sheet: ParticipantSheet? = null
        private set

    private var pending: Pending? = null

    /** The last call placed from this screen, for "Call Again". */
    private var lastPlaced: Pending.Place? = null

    /** A call was shown here: once there is none, the screen goes. */
    private var hadCall = false

    /** Something is said that the user must be able to read: the screen stays though there is no call. */
    private var holdingNotice = false

    /** A call was asked of the call engine (placed, or answered) and its answer has not shown yet: the screen waits for it. */
    private var awaiting: Job? = null

    /** What to do once the screen is in front (a call is placed or answered only from a screen in front). */
    private var whenInFront: (() -> Unit)? = null

    private var remoteSurface: Surface? = null
    private var previewSurface: Surface? = null

    /**
     * The other side's picture and a shared screen, each turned upright by its stream, in its own
     * shape, with the user's pinch, drag and double tap (a group call's tiles have one each too).
     */
    private val remoteFit by lazy { FittedVideo(binding.remoteVideo, noted = FaceTimeLog::note) }
    private val screenFit by lazy { FittedVideo(binding.screenVideo, fixedFill = false) }

    /** How the camera in use is mounted, by side (read once). */
    private val sensors = HashMap<Boolean, Int?>()
    private var ticker: Job? = null

    /** What the screen shows now. */
    @VisibleForTesting
    internal var shown: Pair<Call?, Media> = null to Media()
        private set

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionsAnswered(result)
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.setCamera(true) else say(getString(R.string.facetime_camera_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        binding = ActivityFacetimeCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.content)
        // The session's service is what hands the call's row and log lines to the main process.
        DirectService.start(this)

        binding.accept.setOnClickListener { shown.first?.let { answer(it.guid, withCamera = it.video && !it.ignoreOnly) } }
        binding.audioOnly.setOnClickListener { shown.first?.let { answer(it.guid, withCamera = false) } }
        binding.decline.setOnClickListener { shown.first?.let { calls.decline(it.guid) } }
        binding.end.setOnClickListener { calls.hangUp(shown.first?.guid) }
        binding.mute.setOnClickListener { calls.setMuted(!calls.media.value.muted) }
        binding.camera.setOnClickListener { toggleCamera() }
        binding.flip.setOnClickListener { calls.flipCamera() }
        binding.speaker.setOnClickListener { chooseRoute(it) }
        binding.close.setOnClickListener { leave() }
        binding.callAgain.setOnClickListener { callAgain() }
        binding.minimize.setOnClickListener { stepBack() }
        binding.message.setOnClickListener { shown.first?.conversationId?.let(::openChat) }
        binding.openSettings.setOnClickListener { openAppSettings() }
        binding.remoteVideo.surfaceTextureListener = remoteListener
        binding.localPreview.surfaceTextureListener = previewListener
        setUpRemoteGestures()
        binding.screenVideo.surfaceTextureListener = screenListener
        // A group call's name opens who is in it (as iPhone's call info).
        binding.info.setOnClickListener { if (shown.first?.let { it.group && it.joined } == true) openPeople() }
        dragPreview()
        binding.tiles.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) layoutTiles() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = stepBack()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(calls.state, calls.media) { call, media -> call to media }.collect { (call, media) -> render(call, media) }
            }
        }
        // A group call's tiles follow what the library asks for and who it says is in the call, with what is known of them.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(calls.surfaces.requested, calls.labels) { _, _ -> Unit }.collect { render(calls.state.value, calls.media.value) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { calls.messages.collect(::onMessage) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { calls.waiting.collect(::renderWaiting) }
        }
        binding.waitingDecline.setOnClickListener { calls.waiting.value?.let { calls.decline(it.guid) } }
        binding.waitingAccept.setOnClickListener { calls.waiting.value?.let { answer(it.guid, withCamera = it.video) } }
        take(intent, fresh = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent, fresh = true)
    }

    override fun onStart() {
        super.onStart()
        tellRotation()
        // Back in sight: a camera that was paused while it was not is sent again. Not the floating
        // window come back over the lock screen: its camera waits for the unlock (ACTION_USER_PRESENT).
        if (inPip && (pausedForScreenOff || locked())) pausedForScreenOff = true else calls.screenShown(this)
    }

    /**
     * No longer in sight (another app in front, Home, or the phone locked with the screen off): the
     * camera stops sending this phone's picture until the screen is back ([onStart]). The call and
     * its sound go on, under the in-call notice.
     */
    override fun onStop() {
        calls.screenHidden(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        whenInFront?.let { action ->
            whenInFront = null
            action()
        }
    }

    /** Runs [action] now if the screen is in front, else as soon as it is. */
    private fun inFront(action: () -> Unit) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action() else whenInFront = action
    }

    /** The call engine was asked for a call: the screen stays until it shows (or says why not), a few seconds at most. */
    private fun await() {
        awaiting?.cancel()
        awaiting = lifecycleScope.launch {
            delay(AWAIT_MS)
            awaiting = null
            if (calls.state.value == null && pending == null && !holdingNotice) finishCall()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tellRotation()
        binding.root.post {
            fitRemote()
            fitPreview()
            fitScreen()
        }
    }

    /** Home pressed, or another app came in front, by the user: a video call that is on floats on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipChanged(isInPictureInPictureMode)
    }

    /** In or out of the window: the screen's buttons and texts go or come back, and the screen going off is watched while it floats. */
    @VisibleForTesting
    internal fun pipChanged(floating: Boolean) {
        inPip = floating
        // The window shows each picture whole in its shape, as the user left it unzoomed.
        if (floating) {
            remoteFit.zoom.reset()
            screenFit.zoom.reset()
            tileViews.values.forEach { it.video?.zoom?.reset() }
        }
        if (floating) watchScreenOff() else stopWatchingScreenOff()
        // Opened out to the whole screen (the user's own tap): a camera paused for the screen going off is back with it.
        if (!floating && pausedForScreenOff) {
            pausedForScreenOff = false
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) calls.screenShown(this)
        }
        // The small picture of the user's own camera is not in the window: the camera goes on into the call only, and is back with the screen.
        previewSurface?.let { surface -> if (floating) calls.detachPreview(surface) {} else calls.attachPreview(surface) }
        sheet?.dismiss()
        render(calls.state.value, calls.media.value)
    }

    override fun onDestroy() {
        stopWatchingScreenOff()
        sheet?.dismiss()
        ticker?.cancel()
        // A placed call's "no answer" goes with its screen.
        if (isFinishing && calls.state.value?.phase == Phase.UNANSWERED) calls.hangUp()
        super.onDestroy()
    }

    /** As a phone call: over the lock screen, the screen turned on. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    // --- what brought the screen up ----------------------------------------------------------------

    /**
     * An intent of the app's own: to place a call, to answer the ringing one, or only to show the
     * call there is. Placing and answering happen once, for an intent that is [fresh] (not one a
     * recreated screen still holds) and not brought back from the recent screens; a call to place
     * must also have been asked for just now.
     */
    private fun take(intent: Intent?, fresh: Boolean) {
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (intent != null && fresh && !fromHistory) {
            when (intent.action) {
                ACTION_PLACE -> inFront { placeFrom(intent) }
                ACTION_ANSWER -> intent.getStringExtra(EXTRA_GUID)?.takeIf(CallIds::isGuid)?.let { guid ->
                    val withCamera = intent.getBooleanExtra(EXTRA_WITH_CAMERA, false)
                    inFront { answer(guid, withCamera) }
                }
            }
        }
        // Nothing to show and nothing asked for: no screen.
        if (calls.state.value == null && pending == null && !holdingNotice && awaiting == null && whenInFront == null) finishCall()
    }

    private fun placeFrom(intent: Intent) {
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        if (at <= 0L || kotlin.math.abs(SystemClock.elapsedRealtime() - at) > PLACE_FRESH_MS) {
            if (calls.state.value == null) finishCall()
            return
        }
        fun callable(raw: String) = CallIds.handle(raw) ?: Handles.normalize(raw)?.let(CallIds::handle)
        // A group call: everyone it goes to (each once, a group call's worth at most), the first first.
        val peers = (listOfNotNull(intent.getStringExtra(EXTRA_PEER)) + intent.getStringArrayListExtra(EXTRA_PEERS).orEmpty().take(MAX_PEOPLE))
            .mapNotNull(::callable).distinctBy(CallIds::key).take(MAX_PEOPLE)
        val peer = peers.firstOrNull()
        if (peer == null) {
            refuse(Refusal.NOT_CALLABLE)
            return
        }
        val request = Pending.Place(
            peer = peer,
            name = CallIds.name(intent.getStringExtra(EXTRA_NAME)),
            video = intent.getBooleanExtra(EXTRA_VIDEO, false),
            from = intent.getStringExtra(EXTRA_FROM)?.let(CallIds::handle),
            conversationId = intent.getStringExtra(EXTRA_CONVERSATION)?.takeIf { it.length <= MAX_ID_CHARS },
            // A picture only from the app's own files.
            photo = intent.getStringExtra(EXTRA_PHOTO)?.takeIf { DirectPaths.isAllowed(it, DirectPaths.forApp(this)) },
            peers = peers,
        )
        place(request)
    }

    // --- placing and answering: permissions first --------------------------------------------------

    private fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** The permissions still to ask for: the microphone always, the camera for video. */
    private fun missing(video: Boolean): Array<String> = listOfNotNull(
        Manifest.permission.RECORD_AUDIO.takeIf { !granted(it) },
        Manifest.permission.CAMERA.takeIf { video && !granted(it) && hasCamera() },
    ).toTypedArray()

    private fun hasCamera(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * Whether the phone is locked: this screen shows over the lock screen (a call rings there), but
     * nothing places a call from there.
     */
    private fun locked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Runs [action] at once on an unlocked phone; on a locked one only once the user has unlocked it (never, if they do not). */
    private fun afterUnlock(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            action()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (!isFinishing && !isDestroyed) action()
                }

                override fun onDismissCancelled() = notUnlocked()

                override fun onDismissError() = notUnlocked()

                /** Not unlocked: nothing is placed, and a screen with nothing to show goes. */
                private fun notUnlocked() {
                    if (calls.state.value == null && pending == null && !holdingNotice && awaiting == null) finishCall()
                }
            },
        )
    }

    /** A call to place: never from a locked phone (the unlock comes first), whatever asked for it. */
    private fun place(request: Pending.Place) {
        if (locked()) {
            afterUnlock { place(request) }
            return
        }
        if (calls.busy) {
            refuse(Refusal.IN_CALL)
            return
        }
        clearNotice()
        val ask = missing(request.video)
        if (ask.isEmpty()) {
            placeNow(request)
        } else {
            pending = request
            showAsking(request.name.ifBlank { Handles.display(request.peer) }, request.peer, request.photo)
            askPermissions.launch(ask)
        }
    }

    private fun placeNow(request: Pending.Place) {
        // Without the camera (none, or not allowed), an audio call.
        val video = request.video && hasCamera() && granted(Manifest.permission.CAMERA)
        if (request.video && !video) say(getString(R.string.facetime_camera_denied))
        lastPlaced = request
        await()
        calls.place(request.peer, request.name, video, request.from, request.conversationId, request.photo, request.peers)
    }

    /** Someone calling while the user is in a call ([FaceTimeCalls.waiting]): who, over the call, with "End & Accept" and Decline. */
    private fun renderWaiting(call: Call?) {
        binding.waitingCard.visibility = if (call == null) View.GONE else View.VISIBLE
        if (call == null) return
        binding.waitingName.text = FaceTimeNotices.nameOf(call)
        binding.waitingKind.text = getString(R.string.facetime_waiting_kind, FaceTimeNotices.kind(this, call.video))
    }

    private fun answer(guid: String, withCamera: Boolean) {
        // The ringing call, or the one that waits while the user is in another (answered, that one ends).
        val call = calls.waiting.value?.takeIf { it.guid == guid } ?: calls.state.value
        if (call == null || call.guid != guid || call.phase != Phase.RINGING_IN) {
            // Answered from a notice that outlived its call.
            if (call == null) refuse(Refusal.GONE)
            return
        }
        clearNotice()
        val ask = missing(withCamera && call.video)
        if (ask.isEmpty()) {
            answerNow(guid, withCamera)
        } else {
            pending = Pending.Answer(guid, withCamera)
            askPermissions.launch(ask)
        }
    }

    private fun answerNow(guid: String, withCamera: Boolean) {
        val camera = withCamera && hasCamera() && granted(Manifest.permission.CAMERA)
        if (withCamera && !camera && calls.state.value?.video == true) say(getString(R.string.facetime_camera_denied))
        calls.answer(guid, camera)
    }

    /** What the screen is asked to do next, for tests: place or answer once Android's permission question is answered. */
    @VisibleForTesting
    internal val waitingForPermissions: Boolean get() = pending != null

    /** The user answered Android's question(s). Without the microphone nothing is placed or answered. */
    private fun permissionsAnswered(@Suppress("UNUSED_PARAMETER") result: Map<String, Boolean>) {
        val waiting = pending ?: return
        pending = null
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            say(getString(R.string.facetime_mic_needed), settings = true, hold = waiting is Pending.Place)
            // A ringing call goes on ringing: it can still be declined, or answered once allowed.
            render(calls.state.value, calls.media.value)
            return
        }
        when (waiting) {
            // Locked while Android asked: the unlock first.
            is Pending.Place -> if (locked()) afterUnlock { place(waiting) } else placeNow(waiting)
            is Pending.Answer -> answerNow(waiting.guid, waiting.withCamera)
        }
    }

    private fun toggleCamera() {
        val on = !calls.media.value.cameraOn
        if (on && !hasCamera()) {
            say(getString(R.string.facetime_camera_unavailable))
            return
        }
        if (on && !granted(Manifest.permission.CAMERA)) {
            askCamera.launch(Manifest.permission.CAMERA)
            return
        }
        calls.setCamera(on)
    }

    private fun callAgain() {
        // Over the lock screen "No answer" may show, but a call is not placed from there: the unlock first.
        if (locked()) {
            afterUnlock { callAgain() }
            return
        }
        val again = lastPlaced ?: shown.first?.takeIf { it.outgoing }?.let { Pending.Place(it.peer, it.name, it.video, it.from, it.conversationId, it.photo, it.peers) } ?: return
        hadCall = false
        holdingNotice = true
        calls.hangUp(shown.first?.guid)
        // Once the "no answer" is gone.
        binding.root.postDelayed({ if (!isFinishing && !calls.busy) place(again) }, CALL_AGAIN_DELAY_MS)
    }

    // --- what the call engine says -----------------------------------------------------------------

    private fun onMessage(message: CallMessage) {
        when (message) {
            is CallMessage.NotStarted -> refuse(message.why)
            CallMessage.CameraUnavailable -> say(getString(R.string.facetime_camera_unavailable))
            // The screen goes with the call: said where it is seen all the same.
            CallMessage.EndedForPhoneCall -> Toast.makeText(applicationContext, R.string.facetime_ended_for_phone_call, Toast.LENGTH_LONG).show()
        }
    }

    private fun refuse(why: Refusal) {
        val text = getString(
            when (why) {
                Refusal.OFF -> R.string.facetime_refused_off
                Refusal.IN_CALL -> R.string.facetime_refused_in_call
                Refusal.PHONE_BUSY -> R.string.facetime_refused_phone_busy
                Refusal.NOT_CALLABLE -> R.string.facetime_refused_not_callable
                Refusal.NOT_CONNECTED -> R.string.facetime_refused_not_connected
                Refusal.GONE -> R.string.facetime_refused_gone
            },
        )
        awaiting?.cancel()
        awaiting = null
        if (calls.state.value == null) {
            // Nothing to show it on: said, and the screen goes.
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            holdingNotice = false
            finishCall()
        } else {
            say(text)
        }
    }

    /** Says [text] on the screen; with [settings], a button to the app's settings. [hold]: the screen stays for it though there is no call. */
    private fun say(text: String, settings: Boolean = false, hold: Boolean = false) {
        binding.notice.text = text
        binding.notice.visibility = View.VISIBLE
        binding.openSettings.visibility = if (settings) View.VISIBLE else View.GONE
        if (hold) {
            holdingNotice = true
            binding.unanswered.visibility = View.VISIBLE
            binding.callAgain.visibility = View.GONE
        }
    }

    private fun clearNotice() {
        holdingNotice = false
        binding.notice.visibility = View.GONE
        binding.openSettings.visibility = View.GONE
    }

    /** Who a call to be placed is to, while Android asks for the permissions. */
    private fun showAsking(name: String, peer: String, photo: String?) {
        Avatars.bind(binding.avatar, name, peer, "", photo?.let(::File))
        binding.name.text = name
        binding.status.text = ""
    }

    @VisibleForTesting
    internal fun render(call: Call?, media: Media) {
        shown = call to media
        if (call == null) {
            ticker?.cancel()
            if (hadCall && pending == null && !holdingNotice && awaiting == null) finishCall()
            return
        }
        hadCall = true
        awaiting?.cancel()
        awaiting = null
        if (holdingNotice && call.live) clearNotice()
        // A group call rung here is its caller's ("Alice · Group FaceTime"); a person's call, theirs.
        val name = FaceTimeNotices.title(this, call)
        val address = Handles.display(call.peer)
        // A group call's people, each in a tile.
        val tileIds = if (call.group && call.joined) tileIds(call, media) else emptyList()
        renderTiles(call, media, tileIds)
        val grid = tileIds.isNotEmpty()
        // The other side's picture, a shared screen, a group's tiles, or this phone's own camera, make it a screen of pictures.
        val pictures = call.joined && (media.remoteVideo || media.screenVideo || grid)
        Avatars.bind(binding.avatar, FaceTimeNotices.nameOf(call), call.peer, "", call.photo?.takeIf { DirectPaths.isAllowed(it, DirectPaths.forApp(this)) }?.let(::File))
        binding.name.text = name
        // Under a group ring's name, how many others its member list names (never who).
        val detail = if (call.group && !call.outgoing) FaceTimeNotices.othersText(this, call).orEmpty() else address
        binding.detail.text = detail
        binding.detail.visibility = if (detail.isNotEmpty() && detail != name && !pictures) View.VISIBLE else View.GONE
        binding.remoteVideo.contentDescription = getString(R.string.facetime_remote_video, name)
        binding.info.contentDescription = if (call.group && call.joined) getString(R.string.facetime_people_open) else null
        binding.info.isClickable = call.group && call.joined

        binding.backdrop.visibility = if (pictures) View.GONE else View.VISIBLE
        binding.scrimTop.visibility = if (pictures && !inPip) View.VISIBLE else View.GONE
        binding.scrimBottom.visibility = binding.scrimTop.visibility
        binding.avatar.visibility = if (pictures) View.GONE else View.VISIBLE
        (binding.info.layoutParams as FrameLayout.LayoutParams).topMargin = dp(if (pictures) 14 else 96)
        binding.info.requestLayout()
        binding.name.textSize = if (pictures) 20f else 30f
        // Always there (its Surface is ready before the camera starts), seen only with the camera on (not in the window).
        binding.previewCard.alpha = if (call.joined && media.cameraOn && !inPip) 1f else 0f
        // In the picture-in-picture window, the pictures only.
        binding.content.visibility = if (inPip) View.INVISIBLE else View.VISIBLE
        // A shared screen: the big picture, whole; the other side's camera small over it.
        val screen = call.joined && media.screenVideo
        binding.screenVideo.alpha = if (screen) 1f else 0f
        binding.screenVideo.contentDescription = getString(R.string.facetime_shared_screen)
        layoutRemote(small = screen && !call.group)
        binding.remoteVideo.alpha = if (screen && !media.remoteVideo) 0f else 1f
        if (screen) screenFit.fit(media.screen) else screenFit.forget()
        if (pictures) fitRemote() else remoteFit.forget()
        // The camera's picture is laid out for that side's camera once it is known.
        if (call.joined && media.cameraOn) fitPreview()

        val ringingIn = call.phase == Phase.RINGING_IN || call.phase == Phase.SCREENING
        binding.incoming.visibility = if (ringingIn) View.VISIBLE else View.GONE
        binding.audioOnly.visibility = if (ringingIn && call.video && !call.ignoreOnly) View.VISIBLE else View.GONE
        // A group ring from someone the user does not know: Ignore (nothing is sent), and Accept answers without the camera.
        binding.accept.setImageResource(if (call.video && !call.ignoreOnly) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
        val dismiss = getString(if (call.ignoreOnly) R.string.facetime_ignore else R.string.facetime_decline)
        binding.decline.contentDescription = dismiss
        binding.declineLabel.text = dismiss
        val held = call.phase == Phase.RINGING_OUT || call.joined
        binding.endColumn.visibility = if (held) View.VISIBLE else View.GONE
        binding.controls.visibility = if (call.phase == Phase.ACTIVE) View.VISIBLE else View.GONE
        binding.unanswered.visibility = if (call.phase == Phase.UNANSWERED) View.VISIBLE else View.GONE
        binding.callAgain.visibility = if (call.phase == Phase.UNANSWERED && call.outgoing) View.VISIBLE else View.GONE
        binding.message.visibility = if (call.conversationId != null) View.VISIBLE else View.GONE

        when (call.phase) {
            Phase.SCREENING, Phase.RINGING_IN -> setStatus(getString(if (call.video) R.string.facetime_incoming_video else R.string.facetime_incoming_audio))
            Phase.RINGING_OUT -> setStatus(getString(R.string.facetime_calling))
            Phase.CONNECTING -> setStatus(getString(R.string.facetime_connecting))
            Phase.UNANSWERED -> setStatus(
                getString(
                    when (call.unanswered) {
                        Unanswered.DECLINED -> R.string.facetime_declined
                        Unanswered.FAILED -> R.string.facetime_failed
                        else -> R.string.facetime_no_answer
                    },
                ),
            )
            Phase.ACTIVE -> tick(call)
        }
        if (call.phase != Phase.ACTIVE) ticker?.cancel()
        renderControls(media)
        binding.phoneBanner.visibility = if (media.phoneRinging && call.live && !call.incomingRing && !inPip) View.VISIBLE else View.GONE
        // The screen stays on while it rings and while there are pictures; an audio call's goes dark at the ear.
        val keepOn = ringingIn || call.phase == Phase.RINGING_OUT || call.phase == Phase.CONNECTING || (call.phase == Phase.ACTIVE && (pictures || media.cameraOn))
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updatePip(call, media)
        sheet?.update(call, calls.labels.value)
    }

    // --- a group call's tiles ----------------------------------------------------------------------

    /**
     * One person's tile: their picture's view while the library asks for their camera (none past the
     * camera tiles' cap), their name, and their initials (or, for someone who joined as Apple vouches
     * for, their contact photo) while there is no picture.
     */
    private class Tile(val frame: FrameLayout, var video: FittedVideo?, val label: android.widget.TextView, val initials: android.widget.TextView) {
        /** The Surface of [video]'s view, while the library has it. */
        var surface: Surface? = null

        /** The photo [initials] shows (null for initials only), so it is read once. */
        var photo: String? = null
    }

    /**
     * Who of the group call has a tile, in order: those the library asked to draw a camera for
     * ([TileLayout.MAX_CAMERA_TILES] at most, with a picture), then the others it says are in the
     * call (their name only), [MAX_TILES] in all.
     */
    private fun tileIds(call: Call, media: Media): List<Long> {
        val cameras = cameraIds().take(TileLayout.MAX_CAMERA_TILES)
        val others = call.inCall.map { it.id }.filter { it !in cameras }
        return (cameras + others).distinct().take(MAX_TILES).also { if (media.tiles.isEmpty() && it.isEmpty()) focused = null }
    }

    /** The tiles of [ids]: made for whoever is new, let go of for whoever is gone, named and laid out. */
    private fun renderTiles(call: Call, media: Media, ids: List<Long>) {
        binding.tiles.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        // Who gets a picture: those the library asks to draw a camera for now, the cap at most.
        val withVideo = cameraIds().filter { it in ids }.take(TileLayout.MAX_CAMERA_TILES).toSet()
        // Removing a tile's view tells the library first ([tileListener]), and only then lets go of its Surface.
        for (gone in tileViews.keys.filter { it !in ids }) {
            tileViews.remove(gone)?.let { binding.tiles.removeView(it.frame) }
        }
        if (focused != null && focused !in ids) focused = null
        // Pictures no longer asked for go first, so the cap holds for those that are.
        for ((id, tile) in tileViews) if (id !in withVideo) removeVideo(tile)
        for (id in ids) {
            val tile = tileViews[id] ?: makeTile(id).also {
                tileViews[id] = it
                binding.tiles.addView(it.frame)
            }
            if (id in withVideo && tile.video == null) addVideo(id, tile)
            val known = calls.labels.value
            val label = labelOf(id, call, known)
            tile.label.text = label
            // A contact photo only for someone who joined as Apple vouches for; else their initials.
            val photo = photoOf(id, call, known)
            // Read once (not again on every render, on the main thread); one that could not be read,
            // or none, shows the initials exactly as a new tile does.
            if (photo != null && photo != tile.photo) Avatars.bind(tile.initials, label, photo, "", File(photo))
            tile.photo = photo
            if (photo == null || tile.initials.background !is androidx.core.graphics.drawable.RoundedBitmapDrawable) plainInitials(tile, label)
            tile.initials.visibility = if (id in media.tiles) View.GONE else View.VISIBLE
            tile.frame.contentDescription = getString(R.string.facetime_tile, label)
            // Each tile's picture as the big one's: upright by its stream, in its own shape, filled or whole by shape, zoomed as the user did.
            tile.video?.let { v -> media.tiles[id]?.let(v::fit) ?: v.forget() }
        }
        layoutTiles()
    }

    /** [tile]'s initials alone, on no circle, as [makeTile] made them: whatever a photo's bind left is undone. */
    private fun plainInitials(tile: Tile, label: String) {
        tile.initials.background = null
        tile.initials.backgroundTintList = null
        tile.initials.setTextColor(ContextCompat.getColor(this, R.color.call_on_screen))
        tile.initials.text = Avatars.initials(label)
    }

    /** Who the library asks to draw a camera for, in its order. */
    private fun cameraIds(): List<Long> = calls.surfaces.requested.value
        .filter { it.second == CallSurfaces.STREAM_CAMERA || it.second == CallSurfaces.STREAM_CAMERA_ALTERNATE }
        .map { it.first }.distinct()

    private fun makeTile(id: Long): Tile {
        val frame = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@FaceTimeCallActivity, R.drawable.bg_call_backdrop)
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                focused = if (focused == id) null else id
                layoutTiles()
            }
        }
        val initials = android.widget.TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 28f
            setTextColor(ContextCompat.getColor(this@FaceTimeCallActivity, R.color.call_on_screen))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(initials, FrameLayout.LayoutParams(dp(TILE_AVATAR_DP), dp(TILE_AVATAR_DP), android.view.Gravity.CENTER))
        val label = android.widget.TextView(this).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 0f, 0x99000000.toInt())
            setTextColor(ContextCompat.getColor(this@FaceTimeCallActivity, R.color.call_on_screen))
            setPadding(dp(8), dp(4), dp(8), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        return Tile(frame, null, label, initials)
    }

    /** Gives [tile] a picture's view, under its initials and name: the library is given its Surface once there is one. */
    private fun addVideo(id: Long, tile: Tile) {
        val v = FittedVideo(TextureView(this))
        v.view.surfaceTextureListener = tileListener(id, tile, v)
        // A tap makes it the big one (or not), as the tile's own; pinch, drag and double tap as on the big picture.
        v.gestures(this, active = { !inPip && id in shown.second.tiles }, tapped = { tile.frame.performClick() })
        tile.video = v
        tile.frame.addView(v.view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Takes [tile]'s picture's view away: its listener tells the library first, and only then lets go of the Surface. */
    private fun removeVideo(tile: Tile) {
        val v = tile.video ?: return
        tile.video = null
        tile.frame.removeView(v.view)
    }

    /**
     * A tile's view: registered with the call engine once it has a Surface; told first (and only
     * then let go of) when it goes. Holds its [tile] itself: the tile may already be out of
     * [tileViews] when its view is taken away.
     */
    private fun tileListener(id: Long, holder: Tile, fitted: FittedVideo) = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val surface = Surface(texture)
            holder.surface = surface
            fitted.newSurface()
            calls.attachTile(id, surface)
            calls.media.value.tiles[id]?.let(fitted::fit)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            calls.media.value.tiles[id]?.let(fitted::fit)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            val surface = holder.surface ?: return true
            holder.surface = null
            calls.detachTile(id, surface) {
                surface.release()
                texture.release()
            }
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = fitted.frameDrawn(texture)
    }

    /** The tiles in their places ([TileLayout]): a grid, or the big one and the others small along the top; with a shared screen, all small over it. */
    private fun layoutTiles() {
        val w = binding.tiles.width
        val h = binding.tiles.height
        if (w <= 0 || h <= 0 || tileViews.isEmpty()) return
        val ids = tileViews.keys.toList()
        val screen = calls.media.value.screenVideo
        val thumb = dp(if (inPip) 40 else 96)
        val top = dp(if (inPip) 4 else 120)
        val slots = when {
            // The shared screen is the big picture: every tile goes small over it.
            screen -> TileLayout.focused(ids.size + 1, 0, w, h, thumb, dp(TILE_GAP_DP), top).drop(1)
            focused != null -> TileLayout.focused(ids.size, ids.indexOf(focused), w, h, thumb, dp(TILE_GAP_DP), top)
            else -> TileLayout.slots(ids.size, w, h, dp(TILE_GAP_DP), dp(TILE_GAP_DP))
        }
        ids.forEachIndexed { i, id ->
            val slot = slots.getOrNull(i) ?: return@forEachIndexed
            val tile = tileViews[id] ?: return@forEachIndexed
            tile.frame.layoutParams = FrameLayout.LayoutParams(slot.width, slot.height).apply {
                leftMargin = slot.x
                topMargin = slot.y
            }
            // The big one under the small ones.
            if (id == focused && !screen) tile.frame.z = 0f else tile.frame.z = 1f
        }
    }

    /**
     * What a group call's tile [id] is called: as the user knows them (the main process's word) for
     * someone who joined as Apple vouches for; else the address the call reports, marked as not
     * verified; a FaceTime link's guest as a guest.
     */
    private fun labelOf(id: Long, call: Call, known: Map<String, CallerInfo>): String {
        val reported = call.inCall.firstOrNull { it.id == id } ?: com.lightdecoder.imessage.facetime.InCall(id, "")
        return labelText(CallLabels.of(listOf(reported), call.joiners).first(), known)
    }

    /**
     * The contact photo tile [id] may show: only for someone who joined as Apple vouches for, as
     * the main process knows them, and only a file of the app's own; never for anyone else.
     */
    private fun photoOf(id: Long, call: Call, known: Map<String, CallerInfo>): String? {
        val reported = call.inCall.firstOrNull { it.id == id } ?: return null
        val label = CallLabels.of(listOf(reported), call.joiners).first()
        if (label.kind != CallLabels.Kind.VERIFIED) return null
        val handle = label.handle ?: return null
        return known[CallIds.key(handle)]?.photo?.takeIf { DirectPaths.isAllowed(it, DirectPaths.forApp(this)) }
    }

    private fun labelText(label: CallLabels.Label, known: Map<String, CallerInfo>): String = when (label.kind) {
        CallLabels.Kind.VERIFIED -> label.handle?.let { h -> known[CallIds.key(h)]?.name?.takeIf { it.isNotBlank() } ?: Handles.display(h) } ?: getString(R.string.facetime_someone)
        CallLabels.Kind.GUEST -> getString(R.string.facetime_guest)
        CallLabels.Kind.NOT_VERIFIED -> CallIds.label(label.handle?.let(Handles::display) ?: label.reported).takeIf { it.isNotEmpty() }
            ?.let { getString(R.string.facetime_not_verified, it) } ?: getString(R.string.facetime_not_verified_short)
    }

    /** Who is in the group call and who was invited (as iPhone's call info). */
    private fun openPeople() {
        val call = calls.state.value ?: return
        sheet?.dismiss()
        sheet = ParticipantSheet(this, calls, ::labelText).also { s ->
            s.setOnDismissListener { if (sheet === s) sheet = null }
            s.update(call, calls.labels.value)
            s.show()
        }
    }

    // --- picture in picture ------------------------------------------------------------------------

    /** Whether the call floats on in a window when the screen is left: a video call that is on, with a picture of the other side. */
    @VisibleForTesting
    internal fun canPip(call: Call? = calls.state.value, media: Media = calls.media.value): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            call != null && call.phase == Phase.ACTIVE && call.video && media.anyPicture

    /** Floats the call in a window, if it may; true when it does. */
    private fun enterPip(): Boolean {
        if (inPip || !canPip() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            enterPictureInPictureMode(pipParams(calls.state.value!!, calls.media.value))
        } catch (e: RuntimeException) {
            false
        }
    }

    /** The window's shape (as the pictures are) and its buttons: Mute or Unmute, Camera Off while it is on, End. Never one that answers or places a call. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(call: Call, media: Media): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(pipAspect(call, media), PipParams.DENOMINATOR))
            .setActions(pipActions(call, media))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(canPip(call, media))
        return builder.build()
    }

    /**
     * The window's shape ([PipParams], over [PipParams.DENOMINATOR]): the picture as it is shown,
     * turned upright by its stream as the call screen turns it (a shared screen's, the one person's,
     * or a group call's only tile's); with several tiles, the grid's.
     */
    @VisibleForTesting
    internal fun pipAspect(call: Call, media: Media): Int {
        val tiles = if (call.group && !media.screenVideo) tileViews.size else 1
        val (w, h) = when {
            media.screenVideo -> media.screen.shown
            call.group -> tileViews.keys.singleOrNull()?.let { media.tiles[it]?.shown } ?: (0 to 0)
            else -> media.remoteShown
        }
        return PipParams.aspect(w, h, tiles)
    }

    @VisibleForTesting
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    internal fun pipActions(call: Call, media: Media): List<RemoteAction> {
        fun action(action: String, code: Int, icon: Int, title: Int): RemoteAction {
            val intent = FaceTimeActionReceiver.intent(this, action, call.guid)
                .setData(Uri.Builder().scheme(PIP_SCHEME).authority(action.substringAfterLast('.').lowercase()).appendPath(call.guid).build())
            val pending = PendingIntent.getBroadcast(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = getString(title)
            return RemoteAction(Icon.createWithResource(this, icon), text, text, pending)
        }
        return listOfNotNull(
            action(FaceTimeActionReceiver.ACTION_TOGGLE_MIC, PIP_MUTE, if (media.muted) R.drawable.ic_ft_mic_off else R.drawable.ic_ft_mic, if (media.muted) R.string.facetime_unmute else R.string.facetime_mute),
            if (media.cameraOn) action(FaceTimeActionReceiver.ACTION_CAMERA_OFF, PIP_CAMERA, R.drawable.ic_ft_video_off, R.string.facetime_camera_off) else null,
            action(FaceTimeActionReceiver.ACTION_HANG_UP, PIP_END, R.drawable.ic_ft_end, R.string.facetime_end),
        )
    }

    /** The window's buttons and shape follow the call (and, from Android 12, whether swiping Home floats it), set again only when they change. */
    private fun updatePip(call: Call, media: Media) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val key = listOf(canPip(call, media), media.muted, media.cameraOn, media.remoteShown, media.screenVideo, media.screen.shown, tileViews.size, media.tiles.values.map { it.shown }, call.guid)
        if (key == pipSet) return
        pipSet = key
        try {
            if (inPip || canPip(call, media) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setPictureInPictureParams(pipParams(call, media))
        } catch (e: RuntimeException) {
            // A device that takes no window for it.
        }
    }

    /**
     * While the call floats, the screen going off pauses the camera at once (whatever the window
     * does over the lock screen), and the unlock brings it back.
     */
    private fun watchScreenOff() {
        if (screenOff != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        pausedForScreenOff = true
                        calls.screenHidden(this@FaceTimeCallActivity)
                    }
                    Intent.ACTION_USER_PRESENT -> if (pausedForScreenOff && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        pausedForScreenOff = false
                        calls.screenShown(this@FaceTimeCallActivity)
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenOff = receiver
        } catch (e: RuntimeException) {
            // Not watched: the camera still pauses when the screen is stopped.
        }
    }

    private fun stopWatchingScreenOff() {
        val receiver = screenOff ?: return
        screenOff = null
        runCatching { unregisterReceiver(receiver) }
    }

    /** The screen-off receiver of the floating window, for tests. */
    @VisibleForTesting
    internal val screenOffReceiver: BroadcastReceiver? get() = screenOff

    private fun setStatus(text: String) {
        binding.status.text = text
    }

    /** The call's time, counted up each second. */
    private fun tick(call: Call) {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (true) {
                val seconds = ((System.currentTimeMillis() - call.activeSinceMs) / 1000).coerceAtLeast(0)
                binding.status.text = DateUtils.formatElapsedTime(seconds)
                delay(1000 - (System.currentTimeMillis() % 1000).coerceIn(0, 999))
            }
        }
    }

    /** A button that is "on" is white with a dark sign; "off" it is a see-through disc with a white sign. */
    private fun style(button: ImageButton, on: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (on) R.color.call_control_on else R.color.call_control))
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(ContextCompat.getColor(this, if (on) R.color.call_on_control_on else R.color.call_on_control)))
        button.isSelected = on
    }

    private fun renderControls(media: Media) {
        binding.mute.setImageResource(if (media.muted) R.drawable.ic_ft_mic_off else R.drawable.ic_ft_mic)
        binding.mute.contentDescription = getString(if (media.muted) R.string.facetime_unmute else R.string.facetime_mute)
        binding.muteLabel.setText(if (media.muted) R.string.facetime_unmute else R.string.facetime_mute)
        style(binding.mute, media.muted)
        binding.camera.setImageResource(if (media.cameraOn) R.drawable.ic_ft_video else R.drawable.ic_ft_video_off)
        binding.camera.contentDescription = getString(if (media.cameraOn) R.string.facetime_camera_off else R.string.facetime_camera_on)
        style(binding.camera, media.cameraOn)
        binding.flipColumn.visibility = if (media.cameraOn) View.VISIBLE else View.GONE
        style(binding.flip, false)
        val label = routeLabel(media.route)
        binding.speakerLabel.setText(if (media.routes.size > 2) label else R.string.facetime_speaker)
        binding.speaker.contentDescription = getString(R.string.facetime_audio_route) + ": " + getString(label)
        style(binding.speaker, media.route == Route.SPEAKER)
    }

    private fun routeLabel(route: Route): Int = when (route) {
        Route.EARPIECE -> R.string.facetime_route_earpiece
        Route.SPEAKER -> R.string.facetime_route_speaker
        Route.WIRED -> R.string.facetime_route_wired
        Route.BLUETOOTH -> R.string.facetime_route_bluetooth
    }

    /** The speaker button: on and off with two routes; with more (a headset, Bluetooth), a list to pick from. */
    private fun chooseRoute(anchor: View) {
        val media = calls.media.value
        val routes = media.routes
        if (routes.size <= 2) {
            val other = routes.firstOrNull { it != media.route } ?: return
            calls.setRoute(if (media.route == Route.SPEAKER) other else Route.SPEAKER.takeIf { it in routes } ?: other)
            return
        }
        PopupMenu(this, anchor).apply {
            routes.forEachIndexed { index, route -> menu.add(0, index, index, routeLabel(route)).setCheckable(true).setChecked(route == media.route) }
            setOnMenuItemClickListener { item ->
                routes.getOrNull(item.itemId)?.let(calls::setRoute)
                true
            }
        }.show()
    }

    // --- leaving -----------------------------------------------------------------------------------

    /**
     * Back, or the arrow: a video call that is on floats on in its window; any other call goes on
     * under its notice; with no call to keep, the screen is closed.
     */
    private fun stepBack() {
        val call = calls.state.value
        if (call != null && call.live) {
            if (!enterPip()) moveTaskToBack(true)
        } else {
            leave()
        }
    }

    /** Close: a "no answer" is dismissed, and the screen goes. */
    private fun leave() {
        holdingNotice = false
        if (calls.state.value?.phase == Phase.UNANSWERED) calls.hangUp()
        finishCall()
    }

    private fun finishCall() {
        if (isFinishing) return
        // Still in sight (Android allows iMessage's service to be started): a restart of iMessage for
        // the optional Apple Account that the call kept back is made now. Nothing without one.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) com.lightdecoder.imessage.direct.AppleAccount.callScreenEnded()
        finishAndRemoveTask()
    }

    /**
     * The chat with the call's person (the main process's screen). From a locked phone the unlock
     * comes first: the call screen shows over the lock screen, the chats never do.
     */
    @VisibleForTesting
    internal fun openChat(conversationId: String) {
        val open = Runnable {
            // A video call floats on while the chat is open.
            enterPip()
            try {
                startActivity(RelayIntents.thread(this, conversationId, service = ChatService.IMESSAGE))
            } catch (e: RuntimeException) {
                // Nothing to open it with: stays on the call.
            }
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = open.run()
                },
            )
        } else {
            open.run()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: RuntimeException) {
            // No such screen on this phone.
        }
    }

    // --- pictures ----------------------------------------------------------------------------------

    private fun tellRotation() {
        calls.displayRotation(screenRotation() ?: return)
    }

    /** How the screen is turned ([Surface.ROTATION_0]..), or null when the screen cannot be asked. */
    private fun screenRotation(): Int? {
        @Suppress("DEPRECATION")
        return try {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay)?.rotation
        } catch (e: RuntimeException) {
            // A screen not tied to a display (yet): the default one's.
            try {
                windowManager.defaultDisplay?.rotation
            } catch (e2: RuntimeException) {
                null
            }
        }
    }

    private val remoteListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val surface = Surface(texture)
            remoteSurface = surface
            // A new view: how its frames are turned is read off the first one.
            remoteFit.newSurface()
            calls.attachRemote(surface)
            fitRemote()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fitRemote()

        /** The library is told first (it lets go of the Surface); only then is it released. */
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            val surface = remoteSurface
            remoteSurface = null
            if (surface == null) return true
            calls.detachRemote(surface) {
                surface.release()
                texture.release()
            }
            return false
        }

        /** Every frame: how the decoder turns it ([FittedVideo.frameDrawn]). */
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = remoteFit.frameDrawn(texture)
    }

    private val screenListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val surface = Surface(texture)
            screenSurface = surface
            screenFit.newSurface()
            calls.attachScreen(surface)
            fitScreen()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fitScreen()

        /** The library is told first (it lets go of the Surface); only then is it released. */
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            val surface = screenSurface
            screenSurface = null
            if (surface == null) return true
            calls.detachScreen(surface) {
                surface.release()
                texture.release()
            }
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = screenFit.frameDrawn(texture)
    }

    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val surface = FaceTimeCamera.previewSurface(texture)
            previewSurface = surface
            calls.attachPreview(surface)
            fitPreview()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fitPreview()

        /** The camera stops drawing into it first; only then is it released. */
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            val surface = previewSurface
            previewSurface = null
            if (surface == null) return true
            calls.detachPreview(surface) {
                surface.release()
                texture.release()
            }
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    /**
     * The other side's picture, turned upright as its stream says and in its own shape: filling the
     * view (edges cut) when the two shapes are close, whole (with bars) otherwise, or as the user
     * chose with a double tap; then their pinch and drag.
     */
    private fun fitRemote() {
        val m = calls.media.value
        remoteFit.fit(Media.Picture(m.remoteWidth, m.remoteHeight, m.remoteRotation))
    }

    /** A shared screen: always shown whole (until the user zooms it). */
    private fun fitScreen() = screenFit.fit(calls.media.value.screen)

    /** The other side's camera: the whole screen, or a small corner over a shared screen ([small]). */
    private fun layoutRemote(small: Boolean) {
        val params = binding.remoteVideo.layoutParams as FrameLayout.LayoutParams
        val (width, height) = if (small) dp(if (inPip) 48 else 112) to dp(if (inPip) 64 else 150) else FrameLayout.LayoutParams.MATCH_PARENT to FrameLayout.LayoutParams.MATCH_PARENT
        val top = if (small) dp(if (inPip) 4 else 120) else 0
        val start = if (small) dp(if (inPip) 4 else 16) else 0
        if (params.width == width && params.height == height && params.topMargin == top && params.marginStart == start) return
        params.width = width
        params.height = height
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.topMargin = top
        params.marginStart = start
        binding.remoteVideo.layoutParams = params
    }

    /** The user's own small picture can be dragged, and settles in the nearest corner (clear of the call's buttons). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun dragPreview() {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        binding.previewCard.setOnTouchListener { card, e ->
            if (card.alpha == 0f) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = card.translationX
                    startY = card.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && dx * dx + dy * dy > dp(8) * dp(8)) dragging = true
                    if (dragging) {
                        card.translationX = startX + dx
                        card.translationY = startY + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val area = binding.content
                        val (x, y) = TileLayout.nearestCorner(
                            card.left + card.translationX, card.top + card.translationY, area.width, area.height, card.width, card.height,
                            margin = dp(16), top = dp(72), bottom = dp(PREVIEW_BOTTOM_DP),
                        )
                        card.animate().translationX((x - card.left).toFloat()).translationY((y - card.top).toFloat()).setDuration(180).start()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** The small view of the user's own camera: the camera's picture, upright and in its shape, however the screen is turned and the camera mounted. */
    private fun fitPreview() {
        val view = binding.localPreview
        val front = calls.media.value.cameraFront
        val sensor = sensors.getOrPut(front) { runCatching { FaceTimeCamera.sensorOrientationOf(this, front) }.getOrNull() }
        view.setTransform(previewTransform(view.width, view.height, screenRotation() ?: Surface.ROTATION_0, sensor))
    }

    /** Pinch to zoom the other side's picture (or a shared screen), drag to look around it, double tap to switch between filled and whole. */
    private fun setUpRemoteGestures() {
        // Only while the other side's picture shows; otherwise the screen behind takes the touch as before.
        // (A group call's people are in tiles of their own: its big view is not used, and a shared screen under it takes the touch.)
        remoteFit.gestures(this, active = { binding.backdrop.visibility != View.VISIBLE && !inPip && shown.first?.group != true })
        screenFit.gestures(this, active = { binding.screenVideo.alpha > 0f && !inPip })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_PLACE = "com.lightdecoder.facetime.action.PLACE"
        const val ACTION_ANSWER = "com.lightdecoder.facetime.action.ANSWER"
        const val ACTION_SHOW = "com.lightdecoder.facetime.action.SHOW"
        const val EXTRA_PEER = "com.lightdecoder.facetime.extra.PEER"
        const val EXTRA_NAME = "com.lightdecoder.facetime.extra.NAME"
        const val EXTRA_VIDEO = "com.lightdecoder.facetime.extra.VIDEO"
        const val EXTRA_FROM = "com.lightdecoder.facetime.extra.FROM"
        const val EXTRA_CONVERSATION = "com.lightdecoder.facetime.extra.CONVERSATION"
        const val EXTRA_PHOTO = "com.lightdecoder.facetime.extra.PHOTO"
        const val EXTRA_AT = "com.lightdecoder.facetime.extra.AT"
        const val EXTRA_GUID = "com.lightdecoder.facetime.extra.GUID"
        const val EXTRA_WITH_CAMERA = "com.lightdecoder.facetime.extra.WITH_CAMERA"

        /** Everyone a group call goes to (the first also as [EXTRA_PEER]). */
        const val EXTRA_PEERS = "com.lightdecoder.facetime.extra.PEERS"

        /** A call is placed only for an intent made this recently (never one kept from before). */
        private const val PLACE_FRESH_MS = 20_000L

        /** The most tiles a group call shows (the others are in its list of people). */
        private const val MAX_TILES = 9
        private const val TILE_GAP_DP = 4

        /** The size of a tile's initials or contact photo. */
        private const val TILE_AVATAR_DP = 88

        /** The small picture's corners keep this far from the bottom (the call's buttons). */
        private const val PREVIEW_BOTTOM_DP = 220

        /** The picture-in-picture window's buttons: told apart by their data and their request codes. */
        private const val PIP_SCHEME = "lightdecoder-facetime-pip"
        private const val PIP_MUTE = 21
        private const val PIP_CAMERA = 22
        private const val PIP_END = 23

        /** The call engine is given this long to show a call it was asked for (it may first wait for the session). */
        private const val AWAIT_MS = FaceTimeCalls.CONNECT_WAIT_MS + 4_000L
        private const val CALL_AGAIN_DELAY_MS = 400L
        private const val MAX_ID_CHARS = 256

        private fun base(context: Context, action: String): Intent =
            Intent(context, FaceTimeCallActivity::class.java).setAction(action).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Places a call to [peer] (their address) on the user's tap, from the chat screens: [name]
         * as the chat calls them, the user's address the chat is carried on ([from]), the chat
         * itself ([conversationId], the iMessage archive's) and the person's picture, if any.
         */
        fun placeIntent(
            context: Context,
            peer: String,
            name: String,
            video: Boolean,
            from: String?,
            conversationId: String?,
            photo: String? = null,
        ): Intent = base(context, ACTION_PLACE)
            .putExtra(EXTRA_PEER, peer).putExtra(EXTRA_NAME, name).putExtra(EXTRA_VIDEO, video)
            .putExtra(EXTRA_AT, SystemClock.elapsedRealtime())
            .apply {
                if (from != null) putExtra(EXTRA_FROM, from)
                if (conversationId != null) putExtra(EXTRA_CONVERSATION, conversationId)
                if (photo != null) putExtra(EXTRA_PHOTO, photo)
            }

        /**
         * Places one group FaceTime call to everyone of [peers] (their addresses) on the user's tap,
         * from a group chat ([conversationId]) or the FaceTime list, called [name] there.
         */
        fun placeIntent(
            context: Context,
            peers: List<String>,
            name: String,
            video: Boolean,
            from: String?,
            conversationId: String?,
        ): Intent = placeIntent(context, peers.first(), name, video, from, conversationId)
            .putStringArrayListExtra(EXTRA_PEERS, ArrayList(peers.take(MAX_PEOPLE)))

        /** Answers the ringing call [guid] (the notice's Answer and "Audio only"). */
        fun answerIntent(context: Context, guid: String, withCamera: Boolean): Intent =
            base(context, ACTION_ANSWER).putExtra(EXTRA_GUID, guid).putExtra(EXTRA_WITH_CAMERA, withCamera)

        /** Shows the ringing call [guid] with its Accept and Decline (the notice's tap, and its full-screen intent). */
        fun incomingIntent(context: Context, guid: String): Intent = base(context, ACTION_SHOW).putExtra(EXTRA_GUID, guid)

        /** Back to the call there is (the in-call notice's tap). */
        fun showIntent(context: Context): Intent = base(context, ACTION_SHOW)

        /**
         * How the other side's picture, [bufferWidth] x [bufferHeight] as its decoder makes it, is
         * laid into the view [viewWidth] x [viewHeight]: upright, in its own shape (never
         * stretched), filling the view when the shapes are close and fitting it otherwise, unless the
         * user chose ([zoom]'s fill), then zoomed and moved as the user did. Its shape goes by how its
         * frames come ([turn], null before the first) and the library's [rotation] (0..3,
         * [Media.ROTATION_NONE] for none, [Media.ROTATION_UNKNOWN] when it said none): [VideoFit.shape]. Pure.
         */
        fun remoteTransform(
            viewWidth: Int,
            viewHeight: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            rotation: Int = Media.ROTATION_NONE,
            turn: VideoFit.Turn? = null,
            zoom: VideoFit.Zoom? = null,
        ): Matrix = remoteTransform(viewWidth, viewHeight, VideoFit.shape(bufferWidth, bufferHeight, rotation, turn), zoom)

        /** The same for a picture of [shape] ([VideoFit.shape]). Pure. */
        fun remoteTransform(viewWidth: Int, viewHeight: Int, shape: VideoFit.Shape, zoom: VideoFit.Zoom? = null): Matrix {
            val fill = zoom?.fill ?: VideoFit.fillsByDefault(viewWidth, viewHeight, shape.width, shape.height)
            // The view gets every frame stretched over all of it. Turned upright by its decoder, only its
            // shape is put right; else it is as its decoder made it (its sides the other way round for a
            // quarter turn), and the view turns it as well.
            val (bw, bh) = VideoFit.upright(shape.width, shape.height, shape.degrees)
            return VideoFit.transform(viewWidth, viewHeight, bw, bh, shape.degrees, fill, zoom)
        }

        /** The most lines one view says about its picture ([FittedVideo]'s notes in the iMessage log). */
        internal const val MAX_PICTURE_NOTES = 24

        /** What [FittedVideo] says of its picture in the log. Its numbers are sizes with words between them (never taken for a phone number). Pure. */
        internal fun pictureLine(
            width: Int,
            height: Int,
            rotation: Int,
            turn: VideoFit.Turn?,
            shape: VideoFit.Shape,
            viewWidth: Int,
            viewHeight: Int,
            fill: Boolean,
        ): String {
            val said = if (rotation in 0..3) "rotation $rotation said" else "no rotation said"
            val frames = when (turn) {
                VideoFit.Turn.QUARTER -> "its frames come turned a quarter"
                VideoFit.Turn.HALF -> "its frames come turned half"
                VideoFit.Turn.NONE -> "its frames come as they are"
                null -> "no frame yet"
            }
            val byScreen = if (shape.degrees != 0) ", turned ${shape.degrees} more by the screen" else ""
            return "the other side's picture is ${width}x$height as decoded ($said; $frames): shown as ${shape.width}x${shape.height}$byScreen, " +
                "${if (fill) "filling" else "whole in"} a ${viewWidth}x$viewHeight view"
        }

        /**
         * How the camera's picture ([FaceTimeCamera.PREVIEW]) is laid into the small view
         * [viewWidth] x [viewHeight] with the screen turned to [rotation] ([Surface.ROTATION_0]..):
         * upright, filling the view, in its own shape. Android lays a camera's picture upright for
         * the screen's natural way up, turned as the camera is mounted ([sensor], degrees; a usual
         * phone's 90 when not known): a foldable's inner screen, or a camera mounted otherwise,
         * gives a picture of the other shape, which must not be stretched into the view. Pure.
         */
        fun previewTransform(viewWidth: Int, viewHeight: Int, rotation: Int, sensor: Int? = null): Matrix {
            val mounted = sensor?.takeIf { it % 90 == 0 } ?: 90
            val sideways = ((mounted % 360) + 360) % 180 == 90
            val bw = if (sideways) FaceTimeCamera.PREVIEW.height else FaceTimeCamera.PREVIEW.width
            val bh = if (sideways) FaceTimeCamera.PREVIEW.width else FaceTimeCamera.PREVIEW.height
            val degrees = (360 - rotation.coerceIn(0, 3) * 90) % 360
            return VideoFit.transform(viewWidth, viewHeight, bw, bh, degrees, fill = true)
        }
    }
}
