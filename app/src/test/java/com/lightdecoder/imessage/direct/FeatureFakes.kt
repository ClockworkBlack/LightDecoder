package com.lightdecoder.imessage.direct

import java.util.Collections
import uniffi.rust_bridge.AttributedBody
import uniffi.rust_bridge.CloudAttachmentPage
import uniffi.rust_bridge.CloudChatPage
import uniffi.rust_bridge.CloudMessagePage

/** A stand-in for the library's functions the Apple Account's features use ([FeatureBridge]): every call kept, by name, in order. */
class FakeFeatureBridge : FeatureBridge {

    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    val subscribed = Collections.synchronizedList(ArrayList<List<String>>())
    val invited = Collections.synchronizedList(ArrayList<Pair<String, List<String>>>())
    val shared = Collections.synchronizedList(ArrayList<String?>())
    val removed = Collections.synchronizedList(ArrayList<String>())
    val tokens = Collections.synchronizedList(ArrayList<Pair<String, String?>>())

    var fetched: suspend () -> FeatureBridge.Fetched = { FeatureBridge.Fetched("Bob Example", "Bob", "Example", byteArrayOf(1, 2, 3)) }
    var hasRecord = false
    var pointer = ProfileDescriptor(byteArrayOf(9, 9, 9), "record-mine")
    var subscribeFails: Throwable? = null
    /** Handles whose `statusRemove` fails, and whether `statusResetKeys` does. */
    val removeFails: MutableSet<String> = Collections.synchronizedSet(HashSet())
    var resetFails = false
    var inClique = true
    var chatPages: MutableList<() -> CloudChatPage> = ArrayList()
    var messagePages: MutableList<() -> CloudMessagePage> = ArrayList()
    var attachmentPages: MutableList<() -> CloudAttachmentPage> = ArrayList()
    var decode: (ByteArray) -> AttributedBody = { AttributedBody(String(it), emptyList()) }

    private fun call(name: String) {
        calls += name
    }

    override suspend fun fetchProfile(descriptor: ProfileDescriptor): FeatureBridge.Fetched {
        call("fetchProfile")
        return fetched()
    }

    override suspend fun hasMyProfileRecord(): Boolean {
        call("hasMyProfileRecord")
        return hasRecord
    }

    override suspend fun setProfile(name: String, first: String, last: String, image: ByteArray?, existing: ProfileDescriptor?): ProfileDescriptor {
        call("setProfile")
        return pointer
    }

    override suspend fun statusSubscribe(handles: List<String>) {
        call("statusSubscribe")
        subscribeFails?.let { throw it }
        subscribed += handles
    }

    override suspend fun statusUnsubscribe() = call("statusUnsubscribe")

    override suspend fun shareStatus(mode: String?) {
        call("shareStatus")
        shared += mode
    }

    override suspend fun statusInvite(sender: String, handles: List<String>) {
        call("statusInvite")
        invited += sender to handles
    }

    override suspend fun statusRemove(handle: String) {
        call("statusRemove")
        if (handle in removeFails) throw DirectEngine.ImessageError("no network")
        removed += handle
    }

    override suspend fun statusResetKeys() {
        call("statusResetKeys")
        if (resetFails) throw DirectEngine.ImessageError("no network")
    }

    override suspend fun isInClique(): Boolean {
        call("isInClique")
        return inClique
    }

    private fun tokenOf(t: ByteArray?) = t?.let { String(it) }

    override suspend fun syncCloudChats(token: ByteArray?): CloudChatPage {
        call("syncCloudChats")
        tokens += "chats" to tokenOf(token)
        return chatPages.removeFirstOrNull()?.invoke() ?: CloudChatPage("c-end".toByteArray(), emptyMap(), CloudImport.DONE)
    }

    override suspend fun syncCloudMessages(token: ByteArray?): CloudMessagePage {
        call("syncCloudMessages")
        tokens += "messages" to tokenOf(token)
        return messagePages.removeFirstOrNull()?.invoke() ?: CloudMessagePage("m-end".toByteArray(), emptyMap(), CloudImport.DONE)
    }

    override suspend fun syncCloudAttachments(token: ByteArray?): CloudAttachmentPage {
        call("syncCloudAttachments")
        tokens += "attachments" to tokenOf(token)
        return attachmentPages.removeFirstOrNull()?.invoke() ?: CloudAttachmentPage("a-end".toByteArray(), emptyMap(), CloudImport.DONE)
    }

    override suspend fun downloadCloudAttachments(files: Map<String, String>) = call("downloadCloudAttachments")

    override fun decodeAttributedBody(data: ByteArray): AttributedBody {
        call("decodeAttributedBody")
        return decode(data)
    }

    var summary: (ByteArray) -> uniffi.rust_bridge.MessageSummaryInfo = { throw DirectEngine.ImessageError("no summary") }

    override fun decodeMessageSummaryInfo(data: ByteArray): uniffi.rust_bridge.MessageSummaryInfo {
        call("decodeMessageSummaryInfo")
        return summary(data)
    }
}

/** A stand-in for the keychain join's library steps: each call kept, by name, in order. */
class FakeJoinSteps : KeychainJoin.Steps {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    var auth: AccountBridge.Step = AccountBridge.Step.NeedsDevice2Fa
    var sent: AccountBridge.Step = AccountBridge.Step.Needs2FaVerification
    var verified = AccountBridge.Verified(AccountBridge.Step.LoggedIn, true)
    var reconfigureFails: Throwable? = null
    var cliqueFails: Throwable? = null
    var joined = true
    var codes = ArrayList<String>()
    var recoverFails: Throwable? = null
    var ready = true

    /** When set, the rebuild waits for it (a screen going meanwhile). */
    var reconfigureGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun ready(): Boolean = ready

    override suspend fun authSaved(): AccountBridge.Step {
        calls += "authSaved"
        return auth
    }

    override suspend fun sendToDevices(): AccountBridge.Step {
        calls += "sendToDevices"
        return sent
    }

    override suspend fun verify(code: String): AccountBridge.Verified {
        calls += "verify"
        return verified
    }

    override suspend fun reconfigure() {
        calls += "reconfigure"
        reconfigureGate?.await()
        reconfigureFails?.let { throw it }
    }

    override suspend fun setupClique(code: String): Boolean {
        calls += "setupClique"
        codes += code
        cliqueFails?.let { throw it }
        return true
    }

    override suspend fun inClique(): Boolean {
        calls += "inClique"
        return joined
    }

    override suspend fun recover() {
        calls += "recover"
        recoverFails?.let { throw it }
    }

    override fun signOutHere() {
        calls += "signOutHere"
    }

    override fun close() {
        calls += "close"
    }
}
