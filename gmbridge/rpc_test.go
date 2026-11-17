package gmbridge

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// A send cut off after Google accepted the POST may still go out from the
// server's queue, so it must not come back as connection_closed or internal
// (which the outbox resends) but as phone_not_responding (which it does
// not). wrote: the request's headers had been written.
func TestSendErrorAfterAcceptIsMaybeDelivered(t *testing.T) {
	const secretURL = "https://example.invalid/send?key=SECRET"
	cases := []struct {
		name  string
		err   error
		wrote bool
		code  string
	}{
		{"disconnected while waiting for the phone", fmt.Errorf("x: %w", libgm.ErrConnectionClosed), true, codePhoneNotResponding},
		{"bare cancel from the response wait", context.Canceled, true, codePhoneNotResponding},
		{"cancel while reading the POST response", fmt.Errorf("failed to read response body: %w", context.Canceled), true, codePhoneNotResponding},
		{"POST cancelled after it went out", &url.Error{Op: "Post", URL: secretURL, Err: context.Canceled}, true, codePhoneNotResponding},
		{"POST reset after it went out", &url.Error{Op: "Post", URL: secretURL, Err: errors.New("stream error: INTERNAL_ERROR")}, true, codePhoneNotResponding},
		{"POST cancelled before it went out", &url.Error{Op: "Post", URL: secretURL, Err: context.Canceled}, false, codeConnectionClosed},
		{"dial failed", &url.Error{Op: "Post", URL: secretURL, Err: &net.OpError{Op: "dial", Net: "tcp", Err: errors.New("connection refused")}}, false, codeInternal},
		{"client retired", &url.Error{Op: "Post", URL: secretURL, Err: errClientRetired}, false, codeNotPaired},
		{"phone timeout", fmt.Errorf("%w in 1m0s", libgm.ErrPhoneNotResponding), true, codePhoneNotResponding},
		{"rpc deadline", context.DeadlineExceeded, true, codePhoneNotResponding},
		{"rejected POST", events.HTTPError{Resp: &http.Response{StatusCode: 401}}, true, codeCookiesInvalid},
		{"server error after retries", events.HTTPError{Resp: &http.Response{StatusCode: 503}}, true, codeInternal},
	}
	for _, c := range cases {
		err := sendError(c.err, c.wrote)
		if codeOf(err) != c.code {
			t.Errorf("%s: got %v want %s", c.name, err, c.code)
		}
		if strings.Contains(err.Error(), "SECRET") {
			t.Errorf("%s: URL leaked: %v", c.name, err)
		}
	}
	// Pairing keeps the plain mapping.
	if be := toBridgeError(libgm.ErrConnectionClosed); be.code != codeConnectionClosed {
		t.Errorf("classify must still map ErrConnectionClosed to connection_closed: %v", be)
	}
}

