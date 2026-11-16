// Package gmbridge wraps libgm (the Google Messages for web protocol library
// from mautrix-gmessages) for the LightDecoder Android app. It is compiled
// with gomobile into an .aar; Kotlin sees com.lightdecoder.gm.gmbridge.
//
// The surface is deliberately narrow and gomobile-friendly: strings, []byte
// JSON, bool and int64 only, one callback interface, and errors whose message
// starts with a stable code ("not_connected: ..."). Every exported entry point
// recovers panics, because a Go panic aborts the whole Android process.
//
// Privacy: libgm logs decrypted payloads at debug/trace levels, so its logger
// is zerolog.Nop() and the global zerolog level is disabled. This package
// logs nothing itself.
//
// libgm is part of go.mau.fi/mautrix-gmessages (AGPL-3.0); the app's
// source stays public for that reason.
package gmbridge

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/util"
)

// Version is the bridge API version; Kotlin can check it at startup.
const Version = "1"

const (
	// rpcTimeout is a safety net above libgm's own 60 s response timeout.
	rpcTimeout = 90 * time.Second
	// persistInterval: cookies rotate silently on every response, so the
	// session is saved periodically while connected (contract §3).
	persistInterval = 30 * time.Minute
	// closeDrainWait bounds how long Close waits for queued callbacks.
	closeDrainWait = 2 * time.Second
	// unpairTimeout bounds the UnpairGaia request.
	unpairTimeout = 15 * time.Second
	// mediaTokenMargin: below this much token lifetime left, a download while
	// disconnected is refused rather than sent with a token Google rejects.
	mediaTokenMargin = time.Minute
)

// pairedDeviceName is what Google Messages on the phone lists under Device
// pairing. The app's texts (strings_chat.xml link_intro_stop and
// link_unpair_body, README) tell the user to look for this name, so keep
// them in sync. Devices paired before it was set keep libgm's "libgm" until
// they are paired again.
const pairedDeviceName = "LightDecoder"

func init() {
	// Belt and braces on top of zerolog.Nop(): libgm sometimes logs through
	// zerolog.Ctx(ctx), which falls back to the global level.
	zerolog.SetGlobalLevel(zerolog.Disabled)
	// Sent with every pairing request (libgm's default is "libgm"). Only init
	// writes this global, before any client exists. DeviceType stays TABLET
	// and BrowserType stays OTHER (contract §0.5).
	util.BrowserDetailsMessage.OS = pairedDeviceName
}

// Callback is implemented in Kotlin. It is called from a single Go dispatcher
// goroutine, in order, never from libgm's event handler directly (that
// handler only enqueues into an unbounded FIFO). Both methods return error so
// that gomobile clears any Java exception they throw.
type Callback interface {
	// OnSession asks Kotlin to encrypt and persist the session. Empty/nil data
	// (null in Java) means "session deleted" (after Unpair).
	OnSession(data []byte) error
	// OnEvent delivers one event; kind is one of the contract §3 kinds and
	// payload is UTF-8 JSON.
	OnEvent(kind string, payload []byte) error
}

