package gmbridge

import (
	"context"
	"errors"
	"slices"
	"sync"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// Event kinds (contract §3).
const (
	kindState        = "state"
	kindMessage      = "message"
	kindConversation = "conversation"
	kindTyping       = "typing"
	kindSettings     = "settings"
	kindResync       = "resync"
	kindAccount      = "account"
)

// States carried by kindState events.
const (
	stateConnecting      = "connecting"
	stateConnected       = "connected"
	stateActive          = "active"
	stateActiveElsewhere = "active_elsewhere"
	stateReconnecting    = "reconnecting"
	statePhoneOffline    = "phone_offline"
	stateLoggedOut       = "logged_out"
	stateCookiesInvalid  = "cookies_invalid"
	stateError           = "error"
	stateDisconnected    = "disconnected"
	stateUnpaired        = "unpaired"
)

// hackySetActiveDelay is how long the connector waits for BROWSER_ACTIVE after
// libgm warns that its own SetActiveSession may have been swallowed.
const hackySetActiveDelay = 7 * time.Second

// setActiveRetryDelays are the waits before each retry of a SetActiveSession
// that failed right after the long poll opened. Tests shorten them.
var setActiveRetryDelays = []time.Duration{5 * time.Second, 15 * time.Second, 45 * time.Second}

// setActiveSession is cli.SetActiveSession; tests replace it.
var setActiveSession = func(ctx context.Context, cli *libgm.Client) error {
	return cli.SetActiveSession(ctx)
}

// dispatcher is the single goroutine that calls into Kotlin.
//
// libgm calls its event handler synchronously from its long-poll and pinger
// goroutines and says the handler must not block or make requests. Calling
// into the JVM from there would do both, so the handler only appends a job to
// this unbounded FIFO. One goroutine runs the jobs in order, so Kotlin sees
// events in the order libgm produced them and never concurrently. A panic in
// a job (or in Kotlin, surfacing as a Go panic) is contained to that job.
type dispatcher struct {
	mu     sync.Mutex
	cond   *sync.Cond
	queue  []func()
	closed bool
	done   chan struct{}
}

func newDispatcher() *dispatcher {
	d := &dispatcher{done: make(chan struct{})}
	d.cond = sync.NewCond(&d.mu)
	go d.run()
	return d
}

// push enqueues a job; it never blocks. Jobs pushed after close are dropped.
func (d *dispatcher) push(job func()) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return false
	}
	d.queue = append(d.queue, job)
	d.cond.Signal()
	return true
}

func (d *dispatcher) run() {
	defer close(d.done)
	for {
		d.mu.Lock()
		for len(d.queue) == 0 && !d.closed {
			d.cond.Wait()
		}
		if len(d.queue) == 0 {
			d.mu.Unlock()
			return
		}
		job := d.queue[0]
		d.queue[0] = nil
		d.queue = d.queue[1:]
		d.mu.Unlock()
		runJob(job)
	}
}

func runJob(job func()) {
	defer func() { _ = recover() }()
	job()
}

// close stops accepting jobs, lets the queued ones run, and waits up to wait
// for them. It does not wait forever so that a stuck callback cannot hang
// Close on the caller's thread.
func (d *dispatcher) close(wait time.Duration) {
	d.mu.Lock()
	d.closed = true
	d.cond.Broadcast()
	d.mu.Unlock()
	select {
	case <-d.done:
	case <-time.After(wait):
	}
}

// --- delivery (dispatcher goroutine only) -------------------------------------

func (b *GMBridge) deliverEvent(kind string, v any) {
	if b.cb == nil {
		return
	}
	payload := toJSON(v)
	func() {
		defer func() { _ = recover() }()
		_ = b.cb.OnEvent(kind, payload)
	}()
}

func (b *GMBridge) deliverSession(data []byte) {
	if b.cb == nil {
		return
	}
	func() {
		defer func() { _ = recover() }()
		_ = b.cb.OnSession(data)
	}()
}

// --- enqueueing (any goroutine) -----------------------------------------------

func (b *GMBridge) emitEvent(kind string, v any) {
	b.disp.push(func() { b.deliverEvent(kind, v) })
}

func (b *GMBridge) emitState(state, code, detail string) {
	b.emitEvent(kindState, stateDTO{State: state, Code: code, Detail: detail})
}

// emitStateErr emits a state whose code/detail come from an error.
func (b *GMBridge) emitStateErr(state string, err error) {
	be := toBridgeError(err)
	if be == nil {
		b.emitState(state, "", "")
		return
	}
	b.emitState(state, be.code, be.detail)
}

