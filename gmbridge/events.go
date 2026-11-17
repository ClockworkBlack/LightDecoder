package gmbridge

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"sync"
	"sync/atomic"
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
	// kindTokenRefreshed: libgm refreshed the session token (payload {}). The
	// "session" callback that follows a refresh also follows cookie
	// rotations, so it cannot tell the two apart.
	kindTokenRefreshed = "token_refreshed"
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
// libgm warns that its own SetActiveSession may have been swallowed. Tests
// shorten it.
var hackySetActiveDelay = 7 * time.Second

// setActiveRetryDelays are the waits before each retry of a SetActiveSession
// that failed right after the long poll opened. Tests shorten them.
var setActiveRetryDelays = []time.Duration{5 * time.Second, 15 * time.Second, 45 * time.Second}

// setActiveSession is cli.SetActiveSession; tests replace it.
var setActiveSession = func(ctx context.Context, cli *libgm.Client) error {
	return cli.SetActiveSession(ctx)
}

// pushThrottleResyncDelay is how long after the phone stops throttling its
// pushes the bridge asks Kotlin to catch up: the connector gives the pushes
// the phone held back this long to arrive first (pendingSendResyncDelay in
// its chatsync.go). Tests shorten it.
var pushThrottleResyncDelay = 15 * time.Second

// stopLongPoll is cli.Disconnect, used to close a long poll that libgm
// reopened by itself after Disconnect; tests replace it.
var stopLongPoll = (*libgm.Client).Disconnect

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
		b.noteDataReceived(0)
	case *gmproto.TypingData:
		b.deliverEvent(kindTyping, toTypingDTO(evt, b.cache.conversation(evt.GetConversationID())))
	case *gmproto.Settings:
		// Like the connector, settings without SIM cards are partial updates.
		if evt.SIMCards == nil {
			return
		}
		isDefault := b.cache.applySettings(evt)
		b.deliverEvent(kindSettings, toSettingsDTO(evt, isDefault))
		// Like the connector: while data is missing, settings do not move
		// the stall check's anchor (they arrive on every reconnect).
		if !b.noDataRecently.Load() {
			b.noteDataReceived(0)
		}
	case *events.AccountChange:
		if !evt.IsFake {
			b.deliverEvent(kindAccount, accountDTO{Account: evt.GetAccount(), Enabled: evt.GetEnabled()})
		}
	case *events.AuthTokenRefreshed:
		if data := b.snapshotSession(); data != nil {
			b.deliverSession(data)
		}
		b.deliverEvent(kindTokenRefreshed, struct{}{})
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
	case *events.ListenRecovered:
		if _, live := b.liveSeq(gen); !live {
			// A long poll opened although no connection is wanted (every
			// new libgm long-poll loop reports ListenRecovered when it
			// first opens). See stopUnwantedLongPoll.
			go b.stopUnwantedLongPoll(gen)
			return
		}
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
		b.handleUserAlert(gen, seq, evt)
	case *events.ListenRecovered:
		b.pollDown.Store(false)
		b.deliverCurrentState()
	case *events.PhoneRespondingAgain, *events.PhoneNotResponding:
		// processEvent has recorded the phone's liveness; while the long
		// poll is down "reconnecting" stays (see deliverCurrentState).
		b.deliverCurrentState()
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
		} else if evt.ErrorCount == 0 {
			// ErrorCount 0 only comes from libgm's postConnect, whose
			// SetActiveSession failed (ditto ping failures count from 1).
			// Without it the phone does not push to this session. That
			// call had already replaced the session ID, so a BROWSER_ACTIVE
			// seen before (on an earlier long-poll loop of this connection:
			// Connect's restart, libgm's ping recovery) no longer holds.
			if b.browserActive.Swap(false) {
				b.deliverCurrentState()
			}
			b.scheduleSetActiveRetry(gen, seq)
		}
	case *events.HackySetActiveMayFail:
		b.scheduleHackySetActive(gen, seq)
	case *events.NoDataReceived:
		// libgm noticed a gap (long poll down for a while, phone silent, or
		// the phone answering again) and asked for updates; events from the
		// gap may not be redelivered. Catch up once data flows again, and
		// check whether the phone's push subscription has gone stale.
		b.noDataRecently.Store(true)
		b.scheduleStallRecovery(gen, seq)
	}
}

// deliverCurrentState reports currentState, except while the long poll is
// down: "reconnecting" (with its error) then stays until ListenRecovered,
// which reports currentState itself. Conditions that change meanwhile (the
// phone's liveness, the active session) are recorded and show up then; the
// connector likewise lets a long-poll error win over phone-not-responding.
func (b *GMBridge) deliverCurrentState() {
	if b.pollDown.Load() {
		return
	}
	b.deliverEvent(kindState, b.currentState())
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
	b.noteDataReceived(evt.GetTimestamp())
}

