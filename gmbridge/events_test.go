package gmbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func TestDispatcherKeepsOrder(t *testing.T) {
	d := newDispatcher()
	var mu sync.Mutex
	var got []int
	const n = 5000
	for i := 0; i < n; i++ {
		d.push(func() {
			mu.Lock()
			got = append(got, i)
			mu.Unlock()
		})
	}
	d.close(5 * time.Second)
	if len(got) != n {
		t.Fatalf("delivered %d of %d (close must drain the queue)", len(got), n)
	}
	for i, v := range got {
		if v != i {
			t.Fatalf("out of order at %d: %d", i, v)
		}
	}
	if d.push(func() {}) {
		t.Error("push after close should be refused")
	}
}

func TestDispatcherNeverBlocksProducers(t *testing.T) {
	d := newDispatcher()
	release := make(chan struct{})
	d.push(func() { <-release }) // a slow consumer
	start := time.Now()
	for i := 0; i < 100000; i++ {
		d.push(func() {})
	}
	if time.Since(start) > 2*time.Second {
		t.Error("push should not wait for the consumer")
	}
	close(release)
	d.close(5 * time.Second)
}

func TestDispatcherSurvivesPanics(t *testing.T) {
	d := newDispatcher()
	var ran []string
	d.push(func() { panic("job panic") })
	d.push(func() { ran = append(ran, "after") })
	d.close(2 * time.Second)
	if len(ran) != 1 {
		t.Fatal("a panicking job must not stop the dispatcher")
	}
}

func TestCallbackPanicIsContained(t *testing.T) {
	b, rec := newPairedBridge(t)
	rec.panicOn = kindConversation
	gen := markLive(b)
	h := b.handlerFor(gen)
	h(&gmproto.Conversation{ConversationID: "c1"})
	h(&events.ListenRecovered{})
	waitFor(t, "state after a panicking callback", func() bool { return len(rec.states()) == 1 })
}

func TestStateEventsFromSyntheticLibgmEvents(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	h := b.handlerFor(gen)

	h(&events.ListenRecovered{})
	h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE})
	h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_INACTIVE_FROM_TIMEOUT})
	h(&events.PhoneNotResponding{})
	h(&events.PhoneRespondingAgain{})
	h(&events.ListenTemporaryError{Error: fmt.Errorf("%w in 1m0s", libgm.ErrPhoneNotResponding)})
	h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_MOBILE_BATTERY_LOW}) // ignored

	// PhoneRespondingAgain goes back to what still holds: another browser
	// is still the active session.
	want := []string{stateConnected, stateActive, stateActiveElsewhere, statePhoneOffline, stateActiveElsewhere, stateReconnecting}
	waitFor(t, "six states", func() bool { return len(rec.states()) == len(want) })
	for i, s := range rec.states() {
		if s != want[i] {
			t.Fatalf("states = %v, want %v", rec.states(), want)
		}
	}
	var last stateDTO
	_ = json.Unmarshal(rec.eventsOf(kindState)[5], &last)
	if last.Code != codePhoneNotResponding {
		t.Errorf("reconnecting should carry the error code: %+v", last)
	}
}

func TestDataEvents(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	h := b.handlerFor(gen)

	h(&gmproto.Settings{SIMCards: []*gmproto.SIMCard{{SIMParticipant: &gmproto.SIMParticipant{ID: "5"}}}})
	h(&gmproto.Settings{}) // partial: no SIM cards, ignored like the connector
	h(sampleConversation())
	h(&libgm.WrappedMessage{Message: sampleMessage(), IsOld: true})
	h(&gmproto.TypingData{ConversationID: "c1", User: &gmproto.User{Number: "+15550000007"}, Type: gmproto.TypingTypes_STARTED_TYPING})
	h(&events.AccountChange{AccountChangeOrSomethingEvent: &gmproto.AccountChangeOrSomethingEvent{Account: "a@example.com", Enabled: true}})
	h(&events.AccountChange{AccountChangeOrSomethingEvent: &gmproto.AccountChangeOrSomethingEvent{Account: "fake@example.com"}, IsFake: true})

	waitFor(t, "account event", func() bool { return len(rec.eventsOf(kindAccount)) == 1 })
	if n := len(rec.eventsOf(kindSettings)); n != 1 {
		t.Errorf("settings events: %d", n)
	}
	var msg messageDTO
	_ = json.Unmarshal(rec.eventsOf(kindMessage)[0], &msg)
	if msg.ID != "m1" || !msg.IsOld || msg.SenderName != "Alice Example" || msg.Reactions[0].FromMe != true {
		t.Errorf("message event should use the cached conversation and settings: %+v", msg)
	}
	var typing typingDTO
	_ = json.Unmarshal(rec.eventsOf(kindTyping)[0], &typing)
	if typing.ParticipantID != "7" || !typing.Typing {
		t.Errorf("typing: %+v", typing)
	}
	if b.cache.conversation("c1") == nil {
		t.Error("conversation events should fill the cache")
	}
	if m, ok := b.cache.message("m1"); !ok || m.conversationID != "c1" {
		t.Error("message events should fill the message index")
	}
}

