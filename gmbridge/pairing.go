package gmbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
)

// Pairing is the Google-account ("Gaia") emoji flow, run exactly as the
// mautrix connector runs it (login.go). The emoji is only ever shown to the
// user; the user taps the matching one on their phone. Nothing here answers,
// predicts or skips that confirmation.

const (
	configTimeout = 30 * time.Second
	// startPairingWatchdog bounds StartGaiaPairing, which waits for its long
	// poll to open without watching its context and can block forever.
	startPairingWatchdog = 60 * time.Second
	// defaultFinishTimeout is used when FinishPairing gets 0.
	defaultFinishTimeout = 180 * time.Second
	// pairingIdleExpiry abandons an attempt whose emoji was never followed by
	// FinishPairing or CancelPairing (libgm's pairing request TTL is 300 s).
	pairingIdleExpiry = 6 * time.Minute
)

// requiredCookies are the cookies the connector requires (login.go). Others
// (for example __Secure-1PSIDTS) are passed through when present.
var requiredCookies = []string{"SID", "HSID", "SSID", "APISID", "SAPISID", "OSID"}

// pairingAttempt is one StartPairing → FinishPairing round with its own
// temporary client. The temporary client's long poll carries the pairing
// responses; it is always disconnected when the attempt ends, whatever the
// outcome, and its AuthData is only adopted on success.
type pairingAttempt struct {
	cancelOnce sync.Once
	cancelled  chan struct{}

	mu        sync.Mutex
	client    *libgm.Client
	auth      *libgm.AuthData
	bgCancel  context.CancelFunc
	ps        *libgm.PairingSession
	account   string
	finishing bool
	idle      *time.Timer
}

func newPairingAttempt() *pairingAttempt {
	return &pairingAttempt{cancelled: make(chan struct{})}
}

func (a *pairingAttempt) cancel() {
	a.cancelOnce.Do(func() { close(a.cancelled) })
}

func (a *pairingAttempt) isCancelled() bool {
	select {
	case <-a.cancelled:
		return true
	default:
		return false
	}
}

// end disconnects the temporary client and cancels its long-poll context.
// Cancelling the context alone does not stop libgm's loop; Disconnect does.
// It is idempotent and may run again if the attempt was still being set up
// the first time (a repeated Disconnect is harmless).
func (a *pairingAttempt) end() {
	a.mu.Lock()
	cli, bgCancel, idle := a.client, a.bgCancel, a.idle
	a.bgCancel, a.idle = nil, nil
	a.mu.Unlock()
	if idle != nil {
		idle.Stop()
	}
	if cli != nil {
		cli.Disconnect()
	}
	if bgCancel != nil {
		bgCancel()
	}
}

// abandon cancels the attempt and releases its client.
func (a *pairingAttempt) abandon() {
	a.cancel()
	a.end()
}

// parseCookies validates the cookie map before any network use: every
// required cookie must be present and non-empty.
func parseCookies(raw []byte) (map[string]string, error) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || string(raw) == "null" {
		return nil, newError(codeNoCookies, "no cookies given")
	}
	var in map[string]string
	if err := json.Unmarshal(raw, &in); err != nil {
		return nil, newError(codeInvalidArgument, "cookies must be a JSON object of strings")
	}
	cookies := make(map[string]string, len(in))
	for name, value := range in {
		name, value = strings.TrimSpace(name), strings.TrimSpace(value)
		if name != "" && value != "" {
			cookies[name] = value
		}
	}
	var missing []string
	for _, name := range requiredCookies {
		if cookies[name] == "" {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		// Cookie names are not secret; values never appear in errors.
		return nil, newError(codeNoCookies, "missing "+strings.Join(missing, ", "))
	}
	return cookies, nil
}

// StartPairing begins pairing with the Google account whose cookies are given
// ({"SID":"…",…}) and returns a PairingChallengeDTO with the emoji to show.
func (b *GMBridge) StartPairing(cookiesJSON []byte) (out string, err error) {
	defer recoverInto(&err)
	cookies, err := parseCookies(cookiesJSON)
	if err != nil {
		return "", err
	}

	a := newPairingAttempt()
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		return "", newError(codeInternal, "bridge is closed")
	}
	prev := b.pairing
	if prev != nil {
		prev.mu.Lock()
		busy := prev.ps == nil || prev.finishing
		prev.mu.Unlock()
		if busy {
			b.mu.Unlock()
			return "", newError(codePairingInProgress, "another pairing attempt is running")
		}
	}
	b.pairing = a
	b.mu.Unlock()
	if prev != nil {
		// The previous emoji was never confirmed; start over.
		prev.abandon()
	}

	// Release the attempt on every failure path, including a libgm panic, so
	// a later StartPairing is not refused as "in progress".
	keep := false
	defer func() {
		if !keep {
			a.end()
			b.forgetPairing(a)
		}
	}()
	challenge, err := b.startPairing(a, cookies)
	if err != nil {
		if a.isCancelled() {
			return "", newError(codePairingCancelled, "pairing was cancelled")
		}
		return "", exported(err)
	}
	keep = true
	return string(toJSON(challenge)), nil
}

