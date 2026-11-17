package com.lightdecoder.ui.wa

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityWaSetupBinding
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ChatErrors
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ServiceUi
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import com.lightdecoder.ui.relay.NotificationPermission
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.wa.WaSetupModel.Step
import com.lightdecoder.wa.WaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Links this device to the user's WhatsApp account, in four steps:
 *
 *  1. What this is, honestly: an unofficial client that talks to WhatsApp
 *     the way WhatsApp Web does, which WhatsApp's terms do not allow; the
 *     account can be restricted or banned for it; what is stored on this
 *     phone; how to stop.
 *  2. The user's own number.
 *  3. The pairing code, which the user types into WhatsApp on their phone
 *     (Linked devices > Link a device > Link with phone number instead), or
 *     a QR code they scan with it. That confirmation is theirs alone:
 *     nothing here answers it.
 *  4. Done.
 *
 * Opened with [com.lightdecoder.relay.RelayIntents.waSetup]. The code and
 * the QR code are secrets while they are valid: the window is kept out of
 * screenshots and the recents preview, and neither is ever logged.
 */
class WaSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaSetupBinding
    private lateinit var manager: WaManager
    private val model: WaSetupModel by viewModels()
    private val notifications = NotificationPermission(this, ChatService.WHATSAPP)

    /** The QR text the image shows, so the same code is not drawn twice. */
    private var shownQr = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityWaSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = WaManager.get(this)
        model.attach(manager)

        // A debug build's private storage (the archive, the link's keys) is open to adb: say so up front.
        binding.introDebugWarning.visibility =
            if (RelaySetupActivity.isDebuggableBuild(this)) View.VISIBLE else View.GONE
        binding.introAgree.setOnCheckedChangeListener { _, checked -> binding.introContinue.isEnabled = checked }
        binding.introContinue.isEnabled = binding.introAgree.isChecked
        // Ask for notifications first: once linked, the "connected" notification
        // with its Disconnect button is how the user sees and stops the link.
        binding.introContinue.setOnClickListener { notifications.ensureThen { model.goTo(Step.NUMBER) } }
        binding.getCode.setOnClickListener { model.requestCode(binding.number.text?.toString().orEmpty()) }
        binding.number.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                model.requestCode(binding.number.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.useQr.setOnClickListener { model.startQr() }
        binding.copyCode.setOnClickListener { copyCode() }
        binding.codeCancel.setOnClickListener { model.cancel() }
        binding.qrUseCode.setOnClickListener { model.cancel() }
        binding.doneOpen.setOnClickListener {
            // Its own tab of the conversation list, whichever tab was open last.
            startActivity(
                Intent(this, ConversationsActivity::class.java)
                    .putExtra(RelayIntents.EXTRA_SERVICE, ChatService.WHATSAPP.key)
            )
            finish()
        }
        binding.doneStatus.setOnClickListener {
            startActivity(ServiceUi.status(this, ChatService.WHATSAPP))
            finish()
        }
        onBackPressedDispatcher.addCallback(this) { goBack() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.ui.collect { render(it) } }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            goBack()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Back leaves a code that is showing (abandoning it) before it leaves the screen. */
    private fun goBack() {
        when (model.ui.value.step) {
            Step.CODE, Step.QR -> model.cancel()
            Step.NUMBER -> if (model.ui.value.busy) model.cancel() else model.goTo(Step.INTRO)
            Step.INTRO, Step.DONE -> finish()
        }
    }

    private fun render(ui: WaSetupModel.Ui) {
        binding.stepIntro.visibility = visibleIf(ui.step == Step.INTRO)
        binding.stepNumber.visibility = visibleIf(ui.step == Step.NUMBER)
        binding.stepCode.visibility = visibleIf(ui.step == Step.CODE)
        binding.stepQr.visibility = visibleIf(ui.step == Step.QR)
        binding.stepDone.visibility = visibleIf(ui.step == Step.DONE)
        when (ui.step) {
            Step.INTRO -> Unit
            Step.NUMBER -> {
                binding.numberProgress.visibility = visibleIf(ui.busy)
                binding.getCode.isEnabled = !ui.busy
                binding.useQr.isEnabled = !ui.busy
                binding.number.isEnabled = !ui.busy
                binding.numberError.visibility = visibleIf(ui.error.isNotEmpty())
                if (ui.error.isNotEmpty()) binding.numberError.text = errorText(ui.error)
            }
            Step.CODE -> {
                val code = spaced(ui.code)
                binding.code.text = code
                // Read out character by character, not as a word.
                binding.code.contentDescription =
                    getString(R.string.wa_code_spoken, ui.code.toCharArray().joinToString(" "))
            }
            Step.QR -> showQr(ui.code)
            Step.DONE -> {
                binding.doneTitle.setText(if (ui.already) R.string.wa_done_already_title else R.string.wa_done_title)
                binding.doneBody.setText(if (ui.already) R.string.wa_done_already_body else R.string.wa_done_body)
            }
        }
        if (ui.step != Step.QR) {
            shownQr = ""
            binding.qr.setImageDrawable(null)
        }
    }

    private fun errorText(code: String): String =
        if (code == RelayException.INVALID_ARGUMENT) getString(R.string.wa_number_invalid)
        else ChatErrors.message(this, ChatService.WHATSAPP, code)

    private fun showQr(text: String) {
        if (text == shownQr) return
        shownQr = text
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { qrBitmap(text) }
            // A newer code may have come meanwhile; it draws itself.
            if (shownQr == text) binding.qr.setImageBitmap(bitmap)
        }
    }

    private fun copyCode() {
        val code = model.ui.value.code
        if (code.isEmpty()) return
        val clip = ClipData.newPlainText(getString(R.string.wa_code_clip_label), code)
        // Kept out of the clipboard preview and of keyboards' suggestions where Android can.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(
                if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE",
                true,
            )
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.wa_code_copied, Toast.LENGTH_SHORT).show()
    }

    private fun visibleIf(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    internal companion object {
        /** "ABCD-EFGH": WhatsApp shows the code in two halves on the phone as well. */
        fun spaced(code: String): String =
            if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code

        /** [text] as a black-on-white QR code, or null if it cannot be encoded. One pixel per module; the view scales it. */
        fun qrBitmap(text: String): Bitmap? {
            if (text.isEmpty()) return null
            val matrix = try {
                QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
            } catch (e: WriterException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
            val scale = QR_SCALE
            val width = matrix.width * scale
            val height = matrix.height * scale
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (matrix.get(x / scale, y / scale)) Color.BLACK else Color.WHITE
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        /** Whole pixels per module, so that scaling the bitmap up stays sharp enough to scan. */
        private const val QR_SCALE = 8
    }
}
