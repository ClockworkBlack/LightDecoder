package com.lightdecoder.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.inbox.InboxStore
import com.lightdecoder.wa.WaManager
import com.lightdecoder.wa.WaPrefs
import org.junit.Assert.fail
import javax.crypto.KeyGenerator

/**
 * What every relay runtime test needs: a fresh archive, a software key for
 * the session vault (no Keystore on the JVM), cleared preferences and logs,
 * and a manager whose bridge is a [FakeRelayBridge].
 */
internal class RelayTestEnv {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val originalCipher = SessionVault.cipherProvider
    val db: ArchiveDb
    val repo: ArchiveRepository

    @Volatile
    var fake: FakeRelayBridge? = null
        private set

    /** Applied to each fake right after it is created. */
    var setUpFake: (FakeRelayBridge) -> Unit = {}

    init {
        context.deleteDatabase(DB_NAME)
        db = ArchiveDb(context, DB_NAME)
        repo = ArchiveRepository.forTesting(db)
        SessionVault.cipherProvider = SessionVault.CipherProvider.forKey(
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        )
        SessionVault.save(context, null)
        context.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("inbox", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(WaPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        // History import is started explicitly where a test wants it.
        RelayPrefs.setBackfillDepth(context, BackfillDepth.OFF)
        RelayLog.clear(context)
        InboxStore.get(context).clear()
        RelayBridges.factory = { session, sink -> FakeRelayBridge(session, sink).also { setUpFake(it); fake = it } }
    }

    fun manager(timing: RelayManager.Timing = FAST): RelayManager =
        RelayManager.installForTesting(context, repo, timing)

    /** A manager that is already paired (a session is stored), with its fake bridge created. */
    fun pairedManager(timing: RelayManager.Timing = FAST): Pair<RelayManager, FakeRelayBridge> {
        SessionVault.save(context, FakeRelayBridge.SESSION)
        val manager = manager(timing)
        manager.bridgeForTesting()
        return manager to requireNotNull(fake)
    }

    fun tearDown() {
        RelayManager.resetForTesting()
        // The other service's manager is a process-wide singleton too: one a
        // screen created must not live on into the next test.
        WaManager.resetForTesting()
        com.lightdecoder.mail.MailManager.resetForTesting()
        RelayBridges.factory = null
        SessionVault.save(context, null)
        SessionVault.cipherProvider = originalCipher
        db.close()
    }

    /** Polls [condition] (letting the manager's threads catch up) until it holds or [timeoutMs] passes. */
    fun eventually(manager: RelayManager, timeoutMs: Long = 5_000, what: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            manager.awaitIdleForTesting()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    companion object {
        const val DB_NAME = "relay-runtime-test.db"

        /** Real behaviour, short delays. */
        val FAST = RelayManager.Timing(
            outboxRetryDelaysMs = listOf(10, 20, 30),
            settingsGateMs = 60_000,
            backfillPagePauseMs = 1,
            backfillConversationPauseMs = 1,
            backfillWaitOnlineMs = 2_000,
            typingExpiryMs = 300,
            typingThrottleMs = 5_000,
            reconnectDelaysMs = listOf(60_000),
            healthEventIntervalMs = 0,
            unconfirmedSendCheckMs = 60_000,
            unconfirmedSendGiveUpMs = 3_600_000,
        )
    }
}
