# Google Messages link: architecture and maintenance

This is the developer guide to the Google Messages link added in 0.3.0: how it
is put together, where its state lives, the rules that keep it correct, how to
test it, and how to move it to a new libgm. The product reasoning is in
`docs/rcs-integration.md`; the user-facing summary is in the README.

The code is the source of truth. When this guide and the code disagree, the
code wins and this guide should be fixed. The most important files:

| Topic | File |
|---|---|
| Go API, lifecycle, locks, dropped clients | `gmbridge/bridge.go` |
| Pairing | `gmbridge/pairing.go` |
| Events, states, workarounds | `gmbridge/events.go` |
| Reads and writes | `gmbridge/rpc.go`, `gmbridge/media.go` |
| Go-side caches (SIMs, ForceRCS) | `gmbridge/cache.go` |
| JSON shapes | `gmbridge/dto.go` (Go), `app/.../relay/Dto.kt` (Kotlin) |
| Error codes | `gmbridge/errors.go`, `RelayException` in `app/.../relay/RelayBridge.kt` |
| Session envelope | `gmbridge/session.go` |
| Kotlin seam | `app/.../relay/RelayBridge.kt`, `app/.../relay/GoRelayBridge.kt` |
| Runtime | `app/.../relay/RelayManager.kt`, `Outbox.kt`, `Backfill.kt`, `RelayService.kt`, `ArchiveGate.kt` |
| Archive schema | `app/.../archive/ArchiveDb.kt`, `ArchiveRepository.kt` |
| Build and CI | `app/build.gradle.kts`, `.github/workflows/android.yml` |

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
|   relay-intake thread: sends, retry, discard, local deletions         |
|   IO scope: connect, sync, catch-up, outbox, import, media (2 at once)|
|   ArchiveGate: nothing the phone sends lands after a wipe             |
|   StateFlows: state, typing, backfill, settings, unpairing            |
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
channels, wipes sign-in leftovers, starts the service when a screen starts),
`BootReceiver` (reconnect after boot or app update), `ReconnectReceiver` (the
backoff's wake-up alarm), `NotificationBlockReceiver` (the user turned the
app's notifications or a channel off or on), `MessageNotifier` +
`ReplyReceiver` (optional message notifications with Reply), `RelayNotifications`
(channels `relay_status`, `relay_alerts`, `relay_messages`), `RelayPrefs`,
`RelayLog`, `CookieParser`, `RelayIntents`; in the UI, `SignInWebData` (the
sign-in WebView's data), `LocalArchive` (deleting a kept archive) and
`LicensesActivity` (About > Licences).

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
| Lifecycle | `Connect`, `Disconnect`, `SetActiveSession`, `Unpair`, `ExportSession`, `Close` | `Connect` returns when the long poll is starting; "connected" follows as an event. While connected it does nothing, except restart a long poll that is down. `Unpair` succeeds even when unpaired (it is also "wipe"); see "Unpair". |
| Local | `SeedProtocolSwitches(seedJSON)` | Never touches the network. A JSON array of the archive's protocol-switch tombstones, `{"conversationId","status","statusCode","timestampMs"}` as in MessageDTO (either status field is enough; other statuses are ignored; anything but an array is `invalid_argument`). Forgotten on Unpair and on a new pairing. |
| Reads | `ListConversations(folder, count)`, `GetConversation(id)`, `FetchMessages(id, count, cursorJSON)` → MessagePageDTO, `ListContacts`, `ParticipantThumbnails(idsJSON)` | Need a live connection; fail fast with `not_paired` / `not_connected`, otherwise up to 90 s. |
| Media | `DownloadMedia(mediaId, key)`, `RequestFullSizeImage(messageId, partId)` | Download needs a pairing but not a connection; while disconnected the stored token must still be valid for at least a minute, otherwise `not_connected` (the app then shows "Connect to load"); 32-byte key; capped at 64 MiB (`too_large`). The full-size image arrives later as an updated `message` event. |
| Writes | `SendText(convId, tmpId, text, replyToId)`, `SendMedia(convId, tmpId, data, fileName, mime, caption, replyToId)` → SendResultDTO; `SendReaction(messageId, emoji, add\|remove\|switch)`, `MarkRead(convId, messageId)`, `SetTyping(convId)`, `DeleteMessage(id)`, `SetConversationStatus(convId, archived\|active)`, `SetConversationMuted(convId, muted)`, `DeleteConversation(convId)`, `GetOrCreateConversation(numbersJSON, rcsGroupName)` → ConversationDTO | Go never retries a send. Attachments up to 100 MiB; `SendMedia` uploads outside the 90 s budget (libgm bounds each of its HTTP calls at 5 minutes), then sends under a fresh one. Replies to SMS/MMS go without reply metadata, as in the connector. `ForceRCS` is set, as in the connector, for an RCS chat in automatic send mode whose newest protocol-switch tombstone turned end-to-end encryption on; the bridge learns those from live messages, from every history page, and from the seed. |

### Error codes

`not_paired`, `not_connected`, `no_cookies`, `cookies_invalid`, `no_devices`,
`pairing_init_timeout`, `incorrect_emoji`, `pairing_cancelled`,
`pairing_timeout`, `pairing_in_progress`, `no_pairing`,
`phone_not_responding`, `connection_closed`, `not_found`, `invalid_argument`,
`too_large`, `send_failed`, `logged_out`, `internal`.

Details are written by the bridge, or, for an error it does not recognise, are
libgm's error text cut to 200 characters. Network errors are reduced to
"network error" / "network timeout" because libgm's request URLs carry an
API-key parameter. Google's "caller has no permission" and HTTP 401/403 map to
`cookies_invalid`; HTTP 404 maps to `not_found`; a request of a dropped client
(see "Dropped clients") maps to `not_paired`. Kotlin never writes a detail to
the activity log. The `code: detail` of a state goes into the health row's last
error (in the archive database), where the status screen shows the
explanation of the code, not the detail.

A send that fails after its request went out is reported as
`phone_not_responding`, which the outbox does not retry, because Google may
have the message: the connection closed while waiting for the phone's answer,
or any failure without an HTTP status once the request's headers were written
(a response timeout, a stream reset, a cancel while waiting). A failure before
anything was written (dial, DNS, TLS, a dropped client, a cancel before
sending) keeps its plain code (`connection_closed`, `internal`, …), which the
outbox sends again.

The outbox also records a few reasons of its own that are not bridge codes:
`attachment_unreadable`, `attachment_missing`, `unpaired` (held after an
Unpair, or on pairing a phone that may not be the archive's), `interrupted` (a
send a previous run left mid-call whose echo was not found on the phone; never
re-sent by itself), `unconfirmed` (Google accepted it, but no echo arrived
within an hour and the phone did not show it when asked), and the phone's
send statuses (`FAILURE_2`, …).

### Events

`Callback.OnEvent(kind, json)`:

| Kind | Payload | Raised for |
|---|---|---|
| `state` | `{"state","code","detail"}` | lifecycle changes, below |
| `message` | MessageDTO | every libgm `WrappedMessage`, live and `isOld`: new messages, status, reaction and media updates, deletions |
| `conversation` | ConversationDTO | every `gmproto.Conversation` |
| `typing` | TypingDTO | `gmproto.TypingData` |
| `settings` | SettingsDTO | `gmproto.Settings` that carry SIM cards (Go also caches the SIMs for sending) |
| `resync` | `{}` | the phone finished rebuilding its database (MOBILE_DATABASE_SYNC_COMPLETE); BROWSER_ACTIVE after this session was inactive, after a data gap (`NoDataReceived`) or after a retried SetActiveSession went through; the first message or conversation after a data gap; the first BROWSER_ACTIVE or data after `Connect` restarted a long poll that was down; 15 s after PUSH_THROTTLE_ENDED (one per throttle period). Kotlin re-lists the inbox and catches up the conversations whose latest message it lacks (see "Forward catch-up") |
| `account` | `{"account","enabled"}` | a real `events.AccountChange` (logged as a kind only) |

`Callback.OnSession(bytes)` asks Kotlin to persist the session; `null` means
delete it.

State values: `connecting` (Connect called), `reconnecting`
(`ListenTemporaryError`), `logged_out`, `cookies_invalid`, `error`,
`disconnected` (Disconnect), `unpaired` (Unpair; code `unpair_remote_failed`
when the revoke did not reach Google, so the phone may still list the device).
On `ListenRecovered`, `PhoneNotResponding`, `PhoneRespondingAgain`, the
BROWSER_ACTIVE / BROWSER_INACTIVE* alerts and a failed SetActiveSession after
a long poll opened, Go emits the state that holds now, in this order of
priority: `phone_offline` (code `phone_not_responding`) while the phone is
unresponsive, `active_elsewhere` while another browser is active, `active`
after BROWSER_ACTIVE, otherwise `connected`. `active` drops back to
`connected` when a later long-poll loop's SetActiveSession fails, until the
phone confirms again. While the long poll is down `reconnecting` holds: the
phone's liveness is still recorded, and the state that holds shows on
`ListenRecovered`. Kotlin adds two reasons of its own: `disconnected` with
code `notifications_off` when the link's notification cannot be seen, and
`unpaired` with `unpair_remote_failed` also when the revoke could not be
attempted (see "Unpair").

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
- The protocol-switch seed is `ProtocolSwitchSeed` in `relay/Dto.kt`; Go reads
  it inline in `SeedProtocolSwitches` and has no struct for it in `dto.go`.

`dto_test.go` checks the Go JSON shape and `DtoTest` the Kotlin parsing.
Nothing compares one with the other automatically, so change both together.

### Concurrency inside Go

- **Dispatcher.** libgm calls its event handler synchronously from its long-poll
  and pinger goroutines and requires it not to block or make requests. The
  handler therefore only appends a job to an unbounded FIFO. One dispatcher
  goroutine runs the jobs in order, so Kotlin sees events in libgm's order and
  never concurrently. A panic in one job is contained to that job.
- **Two locks.** `lifecycle` serialises Connect, Disconnect, Unpair, Close,
  pairing adoption, fatal-error handling and closing an unwanted long poll, so
  a client is never swapped while one of these is half done. `mu` guards the
  bridge's fields and is never held across a network call. Order: `lifecycle`
  → `mu` → `AuthData.CookiesLock`.
- **Stale events.** Each client has a generation number and each Connect a
  connection number, both stamped when libgm raises the event. Events from a
  replaced client, or connection events from an older connection, are ignored,
  so a late error cannot tear down a newer connection. Helpers that outlive
  their event (the SetActiveSession retries, the throttle resync) are tied to
  their connection under `mu` (`claimForConnection`, `supersedeForConnection`),
  so an older connection's event can neither start one for the newer
  connection nor use up or replace the newer one's.
- **Fatal verdicts** (logged out, cookies invalid, other fatal errors) are
  handled on their own goroutine, because they need the lifecycle lock and the
  dispatcher must never wait on it.
- **Dropped clients are cut off the network.** libgm cannot stop a client: its
  5 s ack ticker runs for the life of the process and re-sends failed acks, and
  goroutines of a connection (postConnect's SetActiveSession about 3 s after a
  long poll opens, the ping recovery that may call `Reconnect`) can still act
  after Disconnect. Each long-lived client is therefore built with
  `retirableSettings`, libgm's usual HTTP transport behind a switch. When the
  bridge drops a client (Unpair, after its revoke; a lost pairing; a new
  pairing replacing it) the switch is set, and every request of that client
  fails locally with `errClientRetired` before any connection is made.
  Pairing-attempt clients are not behind it. `Close` does not retire the
  client (only tests call `Close`).
- **A long poll reopened after Disconnect is closed.** libgm's ping recovery can
  call `Reconnect` by itself while the old loop sleeps in its error backoff.
  Every new loop reports `ListenRecovered` when it opens; one that arrives while
  no connection is wanted makes `stopUnwantedLongPoll` disconnect the client
  again, under the lifecycle lock so that a Connect just starting is left alone.
- **Saving the session** always goes through `marshalSession`, under
  `CookiesLock.RLock`: libgm rewrites the cookie map on every HTTP response, and
  encoding it concurrently is a fatal, unrecoverable map access.
- **Workarounds**, most of them the connector's:
  - messages deleted while the phone rebuilds its database (between
    MOBILE_DATABASE_SYNC_STARTED and _COMPLETE) are dropped, and so are
    MESSAGE_DELETED updates raised while a `ListConversations` call is in
    flight (the phone sends spurious ones then);
  - after `HackySetActiveMayFail`, if the phone has not confirmed a session
    (BROWSER_ACTIVE) 7 s later, `SetActiveSession` is sent once more. It runs
    per long-poll loop: a later loop's warning replaces a pending one;
  - a SetActiveSession that failed in libgm's postConnect (a `PingFailed` with
    `ErrorCount` 0) is retried after 5 s, 15 s and 45 s until the phone
    confirms. Also per long-poll loop, a newer failure's retries replacing an
    older one's. A retry that goes through makes the next BROWSER_ACTIVE emit
    `resync`;
  - 15 s after PUSH_THROTTLE_ENDED one `resync`, which covers sends whose echo
    the throttle held back (the connector resyncs those chats after the same
    delay). PUSH_THROTTLE_STARTED, PUSH_THROTTLING and the `*_IN_DARK_LAUNCH`
    alerts are ignored, as in the connector;
  - `Connect` while connected restarts a long poll that is down (libgm waits 5 s
    longer after every failed attempt, without limit) and asks for a `resync`
    on the new loop's first BROWSER_ACTIVE or data, since the new loop does
    not know how long the poll was down.
- **Logging.** libgm gets `zerolog.Nop()`, and `init()` disables zerolog's
  global level as well, because at trace, debug and warn levels libgm logs
  decrypted payloads. The package itself logs nothing.
- **Device name.** `init()` sets libgm's `util.BrowserDetailsMessage.OS` to
  `pairedDeviceName` ("LightDecoder"), which the phone lists under Device
  pairing. libgm's default is "libgm"; a phone paired with an earlier build
  keeps showing that until it is paired again. The app's texts tell the user
  to look for "LightDecoder", so keep them in sync.

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
outbox, the history import and the forward catch-up, the `state` / `typing` /
`backfill` / `settings` / `unpairing` flows, the decision to connect, the
archive's owner, sticker checks and media downloads.

Threads:

- Go's dispatcher calls the sink, which copies the data and hands it to the
  single `relay-events` thread. All event handling (archive writes, state,
  session saves) happens there, in Go's order, so Go never waits on the
  database.
- New sends, retries and discards go through the single `relay-intake` thread,
  so they keep the order the user made them in. Deleting another phone's
  archive, deleting a kept archive, holding queued sends on pairing, and
  deletions without a pairing run there too, so they fall between sends,
  never in the middle of one being queued. (Unpair wipes or holds on its own
  coroutine, after "stay connected" is off.)
- Everything that talks to the phone runs on `Dispatchers.IO`; media downloads
  are limited to two at a time.

The public API is the one the UI uses. These throw `RelayException`:
`startPairing`, `finishPairing`, `useHere`, `refreshConversations`,
`loadOlder`, `requestFullSize`, `react`, `setArchived`, `setMuted`,
`startConversation`, and `deleteMessage` and `deleteConversation` while a
pairing exists. Without a pairing those two delete locally only and do not
call the bridge. These do not throw: `markRead`, `unpair`,
`deleteLocalArchive` (false while paired), `mediaFile` (null when the file
cannot be had; it throws only `not_connected`, so the UI can say "Connect to
load"), and `listContacts` (falls back to stored contacts, throws only if
there are none).

### When the link connects

The link connects only when all of these hold: a pairing exists,
`RelayPrefs.liveEnabled` ("stay connected") is on, `RelayService` is running
(or a start is on its way), and the service's notification can be seen
(`RelayNotifications.statusVisible`: the app's notifications are on, which on
Android 13+ includes the permission, and the `relay_status` channel is not
turned off). The service is what shows the notification with Disconnect, so a
reconnect timer, alarm or network callback that fires after the service has
gone does nothing.

- `startLive()` turns `liveEnabled` on and starts the service; the service goes
  foreground and calls back into the manager, which connects. Without a
  pairing it leaves `liveEnabled` off.
- `stopLive()`, and the notification's Disconnect, turn it off, stop the
  service and disconnect, keeping the pairing. Both are cheap and safe on the
  main thread, so quick on/off taps take effect in order: the deferred
  disconnect checks again, under `connectLock`, whether the link is wanted.
- **Hidden notification.** The link never runs without its notification.
  `onNotificationsHidden()` stops the service and disconnects, keeps
  `liveEnabled`, sets `resumeOnVisible` and reports `disconnected` with code
  `notifications_off`. It is checked in `startLive`, `RelayService`'s start,
  `connectIfWanted`, `connectNow`, `onAppVisible` and `BootReceiver`. On
  Android 9+ `NotificationBlockReceiver` gets the protected
  APP_BLOCK_STATE_CHANGED and NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED
  broadcasts; it reads the real state, never the extras, takes the link down
  at once, and starts it again when notifications come back on. Setup does
  not go past its first step until the notification can be seen.
- On Android 14+ the user can swipe the notification away. Its delete intent
  (`ACTION_REPOST`) posts it again while the service is live. Whatever stops
  the service, `onServiceDestroyed` disconnects unless a new start is already
  on its way.
- Foreground service type: `remoteMessaging` on Android 14+, `dataSync` on
  10–13, none on 8–9. `onTimeout` (the dataSync time limit) calls `stopLive()`.
- Android 12+ refuses foreground-service starts from the background. Pairing
  usually finishes while the user is in Google Messages tapping the emoji, so
  that start can be refused. A refused start the user wanted sets
  `resumeOnVisible`, and `LightDecoderApp` starts the service again when one
  of the app's screens starts (`onAppVisible`). A link that is wanted but
  simply not running (after a restart, say) is started there only if "start
  automatically" is on, because some screens can be opened by other apps.
  With the service running, `onAppVisible` takes the link down if its
  notification is hidden and retries an errored link at once.
- Stopping the service between `startForegroundService` and its
  `startForeground` crashes the app. `serviceStartsPending` makes
  `RelayService.stop` leave a pending start alone; that start goes foreground
  and then stops itself. `serviceStartLock` makes counting a start and handing
  it to Android one step as far as `stop` can tell, and the same for checking
  the count and stopping.
- `BootReceiver` (boot completed, app updated) starts the service only if
  `autoStart`, `liveEnabled` and a pairing are all present and the
  notification can be seen.
- When the link cannot run at all (a stored session without a pairing, the Go
  library missing), `giveUpLive()` turns `liveEnabled` off and stops the
  service, so it does not sit in the foreground with nothing to do.
- **Backoff.** After ERROR or a failed connect the link reconnects after
  15 s, 30 s, 1 min, 2 min, 5 min, then every 15 min. The coroutine timer only
  counts while the phone is awake, so a wake-up alarm on elapsed real time
  (`setAndAllowWhileIdle`) backs it up. It fires `ReconnectReceiver` (not
  exported), which connects and keeps the phone awake for up to 25 s through
  `goAsync`.
- While the service runs, a network callback nudges the link when a network
  comes up: ERROR retries at once instead of waiting out its backoff;
  RECONNECTING and PHONE_OFFLINE connect again, which makes Go restart a long
  poll that is down (Go reports an unresponsive phone ahead of a long poll that
  is down, so PHONE_OFFLINE can hide one). Nothing more is asked for while a
  connect is running.
- Before every connect, `connectNow` checks the archive's owner (see "Archive
  owner") and does not connect while another phone's archive could not be
  deleted; it retries with the backoff. Once per pairing it then hands the
  bridge the newest protocol-switch tombstone of each conversation in the
  archive (`seedProtocolSwitches`; after a failure the next connect tries
  again). It checks once more that the link is still wanted just before
  `connect()`, and disconnects if that changed while `connect()` was blocking.

## State machine

`RelayState.Phase`, as `RelayManager.state` reports it. `isOnline` is true for
CONNECTED and ACTIVE only.

| Phase | Comes from | What the manager does |
|---|---|---|
| UNPAIRED | no session, or `unpaired` (code `unpair_remote_failed` when the phone may not know) | — |
| PAIRING | Kotlin only: between `startPairing` and the end of `finishPairing` | back to UNPAIRED/DISCONNECTED on failure or cancel |
| DISCONNECTED | paired but not connected; `disconnected` (code `notifications_off` when the notification cannot be seen); the initial phase when a session file exists | clears typing |
| CONNECTING | `connecting` | counts a new connection; resets the send gate; notes where the archive stands for the forward catch-up, on the event thread, before anything of the connection is stored |
| CONNECTED, ACTIVE | `connected`, `active` | once per connection, also when it got there only after phone_offline or active_elsewhere: check the archive's owner, re-list the inbox (100), catch up what arrived meanwhile, start the history import if it never finished; if the inbox could not be listed, the connection's next online state tries again. Open the send gate on the connection's first `settings` event or after 15 s; run the outbox |
| ACTIVE_ELSEWHERE | `active_elsewhere` | UI offers "Use here" (`SetActiveSession`) |
| RECONNECTING | `reconnecting` | waits; libgm retries by itself, and a network coming back restarts the long poll at once |
| PHONE_OFFLINE | `phone_offline` | waits; libgm's pinger backs off and recovers; a network coming back also connects again |
| LOGGED_OUT | `GaiaLoggedOut`, `RevokePairData`, or "requested entity not found" from a fatal error or failed ping. Go disconnects, clears the pairing (keeping cookies and keys) and saves the session. | pairing gone; `liveEnabled` off; service stopped; "pair again" notification; archive kept; queued sends stay queued, and on pairing again go out only if it is the same phone |
| COOKIES_INVALID | fatal error with invalid credentials or HTTP 401/403, or Connect finding the cookies signed out (empty account email). Go disconnects and keeps the session. | same stop and notification; the pairing still exists but cannot sign in, so setup runs a fresh sign-in |
| ERROR | any other fatal long-poll error, or a failed Connect | reconnect with the backoff above; never after `cookies_invalid`, `logged_out` or `not_paired` |

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
   - A marker file (`no_backup/signin-webview-dirty`) exists from before the
     WebView is created until a wipe ran with no sign-in WebView alive. If a
     sign-in never ended properly (the process was killed), the first screen,
     or `LightDecoderApp` for a process with no screen, deletes the WebView's
     data directory directly (`SignInWebData.wipeIfDirty`).
   - After a paste, the clipboard is cleared if it still holds the pasted text
     or any of the cookies used; either way a toast reminds the user of their
     keyboard's clipboard history, which the app cannot reach. The clip is
     never logged.
   - On a debuggable build the disclosure step shows a warning: adb can read
     the archive and the session there, so use a test account.
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
   `OnSession`, builds the long-lived client (not connected), forgets the
   previous pairing's caches and returns the phone id. The manager saves the
   session, compares the phone and account with the archive's owner (see
   "Archive owner"), records the new owner, and calls `startLive()`.
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
header, too short, failed tag check, permanently invalidated key). Any other
error, such as a Keystore hiccup at boot, throws `UnreadableException` and
keeps the file; the manager then treats the link as paired but not loadable
yet (nothing cached, retried with the backoff), never as "no pairing". Tests
swap `SessionVault.cipherProvider` for a software key.

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

