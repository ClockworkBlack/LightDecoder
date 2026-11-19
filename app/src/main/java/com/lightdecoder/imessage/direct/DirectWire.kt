package com.lightdecoder.imessage.direct

import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import org.json.JSONArray
import org.json.JSONObject

/**
 * What crosses between the ":imessage" process (OpenBubbles' library, [DirectService]) and the
 * app's main process (the Messages archive, [DirectLink]), as JSON in a Bundle. Pure and
 * tested (DirectWireTest), so both sides always agree.
 *
 * Main -> :imessage: [SEND], [UNSEND], [REACH] (requests, answered with the same request id).
 * :imessage -> main: [UPDATE] (an archive change from Apple), answered once it is stored; only
 * then does the library forget the event.
 */
object DirectWire {

    const val SEND = 1
    const val UNSEND = 2
    const val REACH = 3
    const val UPDATE = 10
    const val REPLY = 100

    const val KEY_ID = "id"
    const val KEY_JSON = "json"

    /**
     * Where a message goes: the conversation's members (the user's own address included), its
     * group guid and name, and the address to send from. Built from the archive by the main process.
     */
    data class Target(val participants: List<String>, val groupName: String?, val groupGuid: String?, val from: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("participants", JSONArray(participants))
            .put("groupName", groupName ?: JSONObject.NULL)
            .put("groupGuid", groupGuid ?: JSONObject.NULL)
            .put("from", from)

        companion object {
            fun fromJson(o: JSONObject) = Target(
                o.getJSONArray("participants").let { a -> (0 until a.length()).map { a.getString(it) } },
                o.optStringOrNull("groupName"),
                o.optStringOrNull("groupGuid"),
                o.getString("from"),
            )
        }
    }

    fun send(target: Target, text: String, replyGuid: String?): JSONObject =
        JSONObject().put("target", target.toJson()).put("text", text).put("replyGuid", replyGuid ?: JSONObject.NULL)

    fun unsend(target: Target, guid: String, part: Int): JSONObject =
        JSONObject().put("target", target.toJson()).put("guid", guid).put("part", part)

    fun reach(recipient: String): JSONObject = JSONObject().put("recipient", recipient)

    /** A send's answer: the GUID and Apple's timestamp, or an error. */
    fun sent(guid: String, timestampMs: Long): JSONObject = JSONObject().put("guid", guid).put("ts", timestampMs)

    fun ok(): JSONObject = JSONObject().put("ok", true)

    fun error(message: String): JSONObject = JSONObject().put("error", message)

    fun errorOf(o: JSONObject): String? = o.optStringOrNull("error")

    fun encode(u: DirectArchive.Update): JSONObject = when (u) {
        is DirectArchive.Update.Upsert -> JSONObject().put("kind", "upsert")
            .put("conversation", u.conversation.toJson()).put("message", u.message.toJson())
        is DirectArchive.Update.Remove -> JSONObject().put("kind", "remove").put("messageId", u.messageId)
        is DirectArchive.Update.Receipt -> JSONObject().put("kind", "receipt")
            .put("messageIds", JSONArray(u.messageIds)).put("status", u.status)
        is DirectArchive.Update.Edit -> JSONObject().put("kind", "edit").put("messageId", u.messageId).put("text", u.text)
        DirectArchive.Update.Ignore -> JSONObject().put("kind", "ignore")
    }

    fun decode(o: JSONObject): DirectArchive.Update = when (o.optString("kind")) {
        "upsert" -> DirectArchive.Update.Upsert(
            ConversationDto.fromJson(o.getJSONObject("conversation")),
            MessageDto.fromJson(o.getJSONObject("message")),
        )
        "remove" -> DirectArchive.Update.Remove(o.getString("messageId"))
        "receipt" -> DirectArchive.Update.Receipt(
            o.getJSONArray("messageIds").let { a -> (0 until a.length()).map { a.getString(it) } },
            o.getString("status"),
        )
        "edit" -> DirectArchive.Update.Edit(o.getString("messageId"), o.getString("text"))
        else -> DirectArchive.Update.Ignore
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
