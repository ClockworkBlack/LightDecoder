# Multi-service LightDecoder: Messages and WhatsApp in one app (phase 2 contract)

Repo: /home/user/LightDecoder, branch ccr-b4eccc90-m97k4j.

**Already built**
- **Google Messages link:** relay/ runtime (RelayManager singleton), archive/ (ArchiveRepository over relay-archive.db), and UI in ui/chat (ConversationsActivity, ThreadActivity, NewConversationActivity) and ui/relay (setup, status).
- **Phase 1 added:**
  - the Go WhatsApp bridge, package gmbridge/wa bound into the same aar (see its final report and the generated app/libs/gmbridge-sources.jar for the exact Java API);
  - voice features in ThreadActivity.

**Hard rules (also for WhatsApp)**
1. Linking is never automated: the user types the pairing code into WhatsApp on their phone, or scans the QR, themselves.
2. While connected, a visible foreground notification with Disconnect is shown. Disconnect and Unpair are always available.
3. Local only. Everything is excluded from backup; data_extraction_rules already excludes everything.
4. Logs never contain message text, names, numbers, JIDs or codes.

## Shared Kotlin API

The RUNTIME agent creates these FIRST, before any other edit, so the UI agent can compile against them. Package `com.lightdecoder.chat`.

```kotlin
enum class ChatService(val key: String) { MESSAGES("messages"), WHATSAPP("whatsapp");
    companion object { fun fromKey(key: String?): ChatService = entries.firstOrNull { it.key == key } ?: MESSAGES } }

enum class Capability { SPAM_FOLDER, DELETED_FOLDER, FULL_SIZE_REQUEST, USE_HERE, PRESENCE, SUBJECT, NEW_GROUP, DELETE_FOR_EVERYONE }

data class Presence(val online: Boolean, val lastSeenMs: Long)   // lastSeenMs 0 = unknown

/** What the conversation screens need from one messaging service. */
interface ChatBackend {
    val service: ChatService
    val capabilities: Set<Capability>
    val repo: ArchiveRepository
    val state: StateFlow<RelayState>
    val typing: StateFlow<Map<String, Set<String>>>          // conversationId -> names typing
    val presence: StateFlow<Map<String, Presence>>           // participantId -> presence (empty for Messages)
    val backfill: StateFlow<BackfillProgress>
    fun isPaired(): Boolean
    fun sendText(conversationId: String, text: String, replyToId: String? = null): String
    fun sendMedia(conversationId: String, uri: Uri, caption: String? = null, replyToId: String? = null): String
    fun retry(tmpId: String)
    fun discardFailed(tmpId: String)
    fun typing(conversationId: String)
    fun watchPresence(conversationId: String)                 // 1:1 threads; no-op without PRESENCE
    fun setAppVisible(visible: Boolean)                        // WhatsApp: our own "online"; no-op for Messages
    suspend fun react(messageId: String, emoji: String, remove: Boolean)
    suspend fun markRead(conversationId: String)
    suspend fun loadOlder(conversationId: String, count: Int = 50): Int
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteConversation(conversationId: String)
    suspend fun setArchived(conversationId: String, archived: Boolean)
    suspend fun setMuted(conversationId: String, muted: Boolean)
    suspend fun refreshConversations(folder: Folder = Folder.INBOX): Int
    suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean = false): File?
    suspend fun requestFullSize(messageId: String, partId: String)
    suspend fun contacts(refresh: Boolean): ContactsResult
    suspend fun startConversation(numbers: List<String>, groupName: String?): String
    suspend fun markNotSpam(conversationId: String)
    suspend fun restoreDeletedLocally(conversationId: String): Boolean
    suspend fun purgeDeleted(conversationId: String?): Int
    suspend fun deleteLocalArchive(): Boolean
    suspend fun useHere()
}

object ChatBackends {
    fun get(context: Context, service: ChatService): ChatBackend   // MESSAGES -> RelayManager.get(context); WHATSAPP -> WaManager.get(context)
    fun all(context: Context): List<ChatBackend>
}
```