func TestDeletesDroppedDuringMobileResync(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	h := b.handlerFor(gen)
	deleted := func(id string) *libgm.WrappedMessage {
		return &libgm.WrappedMessage{Message: &gmproto.Message{MessageID: id, ConversationID: "c1",
			MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_MESSAGE_DELETED}}}
	}
	h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_MOBILE_DATABASE_SYNC_STARTED})
	h(deleted("during"))
	h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_MOBILE_DATABASE_SYNC_COMPLETE})
	h(deleted("after"))
	waitFor(t, "resync + one deletion", func() bool {
		return len(rec.eventsOf(kindResync)) == 1 && len(rec.eventsOf(kindMessage)) == 1
	})
	var msg messageDTO
	_ = json.Unmarshal(rec.eventsOf(kindMessage)[0], &msg)
	if msg.ID != "after" || msg.Direction != "deleted" {
		t.Errorf("only the deletion after the sync should pass: %+v", msg)
	}
	if string(rec.eventsOf(kindResync)[0]) != "{}" {
		t.Errorf("resync payload: %s", rec.eventsOf(kindResync)[0])
	}
}

func TestGaiaLoggedOutClearsPairing(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	b.handlerFor(gen)(&events.GaiaLoggedOut{})
	waitFor(t, "logged_out state", func() bool {
		s := rec.states()
		return len(s) == 1 && s[0] == stateLoggedOut
	})
	if b.IsPaired() {
		t.Error("pairing should be cleared")
	}
	auth, _, _, err := parseSession(rec.lastSession())
	if err != nil || auth == nil || isPaired(auth) || auth.Cookies["SID"] == "" {
		t.Errorf("persisted session should keep cookies but not the pairing: %v", err)
	}
	if codeOf(b.Connect()) != codeNotPaired {
		t.Error("Connect after logout should say not_paired")
	}
	// Events from the dropped client are ignored.
	b.handlerFor(gen)(&events.ListenRecovered{})
	time.Sleep(50 * time.Millisecond)
	if len(rec.states()) != 1 {
		t.Errorf("stale client events leaked: %v", rec.states())
	}
}

func TestFatalErrors(t *testing.T) {
	cases := []struct {
		err   error
		state string
		code  string
	}{
		{events.HTTPError{Action: "polling", Resp: &http.Response{StatusCode: 401}}, stateCookiesInvalid, codeCookiesInvalid},
		{fmt.Errorf("failed to refresh auth token: %w", events.ErrInvalidCredentials), stateCookiesInvalid, codeCookiesInvalid},
		{fmt.Errorf("failed to refresh auth token: %w", events.ErrRequestedEntityNotFound), stateLoggedOut, codeLoggedOut},
		{fmt.Errorf("failed to reconnect: %w", fmt.Errorf("boom")), stateError, codeInternal},
	}
	for _, c := range cases {
		b, rec := newPairedBridge(t)
		gen := markLive(b)
		b.handlerFor(gen)(&events.ListenFatalError{Error: c.err})
		waitFor(t, c.state, func() bool { return len(rec.states()) == 1 })
		var st stateDTO
		_ = json.Unmarshal(rec.eventsOf(kindState)[0], &st)
		if st.State != c.state || st.Code != c.code {
			t.Errorf("%v: got %+v want %s/%s", c.err, st, c.state, c.code)
		}
		if _, err := b.ListContacts(); codeOf(err) != codeNotConnected && codeOf(err) != codeNotPaired {
			t.Errorf("after a fatal error calls must fail fast, got %v", err)
		}
		if rec.sessionCount() == 0 {
			t.Error("session should be persisted on a fatal error")
		}
	}
}

func TestPingFailedNotFoundLogsOut(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	h := b.handlerFor(gen)
	h(&events.PingFailed{Error: fmt.Errorf("other"), ErrorCount: 3}) // ignored
	h(&events.PingFailed{Error: fmt.Errorf("x: %w", events.ErrRequestedEntityNotFound)})
	waitFor(t, "logged_out", func() bool { return len(rec.states()) == 1 })
	if rec.states()[0] != stateLoggedOut || b.IsPaired() {
		t.Errorf("states %v paired %v", rec.states(), b.IsPaired())
	}
}

