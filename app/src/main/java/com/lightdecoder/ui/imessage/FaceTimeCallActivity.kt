package com.lightdecoder.ui.imessage

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import com.lightdecoder.imessage.facetime.CallMessage
import com.lightdecoder.imessage.facetime.FaceTimeCalls
import com.lightdecoder.imessage.facetime.FaceTimeCamera
import com.lightdecoder.imessage.facetime.FaceTimeLog
import com.lightdecoder.imessage.facetime.FaceTimeNotices
import com.lightdecoder.imessage.facetime.Media
import com.lightdecoder.imessage.facetime.Phase
import com.lightdecoder.imessage.facetime.Refusal
import com.lightdecoder.imessage.facetime.Route
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
 *
 * Not exported; started by the chat screens (main process), the call notices, and nothing else.
 */
class FaceTimeCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFacetimeCallBinding
    private val calls: FaceTimeCalls by lazy { FaceTimeCalls.get(this) }

    /** What waits for the permissions' answer. */
    private sealed class Pending {
        data class Place(val peer: String, val name: String, val video: Boolean, val from: String?, val conversationId: String?, val photo: String?) : Pending()
        data class Answer(val guid: String, val withCamera: Boolean) : Pending()
    }

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

    /** The user's pinch, drag and double tap on the other side's picture. */
    private val remoteZoom = VideoFit.Zoom()

    /**
     * How the decoder turns the frames being drawn (each frame's SurfaceTexture transform says so):
     * what the picture's shape goes by ([VideoFit.shape]). Null before a stream's first frame.
     */
    private var textureTurn: VideoFit.Turn? = null
    private val textureMatrix = FloatArray(16)

    /** The shape the zoom was made for: another one (another stream, the phone turned) starts fresh. */
    private var zoomFor: VideoFit.Shape? = null

    /** How the other side's picture was last said to be laid out in the log ([notePicture]), and how often so far. */
    private var pictureSaid: PictureNote? = null
    private var pictureNotes = 0

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

        binding.accept.setOnClickListener { shown.first?.let { answer(it.guid, withCamera = it.video) } }
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = stepBack()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(calls.state, calls.media) { call, media -> call to media }.collect { (call, media) -> render(call, media) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { calls.messages.collect(::onMessage) }
        }
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
        // Back in sight: a camera that was paused while it was not is sent again.
        calls.screenShown(this)
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
        }
    }

    override fun onDestroy() {
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
        val peer = intent.getStringExtra(EXTRA_PEER)?.let { CallIds.handle(it) ?: Handles.normalize(it)?.let(CallIds::handle) }
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
        calls.place(request.peer, request.name, video, request.from, request.conversationId, request.photo)
    }

    private fun answer(guid: String, withCamera: Boolean) {
        val call = calls.state.value
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
        val again = lastPlaced ?: shown.first?.takeIf { it.outgoing }?.let { Pending.Place(it.peer, it.name, it.video, it.from, it.conversationId, it.photo) } ?: return
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
        val name = FaceTimeNotices.nameOf(call)
        val address = Handles.display(call.peer)
        // The other side's picture, or this phone's own camera, makes it a screen of pictures.
        val pictures = call.joined && media.remoteVideo
        Avatars.bind(binding.avatar, name, call.peer, "", call.photo?.takeIf { DirectPaths.isAllowed(it, DirectPaths.forApp(this)) }?.let(::File))
        binding.name.text = name
        binding.detail.text = address
        binding.detail.visibility = if (address != name && !pictures) View.VISIBLE else View.GONE
        binding.remoteVideo.contentDescription = getString(R.string.facetime_remote_video, name)

        binding.backdrop.visibility = if (pictures) View.GONE else View.VISIBLE
        binding.scrimTop.visibility = if (pictures) View.VISIBLE else View.GONE
        binding.scrimBottom.visibility = binding.scrimTop.visibility
        binding.avatar.visibility = if (pictures) View.GONE else View.VISIBLE
        (binding.info.layoutParams as FrameLayout.LayoutParams).topMargin = dp(if (pictures) 14 else 96)
        binding.info.requestLayout()
        binding.name.textSize = if (pictures) 20f else 30f
        // Always there (its Surface is ready before the camera starts), seen only with the camera on.
        binding.previewCard.alpha = if (call.joined && media.cameraOn) 1f else 0f
        if (pictures) {
            fitRemote()
        } else if (remoteZoom.isZoomed || remoteZoom.fill != null || textureTurn != null || zoomFor != null) {
            // The stream is gone: the next one starts fresh, by its own first frame.
            remoteZoom.reset()
            remoteZoom.fill = null
            textureTurn = null
            zoomFor = null
        }
        // The camera's picture is laid out for that side's camera once it is known.
        if (call.joined && media.cameraOn) fitPreview()

        val ringingIn = call.phase == Phase.RINGING_IN || call.phase == Phase.SCREENING
        binding.incoming.visibility = if (ringingIn) View.VISIBLE else View.GONE
        binding.audioOnly.visibility = if (ringingIn && call.video) View.VISIBLE else View.GONE
        binding.accept.setImageResource(if (call.video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
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
        // The screen stays on while it rings and while there are pictures; an audio call's goes dark at the ear.
        val keepOn = ringingIn || call.phase == Phase.RINGING_OUT || call.phase == Phase.CONNECTING || (call.phase == Phase.ACTIVE && (pictures || media.cameraOn))
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

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

    /** Back, or the arrow: the call goes on under its notice; with no call to keep, the screen is closed. */
    private fun stepBack() {
        val call = calls.state.value
        if (call != null && call.live) moveTaskToBack(true) else leave()
    }

    /** Close: a "no answer" is dismissed, and the screen goes. */
    private fun leave() {
        holdingNotice = false
        if (calls.state.value?.phase == Phase.UNANSWERED) calls.hangUp()
        finishCall()
    }

    private fun finishCall() {
        if (isFinishing) return
        finishAndRemoveTask()
    }

    /**
     * The chat with the call's person (the main process's screen). From a locked phone the unlock
     * comes first: the call screen shows over the lock screen, the chats never do.
     */
    @VisibleForTesting
    internal fun openChat(conversationId: String) {
        val open = Runnable {
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
            textureTurn = null
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

        /**
         * Every frame: how the decoder turns it. That changes in the middle of a call (the other side
         * turns their phone, and the frames from then on come turned otherwise), and what the library
         * says of it may come before those frames, after them, or not at all: the picture is laid out
         * again with the first frame that is turned otherwise, by that frame.
         */
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            val turn = try {
                texture.getTransformMatrix(textureMatrix)
                VideoFit.textureTurn(textureMatrix)
            } catch (e: RuntimeException) {
                VideoFit.Turn.NONE
            }
            if (turn != textureTurn) {
                textureTurn = turn
                fitRemote()
            }
        }
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
        val view = binding.remoteVideo
        val m = calls.media.value
        val shape = VideoFit.shape(m.remoteWidth, m.remoteHeight, m.remoteRotation, textureTurn)
        if (zoomFor != shape) {
            // Another stream, or the other side turned their phone: back to fitted.
            zoomFor = shape
            remoteZoom.reset()
        }
        view.setTransform(remoteTransform(view.width, view.height, shape, remoteZoom))
        notePicture(m, shape, view.width, view.height)
    }

    /** What [notePicture] says: everything the layout of the other side's picture goes by. */
    private data class PictureNote(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val turn: VideoFit.Turn?,
        val shape: VideoFit.Shape,
        val viewWidth: Int,
        val viewHeight: Int,
        val fill: Boolean,
    )

    /**
     * Says in the iMessage log how the other side's picture is laid out, whenever that changes (a
     * few times a call; never more than [MAX_PICTURE_NOTES]): the size and rotation the library
     * said, how the frames really come, and the shape shown. What to look at, should a picture ever
     * look pulled or squeezed again. Sizes only: nothing of who is calling.
     */
    private fun notePicture(m: Media, shape: VideoFit.Shape, viewWidth: Int, viewHeight: Int) {
        if (!shape.known || viewWidth <= 0 || viewHeight <= 0 || textureTurn == null) return
        val fill = remoteZoom.fill ?: VideoFit.fillsByDefault(viewWidth, viewHeight, shape.width, shape.height)
        val note = PictureNote(m.remoteWidth, m.remoteHeight, m.remoteRotation, textureTurn, shape, viewWidth, viewHeight, fill)
        if (note == pictureSaid || pictureNotes >= MAX_PICTURE_NOTES) return
        pictureSaid = note
        pictureNotes++
        FaceTimeLog.note(pictureLine(note.width, note.height, note.rotation, note.turn, shape, viewWidth, viewHeight, fill))
    }

    /** The small view of the user's own camera: the camera's picture, upright and in its shape, however the screen is turned and the camera mounted. */
    private fun fitPreview() {
        val view = binding.localPreview
        val front = calls.media.value.cameraFront
        val sensor = sensors.getOrPut(front) { runCatching { FaceTimeCamera.sensorOrientationOf(this, front) }.getOrNull() }
        view.setTransform(previewTransform(view.width, view.height, screenRotation() ?: Surface.ROTATION_0, sensor))
    }

    /** Pinch to zoom the other side's picture, drag to look around it, double tap to switch between filled and whole. */
    private fun setUpRemoteGestures() {
        val view = binding.remoteVideo
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                remoteZoom.scaleBy(detector.scaleFactor, detector.focusX - view.width / 2f, detector.focusY - view.height / 2f)
                fitRemote()
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scale.isInProgress) return false
                remoteZoom.panX -= distanceX
                remoteZoom.panY -= distanceY
                fitRemote()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val m = calls.media.value
                val shape = VideoFit.shape(m.remoteWidth, m.remoteHeight, m.remoteRotation, textureTurn)
                val filled = remoteZoom.fill ?: VideoFit.fillsByDefault(view.width, view.height, shape.width, shape.height)
                remoteZoom.fill = !filled
                remoteZoom.reset()
                fitRemote()
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            // Only while the other side's picture shows; otherwise the screen behind takes the touch as before.
            if (binding.backdrop.visibility == View.VISIBLE) return@setOnTouchListener false
            scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            true
        }
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

        /** A call is placed only for an intent made this recently (never one kept from before). */
        private const val PLACE_FRESH_MS = 20_000L

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

        /** The most lines one call screen says about the other side's picture ([notePicture]). */
        private const val MAX_PICTURE_NOTES = 24

        /** [notePicture]'s line. Its numbers are sizes with words between them (never taken for a phone number). Pure. */
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