// emitSession snapshots the current session and queues it for OnSession.
// The snapshot is taken now (not when the job runs) so the order of
// persisted states matches the order of changes.
func (b *GMBridge) emitSession() {
	data := b.snapshotSession()
	if data == nil {
		return
	}
	b.disp.push(func() { b.deliverSession(data) })
}

// emitSessionDeleted tells Kotlin to delete the stored session.
func (b *GMBridge) emitSessionDeleted() {
	b.disp.push(func() { b.deliverSession(nil) })
}

// --- libgm event handling --------------------------------------------------------

// handlerFor returns the libgm event handler for the client of generation gen.
// It only enqueues; all interpretation happens on the dispatcher goroutine.
// The connection (Connect call) current when libgm raised the event is
// recorded now, not when the queued job runs: if Kotlin is slow and the queue
// backs up, an event from a connection that has since been replaced by
// Disconnect + Connect must not act on the new one. b.mu is never held while
// calling into libgm, so taking it here cannot deadlock.
//
// Two kinds of event are judged here rather than on the dispatcher, because
// what they depend on is only true at the moment libgm raises them:
//   - MESSAGE_DELETED while a ListConversations call is in flight is dropped,
//     as the connector does: the phone sends spurious deletions while it
//     serves the conversation list. RPC goroutines are not ordered with the
//     dispatcher queue, so the counter must be read now.
//   - ListenTemporaryError while the long poll is open comes from a loop that
//     has been replaced (Reconnect): libgm's current loop only reports errors
//     while its stream is closed. It must not turn "connected" back into
//     "reconnecting".
func (b *GMBridge) handlerFor(gen uint64) libgm.EventHandler {
	return func(evt any) {
		defer func() { _ = recover() }()
		switch e := evt.(type) {
		case *libgm.WrappedMessage:
			if e.Message != nil && e.GetMessageStatus().GetStatus() == gmproto.MessageStatusType_MESSAGE_DELETED &&
				b.syncingConversations.Load() > 0 {
				return
			}
		}
		b.mu.Lock()
		seq := b.connSeq
		var cli *libgm.Client
		if b.gen == gen {
			cli = b.client
		}
		b.mu.Unlock()
		if _, ok := evt.(*events.ListenTemporaryError); ok && cli != nil && longPollOpen(cli) {
			return
		}
		b.disp.push(func() { b.processEvent(gen, seq, evt) })
	}
}

// processEvent runs on the dispatcher. Events from a client that has since
// been replaced or dropped (gen mismatch) are ignored, so a late error from an
// old connection cannot flip the state of a new one.
func (b *GMBridge) processEvent(gen, seq uint64, raw any) {
	if !b.isCurrentGen(gen) {
		return
	}
	switch evt := raw.(type) {
	case *libgm.WrappedMessage:
		b.handleMessage(gen, seq, evt)
	case *gmproto.Conversation:
		b.cache.putConversation(evt)
		b.deliverEvent(kindConversation, toConversationDTO(evt, b.cache))
		b.resyncIfDataResumed(gen, seq)
	case *gmproto.TypingData:
		b.deliverEvent(kindTyping, toTypingDTO(evt, b.cache.conversation(evt.GetConversationID())))
	case *gmproto.Settings:
		// Like the connector, settings without SIM cards are partial updates.
		if evt.SIMCards == nil {
			return
		}
		isDefault := b.cache.applySettings(evt)
		b.deliverEvent(kindSettings, toSettingsDTO(evt, isDefault))
	case *events.AccountChange:
		if !evt.IsFake {
			b.deliverEvent(kindAccount, accountDTO{Account: evt.GetAccount(), Enabled: evt.GetEnabled()})
		}
	case *events.AuthTokenRefreshed:
		if data := b.snapshotSession(); data != nil {
			b.deliverSession(data)
		}
	case *events.GaiaLoggedOut:
		go b.invalidate(gen, 0, stateLoggedOut, newError(codeLoggedOut, "Google account pairing was removed"))
	case *gmproto.RevokePairData:
		go b.invalidate(gen, 0, stateLoggedOut, newError(codeLoggedOut, "pairing was revoked on the phone"))
	case *events.PhoneNotResponding:
		// Phone liveness belongs to the client, not to one connection, so it
		// is recorded whichever connection reported it.
		b.phoneNotResponding.Store(true)
		b.processConnectionEvent(gen, seq, raw)
	case *events.PhoneRespondingAgain:
		b.phoneNotResponding.Store(false)
		b.processConnectionEvent(gen, seq, raw)
	default:
		b.processConnectionEvent(gen, seq, raw)
	}
}

