# Google Messages link: architecture and maintenance

This is the developer guide to the Google Messages link added in 0.3.0: how it
is put together, where its state lives, the rules that keep it correct, how to
test it, and how to move it to a new libgm. The product reasoning is in
`docs/rcs-integration.md`; the user-facing summary is in the README.

The code is the source of truth. When this guide and the code disagree, the
code wins and this guide should be fixed. The most important files:

| Topic | File |
|---|---|
| Go API, lifecycle, locks | `gmbridge/bridge.go` |
| Pairing | `gmbridge/pairing.go` |
| Events and states | `gmbridge/events.go` |
| Reads and writes | `gmbridge/rpc.go`, `gmbridge/media.go` |
| JSON shapes | `gmbridge/dto.go` (Go), `app/.../relay/Dto.kt` (Kotlin) |
| Error codes | `gmbridge/errors.go`, `RelayException` in `app/.../relay/RelayBridge.kt` |
| Session envelope | `gmbridge/session.go` |
| Kotlin seam | `app/.../relay/RelayBridge.kt`, `app/.../relay/GoRelayBridge.kt` |
| Runtime | `app/.../relay/RelayManager.kt`, `Outbox.kt`, `Backfill.kt`, `RelayService.kt` |
| Archive schema | `app/.../archive/ArchiveDb.kt`, `ArchiveRepository.kt` |

(`app/...` is `app/src/main/java/com/lightdecoder`.)

## Components

```
  Google Messages on the user's phone
        ^
        |  relayed by Google's Messages for web servers
        |  (HTTPS long poll + RPCs; Go makes its own TLS connections)
+-------+---------------------------------------------------------------+
| gmbridge (Go, package gmbridge, libgm v0.2610.0)                      |
|   GMBridge --- long-lived libgm.Client (after pairing)                |
|            `-- pairingAttempt: temporary libgm.Client (during pairing)|
|   libgm event handler -> unbounded FIFO -> one dispatcher goroutine   |
+-------+-----------------------------------------------^---------------+
        | gomobile: libgojni.so + com.lightdecoder.gm.gmbridge.*        |
        | Callback.onSession / onEvent (dispatcher goroutine)           | method calls
        v                                                               |
+-----------------------------------------------------------------------+
| GoRelayBridge : RelayBridge (JSON <-> Dto, errors -> RelayException)  |
+-------+-----------------------------------------------^---------------+
        | RelayBridge.Sink                              | (IO threads only)
        v                                               |
+-----------------------------------------------------------------------+
| RelayManager (process singleton)                                      |
|   relay-events thread: every callback, in order                       |
|   relay-intake thread: new sends, retry, discard                      |
|   IO scope: connect, sync, Outbox.drain, Backfill, media (2 at a time)|
|   StateFlows: state, typing, backfill, settings                       |
+---+----------------+----------------+----------------+----------------+
    |                |                |                |
    v                v                v                v
ArchiveRepository  SessionVault     RelayService     StickerCapture
 SQLite + FTS4,     Keystore         foreground,      -> InboxStore
 MediaCache         AES-256-GCM      notification     (sticker inbox)
    |
    v ArchiveEvents (posted after each commit)
UI: ui/relay (RelaySetupActivity, RelayStatusActivity), ui/chat
    (ConversationsActivity, ThreadActivity, NewConversationActivity),
    the link card on InboxActivity. Screens call RelayManager and read
    ArchiveRepository; they never touch the bridge.