func TestQueuedEventFromOlderConnectionIsIgnored(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	h := b.handlerFor(gen)
	// Hold the dispatcher so the fatal error is still queued when Kotlin
	// disconnects and connects again.
	release := make(chan struct{})
	b.disp.push(func() { <-release })
	h(&events.ListenFatalError{Error: fmt.Errorf("from the old long poll")})
	markLive(b) // Disconnect + Connect: a newer connection
	close(release)
	time.Sleep(50 * time.Millisecond)
	if _, live := b.liveSeq(gen); !live || len(rec.states()) != 0 {
		t.Fatalf("a queued fatal error from an older connection acted on the new one: %v", rec.states())
	}
}

func TestConnectionEventsIgnoredWhenNotLive(t *testing.T) {
	b, rec := newPairedBridge(t)
	b.mu.Lock()
	gen := b.gen
	b.mu.Unlock()
	h := b.handlerFor(gen)
	// A long poll that is shutting down after Disconnect may still report.
	h(&events.ListenTemporaryError{Error: fmt.Errorf("context canceled")})
	h(&events.ListenFatalError{Error: fmt.Errorf("late")})
	h(&gmproto.Conversation{ConversationID: "c2"}) // data still flows
	waitFor(t, "conversation", func() bool { return len(rec.eventsOf(kindConversation)) == 1 })
	time.Sleep(50 * time.Millisecond)
	if len(rec.states()) != 0 || !b.IsPaired() {
		t.Errorf("connection events while disconnected must be ignored: %v", rec.states())
	}
}

func TestAuthTokenRefreshedPersists(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	b.handlerFor(gen)(&events.AuthTokenRefreshed{})
	waitFor(t, "session", func() bool { return rec.sessionCount() == 1 })
	auth, phoneID, account, err := parseSession(rec.lastSession())
	if err != nil || !isPaired(auth) || phoneID == "" || account != "someone@example.com" {
		t.Errorf("persisted session: %v %q %q", err, phoneID, account)
	}
}

func TestStaleFatalDoesNotKillNewerConnection(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	b.mu.Lock()
	oldSeq := b.connSeq
	b.mu.Unlock()
	markLive(b) // Disconnect + Connect again: a newer connection
	b.invalidate(gen, oldSeq, stateError, fmt.Errorf("from the old long poll"))
	if _, live := b.liveSeq(gen); !live {
		t.Fatal("a fatal error from an older connection tore down the new one")
	}
	time.Sleep(20 * time.Millisecond)
	if len(rec.states()) != 0 {
		t.Errorf("no state expected: %v", rec.states())
	}
	// A lost pairing applies regardless of which connection reported it.
	b.invalidate(gen, oldSeq, stateLoggedOut, newError(codeLoggedOut, "gone"))
	if b.IsPaired() {
		t.Error("logged_out must clear the pairing")
	}
}

// The phone sends spurious MESSAGE_DELETED updates while it serves the
// conversation list (connector: syncingConversations). They must never reach
// Kotlin, which would delete archived messages for good.
func TestDeletesDroppedDuringConversationSync(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	h := b.handlerFor(gen)
	deleted := func(id string) *libgm.WrappedMessage {
		return &libgm.WrappedMessage{Message: &gmproto.Message{MessageID: id, ConversationID: "c1",
			MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_MESSAGE_DELETED}}}
	}
	b.whileSyncingConversations(func() {
		h(deleted("during"))
		h(&libgm.WrappedMessage{Message: sampleMessage()}) // other updates still flow
	})
	h(deleted("after"))
	waitFor(t, "two messages", func() bool { return len(rec.eventsOf(kindMessage)) == 2 })
	drain(t, b)
	var ids []string
	for _, p := range rec.eventsOf(kindMessage) {
		var msg messageDTO
		_ = json.Unmarshal(p, &msg)
		ids = append(ids, msg.ID)
	}
	if len(ids) != 2 || ids[0] != "m1" || ids[1] != "after" {
		t.Errorf("only the non-deletion and the deletion after the sync should pass: %v", ids)
	}

	// The count stays balanced even if the call panics.
	func() {
		defer func() { _ = recover() }()
		b.whileSyncingConversations(func() { panic("libgm blew up") })
	}()
	if n := b.syncingConversations.Load(); n != 0 {
		t.Errorf("syncingConversations = %d after a panic", n)
	}
}

