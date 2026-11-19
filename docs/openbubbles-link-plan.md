# Plan: a modified OpenBubbles with a LightDecoder link

Status: **on hold** (the user asked on 2026-11-19 to come back to it later). Nothing here has been
started. LightDecoder 0.5.39 works with the Play Store OpenBubbles through its notifications; this
plan replaces that with a direct link, while keeping the notification path as the fallback.

## Why

The Play Store OpenBubbles (source: github.com/OpenBubbles/openbubbles-app, 1.15.0, checked at
commit eed1b63) takes no commands from other apps:

- Nothing exported accepts send, unsend or edit. The notification buttons go to
  `services.intents.InternalIntentReceiver`, which is not exported.
- Its only outside interface, `IMadridExtension` (AIDL, action
  `com.openbubbles.messaging.MadridExtensionService`), is for iMessage app extensions like
  GamePigeon. OpenBubbles binds to *them*, and an extension can only update messages it made.
- Unsend and edit live in Dart: `lib/services/rustpush/rustpush_service.dart`, `unsend(Message,
  MessagePart)` (around line 1183) and `edit(...)`. They are reached only from its own message
  popup (`message_popup.dart`).

So what LightDecoder can do today is limited by what OpenBubbles' notifications show:
- no real unsend or edit (it opens OpenBubbles instead);
- no messages while OpenBubbles is open on screen, and no history;
- no reactions, and no delivered or read ticks;
- sending to a new person needs a tap in OpenBubbles.

The link removes all of that.

## What it unlocks in LightDecoder

- Unsend (Apple's 2-minute window) and edit (15 minutes) straight from the long-press menu.
- Silent sending to anyone, including new chats by number or email, with pictures and voice
  messages and no tap in OpenBubbles.
- Every message, also while OpenBubbles is open, plus history, with OpenBubbles' real message ids
  in place of LightDecoder's `ob:` hashes.
- Tapbacks, delivered and read ticks, and the other person's edits and unsends.
- Reliable linking to Messages conversations: the link reports each chat's handles (number or
  email).

## Goal: full sync (the user's requirement)

LightDecoder and OpenBubbles show the **same** iMessages, whichever app is on screen. Done means
all of the following is true:

