# The Discord tab

The conversation list has a Discord tab beside Messages and WhatsApp. It is
Discord's own website (`https://discord.com/app`) shown in an Android `WebView`,
with the official Discord app as the fallback. This records what it does, what
it refuses to do, how it is kept apart from the other tabs, and why.

## Where it sits

There is no approved way to build a full Discord client into a third-party app
under your own account. The options, from safest to least safe:

1. **Open the official Discord app** through a normal
   `https://discord.com/channels/...` link. Ordinary use; no policy question.
2. **Show Discord's website in a web view, unmodified.** This tab. Discord's
   terms neither authorize nor forbid it, Discord says mobile browsers are not
   supported, and sign-in or CAPTCHA may not finish. Grey area.
3. **User tokens, self-bots, modified clients, reverse-engineered gateways.**
   Explicitly prohibited by Discord and grounds for account termination. Not
   built. Unlike the Messages and WhatsApp links, there is no Discord bridge
   and no Discord protocol code in this app.

The tab is option 2 with option 1 one tap away: "Open Discord app" is in the
menu, in the sign-in banner, and on the error panel.

This is general information, not legal advice.

## The container

Each tab keeps to its own storage. Messages, WhatsApp and Gmail each have their
own session file, archive database, media folders and preferences
(`ChatService`). They share the app's main process.

The Discord tab is a web page, and a web page's cookies and site storage live in
the WebView's data folder, which Android keeps one of per process. In the main
process that folder is also used by the Google sign-in page of the Messages
link, and `SignInWebData` deletes the whole folder after every sign-in. So
Discord gets a container of its own (`DiscordContainer`, `DiscordProcess`):

- **Its own process.** `DiscordActivity` is declared with
  `android:process=":discord"`. Nothing else is.
- **Its own WebView data folder.** The first thing `LightDecoderApp.onCreate`
  does in that process is `WebView.setDataDirectorySuffix("discord")`, so its
  cookies and storage are in `app_webview_discord`, not `app_webview`. The main
  process's sign-in wipe never touches it, and Discord's page never sees a
  Google cookie.
- **None of the other tabs' runtimes.** `LightDecoderApp.onCreate` returns
  right after that in the Discord process: no Messages or WhatsApp manager, no
  archive, no notification channel is created there. `DiscordActivity` uses the
  other tabs' names only, to draw the tab strip and hand back to the list.
- **Fails closed.** If the data folder could not be set, `DiscordActivity`
  closes before creating a WebView. Before Android 9 a per-process folder does
  not exist, so the tab is not shown at all.

What the container is not: a separate Android app or user. Both processes run
under LightDecoder's one app identity and can read the same private files; the
separation is that the Discord process loads none of the code that does, and a
web page cannot reach files (`allowFileAccess` is off). The Messages and
WhatsApp links are separate from each other by storage, not by process.

One side effect: while the Discord tab is in front, the main process sees no
LightDecoder screen. WhatsApp stops showing you as online, and "read"
notifications behave as they do when the app is closed.

## The boundary

`DiscordActivity` shows the page and does nothing to it:

- No `evaluateJavascript`, no `addJavascriptInterface`, no injected CSS or
  script. Discord's Platform Manipulation Policy forbids modifying the client,
  including its appearance.
- No `shouldInterceptRequest`. Requests and responses are never read or
  rewritten.
- No reads of cookies, `localStorage`, or the session token.
- No user-agent override (see below).
- No automatic input. The person signs in, solves any CAPTCHA, and types every
  message.
- Only `https://discord.com` and its subdomains load on the tab. Other links
  open in the browser; other schemes are dropped.
- The activity is not exported, so nothing outside the app can start it or hand
  it a URL.
- Camera and microphone requests from the page are denied. Calls belong in the
  Discord app.

The app reads the page address, which `WebView` reports to every host app.
`DiscordSession` uses it to tell the sign-in page from a conversation, and
never keeps or shows the query string. The one other thing it reads is below.

## Page size, and folding phones

`DiscordFit` decides how wide the page is laid out (CSS pixels); the page view
is given that width and scaled to fill the screen (`DiscordQr.wide`), as a
browser's zoom does. Larger sizes use `WebSettings.textZoom` instead. Nothing
in the page is changed. The choice ("Page size" in the menu) is kept
separately for narrow screens (under 600 dp: a phone, a folded Fold) and wide
ones (an unfolded Fold, a tablet). On Automatic a narrow screen shows the
page at its natural size, and a wide one lays it out at 940 px or more, where
Discord shows servers, channels and the conversation side by side (940 is
Discord's desktop app's minimum window; that its website switches layout
there is not verified on a device).

