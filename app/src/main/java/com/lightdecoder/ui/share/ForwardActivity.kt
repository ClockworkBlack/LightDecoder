package com.lightdecoder.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityShareBinding
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ConversationItem
import com.lightdecoder.ui.chat.ScreenBackends
import com.lightdecoder.ui.chat.applySystemBarAndImePadding

/**
 * "Forward To": a message's text and files ([com.lightdecoder.ui.chat.ForwardContent]), already
 * copied where shares are kept ([SharedContent.copyOwn]), go to a conversation picked here
 * ([ConversationPicker], as the share screen lists them). Picking one sends nothing: it opens with
 * them in its composer, for the user to look at and send, as an iPhone puts a forwarded message in a
 * new message first. Conversations with someone the user blocked are not offered.
 *
 * Not exported: only LightDecoder's own chat screen starts it. Even so only its own copies are taken
 * ([SharedContent.isOwnCopy]), as the chat screen takes them; the exported share screen
 * ([ShareActivity]) never takes anything of this kind.
 */
class ForwardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private var picker: ConversationPicker? = null
    private var text: String = ""
    private var files: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        binding.toolbar.setTitle(R.string.forward_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        binding.toolbar.setNavigationContentDescription(android.R.string.cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }

        text = intent.getStringExtra(EXTRA_TEXT)?.take(MAX_TEXT_CHARS).orEmpty()
        files = intent.getStringArrayListExtra(EXTRA_FILES).orEmpty()
            .map(Uri::parse).filter { SharedContent.isOwnCopy(this, it) }.distinct().take(SharedContent.MAX_STREAMS)
        if (text.isBlank() && files.isEmpty()) {
            stop(R.string.forward_unavailable)
            return
        }
        binding.what.text = describe()
        val backends = ScreenBackends.all(this).filter { it.isPaired() || (it.service == ChatService.MESSAGES && iMessageOn()) }
        if (backends.isEmpty()) {
            stop(R.string.forward_no_conversations)
            return
        }
        picker = ConversationPicker(
            this, binding, backends, onPick = ::open,
            // Only conversations that can be written in, and never one with someone the user blocked.
            keep = { !it.row.readOnly && !it.blocked },
            publishShortcuts = false,
        ).apply { emptyText = R.string.forward_no_conversations }
        picker?.start()
    }

    override fun onStart() {
        super.onStart()
        picker?.onStart()
    }

    override fun onStop() {
        picker?.onStop()
        super.onStop()
    }

    private fun iMessageOn(): Boolean =
        IMessagePrefs.enabled(this) && ScreenBackends.get(this, ChatService.IMESSAGE).isPaired()

    private fun describe(): String {
        val counted = resources.getQuantityString(R.plurals.share_what_files, files.size, files.size)
        return when {
            files.isEmpty() -> text
            text.isEmpty() -> counted
            else -> getString(R.string.share_what_files_and_text, counted, text)
        }
    }

    private fun stop(message: Int) {
        binding.what.visibility = View.GONE
        binding.queryBox.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.empty.visibility = View.VISIBLE
        binding.empty.setText(message)
    }

    /** The conversation [item] opens with the message in its composer. */
    private fun open(item: ConversationItem) {
        startActivity(RelayIntents.threadWithShared(this, item.row.id, item.service, text, files))
        finish()
    }

    companion object {
        private const val EXTRA_TEXT = "com.lightdecoder.forward.TEXT"
        private const val EXTRA_FILES = "com.lightdecoder.forward.FILES"

        /** The longest text forwarded (an iMessage is never longer). */
        private const val MAX_TEXT_CHARS = 20_000

        /** Opens "Forward To" with [text] and [files] (copies [SharedContent.copyOwn] made). */
        fun intent(context: Context, text: String, files: List<Uri>): Intent =
            Intent(context, ForwardActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putStringArrayListExtra(EXTRA_FILES, ArrayList(files.map { it.toString() }))
    }
}
