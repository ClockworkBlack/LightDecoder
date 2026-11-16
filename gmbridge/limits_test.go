package gmbridge

// Tests of the 0.3.0 known-limitations pass: contract items 1-5 and 8, and
// the bridge's use of the patched libgm.

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mau.fi/util/ptr"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// callLog records the order of stubbed libgm calls.
type callLog struct {
	mu    sync.Mutex
	calls []string
}

func (l *callLog) add(s string) {
	l.mu.Lock()
	l.calls = append(l.calls, s)
	l.mu.Unlock()
}

func (l *callLog) list() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.calls)
}

func (l *callLog) count(s string) int {
	n := 0
	for _, c := range l.list() {
		if c == s {
			n++
		}
	}
	return n
}

func stubAcks(t *testing.T, log *callLog) {
	t.Helper()
	stub(t, &stopAcks, func(*libgm.Client) { log.add("stop-acks") })
	stub(t, &flushAcks, func(context.Context, *libgm.Client) error { log.add("flush-acks"); return nil })
}

// --- 1. Unpair -------------------------------------------------------------------

func TestUnpairReportsRemoteFailureAsError(t *testing.T) {
	log := &callLog{}
	stubAcks(t, log)
	unpairErr := errors.New("network down")
	stub(t, &unpairGaia, func(context.Context, *libgm.Client) error { log.add("revoke"); return unpairErr })

	b, rec := newPairedBridge(t)
	markLive(b)
	err := b.Unpair()
	if err == nil || !strings.HasPrefix(err.Error(), "unpair_remote_failed: ") {
		t.Fatalf("Unpair: %v", err)
	}
	drain(t, b)
	st := rec.stateEvents()
	if len(st) == 0 || st[len(st)-1].State != stateUnpaired || st[len(st)-1].Code != codeUnpairRemoteFailed {
		t.Fatalf("the unpaired event still fires, with the code: %+v", st)
	}
	if b.IsPaired() || rec.lastSession() != nil {
		t.Fatal("the local wipe happens anyway")
	}
	// The pending acks go out before the revoke, which still needs them.
	got := strings.Join(log.list(), ",")
	if !strings.HasPrefix(got, "stop-acks,flush-acks,revoke") {
		t.Errorf("calls %q", got)
	}

	// Told: nil.
	unpairErr = nil
	b2, _ := newPairedBridge(t)
	if err := b2.Unpair(); err != nil {
		t.Fatalf("Unpair: %v", err)
	}
	// Nothing paired: nil (a plain wipe).
	if err := b2.Unpair(); err != nil {
		t.Fatalf("second Unpair: %v", err)
	}
}

// --- 2. SeedSettings --------------------------------------------------------------

func dualSIMSettings() *gmproto.Settings {
	return &gmproto.Settings{
		SIMCards: []*gmproto.SIMCard{
			{SIMParticipant: &gmproto.SIMParticipant{ID: "5"}, SIMData: &gmproto.SIMData{
				SIMPayload: &gmproto.SIMPayload{Two: 2, SIMNumber: 1}, CarrierName: "A"}},
			{SIMParticipant: &gmproto.SIMParticipant{ID: "6"}, SIMData: &gmproto.SIMData{
				SIMPayload: &gmproto.SIMPayload{Two: 2, SIMNumber: 2}, CarrierName: "B"}},
		},
		RCSSettings: &gmproto.RCSSettings{IsDefaultSMSApp: ptr.Ptr(true)},
	}
}

