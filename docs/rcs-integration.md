# Getting incoming stickers into LightDecoder

This records how LightDecoder receives stickers from messages, which approaches
exist, and why this project uses one and not the others.

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
   for web" client to the user's own Google Messages via QR or Google-account
   (Gaia) pairing, then syncs in the background. Full history and two-way RCS,
   but it emulates Google's private protocol (against Google's Terms of
   Service), breaks when that protocol changes, and is AGPL-3.0.

3. **Temporary default-SMS-app role swap.** Briefly take `ROLE_SMS` to read
   restricted E2EE RCS rows from the Telephony DB during a one-time import.
   Disables Google Messages while held; onboarding-only.

4. **Android Enterprise RCS Archival** (`EXTRA_ARCHIVAL_URI`). For
   fully-managed work devices only; needs an IT-configured managed
   configuration. Not applicable to a consumer app.

5. **Cloud RCS Business Messaging** (Google RBM / Twilio). Application-to-person
   only — a branded sender, not the user's own number. Not a personal inbox.

## What LightDecoder uses, and the boundary

LightDecoder uses **Pathway 1**. It is the front door: official API, the user's
own explicit Notification-access grant, nothing emulated, nothing uploaded, no
credentials touched. The trade-off (no history; some E2EE RCS not visible) is
accepted.

Pathway 2 is **deliberately not built.** The hard rule — one that holds whoever
owns the account — is that the initial, user-present pairing confirmation must
never be automated or bypassed, and account cookies must never be captured
without that ceremony. A "pair once, drop the confirmation, mirror in the
background" design is the shape of a covert-interception / stalkerware tool,
and Pathway 2's reliance on reverse-engineering a private protocol makes it
fragile besides. Pathway 1 delivers the stickers without any of that.

Pathways 3–5 do not fit a consumer sticker inbox (role swap disables Messages;
Archival needs device management; RBM is not the user's own number).

## Background and citations

This decision record summarizes a longer analysis the project was given,
*"Navigating Android RCS Restrictions: Technical Architectures and Legal
Pathways"*, which itself cites the Android developer references for
`NotificationListenerService`, `Notification.MessagingStyle.Message`, and
`RemoteInput`, the mautrix-gmessages `libgm` library, and others. The key
technical correction it makes is that `NotificationListenerService` **is** an
official third-party path to incoming RCS message media — which is Pathway 1
above.
