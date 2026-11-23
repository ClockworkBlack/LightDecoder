package com.lightdecoder.imessage.facetime

import java.util.Locale
import java.util.UUID

/**
 * FaceTime calls through the iMessage library (docs: the ":imessage" process's
 * [FaceTimeCalls] drives one call at a time by the rules of [CallRules]). The plain values the
 * rules work on are here: nothing of Android or of the library, so they are tested without a phone.
 */

/** Where the one call is. */
enum class Phase {
    /** An incoming call the main process is being asked about (who it is) before it may ring. */
    SCREENING,

    /** An incoming call ringing here: nothing is joined, no microphone or camera is in use. */
    RINGING_IN,

    /** A call the user placed, ringing on the other side. */
    RINGING_OUT,

    /** A placed call nobody took (no answer, declined, or it could not be placed): shown until left. */
    UNANSWERED,

    /** Answered (either way): the library is joining the call. */
    CONNECTING,

    /** In the call. */
    ACTIVE,
}

/** How a call ended, as its row in the chat says it. */
enum class Outcome(val wire: String) {
    /** It was talked in: its row says how long. */
    ANSWERED("answered"),

    /** An incoming call that was not taken (the caller gave up, it rang out, or it was not rung at all). */
    MISSED("missed"),

    /** Declined: an incoming one by the user (here or on another device), a placed one by the other person. */
    DECLINED("declined"),

    /** A placed call that rang out. */
    UNANSWERED("unanswered"),

    /** A placed call the user took back before it was answered. */
    CANCELLED("cancelled"),

    /** An incoming call the user answered on another of their devices. */
    ELSEWHERE("elsewhere"),

    /** It could not be placed or joined. */
    FAILED("failed"),
    ;

    companion object {
        fun of(wire: String?): Outcome? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why an UNANSWERED call is shown as it is. */
enum class Unanswered { NO_ANSWER, DECLINED, FAILED }

/**
 * The one call. [peer] is the other person's address as the library has it ("tel:+15551234567",
 * "mailto:a@b.c"); [name] what the screens call them ("" until known: their address then).
 */
data class Call(
    val guid: String,
    val peer: String,
    val name: String,
    /** A video call: as it was rung or placed, then as the library says once it is joined. */
    val video: Boolean,
    val outgoing: Boolean,
    val phase: Phase,
    /** The user's own address the call is carried on, where known. */
    val from: String? = null,
    /** The chat with [peer] in the main process's archive, where known: its row goes there, and "Message" opens it. */
    val conversationId: String? = null,
    /** The person's picture (a file of the app's own), where the main process has one. */
    val photo: String? = null,
    /** When it began ringing here, or was placed. */
    val startedMs: Long,
    /** When the library had joined it (0: not yet). */
    val activeSinceMs: Long = 0,
    /** Answered or placed with the camera (a video call taken as "Audio only" is without). */
    val withCamera: Boolean = true,
    /** [Phase.UNANSWERED]: why. */
    val unanswered: Unanswered? = null,
    /** How many other people the library says are in the call now, and whether it ever said one was. */
    val peers: Int = 0,
    val peerSeen: Boolean = false,
    /** The other side left while the library was still joining: nobody is there to join. */
    val abandoned: Boolean = false,
) {
    /** Ringing here, or about to: nothing of the call is joined yet. */
    val incomingRing: Boolean get() = !outgoing && (phase == Phase.SCREENING || phase == Phase.RINGING_IN)

    /** A call that holds the line: anything but a placed call's "nobody answered" left on screen. */
    val live: Boolean get() = phase != Phase.UNANSWERED

    /** Whether the library is joining or has joined it (its microphone may be open). */
    val joined: Boolean get() = phase == Phase.CONNECTING || phase == Phase.ACTIVE
}

/** One call of the library's list (`ftSessions`), as the rules need it. */
data class CallSessionInfo(
    val guid: String,
    val myHandles: List<String>,
    /** Its members' addresses, the user's own among them or not. */
    val members: List<String>,
    /** When it began (ms), as the caller's phone said: null when it never did. Not to be trusted for more than its age. */
    val startMs: Long?,
    val video: Boolean,
    /** Somebody is joined, and it was keyed within the last hour. */
    val active: Boolean,
)

/** A finished call, for its row in the chat (and, for a missed one, its notice). */
data class CallLogEntry(
    val guid: String,
    val peer: String,
    val outgoing: Boolean,
    val video: Boolean,
    val startedMs: Long,
    val durationMs: Long,
    val outcome: Outcome,
    /** A missed call whose notice makes no sound: one that was not rung on purpose (an unknown caller silenced, a ring past the limit). */
    val quiet: Boolean = false,
    /** The chat it belongs in, where the ":imessage" process was told (a placed call's); else it is found by [peer]. */
    val conversationId: String? = null,
)

/** What the main process knows of a caller: their contact name, whether they are known (a name, or a chat with them), and the chat. */
data class CallerInfo(val name: String, val known: Boolean, val conversationId: String? = null, val photo: String? = null)

/** The two FaceTime settings of the iMessage screen. */
data class FaceTimeSettings(val on: Boolean = true, val silenceUnknown: Boolean = false)

/** Why a call the user asked for was not started. */
enum class Refusal {
    /** "FaceTime" is off. */
    OFF,

    /** Already in a FaceTime call. */
    IN_CALL,

    /** The phone is in another call (a cellular one, or another app's). */
    PHONE_BUSY,

    /** Not someone a call can go to (no address the library takes). */
    NOT_CALLABLE,

    /** iMessage is not connected. */
    NOT_CONNECTED,

    /** The call to answer is no longer there. */
    GONE,
}

/** A call's ids and addresses, as they are taken from other people's phones and from the other process: checked, never trusted. */
object CallIds {

    /** A call's guid: a UUID in upper case, as iPhones write them (the library finds a call's relay by that form). */
    private val GUID = Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$")

    private val TEL = Regex("^tel:\\+[0-9]{5,20}$")
    private val MAIL = Regex("^mailto:[^\\s@:/\\\\]{1,64}@[^\\s@:/\\\\]{1,255}$")

    /** The longest address taken. */
    const val MAX_HANDLE_CHARS = 320

    /** The longest name shown for a call. */
    const val MAX_NAME_CHARS = 80

    fun isGuid(s: String?): Boolean = s != null && s.length == 36 && GUID.matches(s)

    /** A guid for a call placed here: upper case (a lower-case one would never get its relay). */
    fun newGuid(): String = UUID.randomUUID().toString().uppercase(Locale.ROOT)

    /**
     * [raw] as an address a call can go to or come from ("tel:+15551234567", "mailto:a@b.c"), or
     * null: anything else, a FaceTime link's guest ("temp:...") among them.
     */
    fun handle(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.length > MAX_HANDLE_CHARS) return null
        return when {
            TEL.matches(s) -> s
            MAIL.matches(s) -> s
            else -> null
        }
    }

    /** What two spellings of one address share: an email address in lower case. */
    fun key(handle: String): String = if (handle.startsWith("mailto:")) handle.lowercase(Locale.ROOT) else handle

    /** [name] as it is shown: one line, no control characters, [MAX_NAME_CHARS] at most. */
    fun name(name: String?): String {
        val clean = name.orEmpty().filter { !it.isISOControl() }.trim()
        if (clean.length <= MAX_NAME_CHARS) return clean
        val end = if (Character.isHighSurrogate(clean[MAX_NAME_CHARS - 1])) MAX_NAME_CHARS - 1 else MAX_NAME_CHARS
        return clean.substring(0, end)
    }
}