Rules the repository and the manager follow (keep them when changing either):

- `ArchiveRepository` is the only writer. Each write is one transaction and
  posts its `ArchiveEvents` change after the commit; callers must not post
  again.
- **Archive gate.** Everything fetched from the phone by a call (the
  conversation list, a page of messages, contacts, a conversation, a media
  download) is written through `ArchiveGate.ifCurrent` with the gate's epoch
  taken before the call. Every wipe goes through `gate.wipe`, which bumps the
  epoch between two such writes, so a reply that comes back after the archive
  was deleted is dropped instead of putting names, text or files back. An
  import or catch-up that meets a wipe ends (`Backfill.ArchiveWiped`).
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
- Media files are written whole or not at all (`MediaCache.writeAtomically`:
  a `.part` file of its own per write, renamed into place; `.part` files over
  an hour old are swept). A download's path is recorded only if the part still
  has that media (`setMediaLocalPath` with the cache key); otherwise the file
  is deleted, unless another part uses it. A file no row points at any more is
  deleted after the commit that dropped it.
- Search: `"`, `*`, `^` and control characters are removed from the query,
  words without a letter or digit are dropped, at most 32 words are kept, each
  is quoted, and the last one gets a prefix `*`. The index uses rowids, so do
  not `VACUUM` the database without rebuilding `messages_fts`.
