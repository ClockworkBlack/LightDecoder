package com.lightdecoder.imessage.direct

import com.lightdecoder.imessage.CallingCodes

/**
 * The rules of the optional Apple Account sign-in ([AppleAccount]), pure: where each of the library's
 * answers leads ([next]), the limits on attempts and codes ([Limits]), how the Apple ID shows
 * ([mask]), what a start finds and does about it ([atStart]), and which errors mean what
 * ([classify]). Tested in AppleAccountRulesTest.
 */
object AppleAccountRules {

    // --- limits (no loops, nothing Apple would take for an attack) ------------------------------

    /** At most this many failed password attempts in [PASSWORD_WINDOW_MS]. */
    const val PASSWORD_MAX = 3
    const val PASSWORD_WINDOW_MS = 15 * 60_000L

    /** Codes (to the trusted devices, or texted): one a minute at most, and at most [CODE_MAX] an hour. */
    const val CODE_COOLDOWN_MS = 60_000L
    const val CODE_MAX = 5
    const val CODE_WINDOW_MS = 60 * 60_000L

    /** The password is let go of this long after it was typed, whatever the sign-in is at. */
    const val CREDENTIALS_MS = 10 * 60_000L

    /** At most this many times of each kind are kept. */
    const val MAX_STAMPS = 20

    /**
     * When the password failed, and when codes were asked for, as times (ms). Kept in the account's
     * file ([AccountFlag]), so a restart of the process does not start them again.
     */
    data class Limits(
        val passwordFailures: List<Long> = emptyList(),
        val deviceCodes: List<Long> = emptyList(),
        val textCodes: List<Long> = emptyList(),
    ) {
        /** How long until a password may be tried again; 0: now. */
        fun passwordWaitMs(now: Long): Long = waitMs(passwordFailures, now, PASSWORD_MAX, PASSWORD_WINDOW_MS, 0)

        /** How long until codes may be asked for on the trusted devices again; 0: now. */
        fun deviceCodeWaitMs(now: Long): Long = waitMs(deviceCodes, now, CODE_MAX, CODE_WINDOW_MS, CODE_COOLDOWN_MS)

        /** How long until a code may be texted again; 0: now. */
        fun textCodeWaitMs(now: Long): Long = waitMs(textCodes, now, CODE_MAX, CODE_WINDOW_MS, CODE_COOLDOWN_MS)

        fun passwordFailed(now: Long) = copy(passwordFailures = added(passwordFailures, now, PASSWORD_WINDOW_MS))
        fun deviceCodeSent(now: Long) = copy(deviceCodes = added(deviceCodes, now, CODE_WINDOW_MS))
        fun textCodeSent(now: Long) = copy(textCodes = added(textCodes, now, CODE_WINDOW_MS))
    }

    /**
     * How long until one more may go, with [max] in [windowMs] and [cooldownMs] after the last. A time
     * later than [now] counts (the clock may have gone back), unless it is too far ahead to be real.
     */
    internal fun waitMs(stamps: List<Long>, now: Long, max: Int, windowMs: Long, cooldownMs: Long): Long {
        val live = recent(stamps, now, windowMs)
        var wait = 0L
        if (live.size >= max) wait = live.sorted()[live.size - max] + windowMs - now
        val last = live.maxOrNull()
        if (last != null && cooldownMs > 0) wait = maxOf(wait, last + cooldownMs - now)
        return wait.coerceAtLeast(0)
    }

    private fun recent(stamps: List<Long>, now: Long, windowMs: Long): List<Long> =
        stamps.filter { it > now - windowMs && it <= now + windowMs }

    private fun added(stamps: List<Long>, now: Long, windowMs: Long): List<Long> =
        (recent(stamps, now, windowMs) + now).takeLast(MAX_STAMPS)

    // --- where an answer leads (§4.3) -----------------------------------------------------------

    /** What the sign-in knows besides the library's answer. */
    data class Progress(
        /** The library signed in to iCloud (`tryIcloudLogin`, or a code checked, gave its user). */
        val user: Boolean = false,
        /** `tryIcloudLogin` was asked since the last answer. */
        val icloudTried: Boolean = false,
        /** The password was tried again once already after a `NeedsLogin`. */
        val retried: Boolean = false,
        /** The user typed the password for this sign-in, and it is still held. */
        val credentials: Boolean = false,
        /** A two-factor session for the trusted devices is open. */
        val circle: Boolean = false,
    )

    sealed class Next {
        /** Signed in: on to the commit (§4.4). */
        object Commit : Next()

        /** Signed in at Apple, not yet to iCloud: `tryIcloudLogin` once more. */
        object IcloudLogin : Next()

        /** Signed in at Apple, and iCloud still has not answered: given up on. */
        object Incomplete : Next()

        /** The first `NeedsLogin`: the password once more. */
        object RetryPassword : Next()