```

Other pieces: `LightDecoderApp` (creates the manager and notification
channels), `BootReceiver` (reconnect after boot or app update),
`MessageNotifier` + `ReplyReceiver` (optional message notifications with
Reply), `RelayNotifications` (channels `relay_status`, `relay_alerts`,
`relay_messages`), `RelayPrefs`, `RelayLog`, `CookieParser`, `RelayIntents`.

## The Go bridge

### Shape of the API

The surface is gomobile-friendly on purpose:

- Parameters and results are only `string`, `[]byte`, `bool`, `int64`, the
  `Callback` interface and `*GMBridge`, with at most one non-error result.
  Structured data crosses as UTF-8 JSON in `[]byte`.
- gomobile generates Java package `com.lightdecoder.gm.gmbridge`
  (`-javapkg com.lightdecoder.gm`). Do not change it to `com.lightdecoder`: the
  aar ships the keep rule `-keep class <javapkg>.** { *; }`, which would turn off
  R8 shrinking for the whole app.
- Kotlin creates the bridge with the static factory
  `Gmbridge.newGMBridge(session, callback)`. gomobile also generates a public
  constructor that throws without declaring it; don't use it.
- Errors cross as `java.lang.Exception` whose message is `"<code>: <detail>"`
  (or just `"<code>"`). `RelayException.fromThrowable` parses the code. Anything
  without a known code becomes `internal`, keeping only the exception's class
  name, because its text was never checked for content.
- Both `Callback` methods return `error` in Go so that gomobile clears any Java
  exception they raise. `GoRelayBridge` still catches every `Throwable` in them.
- A nil or empty `[]byte` arrives in Java as `null`.
- Every exported method recovers panics (returning `internal`, or a safe
  default where there is no error result), since a Go panic aborts the whole
  Android process. Panics in libgm's own background goroutines cannot be
  caught this way.
- `Gmbridge.Version` is `"1"`.

### Methods

Grouped; the doc comments in `gmbridge/*.go` are authoritative.

| Group | Methods | Notes |
|---|---|---|
| Status | `IsPaired`, `IsConnected`, `StatusJSON` → StatusDTO | Never throw. `IsConnected` is false during reconnect gaps. |
| Pairing | `StartPairing(cookiesJSON)` → PairingChallengeDTO, `FinishPairing(timeoutMs)` → phone id, `CancelPairing()` | See "Pairing". |
| Lifecycle | `Connect`, `Disconnect`, `SetActiveSession`, `Unpair`, `ExportSession`, `Close` | `Connect` returns when the long poll is starting; "connected" follows as an event. `Unpair` succeeds even when unpaired (it is also "wipe"). |
| Reads | `ListConversations(folder, count)`, `GetConversation(id)`, `FetchMessages(id, count, cursorJSON)` → MessagePageDTO, `ListContacts`, `ParticipantThumbnails(idsJSON)` | Need a live connection; fail fast with `not_paired` / `not_connected`, otherwise up to 90 s. |
| Media | `DownloadMedia(mediaId, key)`, `RequestFullSizeImage(messageId, partId)` | Download needs a pairing but not a connection; while disconnected the stored token must still be valid for at least a minute, otherwise `not_connected`; 32-byte key; capped at 64 MiB (`too_large`). The full-size image arrives later as an updated `message` event. |
| Writes | `SendText(convId, tmpId, text, replyToId)`, `SendMedia(convId, tmpId, data, fileName, mime, caption, replyToId)` → SendResultDTO; `SendReaction(messageId, emoji, add\|remove\|switch)`, `MarkRead(convId, messageId)`, `SetTyping(convId)`, `DeleteMessage(id)`, `SetConversationStatus(convId, archived\|active)`, `SetConversationMuted(convId, muted)`, `DeleteConversation(convId)`, `GetOrCreateConversation(numbersJSON, rcsGroupName)` → ConversationDTO | Go never retries a send. Attachments up to 100 MiB. Replies to SMS/MMS go without reply metadata, as in the connector. |

### Error codes

`not_paired`, `not_connected`, `no_cookies`, `cookies_invalid`, `no_devices`,
`pairing_init_timeout`, `incorrect_emoji`, `pairing_cancelled`,
`pairing_timeout`, `pairing_in_progress`, `no_pairing`,
`phone_not_responding`, `connection_closed`, `not_found`, `invalid_argument`,
`too_large`, `send_failed`, `logged_out`, `internal`.

Details are written by the bridge or are sanitised libgm errors: network errors
are reduced to "network error" / "network timeout" because libgm's request URLs
carry an API-key parameter. A detail never holds message content, cookies or
keys. Google's "caller has no permission" and HTTP 401/403 map to
`cookies_invalid`; HTTP 404 maps to `not_found`.

The outbox also records a few reasons of its own that are not bridge codes:
`attachment_unreadable`, `attachment_missing`, `unpaired` (held after an
Unpair), `interrupted` (a send cut off mid-call whose echo never showed up on
the phone; never re-sent by itself), `unconfirmed` (sent, but no echo within an
hour), and the phone's send statuses (`FAILURE_2`, …).

### Events

`Callback.OnEvent(kind, json)`:

| Kind | Payload | Raised for |
|---|---|---|
| `state` | `{"state","code","detail"}` | lifecycle changes, below |
| `message` | MessageDTO | every libgm `WrappedMessage`, live and `isOld`: new messages, status, reaction and media updates, deletions |
| `conversation` | ConversationDTO | every `gmproto.Conversation` |
| `typing` | TypingDTO | `gmproto.TypingData` |
| `settings` | SettingsDTO | `gmproto.Settings` that carry SIM cards (Go also caches the SIMs for sending) |
| `resync` | `{}` | the phone finished rebuilding its database; BROWSER_ACTIVE after this session was inactive, after a data gap (`NoDataReceived`) or after a retried SetActiveSession; the first data after a gap. Kotlin re-lists the inbox and catches up conversations whose latest message it lacks |
| `account` | `{"account","enabled"}` | a real `events.AccountChange` (logged as a kind only) |

`Callback.OnSession(bytes)` asks Kotlin to persist the session; `null` means
delete it.

State values: `connecting` (Connect called), `reconnecting`
(`ListenTemporaryError`), `logged_out`, `cookies_invalid`, `error`,
`disconnected` (Disconnect), `unpaired` (Unpair; code `unpair_remote_failed`
when the phone could not be told, so it may still list the device). On
`ListenRecovered`, `PhoneNotResponding`, `PhoneRespondingAgain` and the
BROWSER_ACTIVE / BROWSER_INACTIVE* alerts Go emits the state that holds now, in
this order of priority: `phone_offline` (code `phone_not_responding`) while the
phone is unresponsive, `active_elsewhere` while another browser is active,
`active` after BROWSER_ACTIVE, otherwise `connected`. Kotlin adds one reason of
its own: `disconnected` with code `notifications_off` when the link's
notification is hidden.

### DTOs

The shapes are the `*DTO` structs in `gmbridge/dto.go`, mirrored by the data
classes in `relay/Dto.kt` (`fromJson` is lenient: missing keys and JSON null
give defaults). Conventions:

- Keys are camelCase; timestamps are epoch milliseconds; keys, thumbnail keys
  and inline data are base64; empty lists are `[]`, never `null`.
- Unknown enum values are kept, not dropped: `"UNKNOWN"`, folder `"other"`, and
  the numeric `statusCode`.
- A message page is sorted oldest to newest.
- `direction` comes from the status code: 1–99 `out`, 100–199 `in`, 200–299
  `system`, 300 `deleted`.
- Folders: missing status and ACTIVE → `inbox`; ARCHIVED/KEEP_ARCHIVED →
  `archive`; SPAM/BLOCKED → `spam`; DELETED/TRASH → `deleted`.
- A media part with only a thumbnail has `needsFullSize: true`. A media part
  without its own id gets `idx:N`; those cannot be asked for in full size.
- `muted` is always false: libgm's Conversation has no mute field, so mute is
  local state in the archive plus the Go call.

`dto_test.go` checks the Go JSON shape and `DtoTest` the Kotlin parsing.
Nothing compares one with the other automatically, so change both together.

### Concurrency inside Go

- **Dispatcher.** libgm calls its event handler synchronously from its long-poll
  and pinger goroutines and requires it not to block or make requests. The
  handler therefore only appends a job to an unbounded FIFO. One dispatcher
  goroutine runs the jobs in order, so Kotlin sees events in libgm's order and
  never concurrently. A panic in one job is contained to that job.
- **Two locks.** `lifecycle` serialises Connect, Disconnect, Unpair, Close,
  pairing adoption and fatal-error handling, so a client is never swapped
  while one of these is half done. `mu` guards the bridge's fields and is never
  held across a network call. Order: `lifecycle` → `mu` → `AuthData.CookiesLock`.
- **Stale events.** Each client has a generation number and each Connect a
  connection number, both stamped when libgm raises the event. Events from a
  replaced client, or connection events from an older connection, are ignored,
  so a late error cannot tear down a newer connection.
- **Fatal verdicts** (logged out, cookies invalid, other fatal errors) are
  handled on their own goroutine, because they need the lifecycle lock and the
  dispatcher must never wait on it.
- **Saving the session** always goes through `marshalSession`, under
  `CookiesLock.RLock`: libgm rewrites the cookie map on every HTTP response, and
  encoding it concurrently is a fatal, unrecoverable map access.
- **Connector workarounds ported:** messages deleted while the phone rebuilds
  its database (between MOBILE_DATABASE_SYNC_STARTED and _COMPLETE) are dropped;
  after `HackySetActiveMayFail`, if BROWSER_ACTIVE has not arrived 7 s later,
  `SetActiveSession` is sent once more.
- **Logging.** libgm gets `zerolog.Nop()`, and `init()` disables zerolog's
  global level as well, because at trace, debug and warn levels libgm logs
  decrypted payloads. The package itself logs nothing.

## The Kotlin side

### The seam

`RelayBridge` is the Go API in Kotlin types, plus `RelayBridge.Sink`
(`onSession`, `onEvent`). `GoRelayBridge` is the only class that imports the
generated `com.lightdecoder.gm` code. Constructing it loads `libgojni.so`.
`RelayBridges.factory` is null in the app, so `RelayManager` builds a
`GoRelayBridge`; tests set it to `FakeRelayBridge`.

### RelayManager

One per process (`RelayManager.get`), created by `LightDecoderApp`. If a
session file exists it loads the bridge in the background at startup;
otherwise nothing is loaded until pairing starts. It owns the bridge, the
outbox, the history import, the `state` / `typing` / `backfill` / `settings`
flows, the decision to connect, sticker checks and media downloads.

Threads:

- Go's dispatcher calls the sink, which copies the data and hands it to the
  single `relay-events` thread. All event handling (archive writes, state,
  session saves) happens there, in Go's order, so Go never waits on the
  database.
- New sends, retries and discards go through the single `relay-intake` thread,
  so they keep the order the user made them in.
- Everything that talks to the phone runs on `Dispatchers.IO`; media downloads
  are limited to two at a time.

The public API is the one the UI uses. These throw `RelayException`:
`startPairing`, `finishPairing`, `useHere`, `refreshConversations`,
`loadOlder`, `requestFullSize`, `react`, `deleteMessage`, `setArchived`,
`setMuted`, `deleteConversation`, `startConversation`. These do not:
`markRead`, `unpair`, `mediaFile` (returns null), `listContacts` (falls back to
stored contacts, throws only if there are none).

### When the link connects

The link connects only when all three hold: a pairing exists,
`RelayPrefs.liveEnabled` ("stay connected") is on, and `RelayService` is
running. The service is what shows the notification with Disconnect, so a
reconnect timer or network callback that fires after the service has gone
does nothing.

- `startLive()` turns `liveEnabled` on and starts the service; the service goes
  foreground and calls back into the manager, which connects.
- `stopLive()`, and the notification's Disconnect, turn it off, stop the
  service and disconnect, keeping the pairing.
- Foreground service type: `remoteMessaging` on Android 14+, `dataSync` on
  10–13, none on 8–9. `onTimeout` (the dataSync time limit) calls `stopLive()`.
- Android 12+ refuses foreground-service starts from the background. Pairing
  usually finishes while the user is in Google Messages tapping the emoji, so
  that start can be refused. `LightDecoderApp` therefore starts the service
  whenever one of the app's screens starts, if the link should be running and
  is not.
- Stopping the service between `startForegroundService` and its
  `startForeground` crashes the app. `serviceStartsPending` makes
  `RelayService.stop` leave a pending start alone; that start goes foreground
  and then stops itself.
- `BootReceiver` (boot completed, app updated) starts the service only if
  `autoStart`, `liveEnabled` and a pairing are all present.
- While the service runs, a network callback makes a link in ERROR retry at
  once instead of waiting out its backoff.

## State machine

`RelayState.Phase`, as `RelayManager.state` reports it. `isOnline` is true for
CONNECTED and ACTIVE only.

| Phase | Comes from | What the manager does |
|---|---|---|
| UNPAIRED | no session, or `unpaired` | — |
| PAIRING | Kotlin only: between `startPairing` and the end of `finishPairing` | back to UNPAIRED/DISCONNECTED on failure or cancel |
| DISCONNECTED | paired but not connected; `disconnected`; the initial phase when a session file exists | clears typing |
| CONNECTING | `connecting` | counts a new connection; resets the send gate |
| CONNECTED, ACTIVE | `connected`, `active` | once per connection: re-list the inbox (100), record account and phone, start the history import if it never finished; open the send gate on the connection's first `settings` event or after 15 s; run the outbox |
| ACTIVE_ELSEWHERE | `active_elsewhere` | UI offers "Use here" (`SetActiveSession`) |
| RECONNECTING | `reconnecting` | waits; libgm retries by itself |
| PHONE_OFFLINE | `phone_offline` | waits; libgm's pinger backs off and recovers |
| LOGGED_OUT | `GaiaLoggedOut`, `RevokePairData`, or "requested entity not found" from a fatal error or failed ping. Go disconnects, clears the pairing (keeping cookies and keys) and saves the session. | pairing gone; `liveEnabled` off; service stopped; "pair again" notification; archive kept; queued sends stay queued |
| COOKIES_INVALID | fatal error with invalid credentials or HTTP 401/403, or Connect finding the cookies signed out (empty account email). Go disconnects and keeps the session. | same stop and notification; the pairing still exists but cannot sign in, so setup runs a fresh sign-in |
| ERROR | any other fatal long-poll error, or a failed Connect | reconnect after 15 s, 30 s, 1 min, 2 min, 5 min, then every 15 min; never after `cookies_invalid`, `logged_out` or `not_paired` |

Disconnect moves any connected phase to DISCONNECTED; Unpair moves any phase to
UNPAIRED.

## Pairing

1. `RelaySetupActivity` gets cookies from its sign-in WebView (or the paste
   dialog, through `CookieParser`) and calls `RelayManager.startPairing`. The
   WebView loads `https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config`,
   only allows Google's sign-in hosts, polls `CookieManager` once a second
   until `SID`, `HSID`, `SSID`, `APISID`, `SAPISID` and `OSID` are present, then
   wipes its cookies and storage. The required set is defined twice:
   `requiredCookies` in `pairing.go` and `CookieParser.REQUIRED`.
2. Go `StartPairing` checks the required cookies (`no_cookies`), creates fresh
   `AuthData` and a temporary client, runs `FetchConfig` (30 s; an empty account
   email gives `cookies_invalid`), then `StartGaiaPairing` under a 60 s watchdog
   (`pairing_init_timeout`; libgm's call can otherwise block forever). It
   returns `{emoji, emojiUrl, account}`. The UI renders the emoji as text; the
   URL is not loaded.
3. `RelayManager.finishPairing` calls `FinishPairing(180000)`, which waits for
   the phone's answer: success, `incorrect_emoji`, `pairing_timeout`,
   `pairing_cancelled`, or another error.
4. On success Go disconnects the temporary client, adopts its `AuthData`, sends
   `OnSession`, builds the long-lived client (not connected) and returns the
   phone id. The manager saves the session, records account and phone, and
   calls `startLive()`.
5. Cancel (the Cancel button, or leaving the setup screen for good) calls
   `CancelPairing`, and a waiting `FinishPairing` returns `pairing_cancelled`.
   If the phone confirmed at that same moment, Go revokes the new pairing
   (`UnpairGaia`) rather than leave it orphaned. If `Close` ran while the phone
   was confirming, Go does the same.
6. A new `StartPairing` replaces an attempt whose emoji is showing; while one is
   starting or finishing it returns `pairing_in_progress`. An attempt whose
   emoji was shown but never finished or cancelled expires after 6 minutes
   (libgm's pairing request lives 300 s).

Nothing in this sequence answers for the user. Keep it that way: any change
that would let code respond to the phone's prompt, choose the emoji, or obtain
cookies from anywhere but the user breaks the project's hard rule (see
`docs/rcs-integration.md`).

## Persistence

### Session

Go hands Kotlin an opaque envelope:

```
{"v": 1, "auth": {…libgm AuthData…}, "phoneId": "…", "account": "…"}
```

`auth` is libgm's own JSON: cookies (Google rotates them on responses, which is
why the session is saved periodically), `request_crypto` (AES and HMAC keys),
`refresh_key` (the P-256 key that signs token refreshes), the tachyon token and
expiry, the browser and phone devices, and the pairing id. A bare `AuthData`
JSON is also accepted. A session without `request_crypto` or `refresh_key`, or
that does not parse, makes `newGMBridge` throw `invalid_argument`; the manager
then deletes it and starts unpaired.

`OnSession` fires when a pairing is adopted, on every `AuthTokenRefreshed`,
every 30 minutes while connected, on Disconnect and Close, and whenever a
fatal error (`logged_out`, `cookies_invalid`, `error`) ends the connection.
Unpair sends `null`.

`SessionVault` stores it at `files/relay/session.bin`:

```
[0x01 version][12-byte IV][AES-256-GCM ciphertext + 16-byte tag]
```

The key is the Android Keystore key `lightdecoder_relay_session` (AES-256,
GCM, no padding). It is not bound to user authentication, so the service can
run while the phone is locked. Writes go to a temp file, are synced, then
renamed. `load()` deletes the file only when it is certainly unusable (bad
header, too short, failed tag check, permanently invalidated key); any other
error, such as a Keystore hiccup, returns null and keeps the file. Tests swap
`SessionVault.cipherProvider` for a software key.

### Archive

`databases/relay-archive.db`, `ArchiveDb.VERSION` 1, WAL, foreign keys on. It
is not encrypted by the app. Tables:

| Table | Holds |
|---|---|
| `conversations` | one row per conversation: name, folder, flags, last message summary, `default_outgoing_id`, raw JSON |
| `participants` | per conversation, cascade-deleted with it |
| `contacts` | the phone's contacts, for the new-conversation picker |
| `messages` | one row per message; outgoing local copies have id `tmp:<tmpId>` and `local_state` pending/sending/failed |
| `media` | one row per media part: ids, decryption keys, sizes, inline data, local file paths, `full_size_requested`, `sticker_checked` |
| `messages_fts` | FTS4 (`unicode61`) over subject and text, docid = the message's rowid |
| `outbox_queue` | sends waiting or in flight |
| `connection_health` | one row: last state, last event, last session save, last error, account, phone id |
| `sync_state` | per conversation: history cursor, `history_complete`, last import time |

Rules the repository follows (keep them when changing it):

- `ArchiveRepository` is the only writer. Each write is one transaction and
  posts its `ArchiveEvents` change after the commit; callers must not post
  again.
- Never `INSERT OR REPLACE` a parent row: a replace is a delete, and the
  cascade would delete the conversation's messages. Conversations are written
  as update-then-insert.
- `upsertMessage`: (1) an echo of a tmpId replaces the `tmp:` copy, retiring its
  outbox entry and attachment copy unless the entry is SENDING; (2) an existing
  message is updated in place, and media parts keep their downloaded files and
  flags while their media id is unchanged; (3) a deleted message is removed with
  its files; (4) an unknown conversation gets a placeholder row; (5) the
  conversation summary moves forward only for a strictly newer message.
- Conversation upserts take the phone's data, except the local mute flag and
  the stored snippet when the phone sends no timestamp. A conversation the
  phone reports as deleted or trashed is deleted locally, with its messages,
  files, sync state and queued sends.
- Search: `"`, `*`, `^` and control characters are removed from the query,
  words without a letter or digit are dropped, at most 32 words are kept, each
  is quoted, and the last one gets a prefix `*`. The index uses rowids, so do
  not `VACUUM` the database without rebuilding `messages_fts`.
- There are no migrations yet. A schema change needs `VERSION` bumped and a
  migration in `onUpgrade`.

### Files and preferences

| Path | Contents |
|---|---|
| `files/relay/session.bin` | encrypted session |
| `files/relay-media/<sha256(mediaId), 32 hex>.<ext>` | downloaded media and thumbnails (hashed names keep phone ids out of paths) |
| `files/relay-outbox/<tmpId>` | copies of attachments waiting to be sent |
| `files/relay-log.txt` | activity log, last 120 lines, kinds and outcomes only |
| shared prefs `relay` | `live_enabled`, `auto_start` (default on), `backfill_depth` (default RECENT), `notify_messages` (default off), `catch_stickers` (default on), `backfill_done_at` |

All of it is excluded from backup and device transfer by
`app/src/main/res/xml/data_extraction_rules.xml` (Android 12+) and
`allowBackup="false"` / `fullBackupContent="false"` (older).

Unpair revokes the pairing on the phone (best effort, 15 s), deletes the
session, turns `liveEnabled` off and resets `backfill_done_at`. With "also
delete the archive" it wipes every table and both media directories; without
it, it marks the health row unpaired and holds every queued send as failed.
The sticker inbox is never touched.

## Outbox

- The idempotency key is a UUID tmpId chosen in Kotlin before anything goes to
  the phone. The intake thread writes the local copy `tmp:<tmpId>` and the
  `outbox_queue` row first; Go then uses the tmpId as the message's
  `TmpID`/`TmpID2`/`MessagePayload.TmpID`, and the phone echoes it on the real
  message, which replaces the local copy.
- Attachments are copied into `relay-outbox/` at enqueue time (at most 100 MB),
  so retries survive restarts and expired URI grants. At send time the whole
  file is read into memory, because the Go API takes `[]byte`.
- One coroutine drains the queue, one message at a time, in queue order, and
  only while `canSend()`: the link is online, this connection's send gate is
  open (its first `settings` event, or 15 s after going online, because Go's
  SIM cache is filled per run and a send without it goes from the phone's
  default SIM), and the bridge has not said `not_connected` on this connection.
- Statuses: PENDING → SENDING → SENT or FAILED. A SENT row is deleted once the
  echo arrives, or at once if the echo came first (the phone often echoes
  before the send call returns). The local copy shows "sending" until the echo.

| Outcome of a send | What happens |
|---|---|
| ok | SENT |
| `FAILURE_2`, `FAILURE_3`, `send_failed`, `internal` | retried after 3 s, 8 s, 20 s with the same tmpId, then FAILED |
| `not_connected`, `connection_closed`, `not_paired`, `logged_out`, `cookies_invalid` | back to PENDING; the queue pauses until the next online state |
| `phone_not_responding` and anything else | FAILED, not retried automatically (Google may still deliver it; its echo then clears the failure) |

- Before every retry, and when picking up an entry after a restart, the outbox
  checks whether `tmp:<key>` still exists. If not, the echo already arrived:
  the entry is dropped instead of being sent twice. `pendingOutbox()` returns
  PENDING and interrupted SENDING rows, oldest first.
- Unpair (keeping the archive) holds every queued entry as FAILED with reason
  `unpaired`: conversation ids only mean something on the phone they came
  from, and the next pairing may be a different phone.
- The user can retry or discard a FAILED send; discarding removes the local
  copy and the attachment copy.

## History import

- Depths (`BackfillDepth` in `RelayPrefs.kt`): OFF, RECENT (25 conversations ×
  50 messages, the default) and DEEP (200 × 1000). The import runs once per
  pairing on the first online connection (while `backfill_done_at` is 0), or
  when the user taps "Import history now"; starting one cancels the one
  running, and Disconnect cancels it. It waits up to 60 s for the link to come
  online.
- It covers the inbox only. For each conversation it fetches pages of up to 50,
  starting from the `sync_state` cursor (or the newest page if there is none),
  until the archive holds the target number of the phone's messages or the
  phone returns no cursor (`history_complete`). It pauses 400 ms between pages
  and 800 ms between conversations, because every page is answered by the
  user's phone. An interrupted import resumes from `sync_state`.
- Imported pages are written with `upsertMessages` in one transaction each,
  with no notifications and no sticker checks.
- `loadOlder` (scrolling to the top of a thread) fetches one page before the
  older of the sync cursor and the oldest stored message, the same anchor
  approach the mautrix bridge uses.
- Go's `FetchMessages` drops messages newer than the cursor and the cursor
  message itself, keeps other messages at the cursor's exact millisecond (the
  archive upserts by id, so a duplicate is harmless and a dropped one would be
  lost), and always returns a cursor strictly older than the one it was given,
  or null. A loop that pages until the cursor is null therefore ends.

## Sticker capture path

1. A `message` event is stored with `upsertMessage`, whose result lists
   `newMediaParts`: parts whose media id or thumbnail id is new or changed.
2. If "catch stickers" is on, a part is a candidate when its type is HEIC/HEIF
   (or the `-sequence` variants), empty, or `application/octet-stream`, or any
   `image/*` when the inbox's "keep every picture" option is on; it is at most
   32 MB; and it has not been checked yet.
3. On IO (two at a time) the bytes come from the part's inline data, or from
   `DownloadMedia` (the file is also kept as the part's local copy, so the
   thread screen does not download it again). A thumbnail-only part instead
   gets one `RequestFullSizeImage` (only when online, and not for `idx:` parts);
   the phone later sends an updated message with the full-size id, which comes
   back through step 1.
4. `StickerCapture.capture` runs with source `relay` and id
   `InboxStore.idFor("relay", conversationId, messageId, 0, partId)`. It sniffs
   the bytes, applies the inbox's HEIC-only filter, decodes a preview, stores
   the item, logs a kind-only line and announces it, exactly as for the
   notification listener. `InboxStore.add` refuses an item whose digest
   (SHA-256 of the bytes, first 32 hex characters) is already stored, so a
   sticker caught by both paths is kept once.
5. The part is marked `sticker_checked`, also after a failure other than a
   bridge error, so a picture that crashes the decoder is not retried forever.

Only message events (live, or libgm's catch-up after a reconnect) are checked;
history import pages are not.

## Threading rules

1. **Never call a bridge method on the main thread.** Several block for up to a
   minute (calls have a 90 s safety timeout; `FinishPairing` waits up to 3
   minutes), and `Connect` holds the bridge's lifecycle lock across the config
   fetch (up to 30 s) and a token refresh, so `Disconnect`, `Unpair` and `Close`
   wait behind it. Constructing `GoRelayBridge` loads the native library.
2. **Callbacks never throw into Go and never block for long.** `GoRelayBridge`
   catches every `Throwable`; `RelayManager`'s sink copies the data, hands it to
   `relay-events` and returns.
3. **Never call `close()` from inside a `Sink` callback.** `Close` waits (up
   to 2 s) for the dispatcher to drain, and inside a callback the dispatcher is
   the caller itself, so the wait can only time out.
4. **Keep event order.** Go delivers in order on one goroutine and the manager
   handles in order on one thread. Do not fan event handling out to other
   threads.
5. **In Go, keep the dispatcher non-blocking.** libgm's handler must not block
   or make requests. New handling that needs the network or the lifecycle lock
   goes on its own goroutine, as `invalidate` and `scheduleHackySetActive` do.
6. **One client per AuthData.** Never run two libgm clients on the same
   `AuthData`, and only encode it through `marshalSession`.
7. **Logs stay content-free.** Kinds, codes, counts and exception class names
   only. Never message text, names, numbers, the emoji, cookies, keys, URLs, or
   the message of an exception that may quote a payload (a `JSONException`
   does).

## Build

- `:app:buildGomobileTools` builds `gomobile` and `gobind` from the
  `golang.org/x/mobile` version pinned in `gmbridge/go.mod` (a `tool` block),
  so there is no `gomobile init` and nothing unpinned.
- `:app:buildGoBridge` runs `gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 26 -javapkg com.lightdecoder.gm -trimpath -ldflags="-s -w"`
  in `gmbridge/` and writes `app/libs/gmbridge.aar` and
  `gmbridge-sources.jar`. The targets match the app's `abiFilters`; 26 is the
  app's `minSdk`. `ANDROID_HOME` and `ANDROID_NDK_HOME` come from the Android
  Gradle plugin, so the NDK is the one `ndkVersion` names.
- gomobile silently skips methods whose types it cannot bind and leaves a
  `skipped` comment in the generated Java. CI fails the build if
  `unzip -p app/libs/gmbridge-sources.jar '*.java' | grep -n skipped` prints
  anything; run it yourself after touching the Go API.
- Only one gomobile aar can go into an app (each ships `libgojni.so` and
  `go.Seq`). Any other Go code must be bound in the same `gmbridge` invocation.
- R8 needs no extra rules: the aar's `proguard.txt` keeps `go.**` and
  `com.lightdecoder.gm.**`.
- `testDebugUnitTest` depends on `buildGoBridge`. Excluding it with
  `-x :app:buildGoBridge` does not work: Kotlin's compile task queries the aar
  dependency and fails.
- CI (`.github/workflows/android.yml`) sets up Go from `gmbridge/go.mod`, runs
  `go vet`, `go test` and an `android/arm64` cross-build, builds the aar with the
  skipped-symbol guard, then runs the unit tests and builds the debug APK.

## Changing the Go↔Kotlin API

1. Change the Go method in `gmbridge/`, keeping to gomobile's types, with panic
   recovery and an error code from `errors.go`.
2. Mirror it in `RelayBridge`, `GoRelayBridge`, `FakeRelayBridge` and, for new
   JSON, `gmbridge/dto.go` + `relay/Dto.kt`.
3. Run `go vet`, `go test -race`, `./gradlew :app:buildGoBridge`, the skipped
   guard, and the unit tests. `javap -cp` on the aar's `classes.jar` shows the
   generated signatures if something does not line up.
4. Bump `Version` if old Kotlin could no longer talk to the new Go.

## Bumping libgm

libgm is versioned with mautrix-gmessages (`v0.YYMM.N`). Google changes the
Messages for web protocol from time to time, and libgm also reports a fixed web
client version (`pkg/libgm/util/config.go`) that upstream updates with its
releases, so expect to bump it now and then.

1. Update and check the module:

   ```sh
   cd gmbridge
   go get go.mau.fi/mautrix-gmessages@vNEW
   go mod tidy
   go vet ./...
   go test -race -count=1 ./...
   CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build ./...
   ```

   If the new version needs a newer Go, raise the `go` / `toolchain` lines in
   `go.mod`; CI follows them.
2. Build the aar and run the guard and the app's tests:

   ```sh
   cd ..
   ./gradlew :app:buildGoBridge
   unzip -p app/libs/gmbridge-sources.jar '*.java' | grep -n skipped   # must print nothing
   ./gradlew :app:testDebugUnitTest :app:assembleRelease
   ```

3. Read what changed upstream, in libgm and in its reference consumer:

   ```sh
   MOD=$(go env GOMODCACHE)/go.mau.fi
   diff -ru $MOD/mautrix-gmessages@vOLD/pkg/libgm     $MOD/mautrix-gmessages@vNEW/pkg/libgm
   diff -ru $MOD/mautrix-gmessages@vOLD/pkg/connector $MOD/mautrix-gmessages@vNEW/pkg/connector
   ```

   The bridge copies the connector's behaviour, file by file:

   | gmbridge | connector |
   |---|---|
   | `pairing.go` (cookies, Gaia flow, required cookie names) | `login.go` |
   | `bridge.go` `Connect` (config first, token refresh) | `client.go` |
   | `events.go` (event types, fatal errors, workarounds) | `handlegmessages.go`, `client.go` |
   | `rpc.go` sends (tmp ids, SIM payload, replies, reactions) | `handlematrix.go` |
   | `rpc.go` `FetchMessages` paging | `backfill.go` |
   | `rpc.go` `ListConversations` | `chatsync.go` |
   | `rpc.go` `GetOrCreateConversation` | `startchat.go` |

   Look in particular for:
   - new or renamed event types in `libgm/events` or `gmproto`. `processEvent`
     ignores types it does not know, so a renamed one fails silently;
   - changed error sentinels, which `classify` in `errors.go` matches on;
   - changes to the Gaia pairing steps or the required cookies. Update both
     `requiredCookies` in `pairing.go` and `CookieParser.REQUIRED`;
   - new message status values, which affect `direction`;
   - changes to what libgm logs, or new places that log. The bridge must stay
     silent;
   - new unchecked slicing or other panic paths reachable from calls the bridge
     makes.
4. If dependencies were added or changed licence, update `NOTICE`.
5. Pair a test phone and walk through the manual checks below. No automated
   test reaches Google.

To bump `golang.org/x/mobile` (gomobile and gobind come from it):
`go get golang.org/x/mobile@latest && go mod tidy` in `gmbridge/`, then the
same steps 1–2.

## Testing

**Go** (`cd gmbridge && go vet ./... && go test -race -count=1 ./...`; 40
tests, offline): DTO conversion and JSON shape, status → direction, MIME
fallbacks, folders, paging order and cursors; error-code mapping and safe
details; dispatcher order, non-blocking producers and panic containment;
session round trip and bad sessions; every method failing fast when unpaired
or not connected; cookie validation; the pairing state machine offline;
synthetic libgm events producing the right states; stale events from older
clients or connections being ignored.

**Kotlin** (`./gradlew :app:testDebugUnitTest`; Robolectric on real SQLite with
FTS4; needs Go and a host C++ toolchain, see the README):

- `RelayTestEnv` gives each test a fresh archive, a software AES key for
  `SessionVault`, cleared preferences and logs, and sets `RelayBridges.factory`
  to a `FakeRelayBridge`.
- `FakeRelayBridge` answers calls from what the test scripted (conversations,
  pages, media, send results that may throw), records every call, and pushes
  events into the manager's sink as the Go dispatcher would (`emit`).
  `duringSend` delivers the echo before the send returns, as the phone often
  does.
- Suites: `RelayManagerTest` (pairing, states, session storage, logs, stickers,
  unpair), `OutboxTest`, `BackfillTest`, `RelayServiceTest`,
  `ArchiveRepositoryTest`, `SessionVaultTest`, `DtoTest`, `CookieParserTest`,
  `StickerCaptureTest`, `InboxStoreDedupeTest`, `ScreensSmokeTest` (each screen
  reaches RESUMED with seeded data), `ChatHelpersTest`, `RelayUiTest`.
  `RelayManagerTest` also asserts that the activity log never contains the
  emoji, the account, message text or cookie values.

**Not tested automatically:**

- anything against Google's real servers: sign-in, pairing, connecting, token
  refresh, sending, and the request shapes for archive and mute (which the
  connector never uses, so they are a best reading of libgm);
- `GoRelayBridge` itself and the generated JNI glue (compiled, not exercised);
- the real Android Keystore;
- foreground-service behaviour on real devices: background-start refusals,
  Android 14+ service types, Doze, OEM battery managers, restarts after the
  process is killed;
- the sign-in WebView against Google's real pages;
- the release (R8) APK at runtime (it builds; it has not been run);
- the UI beyond the smoke tests (there are no instrumentation tests).

**Manual checks on a device** after a libgm bump or a change to the runtime:
pair with the emoji (and try a wrong one); the notification shows with
Disconnect; receive and send text and a picture; react, reply, mark read;
send while in airplane mode and watch it go out after reconnecting; force-stop
the app with a send queued; open messages.google.com in a browser and use
"Use here"; remove the device on the phone and see the app stop and ask to
pair again; Unpair and check the device is gone from the phone; receive a HEIC
sticker and find it in the sticker inbox; check the activity log for anything
that is not a kind or outcome.

## Known limitations (0.3.0)

Google and libgm:

- Google may refuse sign-in inside an embedded WebView. The app keeps the
  WebView's own user agent rather than disguising it; the fallback is pasting
  cookies.
- `CancelPairing` cannot withdraw the emoji prompt on the phone: libgm sends its
  cancel with an already-cancelled context, so the request never goes out. The
  phone's dialog times out by itself. If the user taps the emoji there after
  cancelling, the phone can keep an orphaned web pairing, which they can remove
  under Device pairing. Fixing this needs a libgm change.
- After the 60 s pairing watchdog fires, libgm's `StartGaiaPairing` goroutine
  may stay parked for good. libgm also leaks one acknowledgement-timer goroutine
  per client that ever connected.
- A panic in libgm's own background goroutines still crashes the app; only
  calls into the bridge are protected.
- libgm updates token fields without a lock; only the cookie map, the fatal
  case, is protected when saving.
- Only the `SetActiveSession` half of the connector's "SetActiveSession may have
  failed" workaround is ported, not its later client reset. libgm's "no data
  received" event is not surfaced.
- Sends never set `ForceRCS`, which the connector sets for some end-to-end
  encrypted RCS chats.

Connection and Android:

- Doze and battery optimisation are not handled beyond a link to the battery
  settings on the status screen.
- The service can be refused a foreground start (Android 12+ background
  limits, or a restart after the process was killed). The link then comes back
  when the user opens the app. Starting it on screen start ignores the "start
  automatically" setting; that is an open product question.
- `Disconnect` waits behind a `Connect` that is still fetching config or
  refreshing its token (the service and "stay connected" stop at once).
- Go's SIM cache is not persisted, so after a restart the outbox waits for the
  first `settings` event or 15 s. On a dual-SIM phone a send in that window can
  go from the phone's default SIM.
- `settings` is null until the first settings event of each run.
- The health table's "last session refresh" counts every session save, not
  only token refreshes.

Sending:

- A SENT message whose echo never arrives stays "sending"; there is no sweeper.
- If the app dies after a send reached the phone but before the echo arrived,
  the entry is sent again on restart and can be delivered twice. A manual retry
  of a `phone_not_responding` failure can do the same.
- Media sends hold the whole attachment (up to 100 MB) in memory.
- If a send succeeds at the moment Unpair holds it, the local copy shows FAILED
  while the queue entry is SENT; retry does nothing, discard works.

Archive and UI:

- The archive is not encrypted by the app beyond Android's storage encryption.
- Conversations the phone deletes or trashes are deleted locally, without a
  way back.
- If the archive is kept across Unpair and the user pairs a different phone,
  threads from the two phones mix (queued sends are held; the archive is not
  separated). A kept archive can afterwards be deleted only by pairing and
  unpairing again with the delete option, or by clearing the app's storage.
- Mute is local plus a best-effort call; the phone never reports mute state.
- `ArchiveRepository.messages(beforeTs)` pages with a strict `<`, so messages
  sharing the boundary millisecond can be skipped when paging locally.
- `Backfill.loadOlder` scans the whole conversation on every call, and the
  unreferenced-media check scans the media table per file; both are slow on
  very large conversations. A download finishing exactly while unreferenced
  files are cleaned up can lose its new file.
- No "Not spam" action. The reply target is not restored after rotation.
  Links inside a bubble cannot be activated with TalkBack.
- A notification tap from a cold start opens the thread with nothing behind
  it, so Back leaves the app.
- The contact picker's "offline" hint only shows when no contacts are stored,
  because `listContacts` falls back to the stored list quietly.
