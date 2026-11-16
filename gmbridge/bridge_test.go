package gmbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/util"
)

func TestSessionRoundTrip(t *testing.T) {
	original := pairedAuth()
	data, err := marshalSession(original, "phone/1", "someone@example.com")
	if err != nil {
		t.Fatal(err)
	}
	b, err := NewGMBridge(data, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if !b.IsPaired() || b.IsConnected() {
		t.Fatalf("restored bridge: paired=%v connected=%v", b.IsPaired(), b.IsConnected())
	}
	exported, err := b.ExportSession()
	if err != nil || len(exported) == 0 {
		t.Fatalf("export: %v", err)
	}
	again, err := NewGMBridge(exported, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	if !again.IsPaired() {
		t.Fatal("NewGMBridge(ExportSession()) must restore the pairing")
	}
	auth, phoneID, account, err := parseSession(exported)
	if err != nil {
		t.Fatal(err)
	}
	if auth.PairingID != original.PairingID || !bytes.Equal(auth.TachyonAuthToken, original.TachyonAuthToken) ||
		auth.Mobile.GetSourceID() != "someone@example.com" || auth.Cookies["SAPISID"] != "sa" ||
		!bytes.Equal(auth.RequestCrypto.AESKey, original.RequestCrypto.AESKey) ||
		!auth.TachyonExpiry.Equal(original.TachyonExpiry) {
		t.Error("auth data changed in the round trip")
	}
	if phoneID != "phone/1" || account != "someone@example.com" {
		t.Errorf("envelope fields: %q %q", phoneID, account)
	}

	var status statusDTO
	if err := json.Unmarshal([]byte(b.StatusJSON()), &status); err != nil {
		t.Fatal(err)
	}
	if !status.Paired || status.Connected || status.Account != "someone@example.com" || status.PhoneID != "phone/1" || status.SessionID == "" {
		t.Errorf("status: %+v", status)
	}
}

func TestBareAuthDataSessionIsAccepted(t *testing.T) {
	raw, err := json.Marshal(pairedAuth())
	if err != nil {
		t.Fatal(err)
	}
	b, err := NewGMBridge(raw, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if !b.IsPaired() {
		t.Error("libgm's own AuthData JSON should load")
	}
	var status statusDTO
	_ = json.Unmarshal([]byte(b.StatusJSON()), &status)
	if status.Account != "someone@example.com" {
		t.Errorf("account should fall back to the mobile source ID: %+v", status)
	}
}

func TestBadSessions(t *testing.T) {
	for _, raw := range []string{`{nope`, `{"auth":null}`, `{"v":1,"auth":{}}`, `[]`} {
		if _, err := NewGMBridge([]byte(raw), nil); codeOf(err) != codeInvalidArgument {
			t.Errorf("%s: %v", raw, err)
		}
	}
	for _, raw := range []string{"", "null", "  "} {
		b, err := NewGMBridge([]byte(raw), nil)
		if err != nil || b.IsPaired() {
			t.Errorf("%q should give an unpaired bridge: %v", raw, err)
		}
		if data, err := b.ExportSession(); data != nil || err != nil {
			t.Errorf("unpaired export should be nil: %v", err)
		}
		b.Close()
	}
	// A session whose pairing was cleared loads but is not paired.
	ad := pairedAuth()
	ad.ClearPairing()
	data, _ := marshalSession(ad, "", "")
	b, err := NewGMBridge(data, nil)
	if err != nil || b.IsPaired() {
		t.Errorf("cleared pairing: %v paired=%v", err, b.IsPaired())
	}
	b.Close()
}

// allCalls exercises every method that needs a pairing or a connection.
func allCalls(b *GMBridge) map[string]error {
	errs := map[string]error{}
	_, errs["ListConversations"] = b.ListConversations("inbox", 10)
	_, errs["GetConversation"] = b.GetConversation("c1")
	_, errs["FetchMessages"] = b.FetchMessages("c1", 10, nil)
	_, errs["ListContacts"] = b.ListContacts()
	_, errs["ParticipantThumbnails"] = b.ParticipantThumbnails([]byte(`["7"]`))
	errs["RequestFullSizeImage"] = b.RequestFullSizeImage("m1", "p1")
	_, errs["SendText"] = b.SendText("c1", "tmp", "hi", "")
	_, errs["SendMedia"] = b.SendMedia("c1", "tmp", []byte("data"), "a.jpg", "image/jpeg", "", "")
	errs["SendReaction"] = b.SendReaction("m1", "👍", "add")
	errs["MarkRead"] = b.MarkRead("c1", "m1")
	errs["SetTyping"] = b.SetTyping("c1")
	errs["DeleteMessage"] = b.DeleteMessage("m1")
	errs["SetConversationStatus"] = b.SetConversationStatus("c1", "archived")
	errs["SetConversationMuted"] = b.SetConversationMuted("c1", true)
	errs["DeleteConversation"] = b.DeleteConversation("c1")
	_, errs["GetOrCreateConversation"] = b.GetOrCreateConversation([]byte(`["+15550000007"]`), "")
	errs["SetActiveSession"] = b.SetActiveSession()
	return errs
}

func TestUnpairedBridgeFailsFast(t *testing.T) {
	b, err := NewGMBridge(nil, newRecorder())
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	start := time.Now()
	for name, err := range allCalls(b) {
		if codeOf(err) != codeNotPaired {
			t.Errorf("%s: %v", name, err)
		}
	}
	if _, err := b.DownloadMedia("media", make([]byte, 32)); codeOf(err) != codeNotPaired {
		t.Errorf("DownloadMedia: %v", err)
	}
	if err := b.Connect(); codeOf(err) != codeNotPaired {
		t.Errorf("Connect: %v", err)
	}
	if _, err := b.FinishPairing(10); codeOf(err) != codeNoPairing {
		t.Errorf("FinishPairing without StartPairing: %v", err)
	}
	b.CancelPairing() // no attempt: no-op
	if time.Since(start) > time.Second {
		t.Errorf("unpaired calls took %v", time.Since(start))
	}
}

func TestPairedButDisconnectedFailsFast(t *testing.T) {
	b, _ := newPairedBridge(t)
	start := time.Now()
	for name, err := range allCalls(b) {
		if codeOf(err) != codeNotConnected {
			t.Errorf("%s: %v", name, err)
		}
	}
	if time.Since(start) > time.Second {
		t.Errorf("disconnected calls took %v (must not wait for the phone)", time.Since(start))
	}
}

func TestArgumentValidation(t *testing.T) {
	b, _ := newPairedBridge(t)
	markLive(b)
	checks := map[string]error{}
	_, checks["folder"] = b.ListConversations("drafts", 1)
	_, checks["cursor"] = b.FetchMessages("c1", 1, []byte("{bad"))
	_, checks["emptyText"] = b.SendText("c1", "tmp", "  ", "")
	_, checks["noTmp"] = b.SendText("c1", "", "hi", "")
	_, checks["emptyMedia"] = b.SendMedia("c1", "tmp", nil, "", "", "", "")
	checks["action"] = b.SendReaction("m1", "👍", "love")
	checks["status"] = b.SetConversationStatus("c1", "spam")
	checks["indexPart"] = b.RequestFullSizeImage("m1", "idx:2")
	_, checks["numbers"] = b.GetOrCreateConversation([]byte(`[" "]`), "")
	_, checks["numbersJSON"] = b.GetOrCreateConversation([]byte(`{}`), "")
	_, checks["ids"] = b.ParticipantThumbnails([]byte(`nope`))
	_, checks["shortKey"] = b.DownloadMedia("media", []byte{1, 2, 3})
	for name, err := range checks {
		if codeOf(err) != codeInvalidArgument {
			t.Errorf("%s: %v", name, err)
		}
	}
	if out, err := b.ParticipantThumbnails([]byte(`[]`)); err != nil || string(out) != "[]" {
		t.Errorf("no IDs should be an empty array: %s %v", out, err)
	}
}

func TestStartPairingValidatesCookiesOffline(t *testing.T) {
	b, err := NewGMBridge(nil, newRecorder())
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	cases := map[string]string{
		"":            codeNoCookies,
		"null":        codeNoCookies,
		`{"SID":"x"}`: codeNoCookies,
		`{"SID":"x","HSID":"y","SSID":"z","APISID":"a","SAPISID":"b","OSID":" "}`: codeNoCookies,
		`["SID"]`:   codeInvalidArgument,
		`{"SID":1}`: codeInvalidArgument,
	}
	start := time.Now()
	for raw, want := range cases {
		if _, err := b.StartPairing([]byte(raw)); codeOf(err) != want {
			t.Errorf("%q: got %v want %s", raw, err, want)
		}
	}
	if time.Since(start) > time.Second {
		t.Error("cookie validation must not touch the network")
	}
	_, err = b.StartPairing([]byte(`{"SID":"secret-value"}`))
	if bytes.Contains([]byte(err.Error()), []byte("secret-value")) {
		t.Error("cookie values must never appear in errors")
	}
	if !bytes.Contains([]byte(err.Error()), []byte("OSID")) {
		t.Errorf("missing cookie names should be listed: %v", err)
	}
}

func TestParseCookiesKeepsOptionalOnes(t *testing.T) {
	cookies, err := parseCookies([]byte(`{"SID":"1","HSID":"2","SSID":"3","APISID":"4","SAPISID":"5","OSID":"6","__Secure-1PSIDTS":"7","":"x","EMPTY":""}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(cookies) != 7 || cookies["__Secure-1PSIDTS"] != "7" {
		t.Errorf("cookies: %v", cookies)
	}
}

func TestPairingStateMachineOffline(t *testing.T) {
	b, _ := newPairedBridge(t)
	// Simulate an attempt whose emoji is showing (no network involved).
	a := newPairingAttempt()
	a.ps = &libgm.PairingSession{}
	b.mu.Lock()
	b.pairing = a
	b.mu.Unlock()
	// A second attempt that is still starting blocks new ones.
	starting := newPairingAttempt()
	b.mu.Lock()
	b.pairing = starting
	b.mu.Unlock()
	if _, err := b.StartPairing([]byte(`{"SID":"1","HSID":"2","SSID":"3","APISID":"4","SAPISID":"5","OSID":"6"}`)); codeOf(err) != codePairingInProgress {
		t.Errorf("start while starting: %v", err)
	}
	if _, err := b.FinishPairing(10); codeOf(err) != codePairingInProgress {
		t.Errorf("finish while starting: %v", err)
	}
	b.CancelPairing()
	if !starting.isCancelled() {
		t.Error("CancelPairing should cancel the attempt")
	}
	if _, err := b.FinishPairing(10); codeOf(err) != codeNoPairing {
		t.Errorf("finish after cancel: %v", err)
	}
}

func TestUnpairWipesEvenWhenUnpaired(t *testing.T) {
	rec := newRecorder()
	b, err := NewGMBridge(nil, rec)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err := b.Unpair(); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "unpaired", func() bool { return len(rec.states()) == 1 && rec.sessionCount() == 1 })
	if rec.states()[0] != stateUnpaired || rec.lastSession() != nil {
		t.Errorf("states %v session %v", rec.states(), rec.lastSession())
	}
}

func TestDisconnectPersistsAndReports(t *testing.T) {
	b, rec := newPairedBridge(t)
	markLive(b)
	b.Disconnect()
	waitFor(t, "disconnected", func() bool { return len(rec.states()) == 1 && rec.sessionCount() == 1 })
	if rec.states()[0] != stateDisconnected {
		t.Errorf("states: %v", rec.states())
	}
	if _, err := b.ListContacts(); codeOf(err) != codeNotConnected {
		t.Errorf("after Disconnect: %v", err)
	}
	if !b.IsPaired() {
		t.Error("Disconnect must keep the pairing")
	}
}

func TestCloseStopsEverything(t *testing.T) {
	rec := newRecorder()
	b, err := NewGMBridge(pairedSession(t), rec)
	if err != nil {
		t.Fatal(err)
	}
	b.Close()
	if rec.sessionCount() != 1 || len(rec.states()) != 1 {
		t.Errorf("Close should deliver the final session and state before returning: %d %v", rec.sessionCount(), rec.states())
	}
	if err := b.Connect(); codeOf(err) != codeInternal {
		t.Errorf("Connect after Close: %v", err)
	}
	if _, err := b.StartPairing([]byte(`{"SID":"1","HSID":"2","SSID":"3","APISID":"4","SAPISID":"5","OSID":"6"}`)); codeOf(err) != codeInternal {
		t.Errorf("StartPairing after Close: %v", err)
	}
	b.Close() // idempotent

	// A pairing the phone confirms after Close can no longer be persisted,
	// so it must not be adopted (FinishPairing revokes it instead).
	a := newPairingAttempt()
	a.auth = pairedAuth()
	before := b.StatusJSON()
	if b.adoptPairing(a, "phone/2") {
		t.Error("adoptPairing must refuse a closed bridge")
	}
	if b.StatusJSON() != before {
		t.Errorf("closed bridge changed: %s -> %s", before, b.StatusJSON())
	}
}

func TestVersion(t *testing.T) {
	if Version != "1" {
		t.Errorf("Version = %q", Version)
	}
}

// newBridgeWithToken returns a paired, disconnected bridge whose tachyon
// token expires at exp.
func newBridgeWithToken(t *testing.T, exp time.Time) (*GMBridge, *recorder) {
	t.Helper()
	ad := pairedAuth()
	ad.TachyonExpiry = exp
	data, err := marshalSession(ad, "phone/1", "someone@example.com")
	if err != nil {
		t.Fatal(err)
	}
	rec := newRecorder()
	b, err := NewGMBridge(data, rec)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(b.Close)
	return b, rec
}

func TestUnpairRefreshesAnExpiredTokenFirst(t *testing.T) {
	var calls []string
	refreshErr, unpairErr := error(nil), error(nil)
	stub(t, &refreshForUnpair, func(*libgm.Client) error { calls = append(calls, "refresh"); return refreshErr })
	stub(t, &unpairGaia, func(context.Context, *libgm.Client) error { calls = append(calls, "unpair"); return unpairErr })
	unpaired := func(t *testing.T, b *GMBridge, rec *recorder, wantCode string) stateDTO {
		t.Helper()
		// The error carries the same code as the state: the phone was not
		// told. The wipe happened all the same.
		if err := b.Unpair(); codeOf(err) != wantCode {
			t.Fatalf("Unpair returned %v, want code %q", err, wantCode)
		}
		drain(t, b)
		st := rec.stateEvents()
		if len(st) == 0 || st[len(st)-1].State != stateUnpaired || rec.lastSession() != nil || b.IsPaired() {
			t.Fatalf("the local wipe must always happen: %+v paired=%v", st, b.IsPaired())
		}
		return st[len(st)-1]
	}
	cases := []struct {
		name       string
		exp        time.Time
		live       bool
		refreshErr error
		unpairErr  error
		calls      string
		code       string
	}{
		{"expired, disconnected", time.Now().Add(-time.Hour), false, nil, nil, "refresh,unpair", ""},
		{"expiring soon, disconnected", time.Now().Add(30 * time.Minute), false, nil, nil, "refresh,unpair", ""},
		{"fresh token", time.Now().Add(20 * time.Hour), false, nil, nil, "unpair", ""},
		{"live: the long poll keeps it fresh", time.Now().Add(-time.Hour), true, nil, nil, "unpair", ""},
		{"refresh rejected", time.Now().Add(-time.Hour), false, fmt.Errorf("refresh: %w", events.ErrInvalidCredentials), nil, "refresh", codeUnpairRemoteFailed},
		{"revoke rejected", time.Now().Add(20 * time.Hour), false, nil, events.HTTPError{Resp: &http.Response{StatusCode: 401}}, "unpair", codeUnpairRemoteFailed},
		{"pairing already gone", time.Now().Add(20 * time.Hour), false, nil, fmt.Errorf("x: %w", events.ErrRequestedEntityNotFound), "unpair", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			calls, refreshErr, unpairErr = nil, c.refreshErr, c.unpairErr
			b, rec := newBridgeWithToken(t, c.exp)
			if c.live {
				markLive(b)
			}
			st := unpaired(t, b, rec, c.code)
			if got := strings.Join(calls, ","); got != c.calls {
				t.Errorf("calls %q want %q", got, c.calls)
			}
			if st.Code != c.code {
				t.Errorf("state %+v want code %q", st, c.code)
			}
		})
	}
}

// While Unpair runs, the client's events (e.g. from the temporary
// connection) must not reach Kotlin: no data, no re-persisted session.
func TestUnpairDropsTheClientsEvents(t *testing.T) {
	b, rec := newBridgeWithToken(t, time.Now().Add(-time.Hour))
	b.mu.Lock()
	h := b.handlerFor(b.gen)
	b.mu.Unlock()
	stub(t, &refreshForUnpair, func(*libgm.Client) error {
		h(&events.AuthTokenRefreshed{})
		h(&gmproto.Conversation{ConversationID: "c9"})
		// Judge the events now, while Unpair still has the client: Unpair
		// holds only the lifecycle lock here, which the dispatcher never
		// takes. (Left queued, they would be dropped anyway once Unpair has
		// dropped the client, and the test could not tell.)
		drain(t, b)
		return nil
	})
	stub(t, &unpairGaia, func(context.Context, *libgm.Client) error { return nil })
	if err := b.Unpair(); err != nil {
		t.Fatal(err)
	}
	drain(t, b)
	if len(rec.eventsOf(kindConversation)) != 0 || rec.sessionCount() != 1 || rec.lastSession() != nil {
		t.Errorf("events leaked during unpair: conversations=%d sessions=%d", len(rec.eventsOf(kindConversation)), rec.sessionCount())
	}
}

func TestMediaDownloadNeedsAValidToken(t *testing.T) {
	expired, _ := newBridgeWithToken(t, time.Now().Add(-time.Minute))
	if _, err := expired.DownloadMedia("media", make([]byte, 32)); codeOf(err) != codeNotConnected {
		t.Errorf("expired token while disconnected: %v", err)
	}
	if _, err := expired.mediaClient(); codeOf(err) != codeNotConnected {
		t.Errorf("mediaClient: %v", err)
	}
	markLive(expired)
	if _, err := expired.mediaClient(); err != nil {
		t.Errorf("while connected the long poll refreshes the token: %v", err)
	}
	fresh, _ := newBridgeWithToken(t, time.Now().Add(time.Hour))
	if _, err := fresh.mediaClient(); err != nil {
		t.Errorf("a valid token works while disconnected: %v", err)
	}
}

func TestPairedDeviceName(t *testing.T) {
	d := util.BrowserDetailsMessage
	if d.GetOS() != "LightDecoder" || d.GetOS() != pairedDeviceName {
		t.Errorf("the phone lists this name under Device pairing: %q", d.GetOS())
	}
	if d.GetDeviceType() != gmproto.DeviceType_TABLET || d.GetBrowserType() != gmproto.BrowserType_OTHER {
		t.Errorf("device and browser type must stay libgm's defaults: %v %v", d.GetDeviceType(), d.GetBrowserType())
	}
}

// A client the bridge drops (Unpair, lost pairing, replaced by a new
// pairing) can no longer reach the network: libgm's ack ticker and other
// goroutines of it outlive Disconnect, and would keep sending the cookies.
func TestDroppedClientsAreCutOffTheNetwork(t *testing.T) {
	retiredAfter := func(t *testing.T, b *GMBridge, drop func()) (*libgm.Client, *atomic.Bool) {
		t.Helper()
		b.mu.Lock()
		cli, flag := b.client, b.clientRetired
		b.mu.Unlock()
		if cli == nil || flag == nil || flag.Load() {
			t.Fatal("a paired bridge's client must be usable")
		}
		drop()
		if !flag.Load() {
			t.Fatal("the dropped client is still on the network")
		}
		// The libgm client really goes through the switch: its request fails
		// locally, before any connection.
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := cli.FetchConfig(ctx); !errors.Is(err, errClientRetired) {
			t.Fatalf("dropped client's request: %v", err)
		}
		if be := toBridgeError(fmt.Errorf("x: %w", errClientRetired)); be.code != codeNotPaired {
			t.Errorf("code %q", be.code)
		}
		return cli, flag
	}

	t.Run("unpair", func(t *testing.T) {
		stub(t, &unpairGaia, func(context.Context, *libgm.Client) error { return nil })
		b, _ := newPairedBridge(t)
		markLive(b)
		retiredAfter(t, b, func() {
			if err := b.Unpair(); err != nil {
				t.Fatal(err)
			}
		})
	})
	t.Run("unpair keeps the client for the revoke", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		// The switch itself, taken now: retiring sets it before dropping
		// b.clientRetired, and reading it needs no lock that Unpair takes.
		b.mu.Lock()
		retired := b.clientRetired
		b.mu.Unlock()
		stub(t, &unpairGaia, func(ctx context.Context, cli *libgm.Client) error {
			if retired.Load() {
				t.Error("retired before the revoke went out")
			}
			return nil
		})
		retiredAfter(t, b, func() { _ = b.Unpair() })
	})
	t.Run("logged out", func(t *testing.T) {
		b, rec := newPairedBridge(t)
		gen := markLive(b)
		retiredAfter(t, b, func() {
			b.handlerFor(gen)(&events.GaiaLoggedOut{})
			waitFor(t, "logged_out", func() bool { return len(rec.states()) == 1 })
		})
	})
	t.Run("replaced by a new pairing", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		retiredAfter(t, b, func() {
			b.mu.Lock()
			b.client = b.newClientLocked()
			b.mu.Unlock()
		})
		b.mu.Lock()
		current := b.clientRetired.Load()
		b.mu.Unlock()
		if current {
			t.Error("the new client must be usable")
		}
	})
}

// The switch itself: requests pass until it is set, then fail without
// reaching the server; idle connections can still be closed through it.
func TestRetirableTransport(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = io.Copy(io.Discard, r.Body)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	var retired atomic.Bool
	client := retirableSettings(&retired).Compile()
	if client.Timeout != exhttp.SensibleClientSettings.GlobalTimeout {
		t.Errorf("settings changed: timeout %v", client.Timeout)
	}
	post := func() error {
		resp, err := client.Post(srv.URL, "text/plain", strings.NewReader("payload"))
		if err == nil {
			resp.Body.Close()
		}
		return err
	}
	if err := post(); err != nil || hits.Load() != 1 {
		t.Fatalf("before retiring: err=%v hits=%d", err, hits.Load())
	}
	client.CloseIdleConnections()
	retired.Store(true)
	body := &closeTracker{Reader: strings.NewReader("payload")}
	req, _ := http.NewRequest(http.MethodPost, srv.URL, body)
	if _, err := client.Do(req); !errors.Is(err, errClientRetired) {
		t.Errorf("after retiring: %v", err)
	}
	if err := post(); !errors.Is(err, errClientRetired) {
		t.Errorf("after retiring: %v", err)
	}
	if hits.Load() != 1 {
		t.Errorf("a retired client reached the server: %d", hits.Load())
	}
	if !body.closed.Load() {
		t.Error("the request body must be closed")
	}
}

type closeTracker struct {
	io.Reader
	closed atomic.Bool
}

func (c *closeTracker) Close() error { c.closed.Store(true); return nil }
