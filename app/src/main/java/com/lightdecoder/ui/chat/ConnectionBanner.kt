package com.lightdecoder.ui.chat

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lightdecoder.databinding.ViewConnectionBannerBinding
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.relay.NotificationPermission
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
import com.lightdecoder.ui.relay.RelayUi
import com.lightdecoder.ui.relay.relayAttempt
import com.lightdecoder.ui.relay.runInBackground
import kotlinx.coroutines.launch

/**
 * The strip at the top of the messaging screens that says when the link is
 * not live (offline, connecting, in use elsewhere, needs fixing) and offers
 * the one action that helps. Hidden while connected.
 */
class ConnectionBanner(
    private val activity: AppCompatActivity,
    private val binding: ViewConnectionBannerBinding,
    private val manager: RelayManager,
    private val notifications: NotificationPermission,
) {
    private var fix = RelayUi.Fix.NONE

    init {
        binding.bannerAction.setOnClickListener { act() }
    }

    fun render(state: RelayState, paired: Boolean) {
        if (paired && state.isOnline) {
            binding.root.visibility = View.GONE
            return
        }
        binding.root.visibility = View.VISIBLE
        // Without a pairing say "not linked", unless the link knows why (logged out, say).
        val phase = if (!paired && !RelayUi.isProblem(state.phase)) RelayState.Phase.UNPAIRED else state.phase
        binding.bannerText.setText(RelayUi.title(phase))
        // The specific reason, when the link gave one we can explain.
        val reason = if (state.code.isNotEmpty() && RelayUi.isProblem(phase)) RelayErrors.message(activity, state.code) else null
        binding.bannerDetail.text = reason ?: activity.getString(RelayUi.body(phase))
        fix = RelayUi.fix(state, paired)
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

    private fun act() {
        when (fix) {
            RelayUi.Fix.SET_UP -> activity.startActivity(Intent(activity, RelaySetupActivity::class.java))
            RelayUi.Fix.STATUS -> activity.startActivity(Intent(activity, RelayStatusActivity::class.java))
            RelayUi.Fix.CONNECT -> notifications.ensureThen { runInBackground { manager.startLive() } }
            RelayUi.Fix.USE_HERE -> activity.lifecycleScope.launch {
                relayAttempt { manager.useHere() }.onFailure {
                    Toast.makeText(activity, RelayErrors.message(activity, it), Toast.LENGTH_LONG).show()
                }
            }
            RelayUi.Fix.NONE -> Unit
        }
    }
}
