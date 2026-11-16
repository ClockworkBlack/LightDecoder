package gmbridge

import (
	"context"
	"errors"
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
func (b *GMBridge) handlerFor(gen uint64) libgm.EventHandler {
	return func(evt any) {
		defer func() { _ = recover() }()
		b.mu.Lock()
		seq := b.connSeq
		b.mu.Unlock()
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
		b.handleMessage(evt)
	case *gmproto.Conversation:
		b.cache.putConversation(evt)
		b.deliverEvent(kindConversation, toConversationDTO(evt, b.cache))
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
		b.deliverEvent(kindState, stateDTO{State: stateConnected})
	case *events.PhoneRespondingAgain:
		b.deliverEvent(kindState, stateDTO{State: stateConnected})
	case *events.PhoneNotResponding:
		b.deliverEvent(kindState, stateDTO{State: statePhoneOffline, Code: codePhoneNotResponding})
	case *events.ListenTemporaryError:
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
		}
	case *events.HackySetActiveMayFail:
		b.scheduleHackySetActive(gen)
	}
}

func (b *GMBridge) handleMessage(evt *libgm.WrappedMessage) {
	if evt.Message == nil {
		return
	}
	// The phone sends spurious deletions while it rebuilds its database; the
	// connector ignores them, and so do we.
	if evt.GetMessageStatus().GetStatus() == gmproto.MessageStatusType_MESSAGE_DELETED && b.syncingMobileDB.Load() {
		return
	}
	b.cache.indexMessage(evt.Message)
	conv := b.cache.conversation(evt.GetConversationID())
	b.deliverEvent(kindMessage, toMessageDTO(evt.Message, evt.IsOld, conv, b.cache))
}

func (b *GMBridge) handleUserAlert(evt *gmproto.UserAlertEvent) {
	switch evt.GetAlertType() {
	case gmproto.AlertType_BROWSER_ACTIVE:
		b.browserActive.Store(true)
		b.deliverEvent(kindState, stateDTO{State: stateActive})
	case gmproto.AlertType_BROWSER_INACTIVE,
		gmproto.AlertType_BROWSER_INACTIVE_FROM_TIMEOUT,
		gmproto.AlertType_BROWSER_INACTIVE_FROM_INACTIVITY:
		b.browserActive.Store(false)
		b.deliverEvent(kindState, stateDTO{State: stateActiveElsewhere, Detail: evt.GetAlertType().String()})
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