// GMBridge owns at most one long-lived libgm client and at most one pairing
// attempt. It is safe to call from several Java threads.
type GMBridge struct {
	cb    Callback
	disp  *dispatcher
	cache *stateCache

	// lifecycle serialises Connect, Disconnect, Unpair, Close, pairing
	// adoption and invalidation, so a client is never swapped or torn down
	// while another of these is half-way through.
	lifecycle sync.Mutex

	// mu guards the fields below. It is held only for quick reads and
	// writes, never across network calls.
	mu         sync.Mutex
	auth       *libgm.AuthData
	client     *libgm.Client // nil unless paired
	gen        uint64        // bumped whenever client is replaced or dropped
	phoneID    string
	account    string
	live       bool   // Connect succeeded and no Disconnect/fatal error since
	connSeq    uint64 // bumped by every Connect; ties fatal events to their connection
	connCtx    context.Context
	connCancel context.CancelFunc
	pairing    *pairingAttempt
	closed     bool
	// clientRetired is the network switch of client (see retirableSettings).
	clientRetired *atomic.Bool

	// Per-connection flags, written from the dispatcher and helper goroutines.
	// Connect resets them (resetConnectionFlags).
	browserActive     atomic.Bool
	syncingMobileDB   atomic.Bool
	needResync        atomic.Bool  // a retried SetActiveSession went through; resync on BROWSER_ACTIVE
	inactiveElsewhere atomic.Bool  // another browser is the phone's active session
	inactiveDetail    atomic.Value // string: the last BROWSER_INACTIVE* alert name
	wasInactive       atomic.Bool  // was inactive since the last BROWSER_ACTIVE: catch up when active again
	noDataRecently    atomic.Bool  // libgm reported a data gap: catch up once data flows again
	pollDown          atomic.Bool  // the long poll reported a temporary error and has not recovered
	throttleResync    atomic.Bool  // the phone stopped throttling pushes; a delayed resync is pending

	// Each libgm long-poll loop (Connect, Connect's restart, libgm's ping
	// recovery) sends its own SetActiveSession, with a new session ID, so the
	// helpers that follow one up run once per loop, not once per connection:
	// a newer request supersedes an older one (see supersedeForConnection).
	// Never reset; activations counts BROWSER_ACTIVE alerts, so a helper can
	// tell whether the phone confirmed a session after it was started.
	hackyEpoch     atomic.Uint64
	setActiveEpoch atomic.Uint64
	activations    atomic.Uint64

	// Per client: libgm's phone liveness state survives Disconnect/Connect
	// and it reports PhoneNotResponding only once per outage, so this is
	// reset only when a new client is built.
	phoneNotResponding atomic.Bool

	// syncingConversations counts ListConversations calls in flight. The
	// phone sends spurious MESSAGE_DELETED updates while it serves them; like
	// the connector, those are dropped.
	syncingConversations atomic.Int32
}

// NewGMBridge restores a session from a previous OnSession (or starts unpaired
// when session is empty). It never touches the network.
func NewGMBridge(session []byte, cb Callback) (b *GMBridge, err error) {
	defer recoverInto(&err)
	auth, phoneID, account, err := parseSession(session)
	if err != nil {
		return nil, exported(err)
	}
	b = &GMBridge{
		cb:      cb,
		disp:    newDispatcher(),
		cache:   newStateCache(),
		auth:    auth,
		phoneID: phoneID,
		account: account,
	}
	if isPaired(auth) {
		b.client = b.newClientLocked()
	}
	return b, nil
}

// newClientLocked builds a long-lived client for b.auth with the event
// handler installed, and retires the client it replaces. Callers hold b.mu
// (or own b exclusively) and store the result in b.client.
func (b *GMBridge) newClientLocked() *libgm.Client {
	b.retireClientLocked()
	b.gen++
	b.phoneNotResponding.Store(false) // a new client starts with fresh liveness
	retired := new(atomic.Bool)
	cli := libgm.NewClient(b.auth, nil, zerolog.Nop(), retirableSettings(retired))
	cli.SetEventHandler(b.handlerFor(b.gen))
	b.clientRetired = retired
	return cli
}

// retireClientLocked cuts the current client off the network for good.
// Callers hold b.mu and are dropping b.client (Unpair, a lost pairing, a new
// pairing replacing it).
//
// libgm has no way to stop a client completely: its 5 s ack ticker runs for
// the life of the process and retries failed acks forever, and goroutines
// of a connection (postConnect's SetActiveSession about 3 s after a long
// poll opens, the ping recovery that may call Reconnect) can still act
// after Disconnect. With the pairing gone those would keep sending the
// account cookies to Google every few seconds. A retired client's requests
// fail locally instead; the goroutines that remain only spin on that error.
func (b *GMBridge) retireClientLocked() {
	if b.clientRetired != nil {
		b.clientRetired.Store(true)
		b.clientRetired = nil
	}
}

// errClientRetired is what every request of a retired client fails with.
var errClientRetired = errors.New("client was dropped")

// retirableSettings are libgm's usual HTTP settings, behind a switch: once
// retired is set, every request fails with errClientRetired before any
// connection is made. Both of the client's HTTP clients (calls and long
// poll) get their own transport behind the same switch.
func retirableSettings(retired *atomic.Bool) exhttp.ClientSettings {
	cs := exhttp.SensibleClientSettings
	cs.TransportOverride = func(cs exhttp.ClientSettings) http.RoundTripper {
		// Exactly what Compile builds without an override.
		return retirableTransport{retired: retired, next: cs.Configure(&http.Transport{})}
	}
	return cs
}

