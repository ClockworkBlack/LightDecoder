package com.lightdecoder.ui.imessage

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityAppleAccountBinding
import com.lightdecoder.imessage.direct.AppleAccount
import com.lightdecoder.imessage.direct.AppleAccountRules
import com.lightdecoder.imessage.direct.AppleAccountRules.Page
import com.lightdecoder.imessage.direct.AppleAccountRules.Problem
import com.lightdecoder.imessage.direct.AppleAccountRules.Refusal
import com.lightdecoder.ui.applySystemBarPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Sign in with your Apple Account" (SPEC2 §4.7), optional, from the iMessage screen: what it does
 * (every time, with "I understand"), the Apple Account and password, a code from the user's other
 * Apple devices or by text message, Apple's own words when it asks for more, and "Signed in". In the
 * ":imessage" process, where the sign-in itself runs ([AppleAccount], straight, never over the
 * processes' link); not exported, never over the lock screen, and kept out of screenshots and the
 * recent-apps picture (FLAG_SECURE).
 *
 * What is typed stays in the fields only (none keeps its text in the saved state) until it is handed
 * to the sign-in, which holds the password in memory while it may need it again. Leaving the screen
 * lets go of it.
 */
class AppleAccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppleAccountBinding
    private val account: AppleAccount by lazy { AppleAccount.of(this) }

    /** The page shown, as the sign-in has it. */
    private var shown: Page? = null
    private var working: Job? = null
    private var countdown: Job? = null
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityAppleAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leave()
            },
        )
        wire()
        // A sign-in under way (the screen made again) goes on where it was.
        val resumed = account.page
        if (resumed != null) show(resumed) else go { account.begin() }
    }

    override fun onResume() {
        super.onResume()
        // In sight, iMessage's service may be started: a restart still pending is made now. Not under
        // a page that waits to be read ("Signed in", Apple's words, the terms): its "OK" makes it.
        if (!waitsForOk(shown)) account.screenShown()
    }

    override fun onDestroy() {
        // The password is never left behind a screen that is gone (S2).
        account.dropCredentials()
        if (isFinishing && !leaving) {
            // Closed some other way (swiped away, not in sight): as "Not Now", or as "OK" once signed
            // in, but the restart either needs waits for one of LightDecoder's screens to come up.
            if (shown == Page.Done) account.finishSignInLater() else account.cancel(restart = false)
        }
        super.onDestroy()
    }

    /** A page the user reads and closes with "OK", which restarts iMessage if it is to. */
    private fun waitsForOk(page: Page?): Boolean = page == Page.Done || page == Page.Terms || page is Page.AppleMessage

    private fun wire() {
        val intro = binding.intro
        intro.understand.setOnCheckedChangeListener { _, on -> intro.introContinue.isEnabled = on }
        intro.introContinue.setOnClickListener { if (intro.understand.isChecked) go { account.understood() } }
        intro.notNow.setOnClickListener { leave() }

        val c = binding.credentials
        c.signIn.setOnClickListener { signIn() }
        c.password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                signIn()
                true
            } else {
                false
            }
        }
        c.continueAs.setOnClickListener { go { account.continueAsSaved() } }
        c.forgot.setOnClickListener { openIforgot() }

        val d = binding.device
        codeBoxes(d.deviceBoxes, d.deviceCode) { verifyDevice() }
        d.deviceVerify.setOnClickListener { verifyDevice() }
        d.noCode.setOnClickListener { d.deviceOptions.visibility = View.VISIBLE }
        d.textMe.setOnClickListener { go { account.textMeInstead() } }
        d.sendAgain.setOnClickListener {
            if ((shown as? Page.DeviceCode)?.timedOut == true) verifyDevice() else go { account.resendToDevices() }
        }

        val t = binding.text
        t.numbers.setOnCheckedChangeListener { _, id -> t.sendText.isEnabled = id != View.NO_ID }
        t.sendText.setOnClickListener {
            val index = (0 until t.numbers.childCount).firstOrNull { (t.numbers.getChildAt(it) as RadioButton).isChecked } ?: return@setOnClickListener
            go { account.chooseNumber(index) }
        }
        codeBoxes(t.textBoxes, t.textCode) { verifyText() }
        t.textVerify.setOnClickListener { verifyText() }
        t.resend.setOnClickListener { go { account.resendText() } }

        binding.message.messageOk.setOnClickListener { messageOk() }
    }

    private fun signIn() {
        val c = binding.credentials
        val id = c.appleId.text?.toString().orEmpty()
        val password = c.password.text?.toString().orEmpty()
        // Out of the field as soon as it is handed over.
        c.password.text?.clear()
        go { account.signIn(id, password) }
    }

    private fun verifyDevice() {
        val code = binding.device.deviceCode.text?.toString().orEmpty()
        go { account.verifyDeviceCode(code) }
    }

    private fun verifyText() {
        val code = binding.text.textCode.text?.toString().orEmpty()
        go { account.verifyTextCode(code) }
    }

    /** Runs one step of the sign-in (one at a time), then shows where it led. */
    private fun go(step: suspend () -> Page) {
        if (working?.isActive == true) return
        busy(true)
        working = lifecycleScope.launch {
            val page = try {
                step()
            } finally {
                busy(false)
            }
            show(page)
        }
    }

    private fun busy(on: Boolean) {
        binding.progress.visibility = if (on) View.VISIBLE else View.INVISIBLE
        for (b in listOf(binding.credentials.signIn, binding.credentials.continueAs, binding.device.deviceVerify, binding.text.textVerify, binding.text.sendText)) {
            b.alpha = if (on) 0.5f else 1f
        }
    }

    private fun show(page: Page) {
        val previous = shown
        shown = page
        countdown?.cancel()
        val pages = listOf(binding.intro.root, binding.credentials.root, binding.device.root, binding.text.root, binding.message.root)
        val visible = when (page) {
            is Page.Intro -> binding.intro.root
            is Page.Credentials -> binding.credentials.root
            is Page.DeviceCode -> binding.device.root
            is Page.TextNumbers, is Page.TextCode -> binding.text.root
            else -> binding.message.root
        }
        for (p in pages) p.visibility = if (p === visible) View.VISIBLE else View.GONE
        when (page) {
            is Page.Intro -> showIntro(page)
            is Page.Credentials -> showCredentials(page)
            is Page.DeviceCode -> showDevice(page, previous)
            is Page.TextNumbers -> showNumbers(page)
            is Page.TextCode -> showTextCode(page, previous)
            is Page.AppleMessage -> binding.message.messageText.text = page.text
            Page.Terms -> binding.message.messageText.setText(R.string.apple_account_terms)
            Page.Done -> binding.message.messageText.setText(R.string.apple_account_done)
            is Page.Refused -> binding.message.messageText.text = refusalText(page.why)
        }
    }

    private fun showIntro(page: Page.Intro) {
        val bullets = binding.intro.bullets
        bullets.removeAllViews()
        val device = page.device
        val lines = listOf(
            getString(R.string.apple_account_intro_optional),
            getString(R.string.apple_account_intro_unlocks),
            getString(R.string.apple_account_intro_where),
            getString(R.string.apple_account_intro_relay, hostOf(page.relayHost)),
            if (device != null) getString(R.string.apple_account_intro_device, device.name, device.os, device.serial) else getString(R.string.apple_account_intro_device_unknown),
            getString(R.string.apple_account_intro_adp),
            getString(R.string.apple_account_intro_key),
            getString(R.string.apple_account_intro_limits),
            getString(R.string.apple_account_intro_sign_out),
        )
        for (line in lines) {
            bullets.addView(
                TextView(this).apply {
                    text = getString(R.string.apple_account_bullet, line)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding(0, dp(4), 0, dp(4))
                },
            )
        }
        binding.intro.introContinue.isEnabled = binding.intro.understand.isChecked
    }

    private fun showCredentials(page: Page.Credentials) {
        val c = binding.credentials
        c.againBody.visibility = if (page.continueAs != null) View.VISIBLE else View.GONE
        c.continueAs.visibility = if (page.continueAs != null) View.VISIBLE else View.GONE
        page.continueAs?.let { c.continueAs.text = getString(R.string.apple_account_continue_as, it) }
        error(c.credentialsError, page.problem)
        c.forgot.visibility = View.VISIBLE
    }

    private fun showDevice(page: Page.DeviceCode, previous: Page?) {
        val d = binding.device
        if (previous !is Page.DeviceCode) {
            d.deviceCode.text?.clear()
            d.deviceOptions.visibility = View.GONE
        }
        when {
            page.problem != null -> error(d.deviceError, page.problem)
            page.timedOut -> say(d.deviceError, R.string.apple_account_timed_out)
            !page.sent -> say(d.deviceError, R.string.apple_account_not_sent)
            else -> error(d.deviceError, null)
        }
        d.deviceVerify.isEnabled = page.sent
        // After a time-out: "Try Again" (the same code), or a text instead.
        d.sendAgain.setText(if (page.timedOut) R.string.apple_account_try_again else R.string.apple_account_send_again)
        if (page.timedOut || !page.sent) d.deviceOptions.visibility = View.VISIBLE
        if (page.sent) d.deviceCode.requestFocus()
    }

    private fun showNumbers(page: Page.TextNumbers) {
        val t = binding.text
        t.numbersPart.visibility = View.VISIBLE
        t.codePart.visibility = View.GONE
        t.numbers.removeAllViews()
        page.numbers.forEachIndexed { i, number ->
            t.numbers.addView(
                RadioButton(this).apply {
                    id = View.generateViewId()
                    text = number
                    minHeight = dp(48)
                    tag = i
                },
            )
        }
        t.numbers.clearCheck()
        t.sendText.isEnabled = false
        t.numbersPart.visibility = if (page.numbers.isEmpty()) View.GONE else View.VISIBLE
        error(t.textError, page.problem)
    }

    private fun showTextCode(page: Page.TextCode, previous: Page?) {
        val t = binding.text
        t.numbersPart.visibility = View.GONE
        t.codePart.visibility = View.VISIBLE
        if (previous !is Page.TextCode) t.textCode.text?.clear()
        t.textBody.text = page.number?.takeIf { it.isNotBlank() }?.let { getString(R.string.apple_account_text_body, it) }
            ?: getString(R.string.apple_account_text_body_unnamed)
        error(t.textError, page.problem)
        tickResend(page.resendAtMs)
        t.textCode.requestFocus()
    }

    /** "Resend Code", off until a code may be asked for again, with the seconds left. */
    private fun tickResend(atMs: Long) {
        val resend = binding.text.resend
        countdown = lifecycleScope.launch {
            while (true) {
                val left = ((atMs - System.currentTimeMillis()) / 1000).toInt()
                if (left <= 0) {
                    resend.isEnabled = true
                    resend.setText(R.string.apple_account_resend)
                    return@launch
                }
                resend.isEnabled = false
                resend.text = getString(R.string.apple_account_resend_in, left)
                delay(1000)
            }
        }
    }

    private fun say(view: TextView, text: Int) {
        view.setText(text)
        view.visibility = View.VISIBLE
    }

    private fun error(view: TextView, problem: Problem?) {
        val text = problem?.let(::problemText)
        view.text = text
        view.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun problemText(problem: Problem): String = when (problem) {
        Problem.Missing -> getString(R.string.apple_account_missing)
        is Problem.TooManyAttempts -> getString(R.string.apple_account_too_many, minutes(problem.waitMs))
        is Problem.CodeWait ->
            if (problem.waitMs > AppleAccountRules.CODE_COOLDOWN_MS) getString(R.string.apple_account_code_wait_long)
            else getString(R.string.apple_account_code_wait, ((problem.waitMs + 999) / 1000).toInt())
        Problem.WrongPassword -> getString(R.string.apple_account_wrong_password)
        Problem.EnterPassword -> getString(R.string.apple_account_enter_password)
        Problem.Expired -> getString(R.string.apple_account_expired)
        Problem.Incomplete -> getString(R.string.apple_account_incomplete)
        Problem.NoNumbers -> getString(R.string.apple_account_no_numbers)
        Problem.CodeFormat -> getString(R.string.apple_account_code_format)
        is Problem.Apple -> getString(R.string.apple_account_failed, problem.text)
    }

    private fun refusalText(why: Refusal): String = getString(
        when (why) {
            Refusal.BUSY -> R.string.apple_account_busy
            Refusal.NOT_REGISTERED -> R.string.apple_account_not_registered
            Refusal.SIGNED_IN -> R.string.apple_account_already
            Refusal.NOT_STARTED -> R.string.apple_account_not_started
        },
    )

    private fun minutes(ms: Long): Int = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)

    /** "OK": signed in (iMessage restarts), or the words read. */
    private fun messageOk() {
        if (shown == Page.Done) {
            if (!account.canRestart) {
                // A FaceTime call came first: the sign-in is over, and iMessage restarts once the call
                // screen ends the call (or the next time one of LightDecoder's screens comes up).
                account.finishSignInAfterCall()
                binding.message.messageText.setText(R.string.apple_account_restart_pending)
                shown = Page.Refused(Refusal.BUSY)
                return
            }
            // This screen goes first, so that it is not the one Android brings back after the restart.
            leaving = true
            finish()
            account.finishSignInSoon()
            return
        }
        leave()
    }

    /** Leaves the screen; a sign-in not finished is let go of entirely. On "Signed in" it is "OK". */
    private fun leave() {
        if (shown == Page.Done) {
            messageOk()
            return
        }
        leaving = true
        account.cancel()
        finish()
    }

    private fun openIforgot() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(IFORGOT)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            // No browser: nothing to open it in.
        }
    }

    /** Six boxes showing what is typed in [field] (laid over them, its own text unseen); six digits typed: [done]. */
    private fun codeBoxes(boxes: LinearLayout, field: EditText, done: () -> Unit) {
        boxes.removeAllViews()
        val cells = (0 until CODE_LENGTH).map {
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(52)).apply { if (it > 0) marginStart = dp(8) }
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_code_box)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            }
        }
        cells.forEach(boxes::addView)
        field.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val digits = s?.toString().orEmpty()
                    cells.forEachIndexed { i, cell -> cell.text = digits.getOrNull(i)?.toString().orEmpty() }
                    if (digits.length == CODE_LENGTH && working?.isActive != true) done()
                }
            },
        )
    }

    private fun hostOf(address: String): String = try {
        Uri.parse(address).host?.takeIf { it.isNotBlank() } ?: address
    } catch (e: RuntimeException) {
        address
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CODE_LENGTH = 6
        const val IFORGOT = "https://iforgot.apple.com"
    }
}
