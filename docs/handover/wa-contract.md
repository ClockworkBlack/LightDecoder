# WhatsApp link: contract between the Go bridge and Kotlin

Repo: /home/user/LightDecoder (branch ccr-b4eccc90-m97k4j).

The existing Google Messages bridge (`gmbridge/`, package `gmbridge`, Java `com.lightdecoder.gm.gmbridge.*`) is the model to follow:
- the same gomobile rules;
- the same "<code>: <detail>" errors;
- a Callback with `OnEvent(kind, payload []byte) error`;
- DTO JSON in the **same shapes** Kotlin already parses, in `app/src/main/java/com/lightdecoder/relay/Dto.kt`: ConversationDto, ParticipantDto, MessageDto, PartDto, ReactionDto, TypingDto and the state event. Kotlin then reuses its parsing and its SQLite archive unchanged.

## Product rules (same as the Google Messages link)
1. **Linking is never automated.** The user requests a pairing code for their own number and types it into WhatsApp on their phone themselves (Linked devices > Link with phone number). A QR code is offered as well.
2. **Visible and revocable.** A visible foreground notification with Disconnect is shown while connected (Kotlin side). Unpair (log out) is always available.
3. **Local only.**
4. **Logs never contain** message text, names, numbers, JIDs, keys or codes.

## Go side: a new package `gmbridge/wa`
It is bound into the **same** aar as gmbridge, because two Go runtimes in one app clash: `gomobile bind ... ./ ./wa`. Java: `com.lightdecoder.gm.wa.Wa`, with the type `WABridge`.
- **Library:** go.mau.fi/whatsmeow, latest, pinned in go.mod.
- **Store:** whatsmeow's sqlstore on a SQLite file in a directory Kotlin passes in (app-private).
  - Prefer `github.com/mattn/go-sqlite3` (cgo builds with the NDK under gomobile) if it builds for android/arm64, arm and amd64.
  - Otherwise use a pure-Go driver.
  - Whatever is chosen, it must build in Gradle's buildGoBridge task.
- **Device name** shown in WhatsApp's Linked devices: "LightDecoder".

### API (gomobile-friendly)
```
NewWABridge(dataDir string, cb Callback) (*WABridge, error)
IsPaired() bool
StatusJSON() string            // {"paired":bool,"connected":bool,"ownId":"<jid user>","pushName":""}
Connect() error                // also the first step of pairing
Disconnect()
PairPhone(phoneNumber string) (string, error)   // returns the 8-char code to type on the phone; needs Connect first
StartQR() error                // emits "qr" events {"code": "..."} until paired or cancelled
CancelPairing()
Logout() error                 // unpair: tells WhatsApp (best effort), then wipes the local store
                               // "unpair_remote_failed: ..." when only the local wipe happened
Close()

ListConversations(folder string, count int64) ([]byte, error)   // "inbox" | "archive"; from Go's own chat index
ListContacts() ([]byte, error)                                  // ContactDto array, same shape as gmbridge's
FetchMessages(conversationID string, count int64, cursorJSON []byte) ([]byte, error)
    // messages Go has stored for the chat (from history sync and live messages), as a page like gmbridge's,
    // oldest first; when it runs out, it asks the phone for older history (on-demand history sync)
    // and the messages come later as "message" events.
SendText(conversationID, tmpID, text, replyToID string) ([]byte, error)   // returns the sent MessageDto (real id, timestamp, tmpId set)
SendMediaFile(conversationID, tmpID, path, fileName, mime, caption, replyToID string) ([]byte, error)   // likewise; images, video, audio, documents; 100 MB cap
SendReaction(conversationID, messageID, senderID, emoji string) error     // "" removes
MarkRead(conversationID, messageID, senderID string) error
SetTyping(conversationID string, typing bool) error
SubscribePresence(participantID string) error
SetOnline(online bool) error   // our own presence (available while the app is in front)
DeleteMessage(conversationID, messageID string, forEveryone bool) error
SetConversationArchived(conversationID string, archived bool) error
SetConversationMuted(conversationID string, muted bool) error
DownloadMedia(mediaID string, key []byte) ([]byte, error)   // mediaID = Go's opaque token in PartDto.mediaId
GetOrCreateConversation(numbersJSON []byte, groupName string) ([]byte, error)   // 1:1 by number (checks IsOnWhatsApp); groups: may return unsupported
```

### Ids
- **Conversation ids:** the chat JID string, for example `12345@s.whatsapp.net` or `...@g.us`.
- **Participant ids:** the user JID (non-AD).
- **Message ids:** the WhatsApp message id.
- **PartDto.mediaId:** an opaque token (for example base64 of a JSON with directPath, mediaKey, fileEncSHA256, fileSHA256, length and type) that DownloadMedia understands.
- **Thumbnails:** the jpegThumbnail goes in PartDto.inlineData. It is a preview, exactly like Google's mediaData, which Kotlin already treats as a stand-in.

### Events (`kind`, payload JSON)
- **`state`**, the same shape as gmbridge's: `{"state":"connecting|connected|disconnected|logged_out|error|unpaired","code":"","detail":""}`.
- **`qr`** `{"code":"..."}`, **`pair_success`** `{"ownId":"..."}`, **`pair_error`** `{"code":"...","detail":""}`.
- **`message`:** a MessageDto. Live messages, our own messages sent from other devices, and history sync messages (`isOld` true).
  - Edits and revokes update or delete the message: `direction: "deleted"` for a revoke, as in gmbridge.
  - Reactions update `reactions`.
- **`conversation`:** a ConversationDto (name, participants, isGroup, lastMessageTs, unread, folder, muted, pinned).
- **`receipt`** `{"conversationId":"","messageIds":["..."],"status":"delivered|read|played","timestampMs":0}`.
- **`typing`:** a TypingDto `{"conversationId":"","participantId":"","typing":true}`.
- **`presence`** `{"participantId":"","online":true,"lastSeenMs":0}`.
- **`history_sync`** `{"progress":0..100,"done":bool}`.
- **`session`:** not used. The store lives in dataDir.

### Behaviour
- The event handler never blocks: queue as gmbridge does.
- Panics never cross the gomobile boundary.
- Reconnect is automatic (whatsmeow), while Connect() is wanted.
- A logout on the phone (`events.LoggedOut`) emits state `logged_out` and wipes the store.
- Tests:
  - use a fake transport where practical;
  - at minimum, unit tests of the DTO conversion from whatsmeow events and protobufs, token round trips, and the error mapping;
  - `go test -race` passes.
