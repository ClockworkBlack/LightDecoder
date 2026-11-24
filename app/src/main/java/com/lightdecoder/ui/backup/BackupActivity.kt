package com.lightdecoder.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.lightdecoder.R
import com.lightdecoder.backup.AppRestart
import com.lightdecoder.backup.BackupException
import com.lightdecoder.backup.BackupManifest
import com.lightdecoder.backup.ChatBackup
import com.lightdecoder.backup.Passphrase
import com.lightdecoder.databinding.ActivityBackupBinding
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Back up chats ([MODE_BACKUP]) or Restore chats ([MODE_RESTORE]): the passphrase, a file picked
 * through the system's document picker, and the work ([ChatBackup]) off the main thread with a
 * progress dialog that can be cancelled. Kept out of screenshots and the recents preview; the
 * passphrase fields are never autofilled, saved or kept in the screen's saved state, and the
 * characters read from them are wiped once used.
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding
    private val restoring get() = intent.getStringExtra(EXTRA_MODE) == MODE_RESTORE
    private var busy = false
    private val cancelRequested = AtomicBoolean(false)
    private var progressDialog: AlertDialog? = null
    private var progressBar: LinearProgressIndicator? = null
    private var progressText: TextView? = null
    private var lastPermille = -1

    /** Set once a restore replaced the chats (by this screen or one before it): from then on only a restart is safe. */
    private val restored get() = AppRestart.restored.value != null
    private var restartDialog: AlertDialog? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(ChatBackup.MIME)) { uri ->
        if (uri != null) runBackup(uri)
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            binding.passphrase.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            binding.confirm.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        if (restoring) {
            supportActionBar?.setTitle(R.string.restore_menu)
            binding.intro.setText(R.string.restore_intro)
            binding.passphraseNote.visibility = View.GONE
            binding.strength.visibility = View.GONE
            binding.confirmLayout.visibility = View.GONE
            binding.start.setText(R.string.restore_start)
        } else {
            supportActionBar?.setTitle(R.string.backup_menu)
            binding.intro.setText(R.string.backup_intro)
            binding.start.setText(R.string.backup_start)
            binding.passphrase.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = showStrength()
            })
        }
        binding.start.setOnClickListener { start() }
        // A restore that finished, even under a screen that is gone since, asks for the restart here.
        lifecycleScope.launch {
            AppRestart.restored.filterNotNull().first().let { showRestart(it) }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (!busy) finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busy) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        // The chats were replaced: nothing may read the old ones, so the app starts afresh.
        if (restored && !isChangingConfigurations) AppRestart.restart(this)
    }

    override fun onDestroy() {
        // A backup still running when the screen goes stops (and deletes its file).
        cancelRequested.set(true)
        progressDialog?.dismiss()
        super.onDestroy()
    }

    private fun showStrength() {
        val chars = read(binding.passphrase.text)
        val strength = Passphrase.strength(chars)
        chars.fill('\u0000')
        binding.strength.setText(
            when (strength) {
                Passphrase.Strength.TOO_SHORT -> R.string.backup_passphrase_short
                Passphrase.Strength.WEAK -> R.string.backup_strength_weak
                Passphrase.Strength.FAIR -> R.string.backup_strength_fair
                Passphrase.Strength.STRONG -> R.string.backup_strength_strong
            }
        )
    }

    /** What the fields hold is usable: a long enough passphrase, typed the same twice for a backup. */
    private fun fieldsOk(): Boolean {
        binding.passphraseLayout.error = null
        binding.confirmLayout.error = null
        val first = read(binding.passphrase.text)
        try {
            if (restoring) {
                if (first.isEmpty()) {
                    binding.passphraseLayout.error = getString(R.string.backup_passphrase_missing)
                    return false
                }
                return true
            }
            if (first.size < Passphrase.MIN_LENGTH) {
                binding.passphraseLayout.error = getString(
                    if (first.isEmpty()) R.string.backup_passphrase_missing else R.string.backup_passphrase_short
                )
                return false
            }
            val second = read(binding.confirm.text)
            try {
                if (!first.contentEquals(second)) {
                    binding.confirmLayout.error = getString(R.string.backup_passphrase_mismatch)
                    return false
                }
            } finally {
                second.fill('\u0000')
            }
            return true
        } finally {
            first.fill('\u0000')
        }
    }

    private fun start() {
        if (busy || !fieldsOk()) return
        try {
            if (restoring) openDocument.launch(arrayOf(ChatBackup.MIME, "*/*"))
            else createDocument.launch(ChatBackup.defaultFileName())
        } catch (e: android.content.ActivityNotFoundException) {
            showMessage(getString(if (restoring) R.string.restore_error_io else R.string.backup_error_io))
        }
    }

    // --- backup -----------------------------------------------------------------------------

    private sealed class Outcome {
        class BackedUp(val manifest: BackupManifest) : Outcome()
        class Restored(val manifest: BackupManifest) : Outcome()
        object Cancelled : Outcome()
        class Failed(val error: Throwable) : Outcome()
    }

    private fun runBackup(uri: Uri) {
        // The screen may have been recreated while the picker was open: its fields are then empty.
        if (!fieldsOk()) {
            deleteDocument(uri)
            return
        }
        val passphrase = read(binding.passphrase.text)
        val app = applicationContext
        begin(R.string.backup_progress)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                val cancel = ChatBackup.Cancel { if (cancelRequested.get() || !job.isActive) throw CancellationException() }
                var out: OutputStream? = null
                try {
                    out = openForWriting(uri) ?: throw IOException("no output stream")
                    Outcome.BackedUp(ChatBackup.backup(app, out, passphrase, progress = ::onProgress, cancel = cancel))
                } catch (e: Throwable) {
                    // Closed before it is deleted, so no open handle is left on the document.
                    runCatching { out?.close() }
                    deleteDocument(uri)
                    if (e is CancellationException) Outcome.Cancelled else Outcome.Failed(e)
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            end()
            when (outcome) {
                is Outcome.BackedUp -> {
                    clearFields()
                    val m = outcome.manifest
                    val text = getString(R.string.backup_done, m.conversations, m.messages, m.files) +
                        if (m.skippedTooLarge > 0) "\n\n" + getString(R.string.backup_done_skipped, m.skippedTooLarge) else ""
                    showMessage(text, finishAfter = true)
                }
                Outcome.Cancelled -> showMessage(getString(R.string.backup_cancelled))
                is Outcome.Failed -> showFailure(outcome.error)
                is Outcome.Restored -> Unit
            }
        }
    }

    private fun openForWriting(uri: Uri): OutputStream? = try {
        contentResolver.openOutputStream(uri, "wt")
    } catch (e: FileNotFoundException) {
        contentResolver.openOutputStream(uri, "w")
    } catch (e: IllegalArgumentException) {
        contentResolver.openOutputStream(uri, "w")
    }

    /** A backup that did not finish is not left behind (and if the provider cannot delete, it is emptied). */
    private fun deleteDocument(uri: Uri) {
        val deleted = runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
        if (!deleted) runCatching { openForWriting(uri)?.close() }
    }

    // --- restore ----------------------------------------------------------------------------

    private fun confirmRestore(uri: Uri) {
        if (!fieldsOk()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_body)
            .setPositiveButton(R.string.restore_confirm_button) { _, _ -> runRestore(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runRestore(uri: Uri) {
        if (busy || !fieldsOk()) return
        val passphrase = read(binding.passphrase.text)
        val app = applicationContext
        begin(R.string.restore_progress_check)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                val cancel = ChatBackup.Cancel { if (cancelRequested.get() || !job.isActive) throw CancellationException() }
                try {
                    val size = sizeOf(uri)
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("no input stream")
                    val staged = ChatBackup.prepareRestore(app, input, passphrase, progress = ::onProgress, cancel = cancel, totalBytes = size)
                    // From here on it is not cancelled: the swap is quick and must not stop halfway.
                    if (cancelRequested.get()) {
                        staged.close()
                        throw CancellationException()
                    }
                    runOnUiThread { applying() }
                    ChatBackup.applyRestore(app, staged)
                    AppRestart.markRestored(staged.manifest.conversations, staged.manifest.messages)
                    AppRestart.restartLater(app)
                    Outcome.Restored(staged.manifest)
                } catch (e: Throwable) {
                    if (e is CancellationException) Outcome.Cancelled else Outcome.Failed(e)
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            when (outcome) {
                is Outcome.Restored -> {
                    end()
                    clearFields()
                    AppRestart.restored.value?.let { showRestart(it) }
                }
                Outcome.Cancelled -> {
                    end()
                    showMessage(getString(R.string.restore_cancelled))
                }
                is Outcome.Failed -> {
                    end()
                    showFailure(outcome.error)
                }
                is Outcome.BackedUp -> end()
            }
        }
    }

    /** The chats were replaced: the one thing left to do is restart. Shown once per screen. */
    private fun showRestart(info: AppRestart.Restored) {
        if (restartDialog != null || isFinishing || isDestroyed) return
        busy = true
        binding.start.isEnabled = false
        progressDialog?.dismiss()
        progressDialog = null
        restartDialog = MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.restore_done, info.conversations, info.messages))
            .setCancelable(false)
            .setPositiveButton(R.string.restore_restart) { _, _ -> AppRestart.restart(this@BackupActivity) }
            .show()
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    // --- progress ---------------------------------------------------------------------------

    private fun begin(message: Int) {
        busy = true
        cancelRequested.set(false)
        lastPermille = -1
        binding.start.isEnabled = false
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply { setText(message) }
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            max = 1000
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(text)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad / 2
            })
        }
        progressText = text
        progressBar = bar
        progressDialog = MaterialAlertDialogBuilder(this)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelRequested.set(true) }
            .show()
    }

    private fun applying() {
        progressText?.setText(R.string.restore_progress_apply)
        progressBar?.isIndeterminate = true
        progressDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
    }

    /** From the worker thread; the dialog is updated at most once per tenth of a percent. */
    private fun onProgress(done: Long, total: Long) {
        if (total <= 0) return
        val permille = (done.coerceAtMost(total) * 1000 / total).toInt()
        if (permille == lastPermille) return
        lastPermille = permille
        runOnUiThread {
            progressBar?.let {
                if (it.isIndeterminate) it.isIndeterminate = false
                it.setProgressCompat(permille, false)
            }
        }
    }

    private fun end() {
        busy = false
        binding.start.isEnabled = true
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        progressText = null
    }

    // --- messages ---------------------------------------------------------------------------

    private fun showFailure(error: Throwable) {
        val reason = (error as? BackupException)?.reason
        // The reason only: never the passphrase, a path or file content.
        Log.w(TAG, "${if (restoring) "restore" else "backup"} failed: ${reason ?: error.javaClass.simpleName}")
        showMessage(getString(messageFor(reason, restoring)))
    }

    private fun showMessage(text: String, finishAfter: Boolean = false) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ -> if (finishAfter) finish() }
            .setOnCancelListener { if (finishAfter) finish() }
            .show()
    }

    private fun clearFields() {
        binding.passphrase.text?.clear()
        binding.confirm.text?.clear()
    }

    companion object {
        private const val TAG = "ChatBackup"
        const val EXTRA_MODE = "com.lightdecoder.extra.BACKUP_MODE"
        const val MODE_BACKUP = "backup"
        const val MODE_RESTORE = "restore"

        fun backupIntent(context: Context) = Intent(context, BackupActivity::class.java).putExtra(EXTRA_MODE, MODE_BACKUP)
        fun restoreIntent(context: Context) = Intent(context, BackupActivity::class.java).putExtra(EXTRA_MODE, MODE_RESTORE)

        /** The characters of [text], in an array the caller wipes. */
        internal fun read(text: CharSequence?): CharArray {
            if (text == null) return CharArray(0)
            val out = CharArray(text.length)
            if (text is Editable) text.getChars(0, text.length, out, 0) else for (i in out.indices) out[i] = text[i]
            return out
        }

        /** The words shown for a failure of [reason] (null: an error that is not the backup's own). */
        internal fun messageFor(reason: BackupException.Reason?, restoring: Boolean): Int = when (reason) {
            BackupException.Reason.NOT_A_BACKUP -> R.string.restore_error_not_backup
            BackupException.Reason.UNSUPPORTED_FORMAT -> R.string.restore_error_format
            BackupException.Reason.WRONG_PASSPHRASE -> R.string.restore_error_passphrase
            BackupException.Reason.DAMAGED -> R.string.restore_error_damaged
            BackupException.Reason.NEWER_ARCHIVE -> R.string.restore_error_newer
            BackupException.Reason.INVALID_CONTENT -> R.string.restore_error_invalid
            BackupException.Reason.BUSY -> if (restoring) R.string.restore_error_busy else R.string.backup_error_busy
            BackupException.Reason.NO_SPACE -> R.string.restore_error_space
            BackupException.Reason.LOCAL_ARCHIVE_DAMAGED -> R.string.backup_error_local
            BackupException.Reason.IO, null -> if (restoring) R.string.restore_error_io else R.string.backup_error_io
        }
    }
}
