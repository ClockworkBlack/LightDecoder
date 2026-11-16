# Getting incoming stickers and messages into LightDecoder

This records how LightDecoder receives stickers (and, since 0.3.0, messages),
which approaches exist, which ones the project uses, and why. It replaces the
0.2 version of this record, which said Pathway 2 was deliberately not built;
"What changed in 0.3.0" below explains the reversal and what did not change.

## The problem

iPhone stickers arrive on Android as HEIC. Android's HEIF support is optional
per device, and sticker files add an alpha auxiliary image (transparency) and
sometimes an image sequence (animation) that many phones' decoders choke on. So
the sticker shows up as a broken image. LightDecoder's own decoder fixes the
*display*; this doc is about *receiving* stickers automatically.

## The five pathways

1. **On-device notification listener** (`NotificationListenerService` +
   `MessagingStyle` + `getDataUri()`). Official, Play-Store-compliant, no
   protocol emulation, no account access. Reads a picture attachment from a
   chat notification after the user grants Notification access. Captures from
   the moment a message arrives (no history); cannot read content the OS marks
   restricted (some E2EE RCS).

2. **Consented web relay** (`libgm` / mautrix-gmessages). Pairs as a "Messages
   for web" client to the user's own Google Messages, then stays connected and
   receives what the phone relays to its web client. Pairing is by
   Google-account (Gaia) emoji confirmation; the older QR pairing is retired
   for new logins. Full history and two-way messaging, but it emulates
   Google's private protocol (against Google's Terms of Service), breaks when
   that protocol changes, and is AGPL-3.0.

3. **Temporary default-SMS-app role swap.** Briefly take `ROLE_SMS` to read
   restricted E2EE RCS rows from the Telephony DB during a one-time import.
   Disables Google Messages while held; onboarding-only.

4. **Android Enterprise RCS Archival** (`EXTRA_ARCHIVAL_URI`). For
   fully-managed work devices only; needs an IT-configured managed
   configuration. Not applicable to a consumer app.

5. **Cloud RCS Business Messaging** (Google RBM / Twilio). Application-to-person
   only — a branded sender, not the user's own number. Not a personal inbox.

## What LightDecoder uses

**Pathway 1** remains the default and is unchanged. It is the front door:
official API, the user's own explicit Notification-access grant, nothing
emulated, nothing uploaded, no credentials touched. Its limits (no history;
some E2EE RCS not visible) are accepted for anyone who uses only it.

**Pathway 2** is built since 0.3.0, as an **opt-in second source**. It is off
until the user goes through its setup and confirms the pairing on their phone.
The two are independent: the listener needs no pairing, and the link needs no
Notification access. Both hand pictures to the same capture step
(`capture/StickerCapture.kt`), which keeps a picture caught by both once, by
the digest of its bytes.

Pathways 3–5 still do not fit a consumer sticker inbox (role swap disables
Messages; Archival needs device management; RBM is not the user's own number).

## What changed in 0.3.0, and what did not

The 0.2 record turned Pathway 2 down for two reasons: the shape it usually
takes is a covert-interception tool, and it is fragile. The project then
decided to make LightDecoder a local-first Google Messages client as well as a
sticker decoder, which only Pathway 2 can do. The fragility is accepted as a
cost (below). The first reason is answered by building it so that it cannot
take that shape.

The hard rule from 0.2 still holds, whoever owns the account: **the initial,
user-present pairing confirmation is never automated or bypassed, and account
cookies are never captured outside that ceremony.** A "pair once, drop the
confirmation, mirror in the background" design is still out of scope.

## The safeguards

Each one is enforced in code, not just in the UI copy.

- **User-present confirmation, never automated.** Only Google-account emoji
  pairing is offered. The app shows the emoji; the user taps the matching one
  in Google Messages on their phone. `FinishPairing` in `gmbridge/pairing.go`
  only waits for the phone's answer; nothing in the app predicts, answers or
  skips it. A wrong emoji, a timeout or a cancel ends the attempt. When Google
  signs the link out, the app stops and asks the user to pair again in person;
  it never re-pairs by itself.
- **Cookies only from the user.** They come from exactly two places: the user
  signing in to Google in the app's own sign-in WebView (which loads only
  Google's own hosts, and whose cookies and storage are wiped before and
  after), or cookies the user pastes from their own browser. The app does not
  read cookies or accounts from other apps, from browsers, or from Android's
  account manager. Once read, cookies are wiped from the WebView, held in
  memory until pairing ends, and from then on stored only inside the encrypted
  session. They are never logged.