Folding and unfolding are handled in place (`configChanges` includes
`screenSize`, `smallestScreenSize`, `screenLayout` and `density`), so the page
is fitted again without being reloaded.

The top bar is one 48 dp row: back, the tab strip, the tab list arrow, the
menu. It shows no title and no address line; only discord.com loads here.

## Signing in with a QR code (and why not a passkey)

**Passkeys cannot work inside the tab.** Android lets a `WebView` use passkeys
for a site only if the app is tied to that site with Digital Asset Links
(Discord's site, not ours), or if the app is a browser on Google's privileged
list. LightDecoder is neither, so Discord's "Sign in with a passkey" fails here.
The way round it is the QR code: sign in to the official Discord app with the
passkey, and approve the tab from there.

**"Sign in with QR code"** (sign-in banner and menu, `DiscordQr`):

- Discord's sign-in page shows its QR code only when the page is wide, as on a
  computer. The tab gives the page view a computer-size window
  (`DiscordQr.DESKTOP_WIDTH_CSS`, 1100 CSS px) and scales the view down to fit
  the screen (`View.scaleX/Y`; Android maps touches through the scale). The page
  is not changed: no script, no user agent, no viewport override.
- Every 1.5 s, `PixelCopy` copies what the page view shows on screen and zxing
  looks for a QR code in it, as a camera pointed at the screen would. Only a
  code whose content is exactly `https://discord.com/ra/<code>` is taken
  (Discord's remote-auth link, valid two minutes; format per
  docs.discord.food/remote-authentication and Discord's QR Code Login FAQ).
- "Approve in the Discord app" opens that link with the official app
  (`setPackage("com.discord")`), nothing else. The person taps Log in there. It
  is not verified that the Discord app acts on the link when opened this way
  rather than scanned; if it does not, the panel shows the code large for the
  Discord app on another device (Settings > Scan QR Code).
- When the page reaches a conversation (signed in), the tab goes back to the
  phone-size window by itself.

Nothing from Discord reaches the rest of the app: no messages are archived, no
notifications are posted for it, and it has no unread badge.

## User agent

Android's `WebView` identifies itself with a `wv` token in its user agent.
Advice in circulation says to strip that token so Cloudflare and hCaptcha
"don't flag the WebView". LightDecoder does not, for two reasons:

- Disguising the web view as Chrome to get past bot detection is the opposite
  of an unmodified session. It is the one step in that advice that looks like
  evading a protective measure.
- Cloudflare's own guidance for web views is to keep the user agent at its
  default and consistent for the whole session.

If sign-in loops on a CAPTCHA with the real user agent, the answer is the
"Open Discord app" button.

## What was kept and dropped from the advice reviewed

| Advice | Verdict | In the code |
|---|---|---|
| Enable JavaScript and DOM storage | Accurate; DOM storage is off by default | Yes |
| Enable third-party cookies for the CAPTCHA frames | Cloudflare recommends it on Android; hCaptcha's docs do not say, so that part is unverified | Yes, for this WebView only |
| `CookieManager.flush()` when the screen is left | Accurate | Yes, in `onPause` |
| Tell signed-in from signed-out by the address, not the page | Sound; `doUpdateVisitedHistory` is needed because Discord is a single-page app | Yes |
| Strip `; wv` from the user agent | Rejected, see above | No |
| `databaseEnabled = true` is required | Outdated: it controls WebSQL and was deprecated in API 35 | No |
| Turn on passkeys with `WebSettingsCompat` | Does not apply: app mode needs Digital Asset Links with a site you own, browser mode needs Google's privileged-app listing | No; QR sign-in instead |
| Discord moves its token in and out of `localStorage` | Unverified, and irrelevant because storage is never touched | n/a |
| "100% legal and compliant" | Not supportable; see "Where it sits" | n/a |

## Before publishing on Google Play

Play's spam policy restricts apps that mainly wrap someone else's website
without permission. A Discord tab inside a messaging app is a different shape,
but read the current "Webviews" wording before a store release. Discord's name
is used here only as a label, with no logo.

## Tests, and what is not verified

`DiscordSessionTest` (the address logic), `DiscordContainerTest` (which process
is the container) and `DiscordManifestTest` (the manifest puts the Discord
screen, and only it, in that process) are plain JVM tests and pass.

`DiscordActivity`, `DiscordProcess`, `DiscordLinks`, the layout and the tab in
`ConversationsActivity` were written without an Android SDK at hand: they have
not been compiled, and nothing has run on a phone. In particular, check on a
device that signing in to Google for the Messages link leaves the Discord
sign-in in place, and the other way round.
