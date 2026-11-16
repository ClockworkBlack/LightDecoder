package com.lightdecoder.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Google Messages session (cookies and pairing keys) encrypted at
 * rest, with an AES-256-GCM key that lives in the Android Keystore and never
 * leaves it.
 *
 * File filesDir/relay/session.bin = [version 1][12-byte IV][ciphertext + tag],
 * written to a temp file and renamed, so a crash never leaves half a session.
 * A file that cannot be decrypted (key lost, file damaged) is deleted: the
 * only way back is pairing again.
 */
object SessionVault {
    const val KEY_ALIAS = "lightdecoder_relay_session"
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Makes initialised AES/GCM ciphers. Production uses the Keystore key; the
     * Keystore does not exist on the JVM, so tests swap in [forKey] with a
     * software key.
     */
    fun interface CipherProvider {
        /** [iv] null: encrypt with a fresh random IV. Otherwise decrypt with that IV. */
        fun cipher(iv: ByteArray?): Cipher

        companion object {
            fun forKey(key: SecretKey) = CipherProvider { iv ->
                Cipher.getInstance(TRANSFORMATION).apply {
                    if (iv == null) init(Cipher.ENCRYPT_MODE, key)
                    else init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                }
            }
        }
    }

    @Volatile
    var cipherProvider: CipherProvider = CipherProvider { iv -> CipherProvider.forKey(keystoreKey()).cipher(iv) }

    private val lock = Any()

    /** Encrypts and stores [data]; null or empty deletes the stored session. */
    fun save(context: Context, data: ByteArray?) = synchronized(lock) {
        val file = file(context)
        if (data == null || data.isEmpty()) {
            file.delete()
            return@synchronized
        }
        val cipher = cipherProvider.cipher(null)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected IV" }
        val sealed = cipher.doFinal(data)
        val out = ByteArray(1 + IV_BYTES + sealed.size)
        out[0] = VERSION
        iv.copyInto(out, 1)
        sealed.copyInto(out, 1 + IV_BYTES)

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        // Synced before the rename: otherwise a power cut can leave an empty
        // session.bin, which load() would then treat as damaged and delete.
        FileOutputStream(tmp).use {
            it.write(out)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("could not store the session")
        }
    }

    /**
     * The stored session, or null if there is none or it cannot be decrypted.
     * A damaged file, or one sealed with a key that no longer exists, is
     * deleted. Any other failure (the Keystore not answering, a read error)
     * keeps the file, so a passing problem never costs the pairing.
     */
    fun load(context: Context): ByteArray? = synchronized(lock) {
        val file = file(context)
        if (!file.exists()) return@synchronized null
        try {
            val bytes = file.readBytes()
            if (bytes.size < 1 + IV_BYTES + TAG_BITS / 8 || bytes[0] != VERSION) {
                file.delete()
                return@synchronized null
            }
            val iv = bytes.copyOfRange(1, 1 + IV_BYTES)
            cipherProvider.cipher(iv).doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
        } catch (e: BadPaddingException) {
            // Includes AEADBadTagException: tampered, truncated, or another key.
            file.delete()
            null
        } catch (e: KeyPermanentlyInvalidatedException) {
            file.delete()
            null
        } catch (e: Exception) {
            null
        }
    }

    fun exists(context: Context): Boolean = synchronized(lock) { file(context).length() > 1 + IV_BYTES }

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "relay"), "session.bin")

    /** The Keystore key, created on first use. Not tied to the lock screen, so the service can run in the background. */
    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