func TestSeedSettingsRestoresTheSIMChoice(t *testing.T) {
	// The payload of a "settings" event, as Kotlin stores it.
	src := newStateCache()
	payload := toJSON(toSettingsDTO(dualSIMSettings(), src.applySettings(dualSIMSettings())))
	if !strings.Contains(string(payload), `"simPayload":{"two":2,"simNumber":2}`) {
		t.Fatalf("the settings event must carry each SIM's payload: %s", payload)
	}

	b, _ := newPairedBridge(t)
	conv := &gmproto.Conversation{ConversationID: "c1", DefaultOutgoingID: "6"}
	if b.cache.simPayloadFor(conv) != nil {
		t.Fatal("no SIM known before the seed")
	}
	if err := b.SeedSettings(payload); err != nil {
		t.Fatal(err)
	}
	if p := b.cache.simPayloadFor(conv); p.GetSIMNumber() != 2 {
		t.Fatalf("seeded SIM: %v", p)
	}
	if !b.cache.isSelf("5") || !b.cache.isSelf("6") {
		t.Error("the SIMs are our own participants")
	}
	req := b.buildSendRequest(conv, "tmp", []*gmproto.MessageInfo{textInfo("x")}, "")
	if req.GetSIMPayload().GetSIMNumber() != 2 {
		t.Error("a send right after the seed uses the conversation's SIM")
	}

	// Bad input changes nothing.
	if err := b.SeedSettings([]byte("{nope")); codeOf(err) != codeInvalidArgument {
		t.Errorf("bad JSON: %v", err)
	}
	if b.cache.simPayloadFor(conv).GetSIMNumber() != 2 {
		t.Error("bad JSON must not clear the seed")
	}
	// Empty and null clear it.
	for _, empty := range [][]byte{nil, []byte(" null ")} {
		if err := b.SeedSettings(payload); err != nil {
			t.Fatal(err)
		}
		if err := b.SeedSettings(empty); err != nil {
			t.Fatal(err)
		}
		if b.cache.simPayloadFor(conv) != nil {
			t.Errorf("%q must clear the seed", empty)
		}
	}
	// A seed without payloads (an older stored event) only marks our own IDs.
	if err := b.SeedSettings([]byte(`{"sims":[{"participantId":"8"}]}`)); err != nil {
		t.Fatal(err)
	}
	if !b.cache.isSelf("8") || b.cache.simPayloadFor(&gmproto.Conversation{DefaultOutgoingID: "8"}) != nil {
		t.Error("payload-less seed")
	}
}

func TestPhoneSettingsWinOverTheSeed(t *testing.T) {
	b, _ := newPairedBridge(t)
	gen := markLive(b)
	seed := []byte(`{"sims":[{"participantId":"6","simPayload":{"two":2,"simNumber":9}}]}`)
	if err := b.SeedSettings(seed); err != nil {
		t.Fatal(err)
	}
	conv := &gmproto.Conversation{ConversationID: "c1", DefaultOutgoingID: "6"}
	b.handlerFor(gen)(dualSIMSettings())
	drain(t, b)
	if b.cache.simPayloadFor(conv).GetSIMNumber() != 2 {
		t.Fatal("the phone's settings replace the seed")
	}
	// A later seed (an old stored payload) is ignored, and so is a clear.
	_ = b.SeedSettings(seed)
	_ = b.SeedSettings(nil)
	if b.cache.simPayloadFor(conv).GetSIMNumber() != 2 {
		t.Fatal("a seed must not override what the phone sent")
	}
	// Unpair forgets both; a seed counts again afterwards.
	stub(t, &unpairGaia, func(context.Context, *libgm.Client) error { return nil })
	_ = b.Unpair()
	if b.cache.simPayloadFor(conv) != nil {
		t.Fatal("Unpair forgets the SIMs")
	}
	_ = b.SeedSettings(seed)
	if b.cache.simPayloadFor(conv).GetSIMNumber() != 9 {
		t.Fatal("after Unpair a seed applies again")
	}
}

// --- 3. SendMediaFile --------------------------------------------------------------

