package com.lightdecoder.ui.imessage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityAppleAccountBinding
import com.lightdecoder.imessage.direct.AccountFeatures
import com.lightdecoder.imessage.direct.AccountFlag
import com.lightdecoder.imessage.direct.AppleAccount
import com.lightdecoder.imessage.direct.CloudImport
import com.lightdecoder.imessage.direct.KeychainJoin
import com.lightdecoder.imessage.direct.Profiles
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pages of what the Apple Account brings (B6), on the Apple Account screen (in the ":imessage"
 * process, under FLAG_SECURE): "Share Name and Photo" (iPhone: Settings > Messages > Share Name and
 * Photo), "Focus Status" (Settings > Focus > Focus Status) and "Messages in iCloud" (Settings > [name]
 * > iCloud > Messages in iCloud), with the keychain join's own screen. Each does nothing without a
 * signed-in account with iCloud's features on ([AccountFeatures.on]): it only says they need it.
 */
internal class AppleFeaturePages(
    private val activity: AppleAccountActivity,
    private val binding: ActivityAppleAccountBinding,
    private val which: String,
) {

    private val features: AccountFeatures by lazy { AccountFeatures.of(activity) }
    private val account: AppleAccount by lazy { AppleAccount.of(activity) }
    private val page: LinearLayout get() = binding.featurePage
    private var photo: ByteArray? = null
    private var photoView: ImageView? = null
    private var progressJob: Job? = null
    private var joining = false

    private val pickPhoto = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) takePhoto(uri) }

    fun show() {
        for (v in listOf(binding.intro.root, binding.credentials.root, binding.device.root, binding.text.root, binding.message.root)) v.visibility = View.GONE
        page.visibility = View.VISIBLE
        binding.toolbar.title = activity.getString(
            when (which) {
                AppleAccountActivity.FEATURE_PROFILE -> R.string.apple_profile_title
                AppleAccountActivity.FEATURE_FOCUS -> R.string.apple_focus_title
                else -> R.string.apple_icloud_title
            },
        )
        render()
    }

    /** Leaving: a keychain join under way is let go of (nothing was committed that was not). */
    fun leave() {
        if (joining) features.join.cancel()
        joining = false
    }

    fun destroy() {
        progressJob?.cancel()
        if (activity.isFinishing) leave()
    }

    private fun render() {
        page.removeAllViews()
        if (!features.on()) {
            text(R.string.apple_feature_off)
            return
        }
        when (which) {
            AppleAccountActivity.FEATURE_PROFILE -> profilePage()
            AppleAccountActivity.FEATURE_FOCUS -> focusPage()
            else -> icloudPage()
        }
    }

    // --- Share Name and Photo ------------------------------------------------------------------------

    private fun profilePage() {
        val state = account.state.value
        toggle(R.string.apple_profile_receive, state.profiles) { on -> launch { features.setProfilesIn(on) } }
        text(R.string.apple_profile_receive_help, small = true)
        gap()
        text(R.string.apple_profile_body)
        val own = features.profiles.own()
        photo = own?.image
        val image = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = activity.getString(R.string.apple_profile_choose_photo)
        }
        photoView = image
        showPhoto()
        page.addView(image)
        button(R.string.apple_profile_choose_photo) { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        button(R.string.apple_profile_remove_photo) {
            photo = null
            showPhoto()
        }
        val first = field(R.string.apple_profile_first, own?.first.orEmpty())
        val last = field(R.string.apple_profile_last, own?.last.orEmpty())
        button(R.string.apple_profile_save) { save(first.text.toString(), last.text.toString(), replace = false) }
        gap()
        val share = toggle(R.string.apple_profile_share_switch, flagOf().profileShare && own?.pointer != null) { on ->
            launch { features.setProfileShare(on) }
        }
        share.isEnabled = own?.pointer != null
    }

    private fun save(first: String, last: String, replace: Boolean) {
        val pic = photo
        activity.lifecycleScope.launch {
            busy(true)
            val r = try {
                features.profiles.publish(first, last, pic, replace)
            } finally {
                busy(false)
            }
            when (r) {
                Profiles.Publish.Done -> {
                    toast(activity.getString(R.string.apple_profile_saved))
                    account.featuresChanged()
                    render()
                }
                Profiles.Publish.AskReplace -> MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.apple_profile_replace_title)
                    .setMessage(R.string.apple_profile_replace_body)
                    .setPositiveButton(R.string.apple_profile_replace) { _, _ -> save(first, last, replace = true) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .also { d ->
                        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
                        // Cancel is what a stray tap does.
                        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
                    }
                Profiles.Publish.Off -> render()
                is Profiles.Publish.Failed -> toast(activity.getString(R.string.apple_profile_failed, r.why))
            }
        }
    }

    /** A picked picture as the profile's photo: decoded with bounds, its centre square, 512 px, a small JPEG. */
    private fun takePhoto(uri: Uri) {
        activity.lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.IO) { squareJpeg(uri) }
            if (jpeg != null) {
                photo = jpeg
                showPhoto()
            }
        }
    }

    private fun squareJpeg(uri: Uri): ByteArray? = try {
        val resolver = activity.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > 64_000_000L) {
            null
        } else {
            var sample = 1
            while (minOf(w, h) / (sample * 2) >= SIDE) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            decoded?.let {
                val edge = minOf(it.width, it.height)
                val square = Bitmap.createBitmap(it, (it.width - edge) / 2, (it.height - edge) / 2, edge, edge)
                val out = if (edge > SIDE) Bitmap.createScaledBitmap(square, SIDE, SIDE, true) else square
                var quality = 85
                var bytes: ByteArray
                do {
                    bytes = ByteArrayOutputStream().also { s -> out.compress(Bitmap.CompressFormat.JPEG, quality, s) }.toByteArray()
                    quality -= 15
                } while (bytes.size > Profiles.MAX_OWN_IMAGE_BYTES && quality > 20)
                bytes.takeIf { b -> b.size <= Profiles.MAX_OWN_IMAGE_BYTES }
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun showPhoto() {
        val view = photoView ?: return
        val bytes = photo
        val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageResource(R.drawable.bg_avatar)
    }

    // --- Focus Status --------------------------------------------------------------------------------

    private fun focusPage() {
        val flag = flagOf()
        toggle(R.string.apple_focus_receive, flag.focusIn) { on -> launch { features.setFocusIn(on) } }
        if (flag.focusIn && flag.focusUnavailable) text(R.string.apple_focus_unavailable, small = true)
        gap()
        toggle(R.string.apple_focus_share, flag.focusShare) { on -> launch { features.setFocusShare(on) } }
        text(R.string.apple_focus_share_help, small = true)
    }

    // --- Messages in iCloud --------------------------------------------------------------------------

    private fun icloudPage() {
        val flag = flagOf()
        when {
            flag.history == AccountFlag.HISTORY_ON -> historyOn(flag)
            // In the keychain already (turned off since): on again with no join at all.
            features.joined() -> onAgain()
            else -> joinIntro()
        }
    }

    private fun onAgain() {
        text(0).apply { text = activity.getString(R.string.apple_account_bullet, activity.getString(R.string.apple_icloud_join_read_only)) }
        button(R.string.apple_icloud_turn_on) {
            activity.lifecycleScope.launch {
                busy(true)
                try {
                    withContext(Dispatchers.IO) { features.work { features.turnOnAgain() } }
                } finally {
                    busy(false)
                }
                render()
            }
        }
    }

    private fun historyOn(flag: AccountFlag) {
        text(R.string.apple_icloud_window)
        val group = RadioGroup(activity)
        val windows = listOf(
            CloudImport.WINDOW_MONTH to R.string.apple_icloud_window_month,
            CloudImport.WINDOW_YEAR to R.string.apple_icloud_window_year,
            CloudImport.WINDOW_ALL to R.string.apple_icloud_window_all,
        )
        for ((w, label) in windows) {
            group.addView(
                RadioButton(activity).apply {
                    id = View.generateViewId()
                    setText(label)
                    minHeight = dp(48)
                    isChecked = flag.window == w
                    setOnCheckedChangeListener { _, on -> if (on) launch { features.setWindow(w) } }
                },
            )
        }
        page.addView(group)
        val status = text(R.string.apple_icloud_import_now, small = true).apply { text = "" }
        button(R.string.apple_icloud_import_now) { features.importNow() }
        button(R.string.apple_icloud_stop) { features.cloud.stop() }
        gap()
        button(R.string.apple_icloud_turn_off) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.apple_icloud_turn_off)
                .setMessage(R.string.apple_icloud_off_body)
                .setPositiveButton(R.string.apple_icloud_turn_off) { _, _ ->
                    launch {
                        features.historyOff()
                        render()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
        }
        progressJob?.cancel()
        progressJob = activity.lifecycleScope.launch {
            features.cloud.progress.collect { p ->
                status.text = when {
                    p.failed != null -> activity.getString(R.string.apple_icloud_import_failed, p.failed)
                    p.running || p.done || p.chats > 0 || p.messages > 0 -> activity.getString(R.string.apple_icloud_progress, p.chats, p.messages)
                    else -> ""
                }
            }
        }
    }

    private fun joinIntro() {
        when (val p = features.join.begin()) {
            KeychainJoin.Page.Intro -> Unit
            else -> return showJoin(p)
        }
        for (line in listOf(R.string.apple_icloud_join_keychain, R.string.apple_icloud_join_adp, R.string.apple_icloud_join_read_only)) {
            text(0).apply { text = activity.getString(R.string.apple_account_bullet, activity.getString(line)) }
        }
        val understand = CheckBox(activity).apply {
            setText(R.string.apple_account_understand)
            minHeight = dp(48)
        }
        page.addView(understand)
        val turnOn = button(R.string.apple_icloud_turn_on) {
            joining = true
            step { features.join.understood() }
        }
        turnOn.isEnabled = false
        understand.setOnCheckedChangeListener { _, on -> turnOn.isEnabled = on }
    }

    private fun step(block: suspend () -> KeychainJoin.Page) {
        activity.lifecycleScope.launch {
            busy(true)
            val p = try {
                // In the features' own scope: Back or a rotation never cuts a step (a commit above all) short.
                withContext(Dispatchers.IO) { features.work { block() } }
            } finally {
                busy(false)
            }
            showJoin(p)
        }
    }

    private fun showJoin(p: KeychainJoin.Page) {
        page.removeAllViews()
        when (p) {
            KeychainJoin.Page.Intro -> icloudPage()
            is KeychainJoin.Page.DeviceCode -> {
                text(R.string.apple_icloud_code_prompt)
                if (p.problem != null) text(R.string.apple_account_code_format, small = true)
                if (p.waitMs > 0) text(R.string.apple_account_code_wait_long, small = true)
                val code = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    isSaveEnabled = false
                    hint = activity.getString(R.string.apple_account_code)
                }
                page.addView(code)
                button(R.string.apple_account_verify) {
                    val digits = code.text.toString()
                    code.text.clear()
                    step { features.join.verify(digits) }
                }
                button(R.string.apple_account_send_again) { step { features.join.sendAgain() } }
            }
            is KeychainJoin.Page.Code -> {
                joining = false
                text(R.string.apple_icloud_code_title)
                text(0).apply {
                    text = p.code
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                    letterSpacing = 0.2f
                }
                val keep = CheckBox(activity).apply {
                    setText(R.string.apple_icloud_keep_code)
                    minHeight = dp(48)
                }
                page.addView(keep)
                button(R.string.apple_icloud_done) { showJoin(features.join.codeSeen(keep.isChecked)) }
            }
            KeychainJoin.Page.Done -> {
                joining = false
                render()
            }
            is KeychainJoin.Page.Failed -> {
                joining = false
                val line = when (p.why) {
                    KeychainJoin.Why.BUSY -> activity.getString(R.string.apple_account_busy)
                    KeychainJoin.Why.NOT_ALLOWED -> activity.getString(R.string.apple_icloud_not_allowed)
                    KeychainJoin.Why.NOT_READY -> activity.getString(R.string.apple_icloud_not_ready)
                    KeychainJoin.Why.SIGN_IN_AGAIN -> activity.getString(R.string.apple_icloud_failed_sign_in_again)
                    KeychainJoin.Why.ADP -> activity.getString(R.string.apple_icloud_failed_adp)
                    KeychainJoin.Why.NOT_JOINED -> activity.getString(R.string.apple_icloud_failed_not_joined)
                    KeychainJoin.Why.FAILED -> activity.getString(R.string.apple_icloud_failed, p.text.orEmpty())
                    KeychainJoin.Why.TOO_MANY -> activity.getString(R.string.apple_account_too_many, ((p.waitMs + 59_999) / 60_000).toInt().coerceAtLeast(1))
                }
                text(0).apply { text = line }
                button(R.string.apple_account_ok) { activity.finish() }
            }
        }
    }

    // --- small views ---------------------------------------------------------------------------------

    private fun flagOf(): AccountFlag = features.flag()

    private fun launch(block: suspend () -> Unit) {
        activity.lifecycleScope.launch { withContext(Dispatchers.IO) { block() } }
    }

    private fun text(res: Int, small: Boolean = false): TextView = TextView(activity).apply {
        if (res != 0) setText(res)
        setTextAppearance(
            if (small) com.google.android.material.R.style.TextAppearance_Material3_BodySmall else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        setPadding(0, dp(6), 0, dp(6))
        page.addView(this)
    }

    private fun gap() {
        page.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(16)) })
    }

    private fun toggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch = MaterialSwitch(activity).apply {
        setText(label)
        isChecked = checked
        minHeight = dp(48)
        filterTouchesWhenObscured = true
        setOnCheckedChangeListener { _, on -> onChange(on) }
        page.addView(this)
    }

    private fun field(hint: Int, value: String): EditText = EditText(activity).apply {
        setHint(hint)
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSaveEnabled = false
        page.addView(this)
    }

    private fun button(label: Int, onClick: () -> Unit): MaterialButton = MaterialButton(activity).apply {
        setText(label)
        filterTouchesWhenObscured = true
        setOnClickListener { onClick() }
        page.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun busy(on: Boolean) {
        binding.progress.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val SIDE = 512
    }
}