type retirableTransport struct {
	retired *atomic.Bool
	next    *http.Transport
}

func (t retirableTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if t.retired.Load() {
		// A RoundTripper must close the body even when it fails.
		if req.Body != nil {
			_ = req.Body.Close()
		}
		return nil, errClientRetired
	}
	return t.next.RoundTrip(req)
}

// CloseIdleConnections keeps libgm's Disconnect (http.Client.CloseIdleConnections)
// working through the wrapper.
func (t retirableTransport) CloseIdleConnections() { t.next.CloseIdleConnections() }

func (b *GMBridge) isCurrentGen(gen uint64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.gen == gen && b.client != nil
}

// liveSeq reports whether client generation gen is current and its
// connection is wanted, and if so which connection (Connect call) it is.
func (b *GMBridge) liveSeq(gen uint64) (uint64, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.connSeq, b.gen == gen && b.client != nil && b.live
}

// --- status ------------------------------------------------------------------

// IsPaired reports whether a usable Google-account pairing is stored.
func (b *GMBridge) IsPaired() (paired bool) {
	defer func() { _ = recover() }()
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.client != nil && isPaired(b.auth)
}

// IsConnected reports whether the long poll to Google is open right now. It
// is false during reconnect gaps even though calls are still accepted.
func (b *GMBridge) IsConnected() (connected bool) {
	defer func() { _ = recover() }()
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.live && b.client.IsConnected()
}

// StatusJSON returns a StatusDTO.
func (b *GMBridge) StatusJSON() (out string) {
	defer func() {
		if recover() != nil {
			out = "{}"
		}
	}()
	b.mu.Lock()
	defer b.mu.Unlock()
	st := statusDTO{
		Paired:    b.client != nil && isPaired(b.auth),
		Connected: b.live && b.client.IsConnected(),
		Account:   b.account,
		PhoneID:   b.phoneID,
	}
	if st.Paired {
		st.SessionID = b.auth.SessionID.String()
	}
	return string(toJSON(st))
}

// --- lifecycle ---------------------------------------------------------------

// Connect starts the long-lived connection. It returns once the token refresh
// has succeeded and the long poll is starting; "connected" follows as a state
// event. If already connected it does nothing, except that a long poll that
// is down (state "reconnecting") is restarted at once: libgm waits longer
// after every failed attempt ((n+1)*5 s, without limit), so after a long
// outage the next attempt can be minutes away once the network is back.
func (b *GMBridge) Connect() (err error) {
	defer recoverInto(&err)
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()

	b.mu.Lock()
	switch {
	case b.closed:
		b.mu.Unlock()
		return newError(codeInternal, "bridge is closed")
	case b.client == nil || !isPaired(b.auth):
		b.mu.Unlock()
		return newError(codeNotPaired, "pair with your phone first")
	case b.live:
		cli := b.client
		b.mu.Unlock()
		if b.pollDown.Load() && !longPollOpen(cli) {
			// Reconnect ends the sleeping loop (it exits when it wakes) and
			// starts a fresh one whose first attempt is immediate. The fresh
			// loop does not know how long the poll was down, so libgm's
			// catch-up after a gap (NoDataReceived) never runs, and Kotlin's
			// once-per-Connect sync does not run either (no "connecting").
			// What the phone sent meanwhile may not be redelivered: catch up
			// on the first data or BROWSER_ACTIVE, as after a reported gap.
			// Set first, so no event of the new loop can come before it.
			b.noDataRecently.Store(true)
			if rerr := restartLongPoll(cli); rerr != nil {
				return toBridgeError(rerr)
			}
		}
		return nil
	}
	cli, gen := b.client, b.gen
	ctx, cancel := context.WithCancel(context.Background())
	b.live, b.connCtx, b.connCancel = true, ctx, cancel
	b.connSeq++
	b.resetConnectionFlags()
	b.mu.Unlock()

	b.emitState(stateConnecting, "", "")
	if cerr := b.startClient(ctx, cli); cerr != nil {
		b.teardownLocked(gen)
		be := toBridgeError(cerr)
		switch be.code {
		case codeLoggedOut:
			b.clearPairingLocked(gen)
			b.emitSession()
			b.emitState(stateLoggedOut, be.code, be.detail)
		case codeCookiesInvalid:
			b.emitSession()
			b.emitState(stateCookiesInvalid, be.code, be.detail)
		default:
			b.emitState(stateError, be.code, be.detail)
		}
		return be
	}
	go b.persistLoop(ctx, gen)
	return nil
}