func TestSendMediaFile(t *testing.T) {
	var uploaded []byte
	var uploadedName, uploadedMime string
	stub(t, &uploadMedia, func(_ *libgm.Client, data []byte, name, mime string) (*gmproto.MediaContent, error) {
		uploaded, uploadedName, uploadedMime = append([]byte(nil), data...), name, mime
		return &gmproto.MediaContent{MediaID: "media-1"}, nil
	})
	var sent *gmproto.SendMessageRequest
	stub(t, &sendMessage, func(_ context.Context, _ *libgm.Client, req *gmproto.SendMessageRequest) (*gmproto.SendMessageResponse, error) {
		sent = req
		return &gmproto.SendMessageResponse{Status: gmproto.SendMessageResponse_SUCCESS}, nil
	})
	b, _ := newPairedBridge(t)
	markLive(b)
	b.cache.putConversation(sampleConversation())
	convID := sampleConversation().GetConversationID()

	dir := t.TempDir()
	path := filepath.Join(dir, "photo of Alice.png")
	content := append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, 100)...)
	if err := os.WriteFile(path, content, 0o600); err != nil {
		t.Fatal(err)
	}
	out, err := b.SendMediaFile(convID, "tmp-1", path, "photo.png", "", "a caption", "")
	if err != nil {
		t.Fatal(err)
	}
	var res sendResultDTO
	if err := json.Unmarshal(out, &res); err != nil || !res.OK || res.TmpID != "tmp-1" {
		t.Fatalf("result %s (%v)", out, err)
	}
	if string(uploaded) != string(content) || uploadedName != "photo.png" || uploadedMime != "image/png" {
		t.Fatalf("upload: %d bytes, %q, %q", len(uploaded), uploadedName, uploadedMime)
	}
	if infos := sent.GetMessagePayload().GetMessageInfo(); len(infos) != 2 || infos[0].GetMediaContent().GetMediaID() != "media-1" {
		t.Fatalf("sent %v", infos)
	}
	if _, err := os.Stat(path); err != nil {
		t.Error("the file is left alone")
	}

	uploaded = nil
	big := filepath.Join(dir, "big")
	f, err := os.Create(big)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.Truncate(maxUploadBytes + 1); err != nil { // sparse: nothing is written
		t.Fatal(err)
	}
	_ = f.Close()
	empty := filepath.Join(dir, "empty")
	if err := os.WriteFile(empty, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		name, path, code string
	}{
		{"too large", big, codeTooLarge},
		{"missing", filepath.Join(dir, "gone"), codeInvalidArgument},
		{"empty", empty, codeInvalidArgument},
		{"a directory", dir, codeInvalidArgument},
		{"no path", "", codeInvalidArgument},
	} {
		_, err := b.SendMediaFile(convID, "tmp-2", c.path, "x", "", "", "")
		if codeOf(err) != c.code {
			t.Errorf("%s: %v", c.name, err)
		}
		if err != nil && c.path != "" && strings.Contains(err.Error(), dir) {
			t.Errorf("%s: the error quotes the path", c.name)
		}
	}
	if uploaded != nil {
		t.Error("nothing is uploaded for a refused file")
	}
	if _, err := b.SendMediaFile("", "tmp", path, "x", "", "", ""); codeOf(err) != codeInvalidArgument {
		t.Errorf("no conversation: %v", err)
	}
	b.Disconnect()
	if _, err := b.SendMediaFile(convID, "tmp-3", path, "x", "", "", ""); codeOf(err) != codeNotConnected {
		t.Errorf("disconnected: %v", err)
	}
}

// --- 4. not_spam --------------------------------------------------------------------

func TestSetConversationStatusNotSpam(t *testing.T) {
	var reqs []*gmproto.UpdateConversationRequest
	success := true
	stub(t, &updateConversationCall, func(_ context.Context, _ *libgm.Client, req *gmproto.UpdateConversationRequest) (*gmproto.UpdateConversationResponse, error) {
		reqs = append(reqs, req)
		return &gmproto.UpdateConversationResponse{Success: success}, nil
	})
	b, _ := newPairedBridge(t)
	markLive(b)
	conv := sampleConversation()
	conv.IsGroupChat = false // a one-to-one chat: the other side's number goes along
	conv.Status = gmproto.ConversationStatus_SPAM_FOLDER
	b.cache.putConversation(conv)
	if err := b.SetConversationStatus(conv.GetConversationID(), "not_spam"); err != nil {
		t.Fatal(err)
	}
	if len(reqs) != 1 {
		t.Fatalf("%d requests", len(reqs))
	}
	req := reqs[0]
	if req.GetAction() != gmproto.ConversationActionStatus_UNBLOCK || req.GetConversationID() != conv.GetConversationID() ||
		req.GetDeleteData().GetConversationID() != conv.GetConversationID() || req.GetDeleteData().GetPhone() != "+15550000007" {
		t.Fatalf("request %v", req)
	}
	success = false
	if err := b.SetConversationStatus(conv.GetConversationID(), "not_spam"); codeOf(err) != codeSendFailed {
		t.Errorf("rejected: %v", err)
	}
	if err := b.SetConversationStatus("", "not_spam"); codeOf(err) != codeInvalidArgument {
		t.Errorf("no ID: %v", err)
	}
	if err := b.SetConversationStatus("c", "spam"); codeOf(err) != codeInvalidArgument || !strings.Contains(err.Error(), "not_spam") {
		t.Errorf("unknown status: %v", err)
	}
}

