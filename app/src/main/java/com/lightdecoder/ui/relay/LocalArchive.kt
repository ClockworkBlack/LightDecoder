package com.lightdecoder.ui.relay

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.RelayLog
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The message archive an unpair left behind ("keep the archive"). Without a
 * pairing the phone cannot be asked to delete anything, so whatever is
 * deleted then is deleted on this device only.
 */
internal object LocalArchive {

    /** Whether the archive holds any conversation. Reads the database: call off the main thread. */
    fun hasAny(repo: ArchiveRepository): Boolean = repo.conversations(null, 1).isNotEmpty()

    /**
     * Deletes every conversation, message, media file and queued attachment
     * stored for the link. The sticker inbox is not touched. Refuses (returns
     * false, deleting nothing) while a pairing exists: then Unpair with
     * "also delete" is the way, so a running sync cannot write into the
     * emptied archive. Call off the main thread.
     */
    fun deleteUnpaired(context: Context, manager: RelayManager, repo: ArchiveRepository): Boolean {
        if (manager.isPaired()) return false
        repo.clearAll()
        RelayPrefs.setBackfillDoneAt(context, 0)
        RelayPrefs.setArchiveOwner(context, "", "")
        RelayPrefs.setUnconfirmedSends(context, emptyMap())
        MessageNotifier.cancelAll(context)
        RelayLog.add(context, "Local archive deleted")
        return true
    }

    /** Asks first, then deletes; [onDeleted] runs on the main thread once it is gone. */
    fun confirmDelete(activity: AppCompatActivity, onDeleted: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_delete_archive_title)
            .setMessage(R.string.link_delete_archive_body)
            .setPositiveButton(R.string.link_delete_archive_confirm) { _, _ ->
                val app = activity.applicationContext
                activity.lifecycleScope.launch {
                    // Once confirmed it finishes even if the screen closes meanwhile.
                    val deleted = withContext(Dispatchers.IO + NonCancellable) {
                        runCatching {
                            deleteUnpaired(app, RelayManager.get(app), ArchiveRepository.get(app))
                        }.getOrDefault(false)
                    }
                    Toast.makeText(
                        activity,
                        if (deleted) R.string.link_archive_deleted else R.string.link_archive_delete_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    if (deleted) onDeleted()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
