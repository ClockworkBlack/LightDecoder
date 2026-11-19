package com.lightdecoder.imessage.direct

/**
 * Apple's phone-number registration by SMS: the phone texts `REG-REQ?v=3;t=<push token>;r=<id>`
 * to its carrier's Apple gateway, and Apple texts back `REG-RESP?v=3;r=<id>;n=<number>;s=<sig>`.
 * The number and signature then go to `authPhone`, which turns them into the phone's iMessage
 * identity. This is the same exchange an iPhone makes, as documented by the open-source pypush.
 */
object PhoneRegSms {

    /**
     * The text to send, exactly as OpenBubbles 2.0.0 and pypush write it: the push token in upper-case
     * hex, and a semicolon after the request id too (without it Apple's gateway does not answer).
     */
    fun request(pushToken: ByteArray, requestId: String): String =
        "REG-REQ?v=3;t=" + pushToken.joinToString("") { "%02X".format(it) } + ";r=" + requestId + ";"

    /** A fresh request id: digits only, so it survives any carrier's SMS handling. */
    fun newRequestId(random: java.util.Random = java.security.SecureRandom()): String =
        (1 + random.nextInt(9)).toString() + (1..8).joinToString("") { random.nextInt(10).toString() }

    data class Response(val requestId: String, val number: String, val signature: ByteArray)

    /**
     * The answer, if [body] holds Apple's REG-RESP; null for any other text. The reply may come
     * with something before it (a carrier header, or the raw bytes of a data SMS) and after the
     * signature, so it is looked for anywhere and the signature ends at its last hex digit.
     */
    fun parseResponse(body: String): Response? {
        val at = body.indexOf("REG-RESP?")
        if (at < 0) return null
        val text = body.substring(at)
        val fields = text.substringAfter('?').split(';')
            .filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=') }
        val id = fields["r"]?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() } ?: return null
        val number = fields["n"]?.trim()?.takeWhile { it == '+' || it.isDigit() }?.takeIf { it.count(Char::isDigit) >= 7 } ?: return null
        val sigHex = fields["s"]?.takeWhile { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ?: return null
        if (sigHex.isEmpty() || sigHex.length % 2 != 0) return null
        val sig = ByteArray(sigHex.length / 2) { sigHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Response(id, number, sig)
    }

    /**
     * The text of a text-SMS broadcast's parts, as Apple's reply is read from them: parts that
     * start with "~" are carrier headers and left out (as OpenBubbles does).
     */
    fun joinTextParts(parts: List<String>): String = parts.filterNot { it.startsWith("~") }.joinToString("")
}