- There are no migrations yet. A schema change needs `VERSION` bumped and a
  migration in `onUpgrade`.

### Archive owner

Conversation, message and participant ids only mean something on the phone
they came from: another phone can have someone else behind the same
conversation id. So the archive belongs to one phone and Google account,
recorded in `RelayPrefs.archivePhone` / `archiveAccount` (falling back to the
health row, and written with `commit()`, so a crash right after a wipe cannot
leave the old phone recorded).

- **On pairing** (`onPaired`, with the phone id `finishPairing` returned, else
  the bridge's status): a different phone or account than the owner deletes
  the archive, with its media and queued sends, on the intake thread before
  the link goes live. The same phone again (after "logged out" or expired
  cookies) lets queued sends go out as planned. If it is not known which,
  queued sends are held as failed (`unpaired`) for the user to retry or
  discard. The new phone becomes the owner unless the deletion failed.
- **Before every connect**, and again in the initial sync
  (`reconcileArchiveOwner`), the paired phone is compared with the owner and
  recorded over an empty or equal one. A mismatch (a re-pairing that did not
  get as far as deleting the old archive, because the app was killed, say)
  deletes the archive the same way. If it cannot be deleted within 60 s,
  queued sends are held, nothing is sent (`archiveForeign`), the link does not
  connect, and the next connect tries again; a deletion that has not started
  by then is cancelled, so it cannot run in the middle of a connection.
- Unpair with the archive kept keeps the owner, so a later pairing can tell.
- **Without a pairing** (unpaired with the archive kept, or logged out),
  `deleteMessage` and `deleteConversation` delete on this device only (on the
  intake thread, through the gate), and `deleteLocalArchive()` deletes the
  whole archive. The status screen offers it as "Delete local message
  archive" when no session is left, and the conversations screen's menu
  whenever nothing is paired. It refuses while a pairing exists; then Unpair
  with "also delete" is the way. It keeps the health row's status and last
  error, so "logged out" still shows.
- **Wiping** (`wipeArchive`) clears every table and both media directories,
  the owner, the list of unconfirmed sends and the catch-up bounds. The
  callers also reset `backfill_done_at` and cancel message notifications.

### Files and preferences

| Path | Contents |
|---|---|
| `files/relay/session.bin` | encrypted session |
| `files/relay-media/<sha256(mediaId), 32 hex>.<ext>` | downloaded media and thumbnails (hashed names keep phone ids out of paths); `*.part` while being written |
| `files/relay-outbox/<tmpId>` | copies of attachments waiting to be sent |
| `files/relay-log.txt` | activity log, last 120 lines, kinds and outcomes only |
| `no_backup/signin-webview-dirty` | marker: a sign-in WebView may have left cookies behind |
| shared prefs `relay` | `live_enabled`, `auto_start` (default on), `backfill_depth` (default RECENT), `notify_messages` (default off), `catch_stickers` (default on), `backfill_done_at`, `archive_phone` and `archive_account` (the archive's owner), `unconfirmed_sends` (tmpId=time pairs, no content) |

All of it is excluded from backup and device transfer by
`app/src/main/res/xml/data_extraction_rules.xml` (Android 12+) and
`allowBackup="false"` / `fullBackupContent="false"` (older).

### Unpair

`RelayManager.unpair(deleteArchive)` runs in the manager's own scope: if the
screen that asked goes away, only its wait is cancelled. `unpairing` is true
meanwhile, and a second unpair waits for the first.

1. "Stay connected" goes off and the connection's work stops.
2. Under `connectLock`, Go's `Unpair` runs. If the link is disconnected and its
   tachyon token expires within the hour (`libgm.RefreshTachyonBuffer`), a
   temporary connection refreshes it first (libgm's `Connect` is its only
   synchronous refresh; the bridge drops that connection's events). Then
   `UnpairGaia` revokes the pairing (15 s); "requested entity not found" counts
   as already revoked. Go tears down, cuts the client off the network, clears
   the session and its caches, and reports `onSession(null)` and `unpaired`,
   with code `unpair_remote_failed` if the revoke failed.
3. Only then does the service stop, so if it was running, its notification
   with Disconnect was up during any temporary connection. If it was not
   running, none is started: the revoke happens while the screen shows the
   unpair in progress.
4. The manager waits up to 10 s for Go's `unpaired` state, then for the event
   thread, deletes the session file, turns the pairing off and resets
   `backfill_done_at`. With "also delete the archive" it wipes the archive;
   without it, it holds every queued send as failed (`unpaired`).
5. It writes the health row (`unpaired`, account and phone cleared, last error
   the bare code `unpair_remote_failed` or empty) and ends in UNPAIRED with that
   code. The code is also set when a stored pairing could not be loaded or
   Go's `Unpair` threw, since the phone may then not know either. The status
   screen shows a dialog asking the user to remove "LightDecoder" under Device
   pairing in Google Messages, and the inbox's link card says the same while
   the state lasts.

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
  default SIM), no send on this connection has found the link down (one of the
  codes that put an entry back to PENDING, below), no hold of queued sends is
  still waiting to run, and the archive is not another phone's (see "Archive
  owner").
- Statuses: PENDING → SENDING → SENT or FAILED. A SENT row is deleted once the
  echo arrives, or at once if the echo came first (the phone often echoes
  before the send call returns). The local copy shows "sending" until the echo.

| Outcome of a send | What happens |
|---|---|
| ok | SENT |
| `FAILURE_2`, `FAILURE_3`, `send_failed`, `internal` | retried after 3 s, 8 s, 20 s with the same tmpId, then FAILED |
| `not_connected`, `connection_closed`, `not_paired`, `logged_out`, `cookies_invalid` | back to PENDING; the queue pauses until the next online state |
| `phone_not_responding` and anything else | FAILED, not retried automatically (Google may still deliver it; its echo then clears the failure) |

- Before every retry, and when picking up an entry, the outbox checks whether
  `tmp:<key>` still exists. If not, the echo already arrived: the entry is
  dropped instead of being sent twice. `pendingOutbox()` returns PENDING and
  SENDING rows, oldest first.
- **Sends whose outcome is unknown are looked up on the phone** (`reconcile`,
  at the start of every drain, before anything new goes out):
  - *Interrupted:* an entry found SENDING when a drain starts was cut off mid-call
    by a previous run (the app died). It is looked up; if its echo is not
    found (or the phone does not answer), it becomes FAILED `interrupted` and
    waits for the user. It is never sent again by itself.
  - *Unconfirmed:* a send Google accepted (SENT) whose echo has not arrived is
    listed in `RelayPrefs.unconfirmedSends` (tmpId and time, written with
    `commit()`); a SENT row missing from that list is taken in as sent now.
    It is looked up once per process 15 s after sending. After an hour it is
    looked up again until the phone answers, and if the echo is still not
    there it becomes FAILED `unconfirmed`. A lookup the phone did not answer
    gives nothing up.
  - A lookup pages back through the conversation from its newest message, 50
    at a time with 400 ms between pages, up to 10 pages, until the echoes are
    found, a page reaches the earliest send time minus 10 minutes (the
    phone's clock may differ), or the conversation's start. Only the echoes
    are stored: a partial page would make the archive look up to date above a
    gap the forward catch-up still has to fill. Lookups do not wait for the
    import's pager, so sends do not wait behind a history import.
  - A late echo still clears an `interrupted` or `unconfirmed` failure.
- Unpair (keeping the archive) holds every queued entry as FAILED with reason
  `unpaired`, and so does a pairing whose phone is not known to be the
  archive's: conversation ids only mean something on the phone they came
  from. A pairing with a different phone deletes the queue with the archive.
- The user can retry or discard a FAILED send; discarding removes the local
  copy and the attachment copy. The thread shows every FAILED send the same
  way ("Not sent. Tap to try again.") and retries at once, so retrying an
  `interrupted`, `unconfirmed` or `phone_not_responding` send can deliver it
  twice (see "Known limitations").

## Forward catch-up

The phone does not replay as live events what arrived while the link was
down, and the history import only pages backwards, so without this a gap would
stay in the archive for good.

- At "connecting", on the event thread and before anything of the connection
  is stored, the manager notes per conversation the newest message from the
  phone stored so far (`newestStoredTimestamps`, one indexed look per
  conversation). A `resync` takes its own mark when it arrives.
- After the initial sync has listed the inbox (and after each `resync` has),
  a conversation is behind if its latest message is not stored at or below its
  mark. Conversations with nothing stored are skipped: their history is the
  import's, or "load older"'s. A catch-up that did not finish keeps its bound
  for the next one (`unfinishedCatchUps`, cleared when the archive is
  deleted), so messages stored since cannot move it up.
- `Backfill.catchUp` pages newest first, 50 per page with 400 ms between pages,
  up to 10 pages, until a page reaches the bound or the conversation's start.
  Messages stored after the mark (live ones, echoes, a "load older" page) come
  back on the first pages and are stored again harmlessly, so they cannot end
  the paging early. It never touches the import's cursor. What it stores goes
  through the sticker check, without notifications.
- It runs with the connection (the `catchUps` job, cancelled with it) and holds
  the `pager` mutex while it runs. The import takes the pager per conversation,
  so a catch-up waits for one conversation of an import at most. It stops on a
  link failure (`Backfill.ABORT_CODES`) or after two failures in a row, and
  pauses 800 ms after each conversation that got messages.
- The initial sync and each resync run under the `syncs` job. After their
  blocking list call they check, under the lock that `stopConnectionWork`
  cancels under, that they are still current before starting anything.

## History import

- Depths (`BackfillDepth` in `RelayPrefs.kt`): OFF, RECENT (25 conversations ×
  50 messages, the default) and DEEP (200 × 1000). The import runs once per
  pairing on the first online connection (while `backfill_done_at` is 0, which
  pairing, Unpair and archive deletion reset), or when the user taps "Import
  history now"; starting one cancels the one running and waits for it to end,
  and Disconnect cancels it. It waits up to 60 s for the link to come online.
- It covers the inbox only. For each conversation it fetches pages of up to 50
  (always a full 50 below a cursor), starting from the `sync_state` cursor (or
  the newest page if there is none), until the archive holds the target number
  of the phone's messages or the phone returns no cursor (`history_complete`).
  It pauses 400 ms between pages and 800 ms between conversations, because
  every page is answered by the user's phone. An interrupted import resumes
  from `sync_state`.
- A conversation the phone cannot serve is skipped ("N skipped" in the log);
  the run stops on a link failure or after two failures in a row. A run that
  was cancelled while a page was on its way writes nothing more.
- Imported pages are written with `upsertMessages` in one transaction each,
  with no notifications and no sticker checks.
- `loadOlder` (scrolling to the top of a thread) fetches the page before the
  import's `sync_state` cursor (the newest page if there is none) and moves
  the cursor. It never pages from the oldest stored message: a live update can
  store one old message on its own (a reaction to something from last year),
  and paging below it would skip the gap above.
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
   `InboxStore.relayIdFor(phoneId, conversationId, messageId, partId)`, where
   `phoneId` is the archive's owner (else the pairing's phone, else empty):
   ids are only unique on one phone, so a message of another phone with the
   same ids is not taken for one already seen. It sniffs the bytes, applies the
   inbox's HEIC-only filter, decodes a preview, stores the item, logs a
   kind-only line and announces it, exactly as for the notification listener.
   `InboxStore.add` refuses an item whose digest (SHA-256 of the bytes, first
   32 hex characters) is already stored, so a sticker caught by both paths is
   kept once.
5. The part is marked `sticker_checked`, also after a failure other than a
   bridge error, so a picture that crashes the decoder is not retried forever.

Live message events and the forward catch-up's pages are checked; history
import and "load older" pages are not.

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
   goes on its own goroutine, as `invalidate`, `stopUnwantedLongPoll` and the
   SetActiveSession helpers do.
6. **One client per AuthData.** Never run two libgm clients on the same
   `AuthData`, and only encode it through `marshalSession`. Build long-lived
   clients only through `newClientLocked`, so that each can be cut off.
7. **Write what the phone sent through the gate.** Take `gate.epoch()` before
   the call and store with `gate.ifCurrent`; delete the archive only through
   `gate.wipe`.
8. **Logs stay content-free.** Kinds, codes, counts and exception class names
   only. Never message text, names, numbers, the emoji, cookies, keys, URLs, or
   the message of an exception that may quote a payload (a `JSONException`
   does).

## Build

- **Go toolchain.** Every Go step runs with `GOTOOLCHAIN` set to the
  `toolchain` line of `gmbridge/go.mod` (go1.26.11), so `libgojni.so` is
  always built by that exact release; the build fails if the line is missing.
  (`GOTOOLCHAIN=auto` would not do it: gomobile binds in a temporary module
  that only says `go 1.26`.) Gradle runs `go` from `PATH`, or the Go named by
  `-Pgmbridge.go=/path/to/go` (also `gmbridge.go=` in
  `~/.gradle/gradle.properties`): any Go 1.21 or newer, or a golang.org/dl
  wrapper such as `go1.26.11`. A Go that cannot run, or reports another
  version under the pin, fails with a hint. Before binding, `buildGoBridge`
  asks that Go for its `GOROOT` and puts its `bin` first on `PATH`, because
  gomobile and gobind always run `go` from `PATH`.
- `:app:buildGomobileTools` builds `gomobile` and `gobind` from the
  `golang.org/x/mobile` version pinned in `gmbridge/go.mod` (a `tool` block),
  so there is no `gomobile init` and nothing unpinned.
- `:app:buildGoBridge` runs `gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 26 -javapkg com.lightdecoder.gm -trimpath -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384"`
  in `gmbridge/` and writes `app/libs/gmbridge.aar` and
  `gmbridge-sources.jar`. The targets match the app's `abiFilters`; 26 is the
  app's `minSdk`. The `max-page-size` gives 16 KB-aligned LOAD segments, for
  devices with 16 KB pages (the native decoder's CMake build links the same
  way). `ANDROID_HOME` and `ANDROID_NDK_HOME` come from the Android Gradle
  plugin, so the NDK is the one `ndkVersion` names; its path is a task input,
  so changing `ndkVersion` links again.
- gomobile silently skips methods whose types it cannot bind and leaves a
  `// skipped method …` comment in the generated Java. CI fails the build on
  such a marker; run
  `unzip -p app/libs/gmbridge-sources.jar '*.java' | grep -nE '^[[:space:]]*// skipped (constructor|field|method|variable|const|function) '`
  yourself after touching the Go API (it must print nothing).
- Only one gomobile aar can go into an app (each ships `libgojni.so` and
  `go.Seq`). Any other Go code must be bound in the same `gmbridge` invocation.
- R8 needs no extra rules: the aar's `proguard.txt` keeps `go.**` and
  `com.lightdecoder.gm.**`.
- `testDebugUnitTest` depends on `buildGoBridge`. Excluding it with
  `-x :app:buildGoBridge` does not work: Kotlin's compile task queries the aar
  dependency and fails.
- **Licences in the APK.** `bundleLicenses` (before every build) writes
  `assets/NOTICE` and `assets/licenses/*.txt`, which About > Licences shows:
  `00-lightdecoder.txt` (AGPL), `10-libheif.txt` and `11-libde265.txt`,
  `20-go-<module>.txt` for each Go module linked into `libgojni.so` (read from
  the build info of each ABI's library, so exactly what is linked, with its
  version), `30-go.txt` (the Go runtime), and `40-android-libraries.txt` (the
  exact AndroidX, Material, Kotlin and kotlinx.coroutines coordinates from the
  release runtime classpath, then the Apache-2.0 text from
  `third_party/licenses/Apache-2.0.txt`). It fails the build if a
  `libgojni.so` was not built with the pinned Go, a linked Go module has no
  licence file, or an Android library is outside the known Apache-2.0 groups.
  Go modules missing from the module cache are fetched with `go mod download`.
- **Build types.** Debug builds get `applicationIdSuffix ".debug"` and
  `versionNameSuffix "-debug"`, so they install next to the release as
  `com.lightdecoder.debug`. They are debuggable (adb can read the archive and
  the session), and the pairing screen says so. Release is not debuggable.
- **Release signing** reads `LIGHTDECODER_KEYSTORE`,
  `LIGHTDECODER_KEYSTORE_PASSWORD`, `LIGHTDECODER_KEY_ALIAS` and optionally
  `LIGHTDECODER_KEY_PASSWORD` (default: the keystore password), or the Gradle
  properties `lightdecoder.keystore`, `lightdecoder.keystorePassword`,
  `lightdecoder.keyAlias`, `lightdecoder.keyPassword`. A blank value counts as
  unset. A keystore without its password or alias, or a missing file, fails the
  build. Without a keystore the release APK is signed with the local debug key,
  with a warning.
- **CI** (`.github/workflows/android.yml`, with `contents: read`
  permissions):
  - installs exactly the Go of the `toolchain` line (`actions/setup-go@v6`,
    which also exports `GOTOOLCHAIN=local`) and checks `go env GOVERSION`;
  - runs `go vet`, `go test -count=1 -race -timeout 5m` and an `android/arm64`
    cross-build;
  - builds the aar and runs the skipped-symbol guard, which also fails when
    the sources jar is missing or empty;
  - checks that each ABI's `libgojni.so` reports the pinned Go, and that every
    LOAD segment is at least 16 KB aligned on arm64-v8a and x86_64;
  - runs the unit tests with an empty `GOMODCACHE`, so `bundleLicenses` must
    fetch the modules it needs;
  - rebuilds the aar with `-Pgmbridge.go` set to a Go wrapper and a failing
    `go` first on `PATH`, and requires the same aar;
  - builds the release APK. Only builds of `main` are release-signed: only
    they decode the keystore, get its passwords, and enter the `release`
    environment, which should hold the four `LIGHTDECODER_*` secrets with a
    deployment-branch policy of `main` only. Pull requests and runs on other
    branches are signed with the runner's throwaway debug key;
  - checks the release APK: package `com.lightdecoder`, not debuggable, one
    signer (for anything but `main`, exactly the debug key), `zipalign -c -P 16`,
    the licence assets and the arm64 native libraries present, and
    `40-android-libraries.txt` naming the Apache License and the libraries;
  - uploads it as `lightdecoder` from `main`, otherwise as
    `lightdecoder-preview` (which cannot update an installed release).

  CI does not run lint or build the debug APK.

## Changing the Go↔Kotlin API

1. Change the Go method in `gmbridge/`, keeping to gomobile's types, with panic
   recovery and an error code from `errors.go`.
2. Mirror it in `RelayBridge`, `GoRelayBridge`, `FakeRelayBridge` and, for new
   JSON, `gmbridge/dto.go` + `relay/Dto.kt`.
3. Run `go vet`, `go test -race -count=1 -timeout 5m`,
   `./gradlew :app:buildGoBridge`, the skipped guard, and the unit tests.
   `javap -cp` on the aar's `classes.jar` shows the generated signatures if
   something does not line up.
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
   go test -race -count=1 -timeout 5m ./...
   CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build ./...
   ```

   If the new version needs a newer Go, raise the `go` / `toolchain` lines in
   `go.mod`; the Gradle build and CI follow the `toolchain` line.
2. Build the aar and run the guard and the app's tests:

   ```sh
   cd ..
   ./gradlew :app:buildGoBridge
   unzip -p app/libs/gmbridge-sources.jar '*.java' | grep -nE '^[[:space:]]*// skipped (constructor|field|method|variable|const|function) '   # must print nothing
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
   | `events.go` (event types, fatal errors, workarounds) | `handlegmessages.go`, `client.go`, `chatsync.go` |
   | `rpc.go` sends (tmp ids, SIM payload, replies, reactions, ForceRCS) | `handlematrix.go` |
   | `rpc.go` `FetchMessages` paging | `backfill.go` |
   | `rpc.go` `ListConversations` | `chatsync.go` |
   | `rpc.go` `GetOrCreateConversation` | `startchat.go` |

   Look in particular for:
   - new or renamed event types or alert types in `libgm/events` or `gmproto`.
     `processEvent` ignores types it does not know, so a renamed one fails
     silently;
   - changed error sentinels, which `classify` in `errors.go` matches on;
   - changes to the Gaia pairing steps or the required cookies. Update both
     `requiredCookies` in `pairing.go` and `CookieParser.REQUIRED`;
   - new message status values, which affect `direction` and the
     protocol-switch statuses (`protocolSwitchForce`, `ProtocolSwitchSeed.STATUSES`);
   - changes to `util.BrowserDetailsMessage` (the device name), to how libgm
     builds its HTTP clients (`retirableSettings` relies on
     `exhttp.ClientSettings.TransportOverride`), and to its background
     goroutines (ack ticker, ping recovery, `postConnect`);
   - changes to what libgm logs, or new places that log. The bridge must stay
     silent;
   - new unchecked slicing or other panic paths reachable from calls the bridge
     makes.
4. If dependencies were added or changed licence, update `NOTICE`.
   `bundleLicenses` fails the build if a linked module has no licence file.
5. Pair a test phone and walk through the manual checks below. No automated
   test reaches Google.

To bump `golang.org/x/mobile` (gomobile and gobind come from it):
`go get golang.org/x/mobile@latest && go mod tidy` in `gmbridge/`, then the
same steps 1–2.

## Testing

**Go** (`cd gmbridge && go vet ./... && go test -race -count=1 ./...`; 63
tests, offline): DTO conversion and JSON shape, status → direction, MIME
fallbacks, folders, paging order and cursors; error-code mapping and safe
details; send failures through a real HTTP/2 test server (which ones count as
possibly delivered); dispatcher order, non-blocking producers and panic
containment; session round trip and bad sessions; every method failing fast
when unpaired or not connected; cookie validation; the pairing state machine
offline and the device name; synthetic libgm events producing the right
states, resyncs and state priority; stale events from older clients or
connections being ignored; the SetActiveSession retries and the throttle
resync per connection and per long-poll loop; dropped clients failing locally
(checked against a test server); an unwanted long poll being closed; Unpair
refreshing an expired token first; ForceRCS from tombstones, pages and seeds.

**Kotlin** (`./gradlew :app:testDebugUnitTest`; 253 tests in 19 classes;
Robolectric on real SQLite with FTS4; needs Go and a host C++ toolchain, see
the README):

- `RelayTestEnv` gives each test a fresh archive, a software AES key for
  `SessionVault`, cleared preferences and logs, short timings, and sets
  `RelayBridges.factory` to a `FakeRelayBridge`.
- `FakeRelayBridge` answers calls from what the test scripted (conversations,
  pages with cursors, media, send results that may throw), records every call,
  and pushes events into the manager's sink as the Go dispatcher would (`emit`),
  including Unpair's late `unpaired` from its own thread. `duringSend` delivers
  the echo before the send returns, as the phone often does.
- Suites: `RelayManagerTest` (pairing, states, session storage, logs, stickers,
  unpair, archive owner, catch-up, seeding), `OutboxTest`, `BackfillTest`,
  `RelayServiceTest` (service, notification visibility, alarms),
  `RelayPrefsTest`, `ArchiveRepositoryTest`, `SessionVaultTest`, `DtoTest`,
  `CookieParserTest`, `StickerCaptureTest`, `InboxStoreDedupeTest`,
  `ScreensSmokeTest` (each screen reaches RESUMED with seeded data),
  `ChatHelpersTest`, `RelayUiTest`, and `LinkFixesTest`, `LinkFollowUpTest`,
  `LinkRound2Test` (screen behaviour: notifications off, unpair, kept archive,
  "Connect to load", licences, thread switching, the viewer's sources),
  besides the decoder's `HeifCodecTest` and `ImageSnifferTest`.
  `RelayManagerTest` also asserts that the activity log never contains the
  emoji, the account, message text or cookie values.

**Not tested automatically:**

- anything against Google's real servers: sign-in, pairing, connecting, token
  refresh, sending, unpairing, and the request shapes for archive and mute
  (which the connector never uses, so they are a best reading of libgm);
- `GoRelayBridge` itself and the generated JNI glue (compiled, not exercised);
- the real Android Keystore;
- foreground-service behaviour on real devices: background-start refusals,
  Android 14+ service types, Doze and the wake-up alarm, OEM battery managers,
  restarts after the process is killed;
- the sign-in WebView against Google's real pages;
- the release (R8) APK at runtime (it builds, and CI checks it statically; it
  has not been run);
- the UI beyond the Robolectric screen tests (there are no instrumentation
  tests).

**Manual checks on a device** after a libgm bump or a change to the runtime:
pair with the emoji (and try a wrong one); the phone lists "LightDecoder"; the
notification shows with Disconnect; turn the app's notifications off and see
the link go down and say so; receive and send text and a picture; react, reply,
mark read; send while in airplane mode and watch it go out after reconnecting;
receive messages while disconnected and see them appear after reconnecting;
force-stop the app with a send queued; open messages.google.com in a browser
and use "Use here"; remove the device on the phone and see the app stop and ask
to pair again; Unpair and check the device is gone from the phone, and Unpair
with the phone offline to see the "remove it on your phone" dialog; pair a
different phone over a kept archive and see the archive deleted; receive a
HEIC sticker and find it in the sticker inbox; check the activity log for
anything that is not a kind or outcome.

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
  may stay parked for good. Every client that ever connected keeps libgm's
  acknowledgement-ticker goroutine; a dropped client's can no longer reach the
  network, but the goroutine stays.
- After Disconnect, a long-poll loop that was asleep in libgm's error backoff
  keeps its pinger until the sleep ends (about one ping a minute); a long poll
  it reopens is closed again at once.
- Unpair cannot flush libgm's pending acknowledgements first (libgm has no
  exported way). If the same account is paired again, a few old events may be
  delivered again; they are stored by id, so this is harmless.
- A panic in libgm's own background goroutines still crashes the app; only
  calls into the bridge are protected.
- libgm updates token fields without a lock; only the cookie map, the fatal
  case, is protected when saving.
- Only the `SetActiveSession` half of the connector's "SetActiveSession may have
  failed" workaround is ported, not its later client reset, and the
  connector's optional receive-stall recovery (re-arming the session and
  reconnecting while no data arrives) is not ported. After a data gap the
  bridge asks for a catch-up once data flows again or the session becomes
  active.
- ForceRCS follows the newest protocol-switch tombstone the bridge has seen.
  A chat whose switch is neither in the archive nor on a page fetched since
  pairing is sent without it until one shows up. If the archive is deleted
  for another phone after the seed was handed over (a connection made while
  the phone id was not known yet), Go keeps that seed until the next pairing
  or Unpair; it only matters for a chat with the same id.

Connection and Android:

- Doze and battery optimisation are not handled beyond the alarm-backed
  reconnect and a link to the battery settings on the status screen. Android
  limits alarms allowed while idle to roughly one per app every nine minutes.
- The service can be refused a foreground start (Android 12+ background
  limits, or a restart after the process was killed). The link then comes back
  when the user next opens one of the app's screens: always after a start the
  user asked for, otherwise only with "start automatically" on.
- On Android 8 the system does not report notifications being turned off, so
  a connected link only notices when a screen of the app is next shown or the
  link connects again.
- `Disconnect` waits behind a `Connect` that is still fetching config or
  refreshing its token (the service and "stay connected" stop at once).
- Tapping Disconnect in the notification while an unpair is running stops the
  service while Go's temporary revoke connection may still be up (the revoke
  itself is limited to 15 s). An unpair can take up to 10 s longer than the
  revoke while the manager waits for Go's verdict.
