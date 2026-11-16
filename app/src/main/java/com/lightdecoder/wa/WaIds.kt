package com.lightdecoder.wa

import android.content.Context
import java.security.MessageDigest

/**
 * Stand-ins for WhatsApp's ids wherever one has to leave the app's private
 * storage: a notification's tag (other apps with notification access, and
 * bug reports, can read it) or a line of [WaLog].
 *
 * A WhatsApp JID is a phone number, and a message id or a push name
 * identifies a person nearly as well, so none of them is ever used there as
 * it is.
 */
object WaIds {
    /**
     * A stable stand-in for [id]: 32 hex digits of a hash keyed with a
     * secret of this installation ([WaPrefs.idSalt]). The same id always
     * gives the same stand-in on this phone, also after a restart, so a
     * notification can be found again by it; without the secret it cannot be
     * turned back into the id, not even by trying every phone number.
     */
    fun opaque(context: Context, id: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(WaPrefs.idSalt(context).toByteArray())
        digest.update(0)
        digest.update(id.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}
