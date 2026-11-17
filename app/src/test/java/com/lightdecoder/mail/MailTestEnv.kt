package com.lightdecoder.mail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.SessionVault
import org.junit.Assert.fail
import javax.crypto.KeyGenerator

/** A Gmail manager over a fake mail server, a test archive and a software key for the vault. */
internal class MailTestEnv {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val originalCipher = SessionVault.cipherProvider
    val db: ArchiveDb
    val repo: ArchiveRepository
    val fake = FakeMailBridge()

    init {
        SessionVault.cipherProvider = SessionVault.CipherProvider.forKey(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        context.getSharedPreferences(MailPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        MailVault.save(context, null)
        context.deleteDatabase(DB)
        context.deleteDatabase(INDEX)
        MailManager.indexName = INDEX
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.GMAIL)
        ArchiveRepository.installForTesting(repo)
        MailBridges.factory = { fake }
    }

    fun manager(): MailManager = MailManager.installForTesting(
        context, repo, MailManager.Timing(visiblePollMs = 60_000, retryDelaysMs = listOf(10, 20), echoSyncDelayMs = 10),
    )

    fun tearDown() {
        MailManager.resetForTesting()
        MailBridges.factory = null
        MailManager.indexName = MailIndex.NAME
        MailVault.save(context, null)
        SessionVault.cipherProvider = originalCipher
        ArchiveRepository.resetForTesting()
        db.close()
    }

    fun eventually(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    companion object {
        const val DB = "gmail-runtime-test.db"
        const val INDEX = "gmail-index-test.db"
    }
}