### Behind the interface
- **RelayManager** implements ChatBackend with its existing methods; signatures are already as above.
  - `capabilities` = everything except PRESENCE and DELETE_FOR_EVERYONE.
  - `presence` is an empty flow.
  - `watchPresence` and `setAppVisible` do nothing.
- **ArchiveRepository** gains `ArchiveRepository.get(context, service)`.
  - MESSAGES keeps today's singleton and `relay-archive.db`.
  - WHATSAPP uses its own `ArchiveDb(context, name = "whatsapp-archive.db")`.
  - Test hooks: `installForTesting(repo, service)` / `resetForTesting()`.
  - `ArchiveEvents` changes carry the service: add a `service: ChatService` field defaulting to MESSAGES, so screens of one service ignore the other's changes.

## WhatsApp runtime (RUNTIME agent; package `com.lightdecoder.wa`)

**Bridge and manager**
- `WaBridge` interface plus `GoWaBridge`, which wraps `com.lightdecoder.gm.wa.WABridge` (exact methods from the jar). Add a fake for tests: `FakeWaBridge`.
- `WaManager` (singleton `get(context)`, plus test install) implements ChatBackend:
  - `capabilities` = PRESENCE, DELETE_FOR_EVERYONE, NEW_GROUP only if supported.
  - It parses events through the existing Dto parsers and stores them in its own archive.
  - Receipts update message status.
  - Typing and presence go to StateFlows.
  - Pairing API for the UI:
    - `suspend fun requestPairingCode(phoneNumber: String): String`
    - `suspend fun startQr()`
    - `val qr: StateFlow<String?>`
    - `val pairing: StateFlow<WaPairing>`, a sealed type: Idle / WaitingForCode(code) / WaitingForQr(code) / Paired / Failed(code)
    - `fun cancelPairing()`
    - `suspend fun unpair(deleteArchive: Boolean)`
    - `fun startLive()` / `fun stopLive()`
    - `val unpairing: StateFlow<Boolean>`
  - The store lives in `filesDir/whatsapp`, which is excluded from backup already (everything is).
- **Sending:** an outbox that survives restarts, using the WA archive's outbox_queue table.
  - Sends go out only while connected.
  - The returned MessageDto, with `tmpId` set, replaces the local tmp row through the archive's existing tmp-replacement upsert.
  - Failures mark the row FAILED, with retry and discard.

**Foreground service and notifications**
- `WaService`: a foreground service (remoteMessaging on 34+, dataSync below) with its own channel "WhatsApp link" and a Disconnect action. Declare it in the manifest.
  - It connects only while the user wants it live and the notification can be seen, as RelayService does (see RelayNotifications.statusVisible).
  - Reconnect is handled by whatsmeow.
- Boot: BootReceiver also starts WaService when WhatsApp's own auto-start pref is on.
- **Message notifications** are generalised by service:
  - MessageNotifier takes a `ChatService`;
  - a channel per service ("Messages", "WhatsApp messages"), with notification tags/ids namespaced per service;
  - ReplyReceiver and the notification's tap intent carry `EXTRA_SERVICE`;
  - `MessageNotifier.visibleConversationId` becomes per service.
- **Intents:** `RelayIntents.thread(context, conversationId, messageId, service = MESSAGES)` and `conversations(context, service)`, with `EXTRA_SERVICE = "com.lightdecoder.extra.SERVICE"`. Add `waSetup(context)` and `waStatus(context)` intents pointing at the UI agent's activities: `com.lightdecoder.ui.wa.WaSetupActivity` and `WaStatusActivity`.

## UI (UI agent)

**Conversation list: one place for both services**
- A top TabLayout in ConversationsActivity with Messages and WhatsApp. The last tab used is remembered.
- Under each service, the folder tabs that service supports:
  - WhatsApp: Inbox and Archive;
  - Messages: as today.