        /** The password was refused (a second `NeedsLogin`): counts as a failed attempt. */
        object WrongPassword : Next()

        /** What this phone saved does not sign in by itself: the password is needed. */
        object EnterPassword : Next()

        /** Codes to the trusted devices are to be asked for. */
        object SendToDevices : Next()

        /** A code from the trusted devices is to be typed. */
        object EnterDeviceCode : Next()

        /** The trusted numbers to text a code to are to be read. */
        object TextOptions : Next()

        /** A texted code is to be typed ([body]: the library's, to check it with). */
        class EnterTextCode(val body: AccountBridge.Native) : Next()

        /** Apple asks for something only an Apple device can do: its words, and nothing else to do. */
        data class AppleMessage(val text: String) : Next()
    }

    fun next(step: AccountBridge.Step, p: Progress): Next = when (step) {
        is AccountBridge.Step.LoggedIn -> when {
            p.user -> Next.Commit
            !p.icloudTried -> Next.IcloudLogin
            else -> Next.Incomplete
        }
        is AccountBridge.Step.NeedsLogin -> when {
            !p.credentials -> Next.EnterPassword
            !p.retried -> Next.RetryPassword
            else -> Next.WrongPassword
        }
        is AccountBridge.Step.NeedsDevice2Fa -> Next.SendToDevices
        // Without a session of its own the code could not be checked: codes are asked for first.
        is AccountBridge.Step.Needs2FaVerification -> if (p.circle) Next.EnterDeviceCode else Next.SendToDevices
        is AccountBridge.Step.NeedsSms2Fa -> Next.TextOptions
        is AccountBridge.Step.NeedsSmsVerification -> Next.EnterTextCode(step.body)
        is AccountBridge.Step.NeedsExtraStep -> Next.AppleMessage(appleText(step.message))
    }

    // --- errors -----------------------------------------------------------------------------------

    enum class ErrorKind {
        /** Apple wants new iCloud terms accepted (an Apple device or icloud.com does that). */
        TERMS,

        /** The trusted devices did not confirm the code in time (the library waits 30 s). */
        TIMEOUT,
        OTHER,
    }

    const val TERMS_MARKER = "MOBILEME_TERMS_OF_SERVICE_UPDATE"

    /** What an error from the library means, by its words (never by a number: Apple's are not in the library). */
    fun classify(message: String?): ErrorKind {
        val m = message.orEmpty()
        return when {
            m.contains(TERMS_MARKER, ignoreCase = true) -> ErrorKind.TERMS
            m.contains("timed out", ignoreCase = true) -> ErrorKind.TIMEOUT
            else -> ErrorKind.OTHER
        }
    }

    /**
     * Whether an account-gated call's error (redacted) says Apple no longer takes the account's
     * sign-in (a token refresh refused, the account signed out at Apple): §4.8's "needs attention".
     * By its words only, and narrowly: anything else (the network, a timeout) is never one.
     */
    fun isAuthFailure(message: String?): Boolean {
        val m = message.orEmpty()
        if (m.isEmpty() || m.contains("timed out", ignoreCase = true)) return false
        return AUTH_FAILURE.containsMatchIn(m)
    }

    private val AUTH_FAILURE = Regex(
        "unauthori[sz]ed|\\b401\\b|auth(entication)?[ _-]?(failed|failure|error|invalid|expired)|" +
            "invalid[ _-]?(auth|token|credentials?)|token[ _-]?(expired|invalid|refresh failed)|" +
            "not (logged|signed) in|needs? (login|sign[ -]?in)|login required|re-?login",
        RegexOption.IGNORE_CASE,
    )

    /** At most this much of Apple's own words is shown. */
    const val MAX_APPLE_TEXT = 300

    /** Apple's words as they may be shown: no control or direction characters, [MAX_APPLE_TEXT] at most. */
    fun appleText(message: String): String = plain(message, MAX_APPLE_TEXT)

