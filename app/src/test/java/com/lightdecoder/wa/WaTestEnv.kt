package com.lightdecoder.wa

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import org.junit.Assert.fail
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

/**
 * What a WhatsApp runtime or screen test needs: a fresh WhatsApp archive
 * (installed as the process one, since the screens read it through the
 * manager), cleared preferences and log, an empty bridge store, and a
 * manager whose bridge is a [FakeWaBridge].
 */
internal class WaTestEnv {
    val context: Context = ApplicationProvider.getApplicationContext()
    val app: Application get() = context as Application
    val db: ArchiveDb
    val repo: ArchiveRepository

    @Volatile
    var fake: FakeWaBridge? = null
        private set

    /** Applied to each fake right after it is created. */
    var setUpFake: (FakeWaBridge) -> Unit = {}

    private var service: ServiceController<WaService>? = null

    init {
        context.getSharedPreferences(WaPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().clear().commit()
        WaLog.clear(context)
        WaManager.storeDir(context).deleteRecursively()
        context.deleteDatabase(DB_NAME)
        db = ArchiveDb(context, DB_NAME)
        repo = ArchiveRepository.forTesting(db, ChatService.WHATSAPP)
        ArchiveRepository.installForTesting(repo)
        WaBridges.factory = { dir, sink -> FakeWaBridge(dir, sink).also { setUpFake(it); fake = it } }
    }

    /** A manager that is not linked. Its bridge is made on first use. */
    fun manager(timing: WaManager.Timing = FAST): WaManager = WaManager.installForTesting(context, repo, timing)

    /** A manager over a store that holds a link, with its fake bridge created. Not connected. */
    fun linkedManager(timing: WaManager.Timing = FAST): Pair<WaManager, FakeWaBridge> {
        FakeWaBridge.markPaired(WaManager.storeDir(context))
        val manager = manager(timing)
        manager.bridgeForTesting()
        return manager to requireNotNull(fake)
    }

    /** A linked manager the user wants connected, with its service running and the connection up. */
    fun connectedManager(timing: WaManager.Timing = FAST): Pair<WaManager, FakeWaBridge> {
        val (manager, fake) = linkedManager(timing)
        WaPrefs.setLiveEnabled(context, true)
        startService()
        eventually(manager, what = "the connection") { manager.state.value.phase == com.lightdecoder.relay.RelayState.Phase.CONNECTED }
        return manager to fake
    }

    /** Creates the foreground service and gives it a plain start, as Android does after startForegroundService. */
    fun startService(): WaService {
        val controller = Robolectric.buildService(WaService::class.java).create()
        service = controller
        controller.get().onStartCommand(null, 0, 1)
        return controller.get()
    }

    fun tearDown() {
        runCatching { service?.destroy() }
        WaManager.resetForTesting()
        WaBridges.factory = null
        ArchiveRepository.resetForTesting()
        db.close()
        WaManager.storeDir(context).deleteRecursively()
    }

    /** A one-to-one chat with [number], as the bridge reports it. */
    fun conversation(number: String, name: String = "Sam", folder: String = "inbox"): ConversationDto {
        val jid = FakeWaBridge.jid(number)
        return ConversationDto(
            id = jid, name = name, folder = folder, type = "WHATSAPP", lastMessageTs = 1_000,
            participants = listOf(
                ParticipantDto(id = FakeWaBridge.jid(FakeWaBridge.OWN_ID), isMe = true),
                ParticipantDto(id = jid, name = name, number = "+$number", formattedNumber = "+$number"),
            ),
        )
    }

    /** Polls [condition] (letting the manager's threads catch up) until it holds or [timeoutMs] passes. */
    fun eventually(manager: WaManager, timeoutMs: Long = 5_000, what: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            manager.awaitIdleForTesting()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    companion object {
        const val DB_NAME = "whatsapp-runtime-test.db"

        /** Real behaviour, short delays. */
        val FAST = WaManager.Timing(
            outboxRetryDelaysMs = listOf(10, 20, 30),
            typingExpiryMs = 300,
            reconnectDelaysMs = listOf(60_000),
            healthEventIntervalMs = 0,
            busyClearMs = 50,
        )
    }
}
