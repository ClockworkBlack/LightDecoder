package com.lightdecoder.imessage.direct

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * EAP-AKA (RFC 4187), the part a phone does: the carrier's server sends a challenge, the SIM
 * answers it, and the phone wraps the SIM's answer in a signed response. Used for "SMS-less"
 * phone-number verification ([SimAuth]), where the carrier vouches for the number to Apple, as
 * on iPhones of carriers that support it and in OpenBubbles 2.0.0 (which ports Android's own
 * service_entitlement library for this; the formats here follow the same RFC).
 *
 * Pure (no Android classes), so the arithmetic is tested against RFC 4186's published vectors
 * (EapAkaTest). The SIM itself is reached through a function the caller passes in.
 */
object EapAka {

    const val REALM = "nai.epc"

    private const val CODE_REQUEST = 1
    private const val CODE_RESPONSE = 2
    private const val TYPE_EAP_AKA = 23
    private const val SUBTYPE_CHALLENGE = 1
    private const val SUBTYPE_SYNC_FAILURE = 4
    private const val AT_RAND = 1
    private const val AT_AUTN = 2
    private const val AT_RES = 3
    private const val AT_AUTS = 4
    private const val AT_MAC = 11
    private const val SIM_OK = 0xDB
    private const val SIM_RESYNC = 0xDC

    /**
     * The SIM's identity towards the carrier (3GPP TS 23.003): "0" + IMSI + "@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org",
     * the MNC written with three digits. Null when the SIM's operator or IMSI is not known.
     */
    fun identity(mccmnc: String?, imsi: String?, realm: String = REALM): String? {
        if (mccmnc == null || mccmnc.length < 5 || imsi.isNullOrEmpty()) return null
        val mcc = mccmnc.substring(0, 3)
        val mnc = mccmnc.substring(3).let { if (it.length == 2) "0$it" else it }
        return "0$imsi@$realm.mnc$mnc.mcc$mcc.3gppnetwork.org"
    }

    class Challenge(val identifier: Byte, val rand: ByteArray, val autn: ByteArray) {
        /** What the SIM is asked (TelephonyManager.getIccAuthentication): base64 of len|RAND|len|AUTN. */
        fun simRequest(): String =
            Base64.getEncoder().encodeToString(byteArrayOf(rand.size.toByte()) + rand + byteArrayOf(autn.size.toByte()) + autn)
    }

