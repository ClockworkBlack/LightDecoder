package com.lightdecoder.ui.chat

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.databinding.ViewConnectionBannerBinding
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.relay.RelayUi
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.launch

/**
 * The strip at the top of the messaging screens that says when the link of
 * the service on screen is not live (offline, connecting, in use elsewhere,
 * needs fixing) and offers the one action that helps. Hidden while connected.
 *
 * The owner re-renders it whenever it comes back to the front (its state
 * collection restarts), so returning from the notification settings updates
 * the "notifications are off" case. When the screen changes service, [bind]
 * points it at the other link.
 */
internal class ConnectionBanner(
    private val activity: AppCompatActivity,
    private val binding: ViewConnectionBannerBinding,
    private var backend: ChatBackend,
    private val notifications: ServiceNotifications,
) {
    private var fix = RelayUi.Fix.NONE

    init {
        binding.bannerAction.setOnClickListener { act() }
    }

    /** From now on the banner is about [backend]'s link. It stays hidden until that link's state is rendered. */
    fun bind(backend: ChatBackend) {
        if (backend === this.backend) return
        this.backend = backend
        fix = RelayUi.Fix.NONE
        binding.root.visibility = View.GONE
    }

    /**
     * Shows [state]. With [quietWhenUnlinked] a link that is simply not set
     * up shows nothing (the screen says so itself, with a card); a link that
     * was removed or failed still says why.
     */
    fun render(state: RelayState, paired: Boolean, quietWhenUnlinked: Boolean = false) {
        if (paired && state.isOnline) {
            binding.root.visibility = View.GONE
            return
        }
        val service = backend.service
        // Without a pairing say "not linked", unless the link knows why (logged out, say).
        val phase = if (!paired && !RelayUi.isProblem(state.phase)) RelayState.Phase.UNPAIRED else state.phase
        fix = fixFor(state, paired)
        if (quietWhenUnlinked && phase == RelayState.Phase.UNPAIRED) {
            binding.root.visibility = View.GONE
            return
        }
        binding.root.visibility = View.VISIBLE
        binding.bannerText.setText(RelayUi.title(phase, fix))
        // The specific reason, when the link gave one we can explain.
        val reason =
            if (state.code.isNotEmpty() && RelayUi.isProblem(phase)) ChatErrors.message(activity, service, state.code) else null
        binding.bannerDetail.text = reason ?: activity.getString(ServiceUi.phaseBody(service, phase, fix))
        if (fix == RelayUi.Fix.NONE) {
            binding.bannerAction.visibility = View.GONE
        } else {
            binding.bannerAction.visibility = View.VISIBLE
            binding.bannerAction.setText(RelayUi.fixLabel(fix))
        }
        binding.bannerProgress.visibility = when (phase) {
            RelayState.Phase.CONNECTING, RelayState.Phase.RECONNECTING, RelayState.Phase.PAIRING -> View.VISIBLE
            else -> View.GONE
        }
    }

    /** What to offer: [RelayUi.fix] for this link's own notification, and "Use here" only where the service has it. */
    private fun fixFor(state: RelayState, paired: Boolean): RelayUi.Fix {
        val fix = RelayUi.fix(state, paired, RelayUi.notificationsOn(activity, backend.service))
        return if (fix == RelayUi.Fix.USE_HERE && Capability.USE_HERE !in backend.capabilities) RelayUi.Fix.STATUS else fix
    }

    private fun act() {
        val backend = backend
        val service = backend.service
        when (fix) {
            RelayUi.Fix.SET_UP -> activity.startActivity(ServiceUi.setup(activity, service))
            RelayUi.Fix.STATUS -> activity.startActivity(ServiceUi.status(activity, service))
            // Cheap and main-thread safe; called directly so it orders with a Disconnect tap.
            RelayUi.Fix.CONNECT -> notifications[service].ensureThen { ServiceUi.startLive(backend) }
            // A link the user wants starts by itself once they are on and a screen is in
            // front again (the manager's onAppVisible); otherwise the banner then offers Connect.
            RelayUi.Fix.NOTIFICATIONS -> notifications[service].openSettings()
            RelayUi.Fix.USE_HERE -> activity.lifecycleScope.launch {
                relayAttempt { backend.useHere() }.onFailure {
                    Toast.makeText(activity, ChatErrors.message(activity, service, it), Toast.LENGTH_LONG).show()
                }
            }
            RelayUi.Fix.NONE -> Unit
        }
    }
}
