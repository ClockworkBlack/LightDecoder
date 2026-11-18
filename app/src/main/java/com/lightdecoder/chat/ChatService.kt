package com.lightdecoder.chat

/**
 * The messaging services the app can link to. Each has its own runtime
 * ([ChatBackend]), its own archive database, its own media directories, its
 * own notification channels and its own preferences; nothing is shared
 * between them but the screens.
 *
 * [key] is what intents ([com.lightdecoder.relay.RelayIntents.EXTRA_SERVICE])
 * and stored choices carry; it never changes for a service.
 */
enum class ChatService(val key: String) {
    /** Google Messages (SMS, MMS and RCS on the paired phone). */
    MESSAGES("messages"),

    /** WhatsApp, as a linked device. */
    WHATSAPP("whatsapp"),

    /** Gmail, over IMAP and SMTP with the user's app password. */
    GMAIL("gmail"),

    /**
     * iMessage, through the OpenBubbles app ("Blue Bubbles" in the Messages
     * tab's menu): OpenBubbles is the iMessage client, and LightDecoder
     * reads its message notifications and answers through their reply
     * button ([com.lightdecoder.imessage.IMessageManager]). It has no tab of
     * its own: its conversations show in the Messages tab.
     */
    IMESSAGE("imessage");

    companion object {
        /** The service for [key]; anything unknown (or missing) is [MESSAGES], which is all there was before. */
        fun fromKey(key: String?): ChatService = entries.firstOrNull { it.key == key } ?: MESSAGES
    }
}

/**
 * What a service can do beyond the basics every service has. The screens
 * show an action only if its service has the capability.
 */
enum class Capability {
    /** The service has a spam folder ([com.lightdecoder.relay.Folder.SPAM]) and "not spam". */
    SPAM_FOLDER,

    /** Conversations deleted on the phone are kept in a local "deleted" folder. */
    DELETED_FOLDER,

    /** The original of a picture that came as a thumbnail can be asked of the phone. */
    FULL_SIZE_REQUEST,

    /** Another device can take the session over, and "Use here" takes it back. */
    USE_HERE,

    /** Other people's online state and last-seen time ([ChatBackend.presence]). */
    PRESENCE,

    /** Messages can carry a subject line. */
    SUBJECT,

    /** New group conversations can be created from here. */
    NEW_GROUP,

    /** A message can be deleted for everyone in the conversation, not only here. */
    DELETE_FOR_EVERYONE,

    /** The text of one's own message can be changed after it was sent, for everyone. */
    EDIT,
}

/** Whether someone is online now; [lastSeenMs] (epoch ms) is 0 when it is not known. */
data class Presence(val online: Boolean, val lastSeenMs: Long)
