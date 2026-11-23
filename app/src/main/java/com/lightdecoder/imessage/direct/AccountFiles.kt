package com.lightdecoder.imessage.direct

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Where the optional Apple Account stands, as LightDecoder keeps it ([AccountFlag]). */
enum class FlagState(val wire: String?) {
    /** Not signed in (or nothing kept): everything as before there was a sign-in. */
    NONE(null),
    SIGNED_IN("signedIn"),

    /** Signed in, but the account needs the user to sign in again. */
    ATTENTION("attention"),

    /** Signed in a moment ago, the commit not finished (§4.4): finished at the next start. */
    COMMITTING("committing"),
    ;

    companion object {
        fun of(wire: String?): FlagState = entries.firstOrNull { it.wire != null && it.wire == wire } ?: NONE
    }
}

/** What the iMessage screen says once about the Apple Account, after something happened to it. */
enum class AccountNotice(val wire: String) {
    /** A sign-in was interrupted (the process stopped in it) and what it left was removed. */
    INTERRUPTED("interrupted"),

    /** The account is no longer on this phone. */
    SIGNED_OUT("signedOut"),

    /** iMessage was registered again, which signs this phone's Apple Account out (S10). */
    SIGN_IN_AGAIN("signInAgain"),
    ;

    companion object {
        fun of(wire: String?): AccountNotice? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `files/imessage/apple-account.json`: the account's [state] since [sinceMs], the [limits]' times
 * (numbers only), and a [notice] to show once. Never the Apple ID, a password, a code or a token.
 */
data class AccountFlag(
    val state: FlagState = FlagState.NONE,
    val sinceMs: Long = 0,
    val limits: AppleAccountRules.Limits = AppleAccountRules.Limits(),
    val notice: AccountNotice? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .apply { state.wire?.let { put("state", it).put("since", sinceMs) } }
        .apply { if (limits.passwordFailures.isNotEmpty()) put("pw", JSONArray(limits.passwordFailures)) }
        .apply { if (limits.deviceCodes.isNotEmpty()) put("dev", JSONArray(limits.deviceCodes)) }
        .apply { if (limits.textCodes.isNotEmpty()) put("sms", JSONArray(limits.textCodes)) }
        .apply { notice?.let { put("notice", it.wire) } }

    companion object {
        /** [text] read strictly: anything not as written here is left out; text that is no JSON at all is no flag. */
        fun fromJson(text: String?): AccountFlag {
            val o = try {
                JSONObject(text ?: return AccountFlag())
            } catch (e: org.json.JSONException) {
                return AccountFlag()
            }
            val state = FlagState.of(o.opt("state") as? String)
            return AccountFlag(
                state = state,
                sinceMs = if (state == FlagState.NONE) 0 else (o.opt("since") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                limits = AppleAccountRules.Limits(stamps(o, "pw"), stamps(o, "dev"), stamps(o, "sms")),
                notice = AccountNotice.of(o.opt("notice") as? String),
            )
        }

        private fun stamps(o: JSONObject, key: String): List<Long> {
            val a = o.opt(key) as? JSONArray ?: return emptyList()
            return (0 until minOf(a.length(), AppleAccountRules.MAX_STAMPS * 2))
                .mapNotNull { (a.opt(it) as? Number)?.toLong()?.takeIf { t -> t > 0 } }
                .takeLast(AppleAccountRules.MAX_STAMPS)
        }
    }
}

/**
 * The files of the optional Apple Account, in the ":imessage" process: LightDecoder's own (its
 * [flag], the phone's iMessage user [phoneUserFile]) and the library's in its state folder
 * [statePath] (only looked at, and its logs deleted). All under `files/imessage`, which the main
 * process never names ([DirectPaths]) and nothing backs up.
 */
class AccountFiles(private val filesDir: File, val statePath: String) {

    val flagFile = File(filesDir, "imessage/apple-account.json")
    val phoneUserFile = phoneUserFile(filesDir)

    /** For later: the iCloud Keychain's code, iCloud's tokens, the user's own name and photo. Removed by a sign-out. */
    val keychainCodeFile = File(filesDir, "imessage/keychain-code.enc")
    val cloudDir = File(filesDir, "imessage/cloud")
    val myProfileFile = File(filesDir, "imessage/my-profile.json")

    /** The library's saved account: the one sign of an account on this phone that counts. */
    val accountPlist = File(statePath, "account.plist")

    fun accountSaved(): Boolean = accountPlist.isFile

    fun readFlag(): AccountFlag = AccountFlag.fromJson(
        try {
            flagFile.takeIf { it.isFile && it.length() <= MAX_FLAG_BYTES }?.readText()
        } catch (e: java.io.IOException) {
            null
        },
    )

    /** Writes [flag] whole (a crash leaves the old one or the new one), or deletes the file when nothing is left in it. */
    fun writeFlag(flag: AccountFlag) {
        val json = flag.toJson()
        if (json.length() == 0) {
            flagFile.delete()
            return
        }
        flagFile.parentFile?.mkdirs()
        val tmp = File(flagFile.parentFile, flagFile.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(json.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(flagFile)) {
            tmp.delete()
            throw java.io.IOException("the Apple Account's file was not written")
        }
    }

    /**
     * Deletes the library's own logs (`<statePath>/logs*`), which may hold the account's tokens: after
     * every sign-in and on every start while signed in. Returns how many were there.
     */
    fun deleteLibraryLogs(): Int {
        val logs = File(statePath).listFiles { f -> f.name.startsWith("logs") }.orEmpty()
        for (f in logs) f.deleteRecursively()
        return logs.size
    }

    /**
     * Says the library's logs are to be deleted at the next start: written when a restart for the
     * account becomes pending (a sign-out, a sign-in left that worked on the session, a commit), since
     * the session still holds the account in memory until then and the process may end some other
     * way (a crash, Android stopping it). Empty; gone once a start deleted the logs.
     */
    val logsMarker = File(filesDir, "imessage/apple-account-logs")

    fun markLogsToDelete() {
        if (logsMarker.isFile) return
        logsMarker.parentFile?.mkdirs()
        logsMarker.writeText("")
    }

    fun logsToDelete(): Boolean = logsMarker.isFile

    fun logsDeleted() {
        logsMarker.delete()
    }

    /** What a previous device set aside for the next one (LightDecoder never sets anything aside; this is in case). */
    fun deleteSetAside() {
        for (name in SET_ASIDE) File(statePath, name).delete()
    }

    /** LightDecoder's account files: the flag, and what later features keep for the account. The phone's user stays. */
    fun deleteAccountFiles() {
        flagFile.delete()
        File(flagFile.parentFile, flagFile.name + ".tmp").delete()
        keychainCodeFile.delete()
        cloudDir.deleteRecursively()
        myProfileFile.delete()
        deleteLibraryLogs()
    }

    companion object {
        private const val MAX_FLAG_BYTES = 16 * 1024L
        internal val SET_ASIDE = listOf("backup.plist", "previous_device.tmp")

        fun of(context: Context, statePath: String) = AccountFiles(context.filesDir, statePath)

        fun phoneUserFile(filesDir: File) = File(filesDir, "imessage/phone-user.enc")
    }
}

/**
 * The Keystore keys that are the Apple Account's own, deleted by a sign-out: only the library's key
 * for the password's hash, if it made one (the older library did; device check D13 confirms which
 * keys a sign-in makes). Never any other `imessage:` key: the phone's registration needs them.
 */
internal object AccountKeys {
    val ALIASES = listOf(DirectKeystore.PREFIX + "gsa:password")

    fun delete() {
        val store = KeyStore.getInstance(DirectKeystore.ANDROID_KEYSTORE).apply { load(null) }
        for (alias in ALIASES) if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}

/**
 * Makes initialised AES/GCM ciphers for [PhoneUserStore]. The real one uses a key in Android's
 * Keystore ([PhoneUserStore.KEY_ALIAS]); the Keystore does not exist on the JVM, so tests use [forKey].
 */
fun interface AccountCiphers {
    /** [iv] null: to encrypt, with a new random IV. Otherwise to decrypt with that IV. */
    fun cipher(iv: ByteArray?): Cipher

    companion object {
        fun forKey(key: SecretKey) = AccountCiphers { iv ->
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                if (iv == null) init(Cipher.ENCRYPT_MODE, key) else init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        }
    }
}

/**
 * This phone's number as an iMessage user (the library's `saveUser` text), kept after every successful
 * registration in `files/imessage/phone-user.enc`, so that adding or removing the Apple Account never
 * needs a new text message to the number. It holds the registration's keys, so it is encrypted
 * (AES-256-GCM, `[1][IV][ciphertext+tag]`) with a Keystore key of its own, [KEY_ALIAS], outside the
 * library's `imessage:` names ([DirectKeystore.PREFIX]). Kept when the user signs out; read back with
 * the library's `restoreUser` when it is to be registered again.
 */
class PhoneUserStore(private val file: File, private val ciphers: AccountCiphers) {

    fun save(plist: String) = synchronized(LOCK) {
        val cipher = ciphers.cipher(null)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected IV" }
        val sealed = cipher.doFinal(plist.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + IV_BYTES + sealed.size)
        out[0] = VERSION
        iv.copyInto(out, 1)
        sealed.copyInto(out, 1 + IV_BYTES)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(out)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("the phone's iMessage user was not kept")
        }
    }

    /** The kept user's text; null when there is none or it cannot be read (a damaged file, or another key's, is deleted). */
    fun load(): String? = synchronized(LOCK) {
        if (!file.isFile) return null
        try {
            val bytes = file.readBytes()
            if (bytes.size < 1 + IV_BYTES + TAG_BYTES || bytes[0] != VERSION) {
                file.delete()
                return null
            }
            val plain = ciphers.cipher(bytes.copyOfRange(1, 1 + IV_BYTES)).doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
            String(plain, Charsets.UTF_8)
        } catch (e: BadPaddingException) {
            // AEADBadTagException among them: changed, cut short, or sealed with another key.
            file.delete()
            null
        } catch (e: Exception) {
            Log.w(TAG, "the phone's iMessage user could not be read (${e::class.java.simpleName})")
            null
        }
    }

    fun clear() = synchronized(LOCK) {
        file.delete()
    }

    companion object {
        private const val TAG = "PhoneUserStore"
        const val KEY_ALIAS = "ld-phone-user"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private val LOCK = Any()

        fun of(context: Context) = PhoneUserStore(AccountFiles.phoneUserFile(context.filesDir), AccountCiphers { iv -> AccountCiphers.forKey(keystoreKey()).cipher(iv) })

        /** The Keystore key, made on first use. Not tied to the lock screen: registration may run with the phone locked. */
        private fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance(DirectKeystore.ANDROID_KEYSTORE).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, DirectKeystore.ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}