// The same through send and a real HTTP/2 transport with the bridge's
// settings: failures after the POST was written (an HTTP/2 response-header
// timeout is not a context.DeadlineExceeded, a reset stream, a disconnect
// while waiting for the answer) are maybe-delivered; a dial failure is not.
func TestSendFailuresFromTheTransport(t *testing.T) {
	bodyIn := make(chan struct{}, 1)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		switch r.URL.Path {
		case "/slow", "/hold":
			if r.URL.Path == "/hold" {
				bodyIn <- struct{}{}
			}
			<-r.Context().Done() // until the client gives up
		case "/abort":
			panic(http.ErrAbortHandler)
		default:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	srv.EnableHTTP2 = true
	srv.StartTLS()
	defer srv.Close()

	var retired atomic.Bool
	cs := retirableSettings(&retired)
	cs.ResponseHeaderTimeout = 300 * time.Millisecond
	cs.TLSConfig = srv.Client().Transport.(*http.Transport).TLSClientConfig
	client := cs.Compile()
	post := func(ctx context.Context, target string) (*http.Response, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, target, bytes.NewReader([]byte("payload")))
		if err != nil {
			return nil, err
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		_, err = io.ReadAll(resp.Body)
		resp.Body.Close()
		return resp, err
	}
	if resp, err := post(context.Background(), srv.URL+"/ok"); err != nil || resp.ProtoMajor != 2 {
		t.Fatalf("the test server must speak HTTP/2: %v %+v", err, resp)
	}

	closed, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	refused := "https://" + closed.Addr().String() + "/send"
	closed.Close()

	b, _ := newPairedBridge(t)
	conv := &gmproto.Conversation{ConversationID: "c1"}
	infos := []*gmproto.MessageInfo{textInfo("hi")}
	var target string
	stub(t, &sendMessage, func(ctx context.Context, _ *libgm.Client, _ *gmproto.SendMessageRequest) (*gmproto.SendMessageResponse, error) {
		_, err := post(ctx, target)
		if err == nil {
			err = errors.New("the test server answered")
		}
		return nil, err
	})
	cases := []struct {
		name, target, code string
		cancelAfterBody    bool
	}{
		{"no response headers in time", srv.URL + "/slow", codePhoneNotResponding, false},
		{"stream reset after the body arrived", srv.URL + "/abort", codePhoneNotResponding, false},
		{"disconnected while waiting for the answer", srv.URL + "/hold", codePhoneNotResponding, true},
		{"nothing listening", refused, codeInternal, false},
	}
	for _, c := range cases {
		target = c.target
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		if c.cancelAfterBody {
			go func() {
				select {
				case <-bodyIn:
					cancel()
				case <-ctx.Done():
				}
			}()
		}
		_, err := b.send(ctx, nil, conv, "tmp", infos, "")
		cancel()
		if codeOf(err) != c.code {
			t.Errorf("%s: got %v want %s", c.name, err, c.code)
		}
	}
}

// If the budget ran out before SendMessage, nothing reached Google: the
// outbox may send it again safely.
func TestSendWithSpentContextSendsNothing(t *testing.T) {
	b, _ := newPairedBridge(t)
	conv := &gmproto.Conversation{ConversationID: "c1"}
	infos := []*gmproto.MessageInfo{textInfo("hi")}

	expired, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancel()
	// cli is nil: send must not get as far as using it.
	if _, err := b.send(expired, nil, conv, "tmp", infos, ""); codeOf(err) != codeSendFailed {
		t.Errorf("expired budget: %v", err)
	}
	cancelled, cancel2 := context.WithCancel(context.Background())
	cancel2()
	if _, err := b.send(cancelled, nil, conv, "tmp", infos, ""); codeOf(err) != codeConnectionClosed {
		t.Errorf("connection torn down before sending: %v", err)
	}
}

func TestForceRCSFollowsProtocolSwitchTombstones(t *testing.T) {
	b, _ := newPairedBridge(t)
	tomb := func(id string, ts int64, st gmproto.MessageStatusType) *gmproto.Message {
		return &gmproto.Message{MessageID: id, ConversationID: "c1", Timestamp: ts, MessageStatus: &gmproto.MessageStatus{Status: st}}
	}
	rcsAuto := &gmproto.Conversation{ConversationID: "c1", Type: gmproto.ConversationType_RCS, SendMode: gmproto.ConversationSendMode_SEND_MODE_AUTO}
	force := func(conv *gmproto.Conversation) bool {
		return b.buildSendRequest(conv, "tmp", []*gmproto.MessageInfo{textInfo("x")}, "").GetForceRCS()
	}
	if force(rcsAuto) {
		t.Fatal("no tombstone seen: no ForceRCS")
	}

	// Newest page (phone order: newest first): the latest tombstone wins.
	b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
		tomb("m3", 3_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE),
		tomb("m2", 2_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT),
		tomb("m1", 1_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE),
	}}, nil)
	if !force(rcsAuto) {
		t.Fatal("newest tombstone switched to E2EE: ForceRCS expected")
	}
	// An older page must not overwrite what newer messages said.
	b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
		tomb("m0", 500_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS),
	}}, &gmproto.Cursor{LastItemID: "m1", LastItemTimestamp: 1_000})
	if !force(rcsAuto) {
		t.Fatal("an older page changed ForceRCS")
	}
	// Only RCS conversations in automatic send mode are forced.
	if force(&gmproto.Conversation{ConversationID: "c1", Type: gmproto.ConversationType_SMS, SendMode: gmproto.ConversationSendMode_SEND_MODE_AUTO}) ||
		force(&gmproto.Conversation{ConversationID: "c1", Type: gmproto.ConversationType_RCS, SendMode: gmproto.ConversationSendMode_SEND_MODE_XMS}) {
		t.Error("ForceRCS only applies to RCS chats in automatic send mode")
	}
	// A live switch away from E2EE turns it off again.
	gen := markLive(b)
	b.handlerFor(gen)(&libgm.WrappedMessage{Message: tomb("m4", 4_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS)})
	drain(t, b)
	if force(rcsAuto) {
		t.Error("E2EE_TO_RCS tombstone should clear ForceRCS")
	}
	b.handlerFor(gen)(&libgm.WrappedMessage{Message: tomb("m5", 5_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE)})
	drain(t, b)
	if !force(rcsAuto) {
		t.Error("TEXT_TO_E2EE tombstone should set ForceRCS")
	}
	// An old tombstone redelivered live (IsOld) changes nothing either.
	b.handlerFor(gen)(&libgm.WrappedMessage{IsOld: true, Message: tomb("m0", 500_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS)})
	drain(t, b)
	if !force(rcsAuto) {
		t.Error("a redelivered old tombstone changed ForceRCS")
	}
	b.cache.reset()
	if force(rcsAuto) {
		t.Error("cache reset (unpair, new pairing) should forget ForceRCS")
	}
}

