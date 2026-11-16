# Go WhatsApp bridge: final report of the Go agent

The bridge is built to the contract and binds into the same aar as gmbridge. Truth for signatures: `app/libs/gmbridge-sources.jar` and `gmbridge/wa/*.go`.

## Verification
- `gofmt` and `go vet` are clean.
- `go test -count=1 -race ./...` passes for `gmbridge` and `gmbridge/wa` (42 tests in wa).
- `buildGoBridge` produced the current aar and sources jar.
- The `// skipped` guard prints nothing.
- APK: `zipalign -c -P 16` passes; all LOAD segments are aligned to 0x4000.
- Nothing has run against real WhatsApp.

## API as bound in Java
- `com.lightdecoder.gm.wa.Wa`:
  - `static WABridge newWABridge(String dataDir, Callback cb) throws Exception`
  - `static final String Version = "1"`
- `com.lightdecoder.gm.wa.Callback`: `void onEvent(String kind, byte[] payload) throws Exception`
- `com.lightdecoder.gm.wa.WABridge`:
  - `cancelPairing()`, `close()`, `connect()`, `disconnect()`, `logout()`
  - `boolean isPaired()`, `String statusJSON()`
  - `String pairPhone(String phoneNumber)`, `startQR()`
  - `byte[] listConversations(String folder, long count)`, `byte[] listContacts()`
  - `byte[] fetchMessages(String conversationID, long count, byte[] cursorJSON)`
  - `byte[] sendText(String conversationID, String tmpID, String text, String replyToID)`
  - `byte[] sendMediaFile(String conversationID, String tmpID, String path, String fileName, String mimeType, String caption, String replyToID)`
  - `sendReaction(String conversationID, String messageID, String senderID, String emoji)`
  - `markRead(String conversationID, String messageID, String senderID)`
  - `setTyping(String conversationID, boolean typing)`, `subscribePresence(String participantID)`, `setOnline(boolean online)`
  - `deleteMessage(String conversationID, String messageID, boolean forEveryone)`
  - `setConversationArchived(String conversationID, boolean archived)`, `setConversationMuted(String conversationID, boolean muted)`
  - `byte[] downloadMedia(String mediaID, byte[] key)`
  - `byte[] getOrCreateConversation(byte[] numbersJSON, String groupName)`
- Every method except `isPaired`, `statusJSON`, `cancelPairing`, `close` and `disconnect` throws `Exception` with a `"<code>: <detail>"` message, like gmbridge.

## Event kinds and payloads
- **`state`** `{state, code, detail}`:
  - `connecting`: sent by Connect, again after `pair_success`, and for a dropped connection being retried (code `connection_lost`).
  - `connected`: after whatsmeow's Connected.
  - `disconnected`: after Disconnect, CancelPairing or a failed pairing.
  - `logged_out` (code `logged_out`): the device was unlinked from the phone; the store is wiped.
  - `error`: with `stream_replaced`, `temp_banned`, `client_outdated`, `connect_failure` or the code of a failed connect.
  - `unpaired`: after Logout, with code `""` or `unpair_remote_failed`.
- **`qr`** `{code}`: the first code lasts 60 s, then 20 s each.
- **`pair_success`** `{ownId}`.
- **`pair_error`** `{code, detail}` with `pair_failed`, `pairing_timeout` or `client_outdated`.
- **`message`**: a MessageDto with `transport` `"WHATSAPP"`.
  - Status names reuse Google Messages': `OUTGOING_COMPLETE`, `OUTGOING_DELIVERED`, `OUTGOING_DISPLAYED`, `OUTGOING_SENDING`, `OUTGOING_FAILED_GENERIC`, `INCOMING_COMPLETE`, `MESSAGE_DELETED`.
  - History messages have `isOld` true.
  - A revoke, or a delete-for-me from the phone, gives `direction: "deleted"` with empty text and parts.
  - Edits replace the text; reactions update `reactions`.
  - A `partId` is `"text"` or `"media"`.
