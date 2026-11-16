package com.lightdecoder.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightdecoder.relay.RelayManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The pairing steps of [RelaySetupActivity], kept across rotation so turning
 * the phone does not abandon a pairing the user is confirming.
 *
 * This only shows the emoji and waits. The confirmation itself happens on the
 * user's phone, where they tap the matching emoji; nothing here answers it.
 */
class PairingModel : ViewModel() {

    enum class Step { INTRO, SIGN_IN, CONFIRM, DONE }

    /**
     * @param starting     waiting for Google and the phone to produce the emoji
     * @param emoji        the emoji to show; empty until it is known
     * @param errorCode    the bridge error code of the last attempt, empty if none
     * @param alreadyPaired the screen was opened while a pairing already exists
     */
    data class Ui(
        val step: Step = Step.INTRO,
        val starting: Boolean = false,
        val emoji: String = "",
        val account: String = "",
        val errorCode: String = "",
        val alreadyPaired: Boolean = false,
    )

    private val state = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = state.asStateFlow()

    /**
     * The signed-in cookies, in memory only and only for as long as a pairing
     * attempt may need them (a retry after "wrong emoji" reuses them). Never
     * saved, logged or shown; dropped on success, cancel, or when the screen
     * goes away.
     */
    private var cookies: Map<String, String>? = null
    private var job: Job? = null
    private var manager: RelayManager? = null

    /**
     * Whether the sign-in WebView's storage was already wiped for this
     * sign-in. It is wiped once on entry, not again after a rotation, so the
     * user does not lose a half-finished sign-in.
     */
    var signInPrepared = false

    val hasCookies: Boolean get() = cookies != null

    fun goTo(step: Step) {
        if (step == Step.SIGN_IN || step == Step.INTRO) signInPrepared = false
        state.value = Ui(step = step)
    }

    fun showAlreadyPaired() {
        state.value = Ui(step = Step.DONE, alreadyPaired = true)
    }

    /**
     * Starts (or retries) pairing. [newCookies] null retries with the cookies
     * of the last attempt; without any, the user is sent back to sign in.
     */
    fun pair(manager: RelayManager, newCookies: Map<String, String>?) {
        if (newCookies != null) cookies = newCookies
        val signedIn = cookies
        if (signedIn == null) {
            goTo(Step.SIGN_IN)
            return
        }
        this.manager = manager
        job?.cancel()
        state.value = Ui(step = Step.CONFIRM, starting = true)
        job = viewModelScope.launch {
            val challenge = relayAttempt { manager.startPairing(signedIn) }.getOrElse {
                fail(it)
                return@launch
            }
            state.value = state.value.copy(starting = false, emoji = challenge.emoji, account = challenge.account)
            // Blocks until the user taps an emoji on their phone (or it times out).
            relayAttempt { manager.finishPairing() }
                .onSuccess {
                    cookies = null
                    state.value = Ui(step = Step.DONE)
                }
                .onFailure { fail(it) }
        }
    }

    private fun fail(t: Throwable) {
        val code = RelayErrors.codeOf(t)
        // Cookies Google refused are no use for a retry.
        if (RelayErrors.needsNewSignIn(code)) cookies = null
        state.value = state.value.copy(starting = false, emoji = "", errorCode = code)
    }

    /** Abandons the attempt (the phone's prompt times out by itself) and starts over. */
    fun cancel() {
        stopAttempt()
        cookies = null
        goTo(Step.INTRO)
    }

    private fun stopAttempt() {
        val running = job?.isActive == true
        job?.cancel()
        job = null
        val m = manager
        // Cancelling the coroutine does not unblock the bridge call; this does.
        if (running && m != null) runInBackground { m.cancelPairing() }
    }

    override fun onCleared() {
        stopAttempt()
        cookies = null
    }
}