// A long-poll blip must not hide conditions that still hold: libgm reports
// PhoneNotResponding and BROWSER_INACTIVE only once.
func TestRecoveryKeepsConditionsInForce(t *testing.T) {
	last := func(rec *recorder) stateDTO {
		st := rec.stateEvents()
		return st[len(st)-1]
	}
	blip := func(h libgm.EventHandler) {
		h(&events.ListenTemporaryError{Error: fmt.Errorf("network down")})
		h(&events.ListenRecovered{})
	}

	t.Run("phone offline", func(t *testing.T) {
		b, rec := newPairedBridge(t)
		h := b.handlerFor(markLive(b))
		h(&events.ListenRecovered{})
		h(&events.PhoneNotResponding{})
		blip(h)
		waitFor(t, "four states", func() bool { return len(rec.states()) == 4 })
		if st := last(rec); st.State != statePhoneOffline || st.Code != codePhoneNotResponding {
			t.Errorf("after the blip: %+v (states %v)", st, rec.states())
		}
		h(&events.PhoneRespondingAgain{})
		waitFor(t, "five states", func() bool { return len(rec.states()) == 5 })
		if st := last(rec); st.State != stateConnected {
			t.Errorf("phone back: %+v", st)
		}
	})

	t.Run("active elsewhere", func(t *testing.T) {
		b, rec := newPairedBridge(t)
		h := b.handlerFor(markLive(b))
		h(&events.ListenRecovered{})
		h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE})
		h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_INACTIVE})
		blip(h)
		waitFor(t, "five states", func() bool { return len(rec.states()) == 5 })
		if st := last(rec); st.State != stateActiveElsewhere || st.Detail != gmproto.AlertType_BROWSER_INACTIVE.String() {
			t.Errorf("after the blip: %+v (states %v)", st, rec.states())
		}
		h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE})
		waitFor(t, "six states", func() bool { return len(rec.states()) == 6 })
		if st := last(rec); st.State != stateActive {
			t.Errorf("used here again: %+v", st)
		}
		blip(h)
		waitFor(t, "eight states", func() bool { return len(rec.states()) == 8 })
		if st := last(rec); st.State != stateActive {
			t.Errorf("a blip while active should come back as active: %+v", st)
		}
	})

	t.Run("phone offline across reconnect", func(t *testing.T) {
		b, rec := newPairedBridge(t)
		h := b.handlerFor(markLive(b))
		h(&events.ListenRecovered{})
		h(&events.PhoneNotResponding{})
		drain(t, b)
		b.Disconnect()
		markLive(b) // Connect again on the same client
		h(&events.ListenRecovered{})
		waitFor(t, "four states", func() bool { return len(rec.states()) == 4 })
		if s := rec.states(); s[2] != stateDisconnected || last(rec).State != statePhoneOffline {
			t.Errorf("libgm will not report PhoneNotResponding again for this client: %v", s)
		}
	})

	t.Run("new client starts fresh", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		b.phoneNotResponding.Store(true)
		b.mu.Lock()
		b.client = b.newClientLocked()
		b.mu.Unlock()
		if b.currentState().State != stateConnected {
			t.Errorf("a new client has fresh liveness: %+v", b.currentState())
		}
	})
}

// Events from the gaps (another browser active, libgm's NoDataReceived) are
// not redelivered, so Kotlin is asked to catch up, once per gap.
func TestResyncAfterGaps(t *testing.T) {
	msg := func(id string) *libgm.WrappedMessage {
		return &libgm.WrappedMessage{Message: &gmproto.Message{MessageID: id, ConversationID: "c1",
			MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_COMPLETE}}}
	}
	active := &gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE}
	resyncs := func(t *testing.T, b *GMBridge, rec *recorder) int {
		drain(t, b)
		return len(rec.eventsOf(kindResync))
	}

	b, rec := newPairedBridge(t)
	h := b.handlerFor(markLive(b))
	h(&events.ListenRecovered{})
	h(active)
	if n := resyncs(t, b, rec); n != 0 {
		t.Fatalf("the first BROWSER_ACTIVE of a connection needs no resync (Kotlin syncs on connect): %d", n)
	}
	h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_INACTIVE_FROM_INACTIVITY})
	h(msg("elsewhere"))
	h(active) // "use here"
	if n := resyncs(t, b, rec); n != 1 {
		t.Fatalf("active again after another browser: want 1 resync, got %d", n)
	}
	h(active)
	if n := resyncs(t, b, rec); n != 1 {
		t.Fatalf("a repeated BROWSER_ACTIVE must not resync again: %d", n)
	}

	h(&events.NoDataReceived{})
	h(&gmproto.Conversation{ConversationID: "c2"})
	h(msg("m2"))
	if n := resyncs(t, b, rec); n != 2 {
		t.Fatalf("data after a gap: want one more resync, got %d", n)
	}

	h(&events.NoDataReceived{})
	h(active)
	h(msg("m3"))
	if n := resyncs(t, b, rec); n != 3 {
		t.Fatalf("BROWSER_ACTIVE after a gap resyncs once and clears the gap: %d", n)
	}

	// A gap reported by a connection that has been closed does not count.
	h(&events.NoDataReceived{})
	drain(t, b)
	b.Disconnect()
	h(msg("m4"))
	if n := resyncs(t, b, rec); n != 3 {
		t.Fatalf("no resync while disconnected: %d", n)
	}
}