    /** The server's EAP-Request/AKA-Challenge (base64). Throws IllegalArgumentException when it is not one. */
    fun parseChallenge(base64: String): Challenge {
        val d = try {
            Base64.getMimeDecoder().decode(base64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The carrier's challenge is not valid base64.")
        }
        require(d.size > 8 && d[0].toInt() == CODE_REQUEST && d[4].toInt() == TYPE_EAP_AKA && d[5].toInt() == SUBTYPE_CHALLENGE) {
            "The carrier did not send an EAP-AKA challenge."
        }
        var rand: ByteArray? = null
        var autn: ByteArray? = null
        var i = 8
        while (i + 1 < d.size) {
            val type = d[i].toInt() and 0xff
            val len = (d[i + 1].toInt() and 0xff) * 4
            require(len > 0 && i + len <= d.size) { "The carrier's challenge is cut short." }
            if (len == 20) {
                when (type) {
                    AT_RAND -> rand = d.copyOfRange(i + 4, i + 20)
                    AT_AUTN -> autn = d.copyOfRange(i + 4, i + 20)
                }
            }
            i += len
        }
        return Challenge(d[1], requireNotNull(rand) { "The carrier's challenge has no RAND." }, requireNotNull(autn) { "The carrier's challenge has no AUTN." })
    }

    /** What the SIM said. */
    sealed class SimAnswer {
        class Keys(val res: ByteArray, val ck: ByteArray, val ik: ByteArray) : SimAnswer()

        /** The SIM's sequence number is out of step with the network's: the carrier has to be asked again. */
        class Resync(val auts: ByteArray) : SimAnswer()
    }

    /** The SIM's answer (base64): tag 0xDB then RES, CK, IK, or tag 0xDC then AUTS, each with a length byte. */
    fun parseSimAnswer(base64: String): SimAnswer {
        val d = Base64.getMimeDecoder().decode(base64.trim())
        require(d.isNotEmpty()) { "The SIM gave an empty answer." }
        var i = 1
        fun next(): ByteArray {
            require(i < d.size) { "The SIM's answer is cut short." }
            val len = d[i].toInt() and 0xff
            require(i + 1 + len <= d.size) { "The SIM's answer is cut short." }
            return d.copyOfRange(i + 1, i + 1 + len).also { i += 1 + len }
        }
        return when (d[0].toInt() and 0xff) {
            SIM_OK -> {
                val res = next()
                val ck = next()
                val ik = next()
                SimAnswer.Keys(res, ck, ik)
            }
            SIM_RESYNC -> SimAnswer.Resync(next())
            else -> throw IllegalArgumentException("The SIM refused the carrier's challenge.")
        }
    }

    /** MK = SHA-1(identity | IK | CK) (RFC 4187 section 7). */
    fun masterKey(identity: String, ik: ByteArray, ck: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").run {
            update(identity.toByteArray(Charsets.UTF_8))
            update(ik)
            update(ck)
            digest()
        }

    /**
     * The key generator of FIPS 186-2 (change notice 1) with MK as its seed: 20 bytes per round,
     * w = G(XKEY), XKEY = (1 + XKEY + w) mod 2^160, where G is one SHA-1 compression of XKEY padded
     * with zeros. Its output is K_encr (16) | K_aut (16) | MSK (64) | EMSK (64).
     */
    fun prf(mk: ByteArray, length: Int = 160): ByteArray {
        require(mk.size == 20)
        val mod = BigInteger.ONE.shiftLeft(160)
        var xkey = BigInteger(1, mk)
        val out = ByteArrayOutputStream()
        while (out.size() < length) {
            val xval = xkey.toByteArray().let { b -> ByteArray(20).also { o -> // exactly 20 bytes, big-endian
                val take = minOf(20, b.size)
                System.arraycopy(b, b.size - take, o, 20 - take, take)
            } }
            val w = sha1Compress(xval + ByteArray(44))
            out.write(w)
            xkey = xkey.add(BigInteger(1, w)).add(BigInteger.ONE).mod(mod)
        }
        return out.toByteArray().copyOf(length)
    }

    /** K_aut, which signs the response: bytes 16..32 of [prf]. */
    fun kAut(identity: String, ik: ByteArray, ck: ByteArray): ByteArray = prf(masterKey(identity, ik, ck), 32).copyOfRange(16, 32)

    /** SHA-1's compression function on one 64-byte block from the standard initial state (no padding added). */
    internal fun sha1Compress(block: ByteArray): ByteArray {
        require(block.size == 64)
        val w = IntArray(80)
        for (t in 0 until 16) {
            w[t] = ((block[t * 4].toInt() and 0xff) shl 24) or ((block[t * 4 + 1].toInt() and 0xff) shl 16) or
                ((block[t * 4 + 2].toInt() and 0xff) shl 8) or (block[t * 4 + 3].toInt() and 0xff)
        }
        for (t in 16 until 80) w[t] = Integer.rotateLeft(w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16], 1)
        val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
        for (t in 0 until 80) {
            val (f, k) = when (t) {
                in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                else -> (b xor c xor d) to 0xCA62C1D6.toInt()
            }
            val temp = Integer.rotateLeft(a, 5) + f + e + k + w[t]
            e = d; d = c; c = Integer.rotateLeft(b, 30); b = a; a = temp
        }
        val sum = intArrayOf(h[0] + a, h[1] + b, h[2] + c, h[3] + d, h[4] + e)
        return ByteArray(20) { i -> (sum[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
    }

    /**
     * EAP-Response/AKA-Challenge: AT_RES with the SIM's RES, and AT_MAC = HMAC-SHA1-128 with K_aut
     * over the whole packet (its MAC field zero while it is computed).
     */
    fun challengeResponse(identifier: Byte, res: ByteArray, kAut: ByteArray): ByteArray {
        val resPadded = res.copyOf((res.size + 3) / 4 * 4)
        val length = 8 + 4 + resPadded.size + 20
        val p = ByteArray(length)
        p[0] = CODE_RESPONSE.toByte()
        p[1] = identifier
        p[2] = (length ushr 8).toByte()
        p[3] = length.toByte()
        p[4] = TYPE_EAP_AKA.toByte()
        p[5] = SUBTYPE_CHALLENGE.toByte()
        p[8] = AT_RES.toByte()
        p[9] = (1 + resPadded.size / 4).toByte()
        p[10] = ((res.size * 8) ushr 8).toByte()
        p[11] = (res.size * 8).toByte()
        System.arraycopy(resPadded, 0, p, 12, resPadded.size)
        val mac = 12 + resPadded.size
        p[mac] = AT_MAC.toByte()
        p[mac + 1] = 5
        val sig = Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(kAut, "HmacSHA1"))
            doFinal(p)
        }
        System.arraycopy(sig, 0, p, mac + 4, 16)
        return p
    }

    /** Whether [packet]'s AT_MAC is right for [kAut] (for tests, and a check before sending). */
    fun macIsValid(packet: ByteArray, kAut: ByteArray): Boolean {
        val at = packet.size - 20
        if (at < 8 || packet[at].toInt() != AT_MAC) return false
        val zeroed = packet.copyOf().also { java.util.Arrays.fill(it, at + 4, at + 20, 0) }
        val sig = Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(kAut, "HmacSHA1"))
            doFinal(zeroed)
        }
        return MessageDigest.isEqual(sig.copyOf(16), packet.copyOfRange(at + 4, at + 20))
    }

    /** EAP-Response/AKA-Synchronization-Failure carrying the SIM's AUTS. */
    fun syncFailureResponse(identifier: Byte, auts: ByteArray): ByteArray {
        val length = 8 + 2 + auts.size
        val p = ByteArray(length)
        p[0] = CODE_RESPONSE.toByte()
        p[1] = identifier
        p[2] = (length ushr 8).toByte()
        p[3] = length.toByte()
        p[4] = TYPE_EAP_AKA.toByte()
        p[5] = SUBTYPE_SYNC_FAILURE.toByte()
        p[8] = AT_AUTS.toByte()
        p[9] = ((2 + auts.size) / 4).toByte()
        System.arraycopy(auts, 0, p, 10, auts.size)
        return p
    }

    /** The SIM needs another try: its counter was out of step with the carrier's. */
    class ResyncNeeded : Exception("The SIM and the carrier were out of step for this try. Please try again.")

    /**
     * Answers the carrier's [challenge] (base64) for the SIM with [identity]: [sim] asks the SIM
     * (base64 in, base64 out; null when it gave nothing). Returns the response to send back (base64).
     */
    fun respond(challenge: String, identity: String, sim: (String) -> String?): String {
        val ch = parseChallenge(challenge)
        val raw = sim(ch.simRequest()) ?: throw IllegalStateException("The SIM gave no answer to the carrier's challenge.")
        return when (val answer = parseSimAnswer(raw)) {
            is SimAnswer.Keys ->
                Base64.getEncoder().encodeToString(challengeResponse(ch.identifier, answer.res, kAut(identity, answer.ik, answer.ck)))
            is SimAnswer.Resync -> throw ResyncNeeded()
        }
    }
}