// noteDataReceived records that data came from the phone now: the stall
// check's anchor becomes now, or tsMicros (a message's own time) if that is
// later, as in the connector.
func (b *GMBridge) noteDataReceived(tsMicros int64) {
	b.lastDataMicros.Store(max(time.Now().UnixMicro(), tsMicros))
}

// panicHandlerFor returns the handler for panics in the background
// goroutines of client generation gen (patched libgm recovers them). The
// goroutine that panicked is gone, so the connection it belonged to is torn
// down and reported as an "error" state with code internal, as for a fatal
// long-poll error. A panic while that connection is not wanted changes
// nothing. The panic value is not reported: it may quote data.
func (b *GMBridge) panicHandlerFor(gen uint64) func(where string, recovered any) {
	return func(where string, recovered any) {
		defer func() { _ = recover() }()
		b.mu.Lock()
		seq := b.connSeq
		b.mu.Unlock()
		err := newError(codeInternal, fmt.Sprintf("unexpected failure in the connection (%s, %T)", where, recovered))
		go b.invalidate(gen, seq, stateError, err)
	}
}

func (b *GMBridge) handleUserAlert(gen, seq uint64, evt *gmproto.UserAlertEvent) {
	switch evt.GetAlertType() {
	case gmproto.AlertType_BROWSER_ACTIVE:
		b.activations.Add(1)
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
	case gmproto.AlertType_PUSH_THROTTLE_ENDED:
		// The phone held back its pushes for a while (it does so in some
		// power states), and some may never come: the connector then
		// resyncs the chats whose sent messages still lack their echo.
		// Kotlin's catch-up covers those and anything else missing.
		// PUSH_THROTTLE_STARTED needs nothing (the outbox already waits
		// long for echoes), and the *_IN_DARK_LAUNCH variants report a
		// throttle that is not applied; the connector ignores both.
		b.scheduleThrottleResync(gen, seq)
	}
}

// scheduleThrottleResync emits one resync pushThrottleResyncDelay after the
// phone reports that it stopped throttling pushes, if the connection that
// heard it is still the live one by then. Reports within the delay share
// that one resync.
func (b *GMBridge) scheduleThrottleResync(gen, seq uint64) {
	ctx := b.claimForConnection(gen, seq, &b.throttleResync)
	if ctx == nil {
		return
	}
	delay := pushThrottleResyncDelay
	go func() {
		defer func() { _ = recover() }()
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		b.disp.push(func() {
			if cur, live := b.liveSeq(gen); !live || cur != seq {
				return
			}
			b.throttleResync.Store(false)
			b.deliverEvent(kindResync, struct{}{})
		})
	}()
}

// claimForConnection starts a helper that outlives the event that asked for
// it. If connection seq of client gen is still the live one and flag is not
// set yet, it sets flag and returns that connection's context, which ends
// on Disconnect, a fatal error or Unpair; otherwise it returns nil. Connect
// resets the flags under b.mu, so checking the connection and taking the
// flag together under b.mu can neither tie a helper to a newer connection
// nor use up a newer connection's flag.
func (b *GMBridge) claimForConnection(gen, seq uint64, flag *atomic.Bool) context.Context {
	b.mu.Lock()
	defer b.mu.Unlock()
	ctx := b.liveConnCtxLocked(gen, seq)
	if ctx == nil || !flag.CompareAndSwap(false, true) {
		return nil
	}
	return ctx
}

// supersedeForConnection starts a helper that replaces any earlier one of
// its kind: if connection seq of client gen is still the live one, it moves
// epoch on and returns the new value with that connection's context;
// otherwise it returns nil and leaves epoch alone. The earlier helper sees
// the epoch move and stops. Checking and moving under b.mu, like
// claimForConnection, means an event of an older connection can never
// supersede the current connection's helper.
func (b *GMBridge) supersedeForConnection(gen, seq uint64, epoch *atomic.Uint64) (context.Context, uint64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	ctx := b.liveConnCtxLocked(gen, seq)
	if ctx == nil {
		return nil, 0
	}
	return ctx, epoch.Add(1)
}

// liveConnCtxLocked returns the context of connection seq of client gen if
// that is still the live connection, else nil. Callers hold b.mu.
func (b *GMBridge) liveConnCtxLocked(gen, seq uint64) context.Context {
	if b.gen != gen || b.client == nil || !b.live || b.connSeq != seq {
		return nil
	}
	return b.connCtx
}