// resetConnectionFlags clears what the previous connection learned. Not
// phoneNotResponding: that follows the client (see its declaration).
// Callers hold b.mu, which claimForConnection relies on.
func (b *GMBridge) resetConnectionFlags() {
	b.browserActive.Store(false)
	b.syncingMobileDB.Store(false)
	b.needResync.Store(false)
	b.inactiveElsewhere.Store(false)
	b.inactiveDetail.Store("")
	b.wasInactive.Store(false)
	b.noDataRecently.Store(false)
	b.pollDown.Store(false)
	b.throttleResync.Store(false)
}

// startClient is the connector's Connect: fetch the web config first (an
// empty account email means the cookies were signed out), then refresh the
// token and start the long poll. A config fetch that fails for other reasons
// is not fatal, as in the connector; that includes a panic in libgm's config
// version parser (it slices the version string unchecked), which must not
// stop an otherwise working session from connecting. Other panics in libgm
// come back as errors so Connect can clean up.
func (b *GMBridge) startClient(ctx context.Context, cli *libgm.Client) (err error) {
	defer recoverInto(&err)
	cfgErr := func() (cfgErr error) {
		defer recoverInto(&cfgErr)
		cfgCtx, cancel := context.WithTimeout(ctx, configTimeout)
		defer cancel()
		return cli.FetchConfig(cfgCtx)
	}()
	if cfgErr == nil && cli.Config.GetDeviceInfo().GetEmail() == "" {
		return newError(codeCookiesInvalid, "cookies are no longer signed in to Google")
	}
	// libgm's long poll ignores cancellation of the context it is given (only
	// Disconnect stops it), and cancelling it mid-request only provokes a
	// spurious temporary error, so it gets a context of its own; ctx is for
	// this package's calls and helpers.
	return cli.Connect(context.Background())
}

// Disconnect closes the connection (keeping the pairing) and persists the
// session, since cookies may have rotated since the last save.
func (b *GMBridge) Disconnect() {
	defer func() { _ = recover() }()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.disconnectLocked()
}

// disconnectLocked is Disconnect with b.lifecycle already held.
func (b *GMBridge) disconnectLocked() {
	b.mu.Lock()
	gen, paired := b.gen, b.client != nil
	b.mu.Unlock()
	if !paired {
		return
	}
	b.teardownLocked(gen)
	b.emitSession()
	b.emitState(stateDisconnected, "", "")
}

// teardownLocked stops the connection of client generation gen (if it is
// still current): marks it not live, cancels its context (stopping the
// persist loop and helper goroutines) and disconnects libgm, which also fails
// every call still waiting for the phone with ErrConnectionClosed.
func (b *GMBridge) teardownLocked(gen uint64) {
	b.mu.Lock()
	if b.gen != gen || b.client == nil {
		b.mu.Unlock()
		return
	}
	cli, cancel := b.client, b.connCancel
	b.live, b.connCtx, b.connCancel = false, nil, nil
	b.mu.Unlock()
	cli.Disconnect()
	if cancel != nil {
		cancel()
	}
}

// clearPairingLocked forgets the pairing (keeping cookies and keys, as
// AuthData.ClearPairing does) and drops the client, so late events from it
// are ignored and IsPaired turns false.
func (b *GMBridge) clearPairingLocked(gen uint64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.gen != gen || b.client == nil {
		return
	}
	// marshalSession reads AuthData under CookiesLock.RLock without b.mu
	// (ExportSession, the persist loop); hold the write lock so it never
	// encodes a half-cleared token slice.
	b.auth.CookiesLock.Lock()
	b.auth.ClearPairing()
	b.auth.CookiesLock.Unlock()
	b.retireClientLocked()
	b.client = nil
	b.gen++
	b.phoneID = ""
}