// processConnectionEvent handles events about the connection itself. They
// only count while the connection they were raised on (seq) is still the
// wanted one: a long poll that is shutting down after Disconnect can still
// report an error, which must not turn a "disconnected" into "reconnecting"
// or tear down a newer connection.
func (b *GMBridge) processConnectionEvent(gen, seq uint64, raw any) {
	if cur, live := b.liveSeq(gen); !live || cur != seq {
		return
	}
	switch evt := raw.(type) {
	case *gmproto.UserAlertEvent:
		b.handleUserAlert(evt)
	case *events.ListenRecovered:
		b.pollDown.Store(false)
		b.deliverEvent(kindState, b.currentState())
	case *events.PhoneRespondingAgain, *events.PhoneNotResponding:
		b.deliverEvent(kindState, b.currentState())
	case *events.ListenTemporaryError:
		b.pollDown.Store(true)
		be := toBridgeError(evt.Error)
		if be == nil {
			be = newError(codeInternal, "")
		}
		b.deliverEvent(kindState, stateDTO{State: stateReconnecting, Code: be.code, Detail: be.detail})
	case *events.ListenFatalError:
		b.handleFatal(gen, seq, evt.Error)
	case *events.PingFailed:
		if errors.Is(evt.Error, events.ErrRequestedEntityNotFound) {
			go b.invalidate(gen, seq, stateLoggedOut, newError(codeLoggedOut, "pairing no longer exists"))
		} else if evt.ErrorCount == 0 && !b.browserActive.Load() {
			// ErrorCount 0 only comes from libgm's postConnect, whose
			// SetActiveSession failed (ditto ping failures count from 1).
			// Without it the phone does not push to this session.
			b.scheduleSetActiveRetry(gen, seq)
		}
	case *events.HackySetActiveMayFail:
		b.scheduleHackySetActive(gen)
	case *events.NoDataReceived:
		// libgm noticed a gap (long poll down for a while, phone silent, or
		// the phone answering again) and asked for updates; events from the
		// gap may not be redelivered. Catch up once data flows again.
		b.noDataRecently.Store(true)
	}
}

// currentState is the state that holds now, given everything still in
// force: an unresponsive phone or another active browser outlast a long-poll
// blip, and libgm reports each of them only once.
func (b *GMBridge) currentState() stateDTO {
	switch {
	case b.phoneNotResponding.Load():
		return stateDTO{State: statePhoneOffline, Code: codePhoneNotResponding}
	case b.inactiveElsewhere.Load():
		detail, _ := b.inactiveDetail.Load().(string)
		return stateDTO{State: stateActiveElsewhere, Detail: detail}
	case b.browserActive.Load():
		return stateDTO{State: stateActive}
	default:
		return stateDTO{State: stateConnected}
	}
}

// resyncIfDataResumed mirrors the connector's resyncAfterDataResume: the
// first data after a gap libgm reported asks Kotlin to catch up, once.
func (b *GMBridge) resyncIfDataResumed(gen, seq uint64) {
	if !b.noDataRecently.Load() {
		return
	}
	if cur, live := b.liveSeq(gen); !live || cur != seq {
		return
	}
	if b.noDataRecently.Swap(false) {
		b.deliverEvent(kindResync, struct{}{})
	}
}

func (b *GMBridge) handleMessage(gen, seq uint64, evt *libgm.WrappedMessage) {
	if evt.Message == nil {
		return
	}
	// The phone sends spurious deletions while it rebuilds its database; the
	// connector ignores them, and so do we. (Deletions during a
	// ListConversations call are dropped in handlerFor.)
	if evt.GetMessageStatus().GetStatus() == gmproto.MessageStatusType_MESSAGE_DELETED && b.syncingMobileDB.Load() {
		return
	}
	b.cache.indexMessage(evt.Message)
	b.cache.noteProtocolSwitch(evt.Message)
	conv := b.cache.conversation(evt.GetConversationID())
	b.deliverEvent(kindMessage, toMessageDTO(evt.Message, evt.IsOld, conv, b.cache))
	b.resyncIfDataResumed(gen, seq)
}