// After a restart the cache is empty, and a chat's switch to end-to-end
// encryption may lie far beyond the newest page. The archive's tombstones
// (SeedProtocolSwitches) and older history pages fill it in; newer
// tombstones still win, in whatever order they arrive.
func TestForceRCSAfterRestart(t *testing.T) {
	tomb := func(id string, ts int64, st gmproto.MessageStatusType) *gmproto.Message {
		return &gmproto.Message{MessageID: id, ConversationID: "c1", Timestamp: ts, MessageStatus: &gmproto.MessageStatus{Status: st}}
	}
	rcsAuto := &gmproto.Conversation{ConversationID: "c1", Type: gmproto.ConversationType_RCS, SendMode: gmproto.ConversationSendMode_SEND_MODE_AUTO}
	plain := &gmproto.Message{MessageID: "p1", ConversationID: "c1", Timestamp: 9_000_000,
		MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_COMPLETE}}
	force := func(b *GMBridge) bool {
		return b.buildSendRequest(rcsAuto, "tmp", []*gmproto.MessageInfo{textInfo("x")}, "").GetForceRCS()
	}

	t.Run("seeded from the archive", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		seed := `[{"conversationId":"c1","status":"MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE","timestampMs":4000},
			{"conversationId":"c1","status":"MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT","timestampMs":2000},
			{"conversationId":"c2","status":"INCOMING_COMPLETE","timestampMs":5000}]`
		if err := b.SeedProtocolSwitches([]byte(seed)); err != nil {
			t.Fatal(err)
		}
		if !force(b) {
			t.Fatal("the archive's newest tombstone says end-to-end encrypted")
		}
		// The newest page without tombstones, and an older page with an
		// older one, change nothing.
		b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{plain}}, nil)
		b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
			tomb("m1", 1_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS),
		}}, &gmproto.Cursor{LastItemID: "m2", LastItemTimestamp: 2_000})
		if !force(b) {
			t.Fatal("older tombstones overrode the seed")
		}
		// A later live switch still turns it off.
		gen := markLive(b)
		b.handlerFor(gen)(&libgm.WrappedMessage{Message: tomb("m5", 5_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS)})
		drain(t, b)
		if force(b) {
			t.Error("a newer live E2EE_TO_RCS tombstone should clear ForceRCS")
		}
		// Seeding again (e.g. after an import) cannot bring back an older state.
		if err := b.SeedProtocolSwitches([]byte(seed)); err != nil || force(b) {
			t.Errorf("an older seed overrode a newer tombstone: %v", err)
		}
	})

	t.Run("status codes work too", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		seed := fmt.Sprintf(`[{"conversationId":"c1","statusCode":%d,"timestampMs":1}]`,
			gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE)
		if err := b.SeedProtocolSwitches([]byte(seed)); err != nil || !force(b) {
			t.Errorf("seed by status code: %v", err)
		}
	})

	t.Run("bad input", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		for _, raw := range []string{"", " ", "[]", "null"} {
			if err := b.SeedProtocolSwitches([]byte(raw)); err != nil {
				t.Errorf("%q: %v", raw, err)
			}
		}
		if err := b.SeedProtocolSwitches([]byte(`{"conversationId":"c1"}`)); codeOf(err) != codeInvalidArgument {
			t.Errorf("not an array: %v", err)
		}
		if force(b) {
			t.Error("nothing was seeded")
		}
	})

	t.Run("bad JSON keeps the seed", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		seed := `[{"conversationId":"c1","status":"MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE","timestampMs":1}]`
		if err := b.SeedProtocolSwitches([]byte(seed)); err != nil {
			t.Fatal(err)
		}
		if err := b.SeedProtocolSwitches([]byte(`[{`)); codeOf(err) != codeInvalidArgument {
			t.Errorf("broken JSON: %v", err)
		}
		if !force(b) {
			t.Error("a rejected seed changed what was seeded")
		}
	})

	t.Run("learned from an older page", func(t *testing.T) {
		b, _ := newPairedBridge(t)
		b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{plain}}, nil)
		if force(b) {
			t.Fatal("nothing known yet")
		}
		// History import walks back past the switch.
		b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
			tomb("m3", 3_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE),
			tomb("m1", 1_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT),
		}}, &gmproto.Cursor{LastItemID: "p1", LastItemTimestamp: 9_000})
		if !force(b) {
			t.Fatal("the newest tombstone of an older page counts while nothing newer is known")
		}
		// Still older pages do not undo it.
		b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
			tomb("m0", 500_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS),
		}}, &gmproto.Cursor{LastItemID: "m1", LastItemTimestamp: 1_000})
		if !force(b) {
			t.Error("an older page overrode a newer one")
		}
	})
}

