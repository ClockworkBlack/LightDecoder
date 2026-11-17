package com.lightdecoder.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.relay.PairingModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration

/** Pairing gets one automatic second try when Google is slow to start it; a refusal is shown at once. */
@RunWith(AndroidJUnit4::class)
class PairingRetryTest {

    private lateinit var env: RelayTestEnv

    @Before
    fun setUp() {
        env = RelayTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    private val cookies = mapOf("SID" to "1", "HSID" to "2", "SSID" to "3", "APISID" to "4", "SAPISID" to "5", "OSID" to "6")

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun aSlowStartIsTriedAgainByItself() {
        val manager = env.manager()
        env.setUpFake = { it.startPairingFailsOnce += RelayException(RelayException.PAIRING_INIT_TIMEOUT, "slow") }
        val model = PairingModel()
        model.pair(manager, cookies)
        waitFor("the emoji") { model.ui.value.emoji.isNotEmpty() || model.ui.value.errorCode.isNotEmpty() }
        assertEquals("no error shown", "", model.ui.value.errorCode)
        assertEquals(2, env.fake!!.calls.count { it == "startPairing" })
    }

    @Test
    fun aRefusalIsShownWithoutRetrying() {
        val manager = env.manager()
        env.setUpFake = { it.startPairingError = RelayException(RelayException.COOKIES_INVALID, "no") }
        val model = PairingModel()
        model.pair(manager, cookies)
        waitFor("the error") { model.ui.value.errorCode.isNotEmpty() }
        assertEquals(RelayException.COOKIES_INVALID, model.ui.value.errorCode)
        assertEquals(1, env.fake!!.calls.count { it == "startPairing" })
    }
}
