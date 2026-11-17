# The Messenger tab

The app switcher lists Messenger after Messages and Discord, right before
WhatsApp. It is two things that do not touch each other:

1. **An inbox built from the Messenger app's own notifications**, with replies
   sent through that notification's Reply button.
2. **Facebook's own web messaging page** in a web view of its own ("Full
   history / New chat"), for everything a notification cannot carry.

There is no Messenger bridge, no Messenger protocol code and no Messenger
sign-in inside LightDecoder's own code. This records what the tab does, what
it refuses to do, the fact-check of the plan it was built from, and what has
not been tried on a phone.

This is general information, not legal advice.

## Where it sits

The ways a third-party app can show someone their Messenger conversations,
from safest to least safe:

1. **Open the official Messenger app.** Ordinary use.
2. **Read the notifications Messenger posts on the phone**, with the user's
   notification-access grant, and answer through the notification's own Reply
   button. The same public Android mechanism watches, cars and auto-reply apps
   on Google Play use. LightDecoder's code never talks to Meta: the Messenger
   app does the receiving and the sending. Half of this tab.
3. **Show Facebook's website in a web view, unmodified and driven by the
   person.** The other half. Meta's terms forbid accessing or collecting data
   from its products by automated means; a page that a person signs in to and
   operates by hand, with nothing injected and nothing read out, is not that.
   It is still a sign-in from an embedded browser, which Meta can see and may
   challenge.
4. **A bridge that speaks Messenger's private protocol** (mautrix-meta and the
   like) with the account's session. Against Meta's terms, and accounts get
   locked for it. Not built, here or anywhere in the app.

## Fact-check of the plan

The tab follows a plan the project was given ("Hybrid architecture:
notification bridge + local sandboxed web view"). Each claim in it was checked
before anything was written; three needed correcting.

| Claim in the plan | Verdict | In the code |
|---|---|---|
| `NotificationListenerService` + `RemoteInput` can show incoming messages and send quick replies without contacting Meta | **True**, for LightDecoder's own code. What is read is what Android was given to display; a reply is the notification's own Reply action, and the Messenger app sends it. An auto-reply app on Google Play with 500K+ installs (updated November 2026) works exactly this way and says so in its listing. | `MessengerNotificationListener`, `MessengerCapture`, `MessengerReplies` |
| ...and that makes a full real-time inbox | **Overstated.** Only what arrives as a notification, from the moment access is on: nothing while the chat is open in Messenger, muted, or held back because you are active on another device. Pictures and voice clips usually arrive as a line of text. A reply is text only, and only while Messenger offers a Reply button. | The inbox says so; "Open in Messenger" and "Full history" are on every screen |
| The fallback is a web view on `https://www.messenger.com` | **Out of date.** Meta shut messenger.com down as a place to message in April 2026 and redirects it to `facebook.com/messages`. That page needs a Facebook account: people who use Messenger without one now have only the mobile app. | `MessengerWeb.START_URL` is `https://www.facebook.com/messages/`; messenger.com stays allowed in case a link still passes through it |
| A desktop user-agent string stops the redirect to the Play Store | **True but half the job.** Facebook does send phone browsers that open its messages to install Messenger, and "Desktop site" is the long-standing way round it. But a WebView also tells the site "phone, Android" in separate headers (client hints); Android's documentation says the basic ones are still generated when only the string is overridden. Left alone they contradict the string. | Both are set: `MessengerWeb.desktopUserAgent` and `MessengerWebActivity.desktopClientHints` (see "The computer layout") |
| The web view gives access to the full end-to-end encrypted history | **Partly.** Meta says encrypted history can be restored on any platform with the PIN chosen when the backup was made (or a reset). The person has to enter it on Facebook's page; nothing here can do it for them. Passkeys do not work in a web view (see docs/discord-web-view.md), so sign-in is password and code. | Said in the sign-in note |
| It keeps 100% of automated background code outside Meta's network, preventing automated bans | **First half true, second half too strong.** The listener never contacts Meta, and the web page has no life while its screen is closed. But "preventing" is not supportable: a sign-in from an embedded browser is visible to Meta and can be challenged or checkpointed. Lower risk than a bridge, not none. | The boundary below is what keeps the first half true |
| You can license the project under any licence, since no AGPL bridge is linked | **Does not apply to LightDecoder.** The app is already AGPL-3.0-or-later because the Google Messages link uses libgm. This tab adds one library, `androidx.webkit` (Apache-2.0), and no new obligation, but it does not change the app's licence. | `NOTICE` is unchanged; the library is covered by the Apache-2.0 entry |

Sources: TechCrunch, "Meta is shutting down Messenger's standalone website"
(2026-02-19), quoting Meta's help page; Android reference for
`WebSettingsCompat.setUserAgentMetadata`; the Google Play listing of
"AutoResponder for Messenger"; Meta's Terms of Service (the rule against
accessing or collecting data from its products by automated means, quoted from
memory and not re-read for this check).

## The inbox (notifications)

- Android starts `MessengerNotificationListener` only after the user turns on
  **Notification access** for LightDecoder in system settings, and stops it
  when they turn it off. The setup card opens Android's own screen for it;
  nothing can turn it on but the user, there.
- Android hands a listener every app's notifications. The listener looks at the
  package name first and drops everything that is not `com.facebook.orca`
  without reading it. Calls, bundle summaries and promotions of Messenger's are
  dropped too.