func (b *GMBridge) startPairing(a *pairingAttempt, cookies map[string]string) (pairingChallengeDTO, error) {
	ad := libgm.NewAuthData()
	ad.SetCookies(cookies)
	cli := libgm.NewClient(ad, nil, zerolog.Nop(), exhttp.SensibleClientSettings)
	cli.SetEventHandler(func(any) {}) // nothing before pairing is of interest
	bgCtx, bgCancel := context.WithCancel(context.Background())
	a.mu.Lock()
	a.client, a.auth, a.bgCancel = cli, ad, bgCancel
	a.mu.Unlock()
	if a.isCancelled() {
		return pairingChallengeDTO{}, newError(codePairingCancelled, "pairing was cancelled")
	}

	// FetchConfig sets the device ID that sign-in uses and tells us which
	// account the cookies belong to (empty = cookies not signed in).
	cfgCtx, cfgCancel := contextWithCancelSignal(configTimeout, a.cancelled)
	err := cli.FetchConfig(cfgCtx)
	cfgCancel()
	if err != nil {
		if httpStatus(err) == 0 && !errors.Is(err, context.Canceled) {
			return pairingChallengeDTO{}, newError(codeInternal, "could not reach Google: "+sanitizeDetail(err))
		}
		return pairingChallengeDTO{}, err
	}
	account := cli.Config.GetDeviceInfo().GetEmail()
	if account == "" {
		return pairingChallengeDTO{}, newError(codeCookiesInvalid, "cookies are not signed in to a Google account")
	}

	emoji, ps, err := startGaiaPairingWithWatchdog(cli, bgCtx, a.cancelled)
	if err != nil {
		return pairingChallengeDTO{}, err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.isCancelled() {
		return pairingChallengeDTO{}, newError(codePairingCancelled, "pairing was cancelled")
	}
	a.ps, a.account = ps, account
	a.idle = time.AfterFunc(pairingIdleExpiry, func() { b.expirePairing(a) })
	return pairingChallengeDTO{Emoji: emoji, EmojiURL: libgm.GetEmojiSVG(emoji), Account: account}, nil
}

// startGaiaPairingWithWatchdog runs StartGaiaPairing on its own goroutine.
// libgm's call waits for its pairing long poll to open without watching ctx,
// so it can block forever (for example after a 401 on the long poll). On
// timeout or cancel the caller ends the attempt, which disconnects the client;
// the abandoned goroutine then finishes or stays parked, holding nothing else.
func startGaiaPairingWithWatchdog(cli *libgm.Client, bgCtx context.Context, cancelled <-chan struct{}) (string, *libgm.PairingSession, error) {
	ctx, cancel := contextWithCancelSignal(startPairingWatchdog, cancelled)
	defer cancel()
	type result struct {
		emoji string
		ps    *libgm.PairingSession
		err   error
	}
	done := make(chan result, 1)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				done <- result{err: panicError(r)}
			}
		}()
		emoji, ps, err := cli.StartGaiaPairing(ctx, bgCtx)
		done <- result{emoji, ps, err}
	}()
	select {
	case r := <-done:
		if r.err != nil {
			return "", nil, r.err
		}
		if r.ps == nil || r.emoji == "" {
			return "", nil, newError(codeInternal, "pairing returned no emoji")
		}
		return r.emoji, r.ps, nil
	case <-ctx.Done():
		select {
		case <-cancelled:
			return "", nil, newError(codePairingCancelled, "pairing was cancelled")
		default:
			return "", nil, newError(codePairingInitTimeout, "Google did not start pairing in time")
		}
	}
}

// contextWithCancelSignal is a timeout context that also ends when the
// signal channel closes (CancelPairing).
func contextWithCancelSignal(timeout time.Duration, signal <-chan struct{}) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	go func() {
		select {
		case <-signal:
			cancel()
		case <-ctx.Done():
		}
	}()
	return ctx, cancel
}