| What | Today (notifications) | With the link |
| --- | --- | --- |
| Message arriving while its chat is **open in OpenBubbles** | missed (OpenBubbles posts no notification: `MessageHelper.handleNotification` returns when `cm.isChatActive`) | `MESSAGE` event from where it is saved, whatever is on screen |
| Message arriving while OpenBubbles' **chat list** is on screen | missed unless OpenBubbles' "Send Notifications on Chat List" is on | event |
| Messages **sent in OpenBubbles** (or on the user's other Apple devices) | missed (OpenBubbles never notifies its own) | event (`isFromMe`) |
| Messages sent from LightDecoder | shown in OpenBubbles (sent through it) | same, and the result carries the real guid |
| **History** and messages from while LightDecoder was not running | missed | `HISTORY` catch-up: on link start, and after any gap, ask for each chat since the newest guid/time LightDecoder has |
| **Read** | LightDecoder → OpenBubbles only (Mark As Read button) | both ways: `MARK_READ` in, `dateRead` / unread changes out |
| **Edits, unsends, reactions** (both people) | none | events; LightDecoder updates or removes the bubble |
| Delivered / read ticks on the user's own messages | none | `dateDelivered`, `dateRead` |
| Pictures, voice messages, files | text only (pictures through OpenBubbles' screen) | attachments both ways (content uris granted per message) |
| Hidden text (OpenBubbles' "Hide Message Text") | unusable (detected and flagged since 0.5.40) | not involved: events carry the real text |
| Deleting a chat or message | here only | optional: mirror deletes (ask the user first; the default is here only) |

Rules for the sync:
- **Ids:** OpenBubbles' message guid is the id in LightDecoder's archive, so an event that arrives
  twice, or a history page that overlaps, is stored once. On first link, existing `ob:` rows are
  matched to guids by chat, text and time (within the 3 s echo window) and re-keyed.
- **Order of truth:** OpenBubbles is the source of truth for iMessages. LightDecoder never invents
  a state; local-only deletes stay local.
- **Catch-up:** the link keeps a per-chat cursor (newest guid and time). `PING` returns
  OpenBubbles' newest message time, so LightDecoder knows whether to ask for `HISTORY`.
- **Tests:** the emulator test gets one case per row of the table, driven by the extended
  `:fakebubbles`.

## Phase 0: gates (do these first; stop if one fails)

1. **Can a source build register?** The public CI (`.github/workflows/build.yml`) copies
   placeholder FairPlay certificates (`rustpush/certs/legacy-fairplay/*`) into
   `rustpush/certs/fairplay/`. The Play Store build has Apple's real ones, which are not public.
   FairPlay is used in activation (the earlier "Failed to parse Albert Cert" / "Token not found"
   was activation failing). Build the stock **beta** flavor (`com.bluebubbles.messaging.beta`,
   which LightDecoder already recognises), then try registering it through Validation Relay+
   (Report as Mac):
   - on a spare Android phone, or after backing up and uninstalling the Play Store one;
   - never with two installs registered on the same number at once.

   If activation fails with the placeholder certificates, **stop**. Do not pull Apple's keys out
   of the Play Store APK. Instead, ask the OpenBubbles developers for an official link (see the
   alternative at the end).
2. **Where it builds:** GitHub Actions `ubuntu-latest` works for this account (the LightDecoder
   device test runs there); macOS runners do not start. Copy OpenBubbles' recipe:
   - Flutter **3.24.0** stable, Rust stable plus the Android targets, Java **21** (temurin), the
     Android SDK and `protobuf-compiler`;
   - `flutter build apk --flavor beta --release --target-platform android-arm64`;
   - submodules `rustpush` and `telephony_plus`, fetched over https, since `.gitmodules` uses
     `git@`.

   This container can't easily host a Flutter SDK, and the session's GitHub access is limited to
   ClockworkBlack/LightDecoder. So either:
   - **(a)** the user creates `ClockworkBlack/openbubbles-link`, a fork of openbubbles-app, and
     adds it to the session; or
   - **(b)** keep the fork's patch in this repo (`tools/openbubbles-link/`) and have a workflow
     here check out upstream at a pinned commit, apply the patch and build.

   Prefer (b): small, reviewable, and easy to rebase.
3. **Signing:** sign the fork with the **LightDecoder release key** (the same p12). That allows a
   `signature`-level permission between the two apps (see below), with no shared secrets. It also
   means the Play Store never updates it, which is wanted. The key itself never goes into the repo,
   as now.

## Phase 1: the link inside OpenBubbles (the fork's whole diff)

Keep it to new files plus a few hook lines, so it rebases onto upstream releases easily.

**Permission.** Declare in the fork's manifest:

```xml
<permission android:name="com.lightdecoder.permission.OPENBUBBLES_LINK"
    android:protectionLevel="signature" />
```

LightDecoder `uses-permission`s it. Because both apps are signed with the same key, Android grants
it to LightDecoder only.

**Commands in (LightDecoder → OpenBubbles).** Add `services/lightdecoder/LinkReceiver.kt`,
exported and guarded by that permission (`android:permission=...`). Each intent carries a
`requestId` and JSON extras:

| Action | Data |
| --- | --- |
| `SEND` | `chatGuid` or `address`, `text`, attachments as content uris (granted), `replyToGuid` |
| `UNSEND` | `messageGuid`, `part` |
| `EDIT` | `messageGuid`, `part`, `text` |
| `REACT` | `messageGuid`, `kind`, `remove` |
| `MARK_READ` | `chatGuid` |
| `CHATS` | (none) |
| `HISTORY` | `chatGuid`, `beforeMs`, `limit` |
| `PING` | (none): returns the version and whether OpenBubbles is registered |

The receiver hands each command to Dart the way `ReplyChat` does now:

```kotlin
DartWorkManager.createWorker(context, "LD_UNSEND", hashMapOf(...)) { }
```

In Dart, `lib/services/backend/java_dart_interop/method_channel_service.dart` gets new cases next
to `"ReplyChat"` (about line 284):
- **`LD_SEND`:** queue the message like `ReplyChat` does; with an `address` and no chat, create
  the chat first, the way OpenBubbles' new-chat screen does.
- **`LD_UNSEND` / `LD_EDIT`:** `Message.findOne(guid:)`, then `backend.unsend(message,
  part)` / `backend.edit(...)` (rustpush_service).
- **`LD_REACT`:** the existing tapback send.
- **`LD_MARK_READ`:** `chat.toggleHasUnread(false)`.
- **`LD_CHATS` / `LD_HISTORY`:** query the ObjectBox database.

**Events out (OpenBubbles → LightDecoder).** Add `LinkEvents.kt`: broadcasts with
`setPackage("com.lightdecoder")` and `receiverPermission` set to the same permission:
- `RESULT` (`requestId`, `ok`, `error`, `messageGuid`);
- `MESSAGE` (`guid`, `chatGuid`, the chat's handles, `isFromMe`, `text`, attachments as granted
  content uris with their types, `dateCreated`, `dateDelivered`, `dateRead`, `edited`, `unsent`,
  reactions);
- `CHAT` (`guid`, display name, handles, is it a group).

Dart calls these through the existing method channel, from where new and updated messages are saved
(`ActionHandler` / `pushService.reflectMessageDyn` / the incoming path). So LightDecoder hears every
message, whatever is on screen.

**Battery.** Commands arrive as broadcasts. OpenBubbles' APN service already runs, so no new
always-on service is needed.

## Phase 2: LightDecoder

- **`imessage/OpenBubblesLink.kt`:** detects the fork (package installed, permission granted, `PING`
  answered); sends commands; receives events in a manifest receiver guarded by the permission; and
  matches results to requests by `requestId`, with timeouts.
- **`IMessageManager`:** when the link is up, use it for everything above. Store OpenBubbles'
  message guid as the message id, and import history on first link. Keep the notification path as
  the fallback when the fork is not installed (as now).
- **UI:**
  - Unsend and Edit in the long-press menu actually do it.
  - Reactions are enabled for iMessages.
  - Delivered and read ticks show.
  - The iMessage light gets a fourth meaning: "Linked to OpenBubbles" (blue with a ring).
  - The problem report shows the link's version and state, and the iMessage log records each
    command and result (content-free, as now).
- **Tests:**
  - Extend `:fakebubbles` to also act as the fork: answer commands, emit events.
  - Robolectric tests for the protocol.
  - The emulator test (`device-test.yml`) covers send, unsend, edit and incoming while open.

## Phase 3: installing it on the user's phone (one time)

1. In the Play Store OpenBubbles, note the settings. If it offers iCloud Messages sync, make sure
   it is on, so history comes back.
2. Uninstall the Play Store OpenBubbles. Its on-phone chat history goes with it.
3. Install the fork (`OpenBubbles-link-<version>.apk`, signed with the LightDecoder key).
4. Set it up again: Apple ID or phone number through **Validation Relay+** with **Report as Mac**,
   using a new registration code from the relay iPhone, which stays on and connected. Then turn
   notifications on for it.
5. Open LightDecoder: the light shows "Linked", and a Check iMessage confirms the colour on a real
   iPhone.

## Phase 4: keeping it up to date

- Watch upstream OpenBubbles releases. Rebase the patch, build in CI and hand the APK over with
  LightDecoder's, since the user installs both.
- If an upstream change breaks a hook, the fork still works as a plain OpenBubbles, and LightDecoder
  falls back to notifications.

## Risks

- **FairPlay / activation (Phase 0.1):** the main risk; it may end the plan.
- **Apple may flag** an account or number that registers many times: register once, and keep the
  relay iPhone stable.
- **Licence:** OpenBubbles is GPL-3. A personal fork is fine; anything given to others must come
  with its source.
- **Effort:** Phase 0 is one session. Phases 1–2 are about 3–5 sessions (Flutter/Dart plus Kotlin
  on both sides, and tests). Then the re-registration on the phone.

## The alternative that changes nothing on the phone

Ask OpenBubbles' developers to add this link (or any exported, permission-guarded API) upstream.
The Phase 1 design above is the proposal. With an official link, LightDecoder's Phase 2 works with
the Play Store app, and no fork, re-registration or FairPlay question arises.