- A chat notification (`MessagingStyle`) gives who wrote, what, and when.
  That is stored in `messenger_inbox.db` in the app's private storage
  (`MessengerStore`). Backups are off for the whole app. "Clear caught
  messages" and "Remove from this phone" delete LightDecoder's copy only.
- The notification's Reply button (`RemoteInput`) is remembered per
  conversation, in memory only. Typing a reply in LightDecoder fills that
  button in and presses it, exactly as the notification shade would. It is
  shown as "Handed to Messenger" until Messenger's notification shows the line
  as sent.
- What you write in the Messenger app itself is not seen, because Messenger
  does not notify you of your own messages. A conversation here is the other
  side's messages and the replies sent from here.
- The listener never dismisses, changes or answers a notification by itself,
  and LightDecoder posts no notification of its own for Messenger: Messenger's
  stay exactly as Messenger posted them.
- The sticker catcher that read other apps' notifications
  (`inbox.StickerNotificationListener`) is still switched off and not
  declared. This listener is a different one, for Messenger only.

On a phone where LightDecoder was installed from an APK file, Android 13 and
later can treat notification access as a restricted setting: the user first
has to allow restricted settings in LightDecoder's App info. The setup card
says so.

## Facebook's page (the web view)

`MessengerWebActivity` shows `facebook.com/messages` and does nothing to it:

- No `evaluateJavascript`, no `addJavascriptInterface`, no injected CSS or
  script.
- No `shouldInterceptRequest`. Requests and responses are never read or
  rewritten.
- No reads of cookies, `localStorage` or the session.
- No automatic input. The person signs in, passes any security check, and
  types every message.
- Nothing runs while the screen is closed: no background page, no polling.
- **Nothing the page shows is copied into the inbox.** The history stays on
  Facebook's page. Reading it out of the page would be collecting data by
  automated means, which is the line this tab does not cross.
- Only https pages of facebook.com, messenger.com and their subdomains load on
  the screen. Other links open in the browser; other schemes are dropped.
- The activity is not exported, takes no address from its caller, and always
  starts at the same page.
- Camera and microphone requests are denied. Calls belong in the Messenger
  app. Pictures and files can be attached with the system picker.

The app reads the page address, which `WebView` reports to every host app, to
show the sign-in note, and never keeps or shows the query string.

### Its container

As for the Discord tab (docs/discord-web-view.md): the screen is declared with
`android:process=":messenger"`, and the first thing the app does in that
process is `WebView.setDataDirectorySuffix("messenger")`, then nothing else.
Facebook's cookies live in `app_webview_messenger`, apart from the Google
sign-in folder of the Messages link (which is wiped after each sign-in) and
from Discord's. None of the app's other code runs in that process, the inbox
included. If the folder cannot be set, the screen closes before creating a
WebView; before Android 9 the button says the page is not available.

### The computer layout

The Discord tab sends the WebView's user agent untouched, and this tab departs
from that in one way, because without it the page cannot be used at all: a
phone browser that opens Facebook's messages is sent to the Play Store.

What is asked for is what a browser's "Desktop site" switch asks for:

- The user agent is made from the WebView's own. The Android device in the
  brackets becomes `X11; Linux x86_64` and the word `Mobile` goes. The real
  engine version stays, and so does the `wv` mark that says "web view".
- The client hints that say "phone" and "Android" are set to "not a phone" and
  "Linux" to match. The brand list is not touched and keeps saying
  "Android WebView".
- The `X-Requested-With` header, where the WebView sends it, is left alone.

So the page is asked for its computer layout and is not told the web view is
Chrome. If Facebook serves the phone page or a challenge loop to that honest
request, the answer is the Messenger app, not a better disguise.

## Kept apart from the other tabs

The Messenger tab is not a `ChatService`: no backend, no archive, no unread
badge, no notifications of its own. Its code is in
`com.lightdecoder.messenger` and `com.lightdecoder.ui.messenger`, with its own
layouts, strings and database. The rest of the app knows it in five places:
the `Features.MESSENGER` switch, one line in `LightDecoderApp`, the tab strip
of the conversation list and of the Discord screen (`MessengerTab`), and the
manifest.

## Before publishing on Google Play

Notification access for a messaging companion is an accepted use (see the
auto-reply apps). Play's spam policy restricts apps that mainly wrap someone
else's website; a tab inside a messaging app is a different shape, but read the
current "Webviews" wording first. Messenger's and Facebook's names are used
only as labels, with no logo.

## Tests, and what is not verified

`MessengerWebTest`, `MessengerContainerTest`, `MessengerCaptureTest`,
`MessengerStoreTest`, `MessengerRepliesTest`, `MessengerListenerTest` and
`MessengerScreensTest` run with the rest of the unit tests. They use
notifications built the way Android's chat-notification format says, not
notifications captured from Messenger.

Nothing here has run on a phone, and facebook.com is blocked from the build
environment. To check on a device:

- That Messenger's notifications are `MessagingStyle` with a conversation
  shortcut and a typed-reply button, so that conversations keep one entry each
  and the reply box appears.
- That a reply sent through the button arrives, and that Messenger then shows
  it in its notification (which is what turns "Handed to Messenger" into a
  sent message).
- That `facebook.com/messages` loads in its computer layout with the user
  agent and hints above, that sign-in and its security checks finish, and that
  encrypted history can be restored with the PIN.
- That attaching a picture works through the system picker.
- That signing in to Google for the Messages link, or to Discord, leaves the
  Facebook sign-in in place, and the other way round.