// invalidate handles a fatal verdict from libgm (logged out, bad cookies,
// other fatal error) on its own goroutine, because it needs the lifecycle
// lock and the dispatcher must never block. A lost pairing applies to the
// client whatever its connection state; the other verdicts only apply to the
// connection (seq) they came from, so they cannot tear down a newer one.
func (b *GMBridge) invalidate(gen, seq uint64, state string, cause error) {
	defer func() { _ = recover() }()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	if state == stateLoggedOut {
		if !b.isCurrentGen(gen) {
			return
		}
	} else if cur, live := b.liveSeq(gen); !live || cur != seq {
		return
	}
	b.teardownLocked(gen)
	if state == stateLoggedOut {
		b.clearPairingLocked(gen)
	}
	b.emitSession()
	b.emitStateErr(state, cause)
}

// SetActiveSession makes this device the phone's active web session again
// ("use here" after another browser took over).
func (b *GMBridge) SetActiveSession() (err error) {
	defer recoverInto(&err)
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	return exported(cli.SetActiveSession(ctx))
}

// Unpair revokes the pairing on the phone (best effort), disconnects and
// deletes the session. It succeeds even when nothing was paired, so Kotlin
// can use it as "wipe": the local wipe always happens. If the revoke did not
// reach Google, the "unpaired" state carries code unpair_remote_failed so the
// app can tell the user to remove the device on the phone.
//
// While disconnected the tachyon token may have expired (it lasts about a
// day and only Connect and the long poll refresh it), and Google would reject
// the revoke. libgm's only exported way to refresh it synchronously is
// Connect, so a short temporary connection is made first in that case.
func (b *GMBridge) Unpair() (err error) {
	defer recoverInto(&err)
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()

	b.mu.Lock()
	cli, live, auth := b.client, b.live, b.auth
	if cli != nil {
		// Drop everything the client reports from here on: a temporary
		// connection must not deliver data or re-persist the session that
		// is about to be deleted.
		b.gen++
	}
	gen := b.gen
	b.mu.Unlock()
	var remoteErr error
	if cli != nil {
		if !live && tokenExpiresWithin(auth, libgm.RefreshTachyonBuffer) {
			remoteErr = func() (e error) {
				defer recoverInto(&e)
				return refreshForUnpair(cli)
			}()
		}
		if remoteErr == nil {
			remoteErr = func() (e error) {
				defer recoverInto(&e)
				ctx, cancel := context.WithTimeout(context.Background(), unpairTimeout)
				defer cancel()
				return unpairGaia(ctx, cli)
			}()
		}
		// Google saying the pairing does not exist means it is gone already.
		if errors.Is(remoteErr, events.ErrRequestedEntityNotFound) {
			remoteErr = nil
		}
		b.teardownLocked(gen) // also ends the temporary connection
	}
	b.mu.Lock()
	b.retireClientLocked() // after the revoke, which still needed it
	b.auth, b.client, b.phoneID, b.account = nil, nil, "", ""
	b.gen++
	b.mu.Unlock()
	b.phoneNotResponding.Store(false)
	b.cache.reset()
	b.emitSessionDeleted()
	if remoteErr != nil {
		b.emitState(stateUnpaired, codeUnpairRemoteFailed, "the phone may still list this device")
	} else {
		b.emitState(stateUnpaired, "", "")
	}
	return nil
}

// libgm calls that touch the network or the long poll; tests replace them.
var (
	longPollOpen = (*libgm.Client).IsConnected
	// restartLongPoll uses its own context for the same reason as
	// startClient: libgm's long poll ignores cancellation.
	restartLongPoll = func(cli *libgm.Client) error { return cli.Reconnect(context.Background()) }
	// refreshForUnpair refreshes the tachyon token. Connect is the only
	// exported libgm call that does so synchronously; the long poll it also
	// starts is stopped by Unpair's teardown, and its events are dropped.
	refreshForUnpair = func(cli *libgm.Client) error { return cli.Connect(context.Background()) }
	unpairGaia       = func(ctx context.Context, cli *libgm.Client) error { return cli.UnpairGaia(ctx) }
)