// --- 5. Disconnect while Connect is under way ------------------------------------------

func TestDisconnectCancelsAConnectInFlight(t *testing.T) {
	for _, phase := range []string{"config", "token refresh"} {
		t.Run(phase, func(t *testing.T) {
			entered := make(chan struct{})
			block := func(ctx context.Context) error {
				close(entered)
				<-ctx.Done()
				return ctx.Err()
			}
			if phase == "config" {
				stub(t, &fetchConfig, func(ctx context.Context, _ *libgm.Client) error { return block(ctx) })
			} else {
				stub(t, &fetchConfig, func(context.Context, *libgm.Client) error { return errors.New("config unavailable") })
				stub(t, &connectClient, func(ctx context.Context, _ *libgm.Client) error { return block(ctx) })
			}
			b, rec := newPairedBridge(t)
			done := make(chan error, 1)
			go func() { done <- b.Connect() }()
			<-entered
			start := time.Now()
			b.Disconnect()
			if d := time.Since(start); d > time.Second {
				t.Fatalf("Disconnect waited %v", d)
			}
			select {
			case err := <-done:
				if codeOf(err) != codeConnectionClosed {
					t.Fatalf("Connect: %v", err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("Connect did not return")
			}
			drain(t, b)
			if got := strings.Join(rec.states(), ","); got != "connecting,disconnected" {
				t.Fatalf("states %q", got)
			}
			if b.IsConnected() || !b.IsPaired() {
				t.Fatal("disconnected and still paired")
			}
			if _, err := b.ListContacts(); codeOf(err) != codeNotConnected {
				t.Errorf("after: %v", err)
			}
		})
	}
}

func TestUnpairCancelsAConnectInFlight(t *testing.T) {
	stub(t, &unpairGaia, func(context.Context, *libgm.Client) error { return nil })
	entered := make(chan struct{})
	stub(t, &fetchConfig, func(ctx context.Context, _ *libgm.Client) error {
		close(entered)
		<-ctx.Done()
		return ctx.Err()
	})
	b, rec := newPairedBridge(t)
	done := make(chan error, 1)
	go func() { done <- b.Connect() }()
	<-entered
	if err := b.Unpair(); err != nil {
		t.Fatal(err)
	}
	if err := <-done; codeOf(err) != codeConnectionClosed {
		t.Fatalf("Connect: %v", err)
	}
	drain(t, b)
	if got := strings.Join(rec.states(), ","); got != "connecting,unpaired" {
		t.Fatalf("states %q", got)
	}
}

// A Connect that is past the token refresh is not cancelled: Disconnect
// waits for it and then disconnects as usual.
func TestDisconnectLeavesAFinishedConnectAlone(t *testing.T) {
	stub(t, &fetchConfig, func(context.Context, *libgm.Client) error { return errors.New("config unavailable") })
	stub(t, &connectClient, func(context.Context, *libgm.Client) error { return nil })
	b, rec := newPairedBridge(t)
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	b.mu.Lock()
	connecting := b.connecting
	b.mu.Unlock()
	if connecting {
		t.Fatal("still marked connecting")
	}
	b.Disconnect()
	drain(t, b)
	if got := strings.Join(rec.states(), ","); got != "connecting,disconnected" {
		t.Fatalf("states %q", got)
	}
}

// --- 8. token_refreshed ------------------------------------------------------------------

func TestTokenRefreshedEvent(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	b.handlerFor(gen)(&events.AuthTokenRefreshed{})
	drain(t, b)
	got := rec.eventsOf(kindTokenRefreshed)
	if len(got) != 1 || string(got[0]) != "{}" {
		t.Fatalf("token_refreshed events: %q", got)
	}
	if rec.sessionCount() != 1 {
		t.Error("the refreshed token is persisted too")
	}
	// Other session saves are not token refreshes.
	b.Disconnect()
	drain(t, b)
	if len(rec.eventsOf(kindTokenRefreshed)) != 1 {
		t.Error("a plain session save must not report a refresh")
	}
}

// --- acks (patch c) --------------------------------------------------------------------

func TestAcksAreFlushedAndStopped(t *testing.T) {
	t.Run("disconnect", func(t *testing.T) {
		log := &callLog{}
		stubAcks(t, log)
		b, _ := newPairedBridge(t)
		markLive(b)
		b.Disconnect()
		waitFor(t, "the flush", func() bool { return log.count("flush-acks") == 1 })
		if log.list()[0] != "stop-acks" {
			t.Errorf("calls %v", log.list())
		}
	})
	t.Run("close", func(t *testing.T) {
		log := &callLog{}
		stubAcks(t, log)
		b, _ := newPairedBridge(t)
		b.Close()
		if log.count("stop-acks") == 0 || log.count("flush-acks") != 1 {
			t.Errorf("calls %v", log.list())
		}
	})
	t.Run("retired client", func(t *testing.T) {
		log := &callLog{}
		stubAcks(t, log)
		b, rec := newPairedBridge(t)
		gen := markLive(b)
		b.handlerFor(gen)(&events.GaiaLoggedOut{})
		waitFor(t, "logged_out", func() bool { return len(rec.states()) == 1 })
		if log.count("stop-acks") == 0 {
			t.Errorf("calls %v", log.list())
		}
	})
}

// --- CancelPairing (patch a) -------------------------------------------------------------

func TestCancelPairingWithdrawsThePrompt(t *testing.T) {
	var withdrawn atomic.Pointer[libgm.PairingSession]
	stub(t, &cancelGaiaPairing, func(_ *libgm.Client, ps *libgm.PairingSession) error {
		withdrawn.Store(ps)
		return nil
	})
	attempt := func(b *GMBridge, finishing bool) (*pairingAttempt, *libgm.PairingSession) {
		a := newPairingAttempt()
		ps := &libgm.PairingSession{UUID: uuid.New()}
		a.client = fakeClient(libgm.NewAuthData(), newFakeGoogle())
		a.ps, a.finishing = ps, finishing
		b.mu.Lock()
		b.pairing = a
		b.mu.Unlock()
		return a, ps
	}

	b, _ := newPairedBridge(t)
	a, ps := attempt(b, false)
	b.CancelPairing()
	waitFor(t, "the cancel to be sent", func() bool { return withdrawn.Load() == ps })
	if !a.isCancelled() {
		t.Error("the attempt is cancelled")
	}

	// While FinishPairing waits, libgm sends the cancel itself when its
	// wait is cancelled; the bridge does not send a second one.
	withdrawn.Store(nil)
	a, _ = attempt(b, true)
	b.CancelPairing()
	time.Sleep(50 * time.Millisecond)
	if withdrawn.Load() != nil || !a.isCancelled() {
		t.Error("finishing attempt")
	}

	// A new StartPairing replaces an unconfirmed emoji: withdrawn as well.
	_, ps = attempt(b, false)
	_, _ = b.StartPairing([]byte(`{}`)) // fails on the cookies, after nothing
	if withdrawn.Load() == ps {
		t.Fatal("invalid cookies must not touch the old attempt")
	}
}

// A panic in the temporary pairing client's goroutines ends the attempt with
// an internal error rather than pairing_cancelled.
func TestPairingClientPanicIsReported(t *testing.T) {
	a := newPairingAttempt()
	a.panicHandler("pairing long poll", "boom")
	if !a.isCancelled() {
		t.Fatal("the attempt is cancelled")
	}
	if err := a.cancelledError("pairing was cancelled"); codeOf(err) != codeInternal || strings.Contains(err.Error(), "boom") {
		t.Fatalf("error %v", err)
	}
}

// --- panics in libgm's goroutines (patch e) -----------------------------------------------

func TestLibgmPanicBecomesAnErrorState(t *testing.T) {
	b, rec := newPairedBridge(t)
	gen := markLive(b)
	b.panicHandlerFor(gen)("pinger", errors.New("secret detail"))
	waitFor(t, "the error state", func() bool { return len(rec.states()) == 1 })
	st := rec.stateEvents()[0]
	if st.State != stateError || st.Code != codeInternal || strings.Contains(st.Detail, "secret") {
		t.Fatalf("state %+v", st)
	}
	if _, err := b.ListContacts(); codeOf(err) != codeNotConnected {
		t.Error("the connection is torn down")
	}
	if !b.IsPaired() {
		t.Error("the pairing stays")
	}
	// Not connected: nothing to report.
	b.panicHandlerFor(gen)("acks", "x")
	time.Sleep(50 * time.Millisecond)
	drain(t, b)
	if len(rec.states()) != 1 {
		t.Errorf("states %v", rec.states())
	}
}

// --- "SetActiveSession may have failed" client reset ----------------------------------------

func TestHackyClientReset(t *testing.T) {
	const delay = 30 * time.Millisecond
	stub(t, &hackySetActiveDelay, delay)
	var setActive atomic.Int32
	stub(t, &setActiveSession, func(context.Context, *libgm.Client) error { setActive.Add(1); return nil })
	log := &callLog{}
	stubAcks(t, log)
	var resets atomic.Int32
	stub(t, &resetConnect, func(b *GMBridge) error {
		resets.Add(1)
		markLive(b) // what a successful connectLocked leaves behind
		return nil
	})
	warn := &events.HackySetActiveMayFail{}

	b, _ := newPairedBridge(t)
	gen := markLive(b)
	b.mu.Lock()
	oldClient := b.client
	b.mu.Unlock()
	b.handlerFor(gen)(warn)
	waitFor(t, "the client reset", func() bool { return resets.Load() == 1 })
	if setActive.Load() != 1 {
		t.Errorf("SetActiveSession first: %d", setActive.Load())
	}
	b.mu.Lock()
	newClient, newGen := b.client, b.gen
	b.mu.Unlock()
	if newClient == oldClient || newGen == gen {
		t.Fatal("the client is replaced")
	}
	if !b.needResync.Load() {
		t.Error("the next BROWSER_ACTIVE catches up")
	}
	if log.count("flush-acks") == 0 {
		t.Error("the old client's acks are sent first")
	}

	// At most once per Connect: a new warning on the new client only re-sends
	// SetActiveSession.
	b.handlerFor(newGen)(warn)
	time.Sleep(4 * delay)
	drain(t, b)
	time.Sleep(2 * delay)
	if resets.Load() != 1 || setActive.Load() != 2 {
		t.Fatalf("resets %d, SetActiveSession %d", resets.Load(), setActive.Load())
	}

	// Confirmed in time, or the phone not answering: no reset.
	for _, c := range []struct {
		name  string
		after any
	}{
		{"confirmed", &gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE}},
		{"phone not answering", &events.PhoneNotResponding{}},
	} {
		b, _ := newPairedBridge(t)
		gen := markLive(b)
		h := b.handlerFor(gen)
		h(warn)
		waitFor(t, c.name+": SetActiveSession", func() bool { return setActive.Load() >= 3 })
		h(c.after)
		drain(t, b)
		time.Sleep(4 * delay)
		if resets.Load() != 1 {
			t.Fatalf("%s: reset", c.name)
		}
		setActive.Store(2)
	}
}

// --- receive-stall recovery -------------------------------------------------------------------

func TestReceiveStallRecovery(t *testing.T) {
	stub(t, &stallRecoveryDelays, []time.Duration{20 * time.Millisecond, 20 * time.Millisecond, 20 * time.Millisecond})
	var setActive, restarts, lists atomic.Int32
	stub(t, &setActiveSession, func(context.Context, *libgm.Client) error { setActive.Add(1); return nil })
	stub(t, &restartLongPoll, func(*libgm.Client) error { restarts.Add(1); return nil })
	var phoneNewest atomic.Int64
	stub(t, &listInboxForStallCheck, func(context.Context, *libgm.Client) (*gmproto.ListConversationsResponse, error) {
		lists.Add(1)
		return &gmproto.ListConversationsResponse{Conversations: []*gmproto.Conversation{
			{ConversationID: "c1", LastMessageTimestamp: phoneNewest.Load()},
			{ConversationID: "c2", LastMessageTimestamp: 1},
		}}, nil
	})
	active := &gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_ACTIVE}
	start := func(t *testing.T) (*GMBridge, *recorder, libgm.EventHandler) {
		t.Helper()
		setActive.Store(0)
		restarts.Store(0)
		lists.Store(0)
		b, rec := newPairedBridge(t)
		h := b.handlerFor(markLive(b))
		h(active)
		h(&gmproto.Conversation{ConversationID: "c0"}) // data arrived: the anchor
		drain(t, b)
		return b, rec, h
	}

	t.Run("stale subscription", func(t *testing.T) {
		b, rec, h := start(t)
		// Each check finds a message newer than anything delivered.
		phoneNewest.Store(time.Now().Add(time.Hour).UnixMicro())
		go func() {
			for i := 0; i < 200 && lists.Load() < 3; i++ {
				time.Sleep(5 * time.Millisecond)
				phoneNewest.Add(1)
			}
		}()
		h(&events.NoDataReceived{})
		waitFor(t, "three checks", func() bool { return lists.Load() == 3 })
		time.Sleep(100 * time.Millisecond)
		drain(t, b)
		if setActive.Load() != 1 || restarts.Load() != 1 {
			t.Fatalf("SetActiveSession %d, restarts %d", setActive.Load(), restarts.Load())
		}
		if n := len(rec.eventsOf(kindResync)); n != 3 {
			t.Errorf("%d resyncs, want one per gap found", n)
		}
		if lists.Load() != 3 {
			t.Error("it gives up after the third check")
		}
	})
	t.Run("no gap", func(t *testing.T) {
		b, rec, h := start(t)
		phoneNewest.Store(1)
		h(&events.NoDataReceived{})
		waitFor(t, "the check", func() bool { return lists.Load() == 1 })
		time.Sleep(100 * time.Millisecond)
		drain(t, b)
		if setActive.Load() != 0 || restarts.Load() != 0 || len(rec.eventsOf(kindResync)) != 0 || lists.Load() != 1 {
			t.Fatal("nothing to recover")
		}
	})
	t.Run("data resumes", func(t *testing.T) {
		b, rec, h := start(t)
		phoneNewest.Store(time.Now().Add(time.Hour).UnixMicro())
		h(&events.NoDataReceived{})
		h(&gmproto.Conversation{ConversationID: "c1"}) // data flows again at once
		time.Sleep(100 * time.Millisecond)
		drain(t, b)
		if lists.Load() != 0 || setActive.Load() != 0 {
			t.Fatal("no check once data flows")
		}
		if len(rec.eventsOf(kindResync)) != 1 {
			t.Error("the usual catch-up after a gap")
		}
	})
	t.Run("not the active session", func(t *testing.T) {
		b, _, h := start(t)
		phoneNewest.Store(time.Now().Add(time.Hour).UnixMicro())
		h(&gmproto.UserAlertEvent{AlertType: gmproto.AlertType_BROWSER_INACTIVE})
		h(&events.NoDataReceived{})
		time.Sleep(100 * time.Millisecond)
		drain(t, b)
		if lists.Load() != 0 {
			t.Fatal("no check while another browser is active")
		}
	})
	t.Run("disconnected", func(t *testing.T) {
		b, _, h := start(t)
		phoneNewest.Store(time.Now().Add(time.Hour).UnixMicro())
		h(&events.NoDataReceived{})
		drain(t, b)
		b.Disconnect()
		time.Sleep(100 * time.Millisecond)
		if lists.Load() != 0 {
			t.Fatal("no check after Disconnect")
		}
	})
}
