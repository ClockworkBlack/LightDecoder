package com.lightdecoder.ui.relay

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.RelayManager
import kotlinx.coroutines.CancellationException
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
     * stored for the link, through [RelayManager.deleteLocalArchive] (the one
     * way in, through the archive's gate). The sticker inbox is not touched.
     * Refuses (returns false, deleting nothing) while a pairing exists: then
     * Unpair with "also delete" is the way, so a running sync cannot write
     * into the emptied archive. Does not throw.
     */
    suspend fun deleteUnpaired(manager: RelayManager): Boolean =
        try {
            manager.deleteLocalArchive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    /** Asks first, then deletes; [onDeleted] runs on the main thread once it is gone. */
    fun confirmDelete(activity: AppCompatActivity, onDeleted: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_delete_archive_title)
            .setMessage(R.string.link_delete_archive_body)
            .setPositiveButton(R.string.link_delete_archive_confirm) { _, _ ->
                val app = activity.applicationContext
                activity.lifecycleScope.launch {
                    // Once confirmed it finishes even if the screen closes meanwhile
                    // (the manager runs it in its own scope as well).
                    val deleted = withContext(NonCancellable) { deleteUnpaired(RelayManager.get(app)) }
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
