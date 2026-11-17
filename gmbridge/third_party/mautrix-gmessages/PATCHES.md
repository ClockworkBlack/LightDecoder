# Patched libgm for LightDecoder

This directory is a modified copy of the `pkg/libgm` packages of
[mautrix-gmessages](https://github.com/mautrix/gmessages) **v0.2610.0**
(Go module `go.mau.fi/mautrix-gmessages`), by Tulir Asokan and contributors,
under the GNU Affero General Public License v3 (`LICENSE`, with the
exceptions in `LICENSE.exceptions`). `gmbridge/go.mod` uses it through a
`replace` directive.

Only `pkg/libgm` and its subpackages were copied; the rest of the module (the
Matrix bridge, `cmd/`, the connector) is not used by LightDecoder and was left
out. `go.mod` was cut down to what `pkg/libgm` needs (`go mod tidy`); its
`toolchain` line was dropped, since gmbridge's go.mod pins the toolchain.

Every change in the Go files is marked with a `// LightDecoder patch:` comment.
They were made in November 2026. The changes, file by file, with the reason
for each:

## a. Cancelling a Google-account pairing reaches the phone

`pair_google.go`

- New exported `(*Client).CancelGaiaPairing(ctx, ps)`: sends the
  `CANCEL_GAIA_PAIRING` request with a fresh 10 s context (`cancelContext`,
  which keeps ctx's values but not its cancellation).
  *Why:* the cancel was always sent with the caller's context, which is
  already cancelled or expired at that point (that is why the pairing is being
  cancelled), so the request never went out and the phone kept showing the
  emoji prompt.
- `FinishGaiaPairing` sends the cancel through `CancelGaiaPairing` when its
  wait was cancelled, **and also when it timed out** (`DeadlineExceeded`).
  *Why:* a caller giving up after its timeout abandons the pairing just the
  same.
- The two cancels inside `StartGaiaPairing` (client init failed, server init
  not understood) use `CancelGaiaPairing` too, for the same reason.

## b. StartGaiaPairing no longer parks forever

`pair_google.go`

- It no longer waits on a `sync.WaitGroup` for the pairing long poll to open.
  It waits until the long poll opens, the long poll exits without opening (new
  error `ErrPairingLongPollStopped`, e.g. after a 401), or `ctx` ends (then it
  stops the long poll and returns ctx's error).
  *Why:* the wait did not watch ctx, so a long poll that never opened left the
  calling goroutine blocked for the life of the process.
- The long-poll goroutine it starts recovers panics (see e).

## c. The ack goroutine can be stopped, and pending acks flushed

`session_handler.go`, `client.go`

- `startAckInterval` keeps a stop channel next to its ticker (both under a new
  `ackTickerLock`); the goroutine ends when it is closed. New unexported
  `stopAckInterval`. The interval can be started again afterwards (Connect
  and Reconnect do that, as before). After a recovered panic the goroutine
  clears its ticker so that a later Connect starts a new one.
- `sendAckRequest` became a wrapper of the new `sendAckRequestContext(ctx)`,
  which uses ctx for its request and returns the error (failed acks are still
  re-queued).
- New exported `(*Client).StopAckInterval()`, `(*Client).FlushAcks(ctx)` and
  `(*Client).PendingAcks()`.
  *Why:* the 5 s ack ticker ran (and retried failed acks) for the life of the
  process, even for a client that was dropped, and there was no way to send
  pending acks before unpairing or closing.

## d. Disconnect stops the long poll, its pinger and post-connect work at once

`longpoll.go`, `client.go`

- `Client` has a `listenStop` channel (under a new `listenLock`).
  `closeLongPolling` (used by `Disconnect` and `Reconnect`) closes it and
  makes a new one; `doLongPoll` takes it together with its listen ID.
- `doLongPoll` runs on a loop context that ends when that channel closes. Its
  error backoff sleeps (`sleepUnlessStopped`) end at once then, the request it
  is making (token refresh, opening the stream) is cancelled, and the ditto
  pinger, whose stop channel is now the loop context's `Done`, stops with it.
  The pinger also re-checks for stop before every ping and data-receive check.
- `doLongPoll`'s first-connect callback gets the loop context, and
  `postConnect` uses it: its waits end and its acks, `SetActiveSession` and
  bugle-default check are not sent once the long poll was stopped (and a
  `SetActiveSession` that failed only because of that reports no
  `PingFailed`).
- `listenID`, `longPollingConn` and `disconnecting` are read and written under
  `listenLock` (or atomically), and a loop only clears the connection it
  opened itself.
  *Why:* after `Disconnect`, a loop asleep in its backoff kept running (and
  pinging the phone about once a minute) until the sleep ended, and
  `postConnect` could still re-activate the session a few seconds after
  `Disconnect`; the unsynchronised fields were data races between
  `Disconnect` and the loop.
- `Connect` refreshes the token with its `ctx` (new `refreshAuthTokenContext`;
  `refreshAuthToken` became a wrapper of it) and returns ctx's error if ctx
  ended during the refresh. The long poll it starts gets
  `context.WithoutCancel(ctx)`; only `Disconnect` stops it.
  *Why:* a `Connect` waiting for the token refresh could not be cancelled, so
  a `Disconnect` had to wait for it.

## e. Panics in background goroutines can be recovered

`client.go`, `longpoll.go`, `pair.go`, `pair_google.go`, `methods.go`,
`session_handler.go`

- New exported `(*Client).SetPanicHandler(func(where string, recovered any))`
  and unexported `recoverBackground`, deferred at the top of libgm's own
  background goroutines: the long-poll loops (also those of pairing), the
  pinger, ping waits, ping recovery, post-connect and its bugle-default
  watchdog, update requests after a gap, the ack goroutine, the browser
  presence ack and the reconnects after pairing. With a handler set, a panic
  there is passed to it; without one it is re-raised as before.
  *Why:* a panic in a goroutine LightDecoder does not own crashed the whole
  Android app; the bridge now reports it as an error state.

## f. Token fields are written under a lock

`client.go`, `pair.go`, `pair_google.go`, `longpoll.go`, `session_handler.go`,
`media.go`

- `AuthData` gets unexported helpers that read (`tachyonToken`,
  `tachyonExpiry`, `tachyonTTL`) and write (`setTachyonToken`, `setDevices`)
  the token fields under the existing `CookiesLock`. `updateTachyonAuthToken`,
  the device writes after sign-in and QR pairing, `FetchConfig`'s session ID,
  `StartGaiaPairing`'s `DestRegID` and `FinishGaiaPairing`'s key and pairing ID
  writes take the write lock; the background reads of the token (long poll,
  requests, acks, media, token refresh) take the read lock.
  *Why:* the token was rewritten on every refresh without a lock, while the
  app encodes `AuthData` (under `CookiesLock.RLock`) to save the session: a
  data race that could save a half-updated token.

## g. A message can be posted without waiting for the phone's answer

`session_handler.go`, `methods.go`

- `sendMessageWithParams` is split: the wait for the phone's response is
  `waitForResponse`, unchanged. New `sendUserMessageAsync` posts a
  user-initiated request and returns a function that does that wait, and the
  exported `Client.SendMessageAsync` does the same for `SendMessage`.
  *Why:* `SendMessage` returns only when the phone has answered, which takes
  seconds, so several messages typed in a row went out one phone round trip
  apart. The bridge now posts each one as soon as Google accepted the one
  before it (keeping their order) and waits for the phone's answers alongside.

## Not changed

Everything else, including the protocol buffers (`gmproto`), the crypto and
the tools under `gmtest`, `manualdecrypt` and `pblitedecode`, is as in
v0.2610.0.
