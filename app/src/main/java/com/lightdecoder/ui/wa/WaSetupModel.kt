package com.lightdecoder.ui.wa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightdecoder.relay.RelayException
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.wa.WaManager
import com.lightdecoder.wa.WaPairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the WhatsApp setup screen is, kept across rotations so that a
 * linking attempt is not abandoned (and its code not thrown away) by turning
 * the phone. The attempt itself is [WaManager]'s; this only follows
 * [WaManager.pairing] and remembers which step is showing.
 *
 * Nothing here answers for the user: it asks WhatsApp for a code and shows
 * it. Leaving the screen for good abandons an unfinished attempt.
 */
class WaSetupModel : ViewModel() {

    enum class Step { INTRO, NUMBER, CODE, QR, DONE }

    /**
     * [code] is the pairing code (step CODE) or the QR code's text (step
     * QR): shown, never logged. [error] is a bridge error code.
     */
    class Ui(
        val step: Step = Step.INTRO,
        val busy: Boolean = false,
        val code: String = "",
        val error: String = "",
        val already: Boolean = false,
    ) {
        /** Without the code, as for [WaPairing]. */
        override fun toString(): String = "Ui($step, busy=$busy, error=$error)"
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var manager: WaManager? = null
    private var request: Job? = null

    /** Follows [manager]'s linking from now on. Called by every instance of the screen; only the first does anything. */
    fun attach(manager: WaManager) {
        if (this.manager != null) return
        this.manager = manager
        if (manager.isPaired()) {
            _ui.value = Ui(step = Step.DONE, already = true)
        }
        viewModelScope.launch {
            var first = true
            manager.pairing.collect { pairing ->
                // What an earlier visit to this screen ended with is not this visit's news.
                val stale = first && (pairing is WaPairing.Failed || pairing is WaPairing.Paired)
                first = false
                if (!stale) onPairing(pairing)
            }
        }
    }

    private fun onPairing(pairing: WaPairing) {
        val now = _ui.value
        when (pairing) {
            is WaPairing.WaitingForCode -> _ui.value = Ui(Step.CODE, code = pairing.code)
            is WaPairing.WaitingForQr -> _ui.value = Ui(Step.QR, code = pairing.code)
            WaPairing.Paired -> _ui.value = Ui(Step.DONE)
            is WaPairing.Failed ->
                if (now.step != Step.INTRO && now.step != Step.DONE) _ui.value = Ui(Step.NUMBER, error = pairing.code)
            // A code that is no longer offered (cancelled) leaves its step; a new attempt starts from Idle too.
            WaPairing.Idle ->
                if (now.step == Step.CODE || now.step == Step.QR) _ui.value = Ui(Step.NUMBER)
        }
    }

    fun goTo(step: Step) {
        _ui.value = Ui(step)
    }

    /** Asks WhatsApp for a pairing code for the user's own [number]. */
    fun requestCode(number: String) {
        val manager = manager ?: return
        if (_ui.value.busy) return
        if (number.count { it.isDigit() } < MIN_DIGITS) {
            _ui.value = Ui(Step.NUMBER, error = RelayException.INVALID_ARGUMENT)
            return
        }
        start(awaitsEvent = false) { manager.requestPairingCode(number) }
    }

    /** Asks WhatsApp for QR codes instead. */
    fun startQr() {
        val manager = manager ?: return
        if (_ui.value.busy) return
        start(awaitsEvent = true) { manager.startQr() }
    }

    /**
     * [awaitsEvent]: the call returning only means the attempt started (QR
     * codes follow as events); otherwise its result is already in
     * [WaManager.pairing] when it returns.
     */
    private fun start(awaitsEvent: Boolean, block: suspend () -> Unit) {
        _ui.value = Ui(Step.NUMBER, busy = true)
        request = viewModelScope.launch {
            try {
                block()
                val now = _ui.value
                if (!awaitsEvent && now.step == Step.NUMBER && now.busy) {
                    // The code is in the manager's flow by now; if it is not (the attempt was
                    // overtaken), the user is not left looking at a progress bar.
                    val pairing = manager?.pairing?.value
                    if (pairing is WaPairing.WaitingForCode) onPairing(pairing) else _ui.value = Ui(Step.NUMBER)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value.step == Step.NUMBER) _ui.value = Ui(Step.NUMBER, error = RelayErrors.codeOf(e))
            }
        }
    }

    /** Abandons the attempt in progress and goes back to the number. */
    fun cancel() {
        request?.cancel()
        request = null
        manager?.cancelPairing()
        _ui.value = Ui(Step.NUMBER)
    }

    /** The screen is gone for good: an attempt nobody is looking at does not stay open. */
    override fun onCleared() {
        val manager = manager ?: return
        if (_ui.value.step != Step.DONE && !manager.isPaired()) manager.cancelPairing()
    }

    private companion object {
        /** Shorter than any phone number with a country code. */
        const val MIN_DIGITS = 7
    }
}