// FinishPairing waits until the user answers on the phone or timeoutMs passes
// (0 → 180 s). On success the pairing is adopted, OnSession receives it, the
// long-lived client is built (not connected) and the phone ID is returned.
func (b *GMBridge) FinishPairing(timeoutMs int64) (phoneID string, err error) {
	defer recoverInto(&err)
	b.mu.Lock()
	a := b.pairing
	closed := b.closed
	b.mu.Unlock()
	if closed {
		return "", newError(codeInternal, "bridge is closed")
	}
	if a == nil {
		return "", newError(codeNoPairing, "start pairing first")
	}
	a.mu.Lock()
	switch {
	case a.ps == nil && !a.isCancelled():
		a.mu.Unlock()
		return "", newError(codePairingInProgress, "pairing is still starting")
	case a.ps == nil:
		a.mu.Unlock()
		return "", newError(codeNoPairing, "start pairing first")
	case a.finishing:
		a.mu.Unlock()
		return "", newError(codePairingInProgress, "already waiting for the phone")
	}
	a.finishing = true
	if a.idle != nil {
		a.idle.Stop()
	}
	cli, ps := a.client, a.ps
	a.mu.Unlock()
	// The temporary client is released whatever happens (also on a panic).
	defer func() {
		a.end()
		b.forgetPairing(a)
	}()

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeoutMs <= 0 {
		timeout = defaultFinishTimeout
	}
	ctx, cancel := contextWithCancelSignal(timeout, a.cancelled)
	phoneID, ferr := cli.FinishGaiaPairing(ctx, ps)
	timedOut := errors.Is(ctx.Err(), context.DeadlineExceeded)
	cancel()
	a.end()
	b.forgetPairing(a)

	switch {
	case a.isCancelled():
		if ferr == nil {
			// The phone confirmed just as the user cancelled: revoke it so no
			// orphaned pairing is left on the phone.
			go revokeQuietly(a)
		}
		return "", newError(codePairingCancelled, "pairing was cancelled")
	case ferr != nil && timedOut:
		return "", newError(codePairingTimeout, "the phone did not confirm in time")
	case ferr != nil:
		return "", exported(ferr)
	}
	if !b.adoptPairing(a, phoneID) {
		// Close ran while the phone was confirming: nothing can persist the
		// new pairing any more, so revoke it rather than leave it orphaned
		// on the phone.
		go revokeQuietly(a)
		return "", newError(codePairingCancelled, "the bridge was closed")
	}
	return phoneID, nil
}

// CancelPairing abandons the current attempt. A FinishPairing that is waiting
// returns pairing_cancelled. The phone's dialog times out by itself.
func (b *GMBridge) CancelPairing() {
	defer func() { _ = recover() }()
	b.mu.Lock()
	a := b.pairing
	b.pairing = nil
	b.mu.Unlock()
	if a != nil {
		a.abandon()
	}
}

func (b *GMBridge) forgetPairing(a *pairingAttempt) {
	b.mu.Lock()
	if b.pairing == a {
		b.pairing = nil
	}
	b.mu.Unlock()
}

// expirePairing drops an attempt nobody finished, freeing its long poll.
func (b *GMBridge) expirePairing(a *pairingAttempt) {
	defer func() { _ = recover() }()
	a.mu.Lock()
	finishing := a.finishing
	a.mu.Unlock()
	if finishing {
		return
	}
	b.forgetPairing(a)
	a.abandon()
}

// adoptPairing makes the freshly paired AuthData the bridge's session: any
// previous client is torn down (two clients must never share AuthData or a
// pairing), a new long-lived client is built, and the session is persisted.
// It returns false (adopting nothing) if the bridge was closed meanwhile:
// the dispatcher is gone, so the session could never reach Kotlin.
func (b *GMBridge) adoptPairing(a *pairingAttempt, phoneID string) bool {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()

	b.mu.Lock()
	oldGen, closed := b.gen, b.closed
	b.mu.Unlock()
	if closed {
		return false
	}
	b.teardownLocked(oldGen)

	a.mu.Lock()
	auth, account := a.auth, a.account
	a.mu.Unlock()
	if account == "" {
		account = auth.Mobile.GetSourceID()
	}
	b.mu.Lock()
	b.auth, b.phoneID, b.account = auth, phoneID, account
	b.client = b.newClientLocked()
	b.mu.Unlock()
	b.cache.reset()
	b.emitSession()
	return true
}

// revokeQuietly unpairs an attempt that succeeded after it was cancelled.
func revokeQuietly(a *pairingAttempt) {
	defer func() { _ = recover() }()
	a.mu.Lock()
	cli := a.client
	a.mu.Unlock()
	if cli == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_ = cli.UnpairGaia(ctx)
}