- **`conversation`**: a ConversationDto with `type` `"WHATSAPP"`, `folder` `inbox` or `archive`, and participants that include me (`isMe`). A chat deleted on the phone is sent with `folder: "deleted"`.
- **`receipt`** `{conversationId, messageIds, status: delivered|read|played, timestampMs}`. The stored status only moves forward.
- **`typing`**: TypingDto, with `number` filled in.
- **`presence`** `{participantId, online, lastSeenMs}`.
- **`history_sync`** `{progress, done}`: only for the initial, recent and full syncs, not on-demand ones.

## Store and driver
- `github.com/mattn/go-sqlite3` v1.14.53 (cgo), which builds with the NDK for arm64, arm and amd64. It compiles SQLite 3.54.1.
- One file, `<dataDir>/whatsapp.db`, in WAL mode with foreign keys on. It holds whatsmeow's sqlstore plus the tables `lightdecoder_chat` (chat index) and `lightdecoder_message` (MessageDto JSON).
- `Logout` and a logout on the phone delete that file and start over unpaired.
- whatsmeow is pinned at `v0.0.0-20261116124925-2ecca2c913b9`. The device name is set to "LightDecoder".

## Size
libgojni.so, before → after:

| ABI | Before | After |
|---|---|---|
| arm64-v8a | 9,958,896 | 18,871,776 |
| armeabi-v7a | 9,831,316 | 18,675,252 |
| x86_64 | 10,633,904 | 19,827,656 |

The APK goes from 38.6 MB to 65.6 MB. Most of the growth is whatsmeow's generated protobuf code, plus about 1 MB for SQLite.

## Build and licences
- `gmbridge/go.mod`, `go.sum`: adds whatsmeow and go-sqlite3; shared x/ modules moved up as whatsmeow requires.
- `app/build.gradle.kts`:
  - `buildGoBridge` binds `.` and `./wa` into the one aar, with a new `goPackages` input.
  - `bundleLicenses` adds `21-sqlite.txt`.
  - whatsmeow (MPL-2.0), libsignal (GPL-3.0) and the other new modules are picked up by the existing per-module licence step.
- `NOTICE`: a new WhatsApp section listing every module now linked.

## Where the bridge differs from, or goes beyond, the contract
- **Pairing code:** `PairPhone` returns the 8 characters without the dash. The linking request calls the client "Chrome (Linux)", because WhatsApp's server only accepts browser names there. The phone should then list the device as "LightDecoder"; this is not confirmed on a real phone.
- **New error codes:** `already_paired`, `timeout`, `rate_limited`, `not_on_whatsapp`, `not_allowed`, `unsupported`, `not_ready`, `pair_failed`, `stream_replaced`, `temp_banned`, `client_outdated`, `connect_failure`, `connection_lost`. Codes shared with gmbridge keep its spelling. All details are scrubbed of ids and numbers.
- **`SetOnline`:** fails with `not_ready` until WhatsApp has synced the push name.
- **`ListConversations`:** `"spam"` returns `[]`.
- **`FetchMessages`:**
  - The cursor is null when the page is empty.
  - The on-demand history request is sent in the background, at most once a minute per chat.
  - It is anchored on the oldest stored message, so a chat with nothing stored cannot ask for history.
- **Media:**
  - `mediaId` is `"wa1."` + base64url JSON. `downloadMedia` uses `key` only when the token has no key, and `PartDto.key` is left empty.
  - `downloadMedia` needs a live connection.
  - A vCard comes as a media part with only `inlineData`.
  - GIF and unusual video types are sent as documents.
- **Unknown message types** are skipped, not sent as placeholders.
- **Group receipts:** the first member's receipt advances the status, because whatsmeow gives no member count.
- **Delete-for-me:** the patch is built by hand, because whatsmeow has no helper for it.
- **Testing:** no fake websocket was practical, so network calls go through replaceable function variables, as in gmbridge.