    /** [s] with no control, format or direction characters (one space for a line break), cut to [max]. */
    fun plain(s: String, max: Int): String {
        val b = StringBuilder()
        for (ch in s) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> b.append(' ')
                Character.isISOControl(ch) -> Unit
                Character.getType(ch) == Character.FORMAT.toInt() -> Unit
                else -> b.append(ch)
            }
        }
        val out = b.toString().trim().replace(Regex(" {2,}"), " ")
        if (out.length <= max) return out
        val end = if (Character.isHighSurrogate(out[max - 1])) max - 1 else max
        return out.substring(0, end).trimEnd() + "…"
    }

    // --- how the Apple ID shows -------------------------------------------------------------------

    const val DOTS = "•••"
    private const val NUMBER_DOTS = "•••••"

    /**
     * The Apple ID as the screens show it: an email with only its first character and its domain
     * (`j•••@icloud.com`), a number with only its calling code and last four digits (`+1•••••4567`).
     */
    fun mask(appleId: String): String {
        val id = plain(appleId, 320)
        val at = id.lastIndexOf('@')
        if (at >= 0) {
            val local = id.substring(0, at)
            val first = if (local.isEmpty()) "" else local.substring(0, Character.charCount(local.codePointAt(0)))
            return first + DOTS + id.substring(at)
        }
        val digits = id.filter { it in '0'..'9' }
        if (digits.length < 6) return NUMBER_DOTS
        val last = digits.takeLast(4)
        if (!id.trimStart().startsWith("+")) return NUMBER_DOTS + last
        val code = (1..3).map { digits.take(it) }.firstOrNull { it in CallingCodes.codes }?.takeIf { it.length < digits.length - 4 }.orEmpty()
        return "+" + code + NUMBER_DOTS + last
    }

    // --- what a start finds (§4.4) ---------------------------------------------------------------

    enum class StartAction {
        /** No account here: nothing, as before there was an Apple Account sign-in. */
        NOTHING,

        /** The library saved an account that was never committed: a sign-in was interrupted. Wiped, and said once. */
        RESET_INTERRUPTED,

        /** The commit was interrupted after the account was in place: it is finished. */
        FINISH_COMMIT,

        /** Signed in: as it is. */
        NORMAL,

        /** Signed in, but the library's account is gone (Apple or the library took it): signed out, and said once. */
        VANISHED,
    }

    fun atStart(accountSaved: Boolean, state: FlagState): StartAction = when {
        !accountSaved && state == FlagState.NONE -> StartAction.NOTHING
        accountSaved && state == FlagState.NONE -> StartAction.RESET_INTERRUPTED
        accountSaved && state == FlagState.COMMITTING -> StartAction.FINISH_COMMIT
        accountSaved -> StartAction.NORMAL
        // No account saved, and a flag that says there is one (a commit that never got to it included).
        else -> StartAction.VANISHED
    }

    // --- the screens -------------------------------------------------------------------------------

    /** Why the sign-in (or sign-out) cannot be done now. */
    enum class Refusal { BUSY, NOT_REGISTERED, SIGNED_IN, NOT_STARTED }

    /** What went wrong, for the page to say (the screen has the words). */
    sealed class Problem {
        /** No Apple ID or no password typed. */
        object Missing : Problem()

        /** [PASSWORD_MAX] failures in [PASSWORD_WINDOW_MS]: the next try in [waitMs]. */
        data class TooManyAttempts(val waitMs: Long) : Problem()

        /** A code was asked for less than a minute ago, or [CODE_MAX] in the hour: the next in [waitMs]. */
        data class CodeWait(val waitMs: Long) : Problem()

        /** Apple refused the Apple ID or the password. */
        object WrongPassword : Problem()

        /** What this phone saved does not sign in by itself: the password is needed. */
        object EnterPassword : Problem()

        /** The password was let go of ([CREDENTIALS_MS]) before the sign-in needed it again. */
        object Expired : Problem()

        /** Apple signed in, iCloud did not answer. */
        object Incomplete : Problem()

        /** The account has no trusted phone number to text a code to. */
        object NoNumbers : Problem()

        /** A code is six digits. */
        object CodeFormat : Problem()

        /** Apple's or the library's words, redacted ([com.lightdecoder.imessage.Redact.secrets]) and cut. */
        data class Apple(val text: String) : Problem()
    }

    /** A page of the sign-in screen, as [AppleAccount] has the sign-in. */
    sealed class Page {
        /** "Before you sign in": [device] Apple will list (null when it could not be read), [relayHost] the relay contacted. */
        data class Intro(val device: AccountBridge.Device?, val relayHost: String) : Page()

        /**
         * The Apple ID and password. [continueAs] (masked): what this phone saved, offered when it needs
         * a new sign-in; [problem]; [iforgot]: "Forgot password?" offered with it.
         */
        data class Credentials(val continueAs: String? = null, val problem: Problem? = null, val iforgot: Boolean = false) : Page()

        /** The code from the trusted devices: [sent] when they were asked; [timedOut] when Apple did not confirm one in time. */
        data class DeviceCode(val sent: Boolean = true, val problem: Problem? = null, val timedOut: Boolean = false) : Page()

        /** The trusted numbers to text a code to, as Apple writes them. */
        data class TextNumbers(val numbers: List<String>, val problem: Problem? = null) : Page()

        /** The texted code: [number] it went to, [resendAtMs] from when "Resend Code" may be used. */
        data class TextCode(val number: String?, val problem: Problem? = null, val resendAtMs: Long = 0) : Page()

        /** Apple's own words (`NeedsExtraStep`): only "OK". */
        data class AppleMessage(val text: String) : Page()

        /** Apple wants new iCloud terms accepted: what to do, and "OK". */
        object Terms : Page()

        /** Signed in: iMessage restarts on "OK". */
        object Done : Page()

        data class Refused(val why: Refusal) : Page()
    }
}