// stopUnwantedLongPoll closes a long poll of client gen that opened while
// no connection is wanted. libgm's ping recovery calls Reconnect by itself,
// and after Disconnect it can still do so while the old long-poll loop sleeps
// in its error backoff (the recovery only stops when that loop returns). The
// reopened long poll would stay up with no foreground notification. Under
// the lifecycle lock, so a Connect that has just started is left alone.
func (b *GMBridge) stopUnwantedLongPoll(gen uint64) {
	defer func() { _ = recover() }()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	cli := b.client
	unwanted := b.gen == gen && cli != nil && !b.live
	b.mu.Unlock()
	if unwanted {
		stopLongPoll(cli)
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
// phone has not confirmed a session as active (BROWSER_ACTIVE) 7 s after
// libgm warned, send SetActiveSession once more. libgm warns from the
// postConnect of each long-poll loop, before that loop's SetActiveSession,
// so a BROWSER_ACTIVE from before the warning (an earlier loop) does not
// count, and a warning from a later loop replaces a pending one.
func (b *GMBridge) scheduleHackySetActive(gen, seq uint64) {
	ctx, epoch := b.supersedeForConnection(gen, seq, &b.hackyEpoch)
	if ctx == nil {
		return
	}
	activations := b.activations.Load()
	delay, call := hackySetActiveDelay, setActiveSession
	go func() {
		defer func() { _ = recover() }()
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		if b.hackyEpoch.Load() != epoch || b.activations.Load() != activations {
			return
		}
		if cur, live := b.liveSeq(gen); !live || cur != seq {
			return
		}
		cli, err := b.liveClient()
		if err != nil {
			return
		}
		func() {
			callCtx, cancel := context.WithTimeout(ctx, rpcTimeout)
			defer cancel()
			_ = call(callCtx, cli)
		}()
		// The connector's second step: still not confirmed another 7 s later,
		// with the phone answering, so the client is replaced and connected
		// again (at most once per Connect).
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		if b.hackyEpoch.Load() != epoch || b.activations.Load() != activations || b.phoneNotResponding.Load() {
			return
		}
		if cur, live := b.liveSeq(gen); !live || cur != seq {
			return
		}
		if b.hackyResetDone.CompareAndSwap(false, true) {
			b.resetClient(gen, seq)
		}
	}()
}

// resetClient is the connector's ResetClient + Connect: the client of
// connection seq is torn down and replaced by a new one for the same session
// (its pending acks are sent first), which is connected at once. Kotlin sees
// "connecting" and what follows as for any Connect; a resync follows the next
// BROWSER_ACTIVE, for anything the stuck session missed.
func (b *GMBridge) resetClient(gen, seq uint64) {
	defer func() { _ = recover() }()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	if cur, live := b.liveSeq(gen); !live || cur != seq {
		return
	}
	b.teardownLocked(gen)
	b.mu.Lock()
	old := b.client
	if b.closed || b.gen != gen || old == nil {
		b.mu.Unlock()
		return
	}
	b.mu.Unlock()
	stopAcks(old)
	sendPendingAcks(old, ackFlushTimeout)
	b.mu.Lock()
	if b.closed || b.gen != gen || b.client != old {
		b.mu.Unlock()
		return
	}
	b.client = b.newClientLocked()
	b.mu.Unlock()
	if resetConnect(b) == nil {
		b.needResync.Store(true)
	}
}

// resetConnect connects the new client of resetClient; tests replace it.
var resetConnect = (*GMBridge).connectLocked

// stallRecoveryDelays are the connector's waits before each check of a
// receive-stall recovery (2 min, then 5 min). Tests shorten them.
var stallRecoveryDelays = []time.Duration{2 * time.Minute, 5 * time.Minute, 5 * time.Minute}

// stallCheckCount is how many inbox conversations the stall check compares
// (the connector's initial_chat_sync_count default).
const stallCheckCount = 25

// listInboxForStallCheck lists the newest inbox conversations; tests replace it.
var listInboxForStallCheck = func(ctx context.Context, cli *libgm.Client) (*gmproto.ListConversationsResponse, error) {
	return cli.ListConversations(ctx, &gmproto.ListConversationsRequest{Count: stallCheckCount, Folder: gmproto.ListConversationsRequest_INBOX})
}

// scheduleStallRecovery is the connector's recoverReceiveStall (its optional
// hacky_stall_detect, always on here). libgm reported that no data came for
// a while and asked the phone for updates; give that time to arrive, then
// compare the phone's newest conversations with the last data received. If
// the phone has newer messages than the event stream delivered, the push
// subscription has gone stale although the connection looks fine: ask
// Kotlin to catch up and re-arm the subscription (SetActiveSession); if the
// next check still finds a gap, reconnect the long poll; after a third, give
// up until the next report. It only runs while the phone answers and this
// session is the active one, and stops as soon as data flows again. A newer
// report replaces a running recovery.
func (b *GMBridge) scheduleStallRecovery(gen, seq uint64) {
	ctx, epoch := b.supersedeForConnection(gen, seq, &b.stallEpoch)
	if ctx == nil {
		return
	}
	delays := slices.Clone(stallRecoveryDelays)
	list, call, restart := listInboxForStallCheck, setActiveSession, restartLongPoll
	go func() {
		defer func() { _ = recover() }()
		for attempt, delay := range delays {
			select {
			case <-ctx.Done():
				return
			case <-time.After(delay):
			}
			if b.stallEpoch.Load() != epoch || !b.noDataRecently.Load() {
				return
			}
			if cur, live := b.liveSeq(gen); !live || cur != seq {
				return
			}
			if !b.browserActive.Load() || b.phoneNotResponding.Load() || b.inactiveElsewhere.Load() {
				return
			}
			anchor := b.lastDataMicros.Load()
			if anchor == 0 {
				return
			}
			cli, err := b.liveClient()
			if err != nil {
				return
			}
			newest, gap := b.findReceiveGap(ctx, cli, list, anchor)
			if !gap {
				return
			}
			// Like the connector, move the anchor up to what the catch-up
			// covers, so the same gap is not found again.
			for cur := b.lastDataMicros.Load(); newest > cur; cur = b.lastDataMicros.Load() {
				if b.lastDataMicros.CompareAndSwap(cur, newest) {
					break
				}
			}
			b.disp.push(func() {
				if cur, live := b.liveSeq(gen); live && cur == seq {
					b.deliverEvent(kindResync, struct{}{})
				}
			})
			switch attempt {
			case 0:
				func() {
					callCtx, cancel := context.WithTimeout(ctx, rpcTimeout)
					defer cancel()
					_ = call(callCtx, cli)
				}()
			case 1:
				b.restartForStall(gen, seq, restart)
			default:
				return
			}
		}
	}()
}

// findReceiveGap reports whether one of the phone's newest inbox
// conversations has a message newer than anchorMicros, and the newest
// message time seen. An error counts as no gap.
func (b *GMBridge) findReceiveGap(ctx context.Context, cli *libgm.Client,
	list func(context.Context, *libgm.Client) (*gmproto.ListConversationsResponse, error), anchorMicros int64) (newest int64, gap bool) {
	callCtx, cancel := context.WithTimeout(ctx, rpcTimeout)
	defer cancel()
	var resp *gmproto.ListConversationsResponse
	var err error
	b.whileSyncingConversations(func() { resp, err = list(callCtx, cli) })
	if err != nil {
		return 0, false
	}
	for _, conv := range resp.GetConversations() {
		ts := conv.GetLastMessageTimestamp()
		newest = max(newest, ts)
		if ts > anchorMicros {
			gap = true
		}
	}
	return newest, gap
}

// restartForStall restarts the long poll of connection seq, under the
// lifecycle lock so that it cannot reopen a connection that was closed.
// What the phone sent meanwhile is caught up on the next data (as Connect's
// restart does).
func (b *GMBridge) restartForStall(gen, seq uint64, restart func(*libgm.Client) error) {
	defer func() { _ = recover() }()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	if cur, live := b.liveSeq(gen); !live || cur != seq {
		return
	}
	cli, err := b.liveClient()
	if err != nil {
		return
	}
	_ = restart(cli)
}

// scheduleSetActiveRetry retries the SetActiveSession that libgm's
// postConnect sends once and gives up on (it only reports PingFailed). Until
// one goes through the phone does not push to this session, and libgm itself
// would only notice about 10 minutes later. Retries stop once the phone
// confirms the session (BROWSER_ACTIVE) or the connection changes. A retry
// that goes through makes the next BROWSER_ACTIVE ask Kotlin to resync, for
// whatever arrived meanwhile. Every failure is retried, since each long-poll
// loop of a connection sends its own (Connect's restart and libgm's ping
// recovery start new loops); a newer failure's retries replace an older
// one's. Separate from the hacky retry so the two cannot interfere.
func (b *GMBridge) scheduleSetActiveRetry(gen, seq uint64) {
	ctx, epoch := b.supersedeForConnection(gen, seq, &b.setActiveEpoch)
	if ctx == nil {
		return
	}
	activations := b.activations.Load()
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
			// Confirmed since the failure, or a newer failure took over.
			if b.activations.Load() != activations || b.setActiveEpoch.Load() != epoch {
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
