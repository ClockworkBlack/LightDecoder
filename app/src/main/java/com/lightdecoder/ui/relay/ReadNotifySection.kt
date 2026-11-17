package com.lightdecoder.ui.relay

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ReadNotifyPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "read" notifications part of a link's settings ([R.layout.section_read_notify]):
 * the switch for all of it, "even while LightDecoder is open", and the
 * list of people (this service's conversations) to have it for.
 */
object ReadNotifySection {

    fun bind(activity: AppCompatActivity, root: View, service: ChatService, askPermission: () -> Unit) {
        val enabled = root.findViewById<MaterialSwitch>(R.id.switchReadNotify)
        val whileLooking = root.findViewById<MaterialSwitch>(R.id.switchReadWhileLooking)
        val people = root.findViewById<View>(R.id.readNotifyPeople)
        fun sync() {
            enabled.isChecked = ReadNotifyPrefs.enabled(activity)
            whileLooking.isChecked = ReadNotifyPrefs.whileLooking(activity)
            whileLooking.isEnabled = enabled.isChecked
            people.isEnabled = enabled.isChecked
        }
        enabled.setOnClickListener {
            ReadNotifyPrefs.setEnabled(activity, enabled.isChecked)
            if (enabled.isChecked) askPermission()
            sync()
        }
        whileLooking.setOnClickListener { ReadNotifyPrefs.setWhileLooking(activity, whileLooking.isChecked) }
        people.setOnClickListener { choosePeople(activity, service) }
        sync()
    }

    /** Every conversation of [service], ticked when its read notifications are on. */
    private fun choosePeople(activity: AppCompatActivity, service: ChatService) {
        activity.lifecycleScope.launch {
            val conversations = withContext(Dispatchers.IO) {
                runCatching { ArchiveRepository.get(activity, service).conversations(folder = null) }.getOrDefault(emptyList())
            }.filter { it.name.isNotBlank() }.sortedBy { it.name.lowercase() }
            if (conversations.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.read_choose_people)
                    .setMessage(R.string.read_choose_people_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val names = conversations.map { it.name }.toTypedArray()
            val ticked = BooleanArray(conversations.size) { ReadNotifyPrefs.forConversation(activity, service, conversations[it].id) }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.read_choose_people)
                .setMultiChoiceItems(names, ticked) { _, which, on -> ticked[which] = on }
                .setPositiveButton(R.string.read_choose_people_done) { _, _ ->
                    conversations.forEachIndexed { i, c -> ReadNotifyPrefs.setForConversation(activity, service, c.id, ticked[i]) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
