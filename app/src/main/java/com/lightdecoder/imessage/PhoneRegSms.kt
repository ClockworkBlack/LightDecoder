package com.lightdecoder.imessage

/**
 * Apple's phone-number registration by SMS: the phone texts `REG-REQ?v=3;t=<push token>;r=<id>`
 * to its carrier's Apple gateway, and Apple texts back `REG-RESP?v=3;r=<id>;n=<number>;s=<sig>`.
 * The number and signature then go to `authPhone`, which turns them into the phone's iMessage
 * identity. This is the same exchange an iPhone makes, as documented by the open-source pypush.
 */
object PhoneRegSms {

    /** The text to send. The push token is written as upper-case hex. */
    fun request(pushToken: ByteArray, requestId: String): String =
        "REG-REQ?v=3;t=" + pushToken.joinToString("") { "%02X".format(it) } + ";r=" + requestId

    /** A fresh request id: digits only, so it survives any carrier's SMS handling. */
    fun newRequestId(random: java.util.Random = java.security.SecureRandom()): String =
        (1 + random.nextInt(9)).toString() + (1..8).joinToString("") { random.nextInt(10).toString() }

    data class Response(val requestId: String, val number: String, val signature: ByteArray)

    /** The answer, if [body] is Apple's REG-RESP; null for any other text. */
    fun parseResponse(body: String): Response? {
        val text = body.trim()
        if (text.substringBefore('?') != "REG-RESP") return null
        val fields = text.substringAfter('?').split(';')
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val id = fields["r"] ?: return null
        val number = fields["n"]?.takeIf { it.isNotBlank() } ?: return null
        val sigHex = fields["s"] ?: return null
        if (sigHex.isEmpty() || sigHex.length % 2 != 0 || !sigHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        val sig = ByteArray(sigHex.length / 2) { sigHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Response(id, number, sig)
    }
}
