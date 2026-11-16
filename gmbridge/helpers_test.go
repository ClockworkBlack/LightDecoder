package gmbridge

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// recorder is a Callback that remembers everything, like the Kotlin sink.
type recorder struct {
	mu       sync.Mutex
	events   []recorded
	sessions [][]byte
	changed  chan struct{}
	// panicOn makes OnEvent panic for one kind, to test containment.
	panicOn string
}

type recorded struct {
	kind    string
	payload []byte
}

func newRecorder() *recorder { return &recorder{changed: make(chan struct{}, 1)} }

func (r *recorder) OnSession(data []byte) error {
	r.mu.Lock()
	r.sessions = append(r.sessions, data)
	r.mu.Unlock()
	r.poke()
	return nil
}

func (r *recorder) OnEvent(kind string, payload []byte) error {
	if kind == r.panicOn {
		panic("callback blew up")
	}
	r.mu.Lock()
	r.events = append(r.events, recorded{kind, payload})
	r.mu.Unlock()
	r.poke()
	return nil
}

func (r *recorder) poke() {
	select {
	case r.changed <- struct{}{}:
	default:
	}
}

// states returns the "state" values seen so far, in order.
func (r *recorder) states() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for _, e := range r.events {
		if e.kind == kindState {
			var st stateDTO
			_ = json.Unmarshal(e.payload, &st)
			out = append(out, st.State)
		}
	}
	return out
}

func (r *recorder) eventsOf(kind string) [][]byte {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out [][]byte
	for _, e := range r.events {
		if e.kind == kind {
			out = append(out, e.payload)
		}
	}
	return out
}

func (r *recorder) sessionCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.sessions)
}

func (r *recorder) lastSession() []byte {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.sessions) == 0 {
		return nil
	}
	return r.sessions[len(r.sessions)-1]
}

// waitFor polls cond until it holds or 3 s pass.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// pairedAuth builds an AuthData that passes isPaired without any network.
func pairedAuth() *libgm.AuthData {
	ad := libgm.NewAuthData()
	ad.PairingID = uuid.New()
	ad.DestRegID = uuid.New()
	ad.SessionID = uuid.New()
	ad.TachyonAuthToken = []byte("synthetic-token")
	ad.TachyonExpiry = time.Now().Add(24 * time.Hour).UTC().Truncate(time.Second)
	ad.TachyonTTL = (24 * time.Hour).Microseconds()
	ad.Browser = &gmproto.Device{UserID: 1, SourceID: "browser", Network: "GDitto"}
	ad.Mobile = &gmproto.Device{UserID: 2, SourceID: "someone@example.com", Network: "GDitto"}
	ad.Cookies = map[string]string{"SID": "s", "HSID": "h", "SSID": "ss", "APISID": "a", "SAPISID": "sa", "OSID": "o"}
	return ad
}

func pairedSession(t *testing.T) []byte {
	t.Helper()
	data, err := marshalSession(pairedAuth(), "someone@example.com/1", "someone@example.com")
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// newPairedBridge returns a paired, not connected bridge.
func newPairedBridge(t *testing.T) (*GMBridge, *recorder) {
	t.Helper()
	rec := newRecorder()
	b, err := NewGMBridge(pairedSession(t), rec)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(b.Close)
	return b, rec
}

// markLive pretends Connect succeeded, without touching the network.
func markLive(b *GMBridge) uint64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.live = true
	b.connSeq++
	b.connCtx, b.connCancel = context.WithCancel(context.Background())
	b.resetConnectionFlags()
	return b.gen
}

// stub replaces a package-level hook for the duration of the test.
func stub[T any](t *testing.T, target *T, value T) {
	t.Helper()
	old := *target
	*target = value
	t.Cleanup(func() { *target = old })
}

// stateEvents returns the decoded "state" events seen so far, in order.
func (r *recorder) stateEvents() []stateDTO {
	var out []stateDTO
	for _, p := range r.eventsOf(kindState) {
		var st stateDTO
		_ = json.Unmarshal(p, &st)
		out = append(out, st)
	}
	return out
}

// drain waits until the dispatcher has run everything queued so far.
func drain(t *testing.T, b *GMBridge) {
	t.Helper()
	done := make(chan struct{})
	b.disp.push(func() { close(done) })
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("dispatcher did not drain")
	}
}

func codeOf(err error) string {
	if err == nil {
		return ""
	}
	code, _, _ := strings.Cut(err.Error(), ": ")
	return code
}