- An unlinked service shows a card to link it (Messages → today's setup; WhatsApp → WaSetupActivity).
- The connection banner, refresh and new-conversation actions follow the selected service.
- Unread badges per service tab.

**Thread, new conversation and search**
- ThreadActivity and NewConversationActivity read `EXTRA_SERVICE` and use `ChatBackends.get`, with `backend.repo` for reads.
- For WhatsApp, the thread subtitle shows "online", "typing…" or "last seen <time>" in 1:1 chats. It calls `watchPresence` and `setAppVisible(true/false)` in onStart/onStop.
- Delete message offers "Delete for everyone" vs "Delete for me" with DELETE_FOR_EVERYONE.
- Full-size requests only with FULL_SIZE_REQUEST.
- Search covers the selected service.

**WhatsApp screens**
- **WaSetupActivity:**
  1. Disclosure: unofficial client; against WhatsApp's terms; the account may be banned; what is stored on this phone.
  2. Enter the phone number in international format.
  3. "Get pairing code" shows the 8-character code large and copyable, with steps: on your phone, WhatsApp > Settings > Linked devices > Link a device > "Link with phone number instead", then enter this code.
  4. A "Use a QR code instead" alternative renders the QR. Use com.google.zxing:core (Apache-2.0); bundleLicenses must include its licence, which means coordinating the build file with the runtime agent.
  5. It waits for pairing, then finishes into the WhatsApp tab.
  6. It requests POST_NOTIFICATIONS like the Messages setup does.
- **WaStatusActivity:**
  - state;
  - Connect/Disconnect;
  - "Stay connected" and "Start automatically";
  - Unpair (keep or delete the archive);
  - delete the local archive when unpaired;
  - the device name shown on the phone ("LightDecoder").
- Voice features (phase 1) must keep working for both services.

## Tests
- Robolectric tests for:
  - WaManager with FakeWaBridge: pairing flow states, message events into the archive, receipts, typing/presence flows, outbox send with tmp replacement, unpair;
  - the service tabs and switching;
  - a WhatsApp thread showing presence;
  - WaSetupActivity showing a pairing code.
- All existing tests must keep passing.
- Lint: 0 errors.
- Never use `-x :app:buildGoBridge`.

## Additions (binding)

**Media files per service**
- Each service keeps its downloaded media and its outbox copies in its **own directories**: `MediaCache` takes the service, and WhatsApp uses for example `filesDir/whatsapp-media` and `filesDir/whatsapp-outbox`.
- The archive deletes media files that no row references any more, and that check only sees its own database. Two services sharing one directory would delete each other's files.

**WhatsApp prefs and logs**
- WhatsApp has its own prefs (`WaPrefs`: live wanted, auto-start, notifications on) and its own activity log lines, clearly prefixed.
- A WhatsApp JID contains the phone number, so JIDs, message ids, push names and pairing codes never go into logs, exception messages that are logged, or notification tags that other apps can read. Hash them where an id is needed.

**Go bridge: where the truth is**
- The Go bridge is being finished by a separate agent. Its final report, if present, is at /tmp/claude-0/-home-user/f6a1dca6-81a7-528c-a4e8-937b46189ca8/scratchpad/wa-go-report.md.
- The truth for the Java API is `app/libs/gmbridge-sources.jar` (classes under `com/lightdecoder/gm/wa/`) and `gmbridge/wa/*.go`.
- If the jar has no `wa` classes yet, the Go agent is still building. Write everything against the Kotlin `WaBridge` interface first and wire `GoWaBridge` last.

**Manifest and stubs**
- The foundation step declares in the manifest: `com.lightdecoder.wa.WaService`, `com.lightdecoder.ui.wa.WaSetupActivity` and `com.lightdecoder.ui.wa.WaStatusActivity`.
- It also creates minimal compiling stubs for them and for `WaManager`, with the full public API signatures.
- Later steps fill them in without changing the signatures. If a signature must change, the agent that changes it fixes every caller it can see and reports the change.