// When the archive is deleted (it may have been another phone's, where the
// same conversation id is someone else), what it seeded must go, but what
// this pairing itself reported stays. Seeding again replaces the seed.
func TestProtocolSwitchSeedsAreReplacedAndCleared(t *testing.T) {
	const (
		toE2EE   = "MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE"
		fromE2EE = "MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS"
	)
	b, _ := newPairedBridge(t)
	force := func(convID string) bool {
		conv := &gmproto.Conversation{ConversationID: convID, Type: gmproto.ConversationType_RCS, SendMode: gmproto.ConversationSendMode_SEND_MODE_AUTO}
		return b.buildSendRequest(conv, "tmp", []*gmproto.MessageInfo{textInfo("x")}, "").GetForceRCS()
	}
	seed := func(entries ...string) {
		t.Helper()
		if err := b.SeedProtocolSwitches([]byte("[" + strings.Join(entries, ",") + "]")); err != nil {
			t.Fatal(err)
		}
	}
	entry := func(convID, status string, ts int64) string {
		return fmt.Sprintf(`{"conversationId":%q,"status":%q,"timestampMs":%d}`, convID, status, ts)
	}
	tomb := func(id, convID string, ts int64, st gmproto.MessageStatusType) *gmproto.Message {
		return &gmproto.Message{MessageID: id, ConversationID: convID, Timestamp: ts, MessageStatus: &gmproto.MessageStatus{Status: st}}
	}

	seed(entry("c1", toE2EE, 4000), entry("c2", toE2EE, 4000))
	// This pairing reports c3 encrypted, and an older switch away for c1.
	b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
		tomb("m3", "c3", 3_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE),
	}}, nil)
	b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
		tomb("m1", "c1", 1_000_000, gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS),
	}}, &gmproto.Cursor{LastItemID: "m9", LastItemTimestamp: 9_000})
	if !force("c1") || !force("c2") || !force("c3") {
		t.Fatal("seeded and learned switches should both count (the newer one per chat)")
	}

	// Seeding again replaces the earlier seed: c2 is no longer in the archive.
	seed(entry("c1", toE2EE, 4000))
	if !force("c1") || force("c2") || !force("c3") {
		t.Errorf("reseed: c1=%v c2=%v c3=%v, want true false true", force("c1"), force("c2"), force("c3"))
	}

	// The archive was deleted: its seeds go; c1 falls back to what this
	// pairing reported (an older switch away), c3 stays as learned.
	if err := b.ClearProtocolSwitches(); err != nil {
		t.Fatal(err)
	}
	if force("c1") || force("c2") || !force("c3") {
		t.Errorf("after clear: c1=%v c2=%v c3=%v, want false false true", force("c1"), force("c2"), force("c3"))
	}

	// The new archive's seeds (empty, or the new phone's) are used from now on.
	seed()
	if force("c1") || !force("c3") {
		t.Error("an empty seed should leave only what was learned")
	}
	seed(entry("c2", toE2EE, 5000), entry("c3", fromE2EE, 100))
	if !force("c2") || !force("c3") {
		t.Error("a new seed counts, but not over a newer learned switch")
	}

	// A new pairing or Unpair forgets both.
	b.cache.reset()
	if force("c2") || force("c3") {
		t.Error("cache reset should forget seeded and learned switches")
	}
}