// tokenExpiresWithin reports whether auth's tachyon token is missing or ends
// within d.
func tokenExpiresWithin(auth *libgm.AuthData, d time.Duration) bool {
	if auth == nil {
		return true
	}
	auth.CookiesLock.RLock()
	exp := auth.TachyonExpiry
	auth.CookiesLock.RUnlock()
	return exp.IsZero() || time.Until(exp) <= d
}

// ExportSession returns the current session JSON (null in Java if there is
// none). It is the same data OnSession receives.
func (b *GMBridge) ExportSession() (data []byte, err error) {
	defer recoverInto(&err)
	b.mu.Lock()
	auth, phoneID, account := b.auth, b.phoneID, b.account
	b.mu.Unlock()
	if auth == nil {
		return nil, nil
	}
	data, err = marshalSession(auth, phoneID, account)
	if err != nil {
		return nil, newError(codeInternal, "could not encode session")
	}
	return data, nil
}

// Close disconnects, abandons any pairing attempt, delivers queued callbacks
// (briefly) and stops the dispatcher. The bridge is unusable afterwards.
func (b *GMBridge) Close() {
	defer func() { _ = recover() }()
	b.CancelPairing()
	b.lifecycle.Lock()
	b.disconnectLocked()
	b.mu.Lock()
	b.closed = true
	b.mu.Unlock()
	b.lifecycle.Unlock()
	b.disp.close(closeDrainWait)
}

// snapshotSession encodes the current session, or nil if there is none.
func (b *GMBridge) snapshotSession() []byte {
	b.mu.Lock()
	auth, phoneID, account := b.auth, b.phoneID, b.account
	b.mu.Unlock()
	if auth == nil {
		return nil
	}
	data, err := marshalSession(auth, phoneID, account)
	if err != nil {
		return nil
	}
	return data
}

// persistLoop saves the session every persistInterval while connected.
func (b *GMBridge) persistLoop(ctx context.Context, gen uint64) {
	defer func() { _ = recover() }()
	ticker := time.NewTicker(persistInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if b.isCurrentGen(gen) {
				b.emitSession()
			}
		}
	}
}

// --- client access for calls ---------------------------------------------------

// liveClient returns the client if Connect has succeeded and no Disconnect or
// fatal error happened since. Calls fail fast with not_paired/not_connected
// instead of letting libgm wait 60 s for a response that cannot arrive.
func (b *GMBridge) liveClient() (*libgm.Client, error) {
	cli, _, err := b.liveClientCtx()
	return cli, err
}

func (b *GMBridge) liveClientCtx() (*libgm.Client, context.Context, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch {
	case b.closed:
		return nil, nil, newError(codeInternal, "bridge is closed")
	case b.client == nil:
		return nil, nil, newError(codeNotPaired, "pair with your phone first")
	case !b.live || b.connCtx == nil:
		return nil, nil, newError(codeNotConnected, "connect first")
	}
	return b.client, b.connCtx, nil
}

// rpc returns the live client and a call context that ends at rpcTimeout or
// when the connection is torn down.
func (b *GMBridge) rpc() (*libgm.Client, context.Context, context.CancelFunc, error) {
	cli, connCtx, err := b.liveClientCtx()
	if err != nil {
		return nil, nil, nil, err
	}
	ctx, cancel := context.WithTimeout(connCtx, rpcTimeout)
	return cli, ctx, cancel, nil
}

// mediaClient returns the client for a media download, connected or not:
// downloads only need the token, not the long poll. While not connected it
// refuses once the token is (nearly) expired, because only Connect and the
// long poll refresh it, and libgm would send the stale token anyway: Google's
// error page then reads as a bad stream, i.e. "media not found".
func (b *GMBridge) mediaClient() (*libgm.Client, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch {
	case b.closed:
		return nil, newError(codeInternal, "bridge is closed")
	case b.client == nil:
		return nil, newError(codeNotPaired, "pair with your phone first")
	case !b.live && tokenExpiresWithin(b.auth, mediaTokenMargin):
		return nil, newError(codeNotConnected, "connect to load this media")
	}
	return b.client, nil
}
