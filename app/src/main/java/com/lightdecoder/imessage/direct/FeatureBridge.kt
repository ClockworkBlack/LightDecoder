package com.lightdecoder.imessage.direct

import uniffi.rust_bridge.AttributedBody
import uniffi.rust_bridge.CloudAttachmentPage
import uniffi.rust_bridge.CloudChatPage
import uniffi.rust_bridge.CloudMessagePage
import uniffi.rust_bridge.IMessageNameRecord
import uniffi.rust_bridge.IMessageNicknameRecord
import uniffi.rust_bridge.SharedPushStateHandle
import uniffi.rust_bridge.StatusKitInvitee

/**
 * The library's functions the Apple Account's features (B6) use, on the live session, in the
 * ":imessage" process only: Name & Photo, Focus status, the iCloud Keychain's state, and reading
 * Messages in iCloud. Tests put a fake here: the library cannot run in them.
 *
 * Only these, and only reads where iCloud is concerned: nothing here deletes, saves or resets
 * anything in iCloud (no `deleteCloud*`, `saveCloud*`, `resetCloudMessages`, `resetClique`), and
 * nothing of Find My. Every function may throw.
 */
interface FeatureBridge {

    /** A name and photo someone shared, as fetched: the photo's bytes as they came (null: none), never a poster. */
    data class Fetched(val name: String, val first: String, val last: String, val image: ByteArray?)

    /** `fetchProfile`: the record [descriptor] points to (its poster left out of the request). */
    suspend fun fetchProfile(descriptor: ProfileDescriptor): Fetched

    /** `hasMyProfileRecord`: whether the account already shares a name and photo (from the user's iPhone, say). */
    suspend fun hasMyProfileRecord(): Boolean

    /** `setProfile`: publishes the user's name and photo (retiring [existing]); where it now is. */
    suspend fun setProfile(name: String, first: String, last: String, image: ByteArray?, existing: ProfileDescriptor?): ProfileDescriptor

    suspend fun statusSubscribe(handles: List<String>)
    suspend fun statusUnsubscribe()

    /** `statuskitShareStatus`: [mode] the Focus shared ("dnd"), null when none is on. */
    suspend fun shareStatus(mode: String?)

    /** `statuskitInvite`: lets [handles] see the user's Focus, as shared from [sender]. */
    suspend fun statusInvite(sender: String, handles: List<String>)
    suspend fun statusRemove(handle: String)
    suspend fun statusResetKeys()

    /** `isInClique`: this phone is in the account's iCloud Keychain. */
    suspend fun isInClique(): Boolean

    suspend fun syncCloudChats(token: ByteArray?): CloudChatPage
    suspend fun syncCloudMessages(token: ByteArray?): CloudMessagePage
    suspend fun syncCloudAttachments(token: ByteArray?): CloudAttachmentPage

    /** `downloadCloudAttachments`: each record id's file into the path LightDecoder chose for it. */
    suspend fun downloadCloudAttachments(files: Map<String, String>)

    /** `decodeAttributedBody`: an imported message's body (bounded by the caller first). */
    fun decodeAttributedBody(data: ByteArray): AttributedBody
}

/** The real [FeatureBridge]: `uniffi.rust_bridge` on the session [session] gives (the engine's). */
class NativeFeatureBridge(private val session: () -> SharedPushStateHandle?) : FeatureBridge {

    private fun live(): SharedPushStateHandle = session() ?: throw DirectEngine.ImessageError("iMessage is not connected.")

    override suspend fun fetchProfile(descriptor: ProfileDescriptor): FeatureBridge.Fetched {
        val r = uniffi.rust_bridge.fetchProfile(live(), descriptor.toMessage())
        return FeatureBridge.Fetched(r.name.name, r.name.first, r.name.last, r.image)
    }

    override suspend fun hasMyProfileRecord(): Boolean = uniffi.rust_bridge.hasMyProfileRecord(live())

    override suspend fun setProfile(name: String, first: String, last: String, image: ByteArray?, existing: ProfileDescriptor?): ProfileDescriptor {
        val record = IMessageNicknameRecord(IMessageNameRecord(name, first, last), image, null)
        val pointer = uniffi.rust_bridge.setProfile(live(), record, existing?.toMessage())
        return ProfileDescriptor.of(pointer) ?: throw DirectEngine.ImessageError("Apple did not say where the name and photo are.")
    }

    override suspend fun statusSubscribe(handles: List<String>) = uniffi.rust_bridge.statuskitSubscribe(live(), handles)
    override suspend fun statusUnsubscribe() = uniffi.rust_bridge.statuskitUnsubscribe(live())
    override suspend fun shareStatus(mode: String?) = uniffi.rust_bridge.statuskitShareStatus(live(), mode)
    override suspend fun statusInvite(sender: String, handles: List<String>) =
        uniffi.rust_bridge.statuskitInvite(live(), sender, handles.map { StatusKitInvitee(it, emptyList()) })
    override suspend fun statusRemove(handle: String) = uniffi.rust_bridge.statuskitRemove(live(), handle)
    override suspend fun statusResetKeys() = uniffi.rust_bridge.statuskitResetKeys(live())

    override suspend fun isInClique(): Boolean = uniffi.rust_bridge.isInClique(live())

    override suspend fun syncCloudChats(token: ByteArray?): CloudChatPage = uniffi.rust_bridge.syncCloudChats(live(), token)
    override suspend fun syncCloudMessages(token: ByteArray?): CloudMessagePage = uniffi.rust_bridge.syncCloudMessages(live(), token)
    override suspend fun syncCloudAttachments(token: ByteArray?): CloudAttachmentPage = uniffi.rust_bridge.syncCloudAttachments(live(), token)
    override suspend fun downloadCloudAttachments(files: Map<String, String>) = uniffi.rust_bridge.downloadCloudAttachments(live(), files)

    override fun decodeAttributedBody(data: ByteArray): AttributedBody = uniffi.rust_bridge.decodeAttributedBody(data)
}