// libgm's postConnect sends SetActiveSession once and only reports a failure
// as PingFailed{ErrorCount: 0}; the bridge retries it.
func TestSetActiveSessionRetriedAfterPostConnectFailure(t *testing.T) {
	stub(t, &setActiveRetryDelays, []time.Duration{10 * time.Millisecond, 10 * time.Millisecond, 10 * time.Millisecond})
	var mu sync.Mutex
	calls := 0
	stub(t, &setActiveSession, func(context.Context, *libgm.Client) error {
		mu.Lock()
		defer mu.Unlock()
		calls++
		if calls == 1 {
			return fmt.Errorf("network blip")
		}
		return nil
	})
	callCount := func() int {
		mu.Lock()
		defer mu.Unlock()
		return calls
	}

	b, rec := newPairedBridge(t)
	h := b.handlerFor(markLive(b))
	h(&events.ListenRecovered{})
	h(&events.PingFailed{Error: fmt.Errorf("ditto ping"), ErrorCount: 2}) // a ping failure: not retried
	drain(t, b)
	time.Sleep(50 * time.Millisecond)
	if callCount() != 0 {
		t.Fatal("ping failures must not trigger SetActiveSession retries")
	}

	h(&events.PingFailed{Error: fmt.Errorf("failed to set active session: boom")})
	waitFor(t, "a successful retry", func() bool { return callCount() == 2 })
	time.Sleep(50 * time.Millisecond)
	if callCount() != 2 {
		t.Errorf("retries must stop after one goes through: %d", callCount())
	}
	h(&events.PingFailed{Error: fmt.Errorf("failed to set active session: again")})
	drain(t, b)
	time.Sleep(50 * time.Millisecond)
	if callCount() != 2 {
		t.Errorf("once per connection: %d", callCount())
	}
	h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE})
	waitFor(t, "resync after the retried activation", func() bool { return len(rec.eventsOf(kindResync)) == 1 })

	// Already active: nothing to retry.
	b2, _ := newPairedBridge(t)
	h2 := b2.handlerFor(markLive(b2))
	h2(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE})
	h2(&events.PingFailed{Error: fmt.Errorf("failed to set active session: boom")})
	drain(t, b2)
	time.Sleep(50 * time.Millisecond)
	if callCount() != 2 {
		t.Errorf("no retry when the session is already active: %d", callCount())
	}
}

// Connect while connected restarts a long poll that is stuck in libgm's
// growing backoff, and only then.
func TestConnectWhileLiveRestartsADownLongPoll(t *testing.T) {
	open := false
	restarts := 0
	stub(t, &longPollOpen, func(*libgm.Client) bool { return open })
	stub(t, &restartLongPoll, func(*libgm.Client) error { restarts++; return nil })

	b, rec := newPairedBridge(t)
	h := b.handlerFor(markLive(b))
	if err := b.Connect(); err != nil || restarts != 0 {
		t.Fatalf("before the first error: err=%v restarts=%d", err, restarts)
	}
	h(&events.ListenTemporaryError{Error: fmt.Errorf("network down")})
	drain(t, b)
	if err := b.Connect(); err != nil || restarts != 1 {
		t.Fatalf("long poll down: err=%v restarts=%d", err, restarts)
	}
	h(&events.ListenRecovered{})
	drain(t, b)
	if err := b.Connect(); err != nil || restarts != 1 {
		t.Fatalf("recovered: err=%v restarts=%d", err, restarts)
	}
	// A late error from the replaced loop while the new stream is open
	// must not flip the state back to reconnecting.
	open = true
	h(&events.ListenTemporaryError{Error: fmt.Errorf("from the old loop")})
	drain(t, b)
	if s := rec.states(); len(s) != 2 || s[1] != stateConnected {
		t.Errorf("states: %v", s)
	}
	if err := b.Connect(); err != nil || restarts != 1 {
		t.Fatalf("open: err=%v restarts=%d", err, restarts)
	}
}
