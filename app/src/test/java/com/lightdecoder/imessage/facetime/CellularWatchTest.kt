package com.lightdecoder.imessage.facetime

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cellular call during a FaceTime call without Telecom ([CellularWatch], SPEC2 B3.2): answered, the
 * FaceTime microphone is muted first and then the call ends; ringing only shows the banner; the mode
 * the phone was in when the watch began is never taken for a call.
 */
class CellularWatchTest {

    private class Source(var mode: Int = AudioManager.MODE_NORMAL) : CellularWatch.Source {
        var changed: ((Int) -> Unit)? = null
        var stopped = false
        override fun mode() = mode
        override fun start(changed: (Int) -> Unit) {
            this.changed = changed
        }
        override fun stop() {
            stopped = true
            changed = null
        }
        fun to(m: Int) {
            mode = m
            changed?.invoke(m)
        }
    }

    private val done = ArrayList<String>()
    private var telecom = false

    private fun watch(source: Source) = CellularWatch(
        source,
        banner = { done += if (it) "banner" else "no banner" },
        mute = { done += "mute" },
        end = { done += "end" },
        telecomHas = { telecom },
    )

    @Test
    fun aPhoneCallAnsweredMutesTheMicrophoneAtOnceThenEndsTheCall() {
        val source = Source()
        val w = watch(source)
        w.start()
        source.to(AudioManager.MODE_IN_COMMUNICATION) // the FaceTime call's own audio: nothing
        assertTrue(done.isEmpty())
        source.to(AudioManager.MODE_RINGTONE)
        source.to(AudioManager.MODE_IN_CALL)
        assertEquals(listOf("banner", "mute", "no banner", "end"), done)
        // Ended once only, however often the mode is told again.
        source.to(AudioManager.MODE_IN_COMMUNICATION)
        source.to(AudioManager.MODE_IN_CALL)
        assertEquals(1, done.count { it == "end" })
    }

    @Test
    fun aPhoneCallRingingOnlyShowsTheBanner() {
        val source = Source()
        val w = watch(source)
        w.start()
        source.to(AudioManager.MODE_RINGTONE)
        assertEquals(listOf("banner"), done)
        // Declined or gone: the banner goes, the call goes on.
        source.to(AudioManager.MODE_IN_COMMUNICATION)
        assertEquals(listOf("banner", "no banner"), done)
        w.stop()
        assertTrue(source.stopped)
        assertEquals(listOf("banner", "no banner"), done)
    }

    @Test
    fun theModeTheWatchBeganInIsNeverTakenForACall() {
        val source = Source(AudioManager.MODE_IN_CALL)
        val w = watch(source)
        w.start()
        source.to(AudioManager.MODE_IN_CALL)
        assertTrue(done.isEmpty())
        w.stop()
        // Stopped: nothing more.
        source.changed = null
        w.onMode(AudioManager.MODE_RINGTONE)
        assertTrue(done.isEmpty())
    }

    @Test
    fun withTelecomHavingTheCallNothingIsDoneHere() {
        telecom = true
        val source = Source()
        val w = watch(source)
        w.start()
        source.to(AudioManager.MODE_RINGTONE)
        source.to(AudioManager.MODE_IN_CALL)
        assertTrue(done.toString(), done.none { it == "end" || it == "mute" || it == "banner" })
        assertEquals(CellularWatch.Action.END, CellularWatch.decide(AudioManager.MODE_IN_CALL))
        assertEquals(CellularWatch.Action.BANNER, CellularWatch.decide(AudioManager.MODE_RINGTONE))
        assertEquals(CellularWatch.Action.CLEAR, CellularWatch.decide(AudioManager.MODE_NORMAL))
    }
}
