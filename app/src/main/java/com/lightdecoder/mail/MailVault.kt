package com.lightdecoder.mail

import android.content.Context
import com.lightdecoder.relay.SessionVault
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.crypto.BadPaddingException

/** A Gmail address and its app password. [toString] never shows the password. */
class MailAccount(val email: String, val password: String) {
    override fun toString(): String = "MailAccount"

    /** The part after the @, for Message-IDs this app makes. */
    val domain: String get() = email.substringAfter('@', "localhost").ifBlank { "localhost" }
}

/**
 * Keeps the Gmail address and app password encrypted at rest, with the same
 * Android Keystore AES-256-GCM key as the Google Messages session
 * ([SessionVault.cipherProvider]; tests swap in a software key there).
 * File filesDir/mail/account.bin = [version 1][12-byte IV][ciphertext + tag].
 */
object MailVault {
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private val lock = Any()

    fun save(context: Context, account: MailAccount?) = synchronized(lock) {
        val file = file(context)
        if (account == null) {
            file.delete()
            return@synchronized
        }
        val plain = JSONObject().put("email", account.email).put("password", account.password).toString().toByteArray()
        val cipher = SessionVault.cipherProvider.cipher(null)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected IV" }
        val sealed = cipher.doFinal(plain)
        plain.fill(0)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use {
            it.write(byteArrayOf(VERSION))
            it.write(iv)
            it.write(sealed)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("could not store the account")
        }
    }

    /** The stored account, or null if there is none or it cannot be read (it is then deleted). */
    fun load(context: Context): MailAccount? = synchronized(lock) {
        val file = file(context)
        if (!file.exists()) return@synchronized null
        try {
            val bytes = file.readBytes()
            if (bytes.size <= 1 + IV_BYTES || bytes[0] != VERSION) {
                file.delete()
                return@synchronized null
            }
            val iv = bytes.copyOfRange(1, 1 + IV_BYTES)
            val plain = SessionVault.cipherProvider.cipher(iv).doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
            val json = JSONObject(String(plain))
            plain.fill(0)
            MailAccount(json.getString("email"), json.getString("password"))
        } catch (e: BadPaddingException) {
            file.delete()
            null
        } catch (e: JSONException) {
            file.delete()
            null
        }
    }

    fun exists(context: Context): Boolean = file(context).length() > 1 + IV_BYTES

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "mail"), "account.bin")
}