- If a connection came up while the phone id was not known and the initial
  sync's deletion of another phone's archive then fails, live events of that
  connection still land in the old archive (sends stay blocked).
- Go's SIM cache is not persisted, so after a restart the outbox waits for the
  first `settings` event or 15 s. On a dual-SIM phone a send in that window can
  go from the phone's default SIM.
- `settings` is null until the first settings event of each run.
- The health table's "last session refresh" counts every session save, not
  only token refreshes.

Sending:

- The thread shows every failed send as "Not sent. Tap to try again." and
  retries at once. For `interrupted`, `unconfirmed` and `phone_not_responding`
  failures the message may in fact have been delivered, so a retry can send it
  twice; there is no separate label and no confirmation yet.
- A forward catch-up fetches at most 10 pages (500 messages) per conversation;
  anything further back in a gap is left to "load older" and the import.
- Media sends hold the whole attachment (up to 100 MB) in memory.
- If a send succeeds at the moment Unpair holds it, the local copy shows FAILED
  while the queue entry is SENT; retry does nothing, discard works.

Archive and UI:

- The archive is not encrypted by the app beyond Android's storage encryption.
- Conversations the phone deletes or trashes are deleted locally, without a
  way back.
- Mute is set on the phone and stored here; the phone never reports mute
  state, so a change made on the phone is not seen.
- The check for unreferenced media scans the media table once per file (its
  paths have no index), which is slow with very many attachments. A download
  finishing exactly while unreferenced files are cleaned up can lose its new
  file; it is downloaded again when next shown.
- No "Not spam" action. The reply target is not restored after rotation.
  Links inside a bubble cannot be activated with TalkBack.
- If the link comes online exactly while the thread screen switches to
  another conversation, the new thread can be paged from the phone before
  its stored messages are read (harmless, but one extra request).
- The contact picker's "offline" hint only shows when no contacts are stored,
  because `listContacts` falls back to the stored list quietly.