- **Visible.** The app only connects while its foreground service runs, and
  that service always shows a notification with what the link is doing and a
  one-tap Disconnect. On Android 13+ notification permission is asked for
  before the link goes live, so that notification is not hidden. The paired
  session also shows on the phone, in Google Messages under Device pairing.
- **Revocable.** Disconnect (keeps the pairing) is offered in the
  notification and the app whenever the link is up or trying to connect.
  Unpair (revokes the pairing on the phone, deletes the session, and
  optionally the local archive) is offered whenever a pairing exists, and
  still after Google has signed the link out or rejected its cookies.
  Removing the session on the phone ends it too.
- **Local-only.** No LightDecoder servers, no analytics, no crash reporting. The
  session is encrypted at rest with an Android Keystore AES-256-GCM key. The
  message archive is app-private SQLite. Backup and device-to-device transfer
  exclude everything the app stores.
- **No covert mode.** There is no setting that hides the notification, connects
  without the service, pairs without the emoji step, exports the session, or
  lets another app drive the link. The link's service, screens and reply
  receiver are not exported; its one exported receiver reacts only to the
  system's boot and app-update broadcasts, and then only starts the visible
  service if the user chose to stay connected.
- **Content-free logs.** The link's activity log and the Go side record kinds
  and outcomes only: never message text, names, numbers, the pairing emoji,
  cookies or keys. libgm's own logging is turned off, because at its detailed
  levels it logs decrypted data.

## Trade-offs

What Pathway 2 gives that Pathway 1 cannot: message history, two-way messaging
(send, reply, react, read, typing), search over a local archive, and pictures
the phone relays to its web client, including ones that never show up in a
notification the listener can read.

What it costs:

- **Terms of Service.** It emulates Messages for web over Google's private,
  undocumented protocol. That is against Google's Terms of Service; Google can
  block it, or act on the account used. Setup says so before the user starts.
- **Fragility and upkeep.** It breaks when Google changes the protocol, until
  libgm is updated and the app rebuilt. Keeping libgm current is now ongoing
  work (`docs/google-messages-link.md`, "Bumping libgm").
- **Distribution.** Pathway 1 alone is Play-Store-compliant. With Pathway 2 in
  the app, that can no longer be assumed.
- **Licence.** libgm is AGPL-3.0 and is now actually linked, so the app is
  AGPL-3.0-or-later (it already was, in anticipation) and its source stays
  public. Attribution is in `NOTICE`.
- **Permissions and size.** The app now needs `INTERNET` (0.2 had no network
  permission at all), foreground-service and boot permissions. The Go library
  adds about 10 MB per CPU type, about 30 MB to an APK carrying three.
- **Battery and dependence on the phone.** While connected, the app keeps a
  long-poll connection to Google open and the phone answers a ping about once
  a minute. The phone has to be on and online, and only one web session can be
  active at a time.
- **Sign-in friction.** Google may refuse sign-in inside an embedded WebView;
  the fallback is pasting cookies, which is less friendly.

## Background and citations

This decision record summarizes a longer analysis the project was given,
*"Navigating Android RCS Restrictions: Technical Architectures and Legal
Pathways"*, which itself cites the Android developer references for
`NotificationListenerService`, `Notification.MessagingStyle.Message`, and
`RemoteInput`, the mautrix-gmessages `libgm` library, and others. The key
technical correction it makes is that `NotificationListenerService` **is** an
official third-party path to incoming RCS message media — which is Pathway 1
above.

The Pathway 2 build in 0.3.0 follows a second document the project was given,
*"Technical and Architectural Blueprint for Option B: The Consented Local
Google Messages RCS Bridge and Android Messaging Ecosystem"*, specifically its
"Architecture 3A" (libgm compiled with gomobile into an Android library). It
uses libgm from `go.mau.fi/mautrix-gmessages` v0.2610.0 and copies the pairing,
connection and sending logic of that project's own consumer of libgm, its
Matrix bridge (`pkg/connector`).