func (b *GMBridge) handleUserAlert(evt *gmproto.UserAlertEvent) {
	switch evt.GetAlertType() {
	case gmproto.AlertType_BROWSER_ACTIVE:
		b.browserActive.Store(true)
		b.inactiveElsewhere.Store(false)
		b.deliverEvent(kindState, b.currentState())
		// Like the connector: catch up when this session becomes active
		// after another one was (useHere also resets the session ID), after
		// a data gap, or after a retried SetActiveSession. Kotlin syncs
		// once per Connect by itself, so only these later cases need it.
		// All three flags are cleared.
		wasInactive := b.wasInactive.Swap(false)
		noData := b.noDataRecently.Swap(false)
		retried := b.needResync.Swap(false)
		if wasInactive || noData || retried {
			b.deliverEvent(kindResync, struct{}{})
		}
	case gmproto.AlertType_BROWSER_INACTIVE,
		gmproto.AlertType_BROWSER_INACTIVE_FROM_TIMEOUT,
		gmproto.AlertType_BROWSER_INACTIVE_FROM_INACTIVITY:
		b.browserActive.Store(false)
		b.inactiveElsewhere.Store(true)
		b.inactiveDetail.Store(evt.GetAlertType().String())
		b.wasInactive.Store(true)
		b.deliverEvent(kindState, b.currentState())
	case gmproto.AlertType_MOBILE_DATABASE_SYNC_STARTED:
		b.syncingMobileDB.Store(true)
	case gmproto.AlertType_MOBILE_DATABASE_SYNC_COMPLETE:
		b.syncingMobileDB.Store(false)
		b.deliverEvent(kindResync, struct{}{})
	}
}

// handleFatal: the long poll has exited for good. Bad cookies and a vanished
// pairing get their own states so the UI can say what to fix.
func (b *GMBridge) handleFatal(gen, seq uint64, err error) {
	switch {
	case errors.Is(err, events.ErrRequestedEntityNotFound):
		go b.invalidate(gen, seq, stateLoggedOut, newError(codeLoggedOut, "pairing no longer exists"))
	case errors.Is(err, events.ErrInvalidCredentials) || isHTTPAuthError(err):
		go b.invalidate(gen, seq, stateCookiesInvalid, newError(codeCookiesInvalid, "Google rejected the account cookies"))
	default:
		go b.invalidate(gen, seq, stateError, err)
	}
}

func isHTTPAuthError(err error) bool {
	status := httpStatus(err)
	return status == 401 || status == 403
}

// scheduleHackySetActive mirrors the connector's hackyResetActive: if the
// phone has not confirmed this session as active 7 s after libgm warned, send
// SetActiveSession once more. Once per connection.
func (b *GMBridge) scheduleHackySetActive(gen uint64) {
	if !b.hackyScheduled.CompareAndSwap(false, true) {
		return
	}
	b.mu.Lock()
	ctx := b.connCtx
	b.mu.Unlock()
	if ctx == nil {
		return
	}
	go func() {
		defer func() { _ = recover() }()
		select {
		case <-ctx.Done():
			return
		case <-time.After(hackySetActiveDelay):
		}
		if b.browserActive.Load() || !b.isCurrentGen(gen) {
			return
		}
		cli, err := b.liveClient()
		if err != nil {
			return
		}
		callCtx, cancel := context.WithTimeout(ctx, rpcTimeout)
		defer cancel()
		_ = cli.SetActiveSession(callCtx)
	}()
}

// scheduleSetActiveRetry retries the SetActiveSession that libgm's
// postConnect sends once and gives up on (it only reports PingFailed). Until
// one goes through the phone does not push to this session, and libgm itself
// would only notice about 10 minutes later. Retries stop once the phone
// confirms the session (BROWSER_ACTIVE) or the connection changes. A retry
// that goes through makes the next BROWSER_ACTIVE ask Kotlin to resync, for
// whatever arrived meanwhile. Once per connection; separate from the hacky
// retry so the two cannot race over one flag.
func (b *GMBridge) scheduleSetActiveRetry(gen, seq uint64) {
	if !b.setActiveRetrying.CompareAndSwap(false, true) {
		return
	}
	b.mu.Lock()
	ctx := b.connCtx
	b.mu.Unlock()
	if ctx == nil {
		return
	}
	delays := slices.Clone(setActiveRetryDelays)
	call := setActiveSession
	go func() {
		defer func() { _ = recover() }()
		for _, delay := range delays {
			select {
			case <-ctx.Done():
				return
			case <-time.After(delay):
			}
			if b.browserActive.Load() {
				return
			}
			if cur, live := b.liveSeq(gen); !live || cur != seq {
				return
			}
			cli, err := b.liveClient()
			if err != nil {
				return
			}
			err = func() error {
				callCtx, cancel := context.WithTimeout(ctx, rpcTimeout)
				defer cancel()
				return call(callCtx, cli)
			}()
			if err == nil {
				if cur, live := b.liveSeq(gen); live && cur == seq {
					b.needResync.Store(true)
				}
				return
			}
		}
	}()
}
