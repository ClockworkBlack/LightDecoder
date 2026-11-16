package wa

import (
	"context"
	"encoding/json"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waAdv"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
)

// recorder is a Callback that remembers everything, like the Kotlin sink.
type recorder struct {
	mu      sync.Mutex
	events  []recorded
	changed chan struct{}
	panicOn string
}

type recorded struct {
	kind    string
	payload []byte
}

func newRecorder() *recorder { return &recorder{changed: make(chan struct{}, 1)} }

func (r *recorder) OnEvent(kind string, payload []byte) error {
	if kind == r.panicOn {
		panic("callback blew up")
	}
	r.mu.Lock()
	r.events = append(r.events, recorded{kind, append([]byte(nil), payload...)})
	r.mu.Unlock()
	select {
	case r.changed <- struct{}{}:
	default:
	}
	return nil
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

func (r *recorder) states() []string {
	var out []string
	for _, p := range r.eventsOf(kindState) {
		var st stateDTO
		_ = json.Unmarshal(p, &st)
		out = append(out, st.State)
	}
	return out
}

func (r *recorder) lastState() stateDTO {
	var st stateDTO
	if all := r.eventsOf(kindState); len(all) > 0 {
		_ = json.Unmarshal(all[len(all)-1], &st)
	}
	return st
}

// waitFor polls until cond holds or fails the test after 5 s.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// drain waits until everything queued on the dispatcher so far has run.
func drain(t *testing.T, b *WABridge) {
	t.Helper()
	done := make(chan struct{})
	if !b.disp.push(func() { close(done) }) {
		return
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("dispatcher did not drain")
	}
}

func mustCode(t *testing.T, err error, code string) {
	t.Helper()
	if err == nil {
		t.Fatalf("want %s error, got nil", code)
	}
	if !strings.HasPrefix(err.Error(), code) {
		t.Fatalf("want %s error, got %q", code, err.Error())
	}
}

var (
	meJID    = types.NewJID("15550001111", types.DefaultUserServer)
	aliceJID = types.NewJID("15550002222", types.DefaultUserServer)
	bobJID   = types.NewJID("15550003333", types.DefaultUserServer)
	groupJID = types.NewJID("120363000000000001", types.GroupServer)
	aliceLID = types.NewJID("99887766", types.HiddenUserServer)
)

// fakeID is an identity for conversion tests.
type fakeID struct {
	names map[types.JID]string
}

func (f fakeID) norm(j types.JID) types.JID {
	j = j.ToNonAD()
	if j == aliceLID {
		return aliceJID
	}
	return j
}
func (f fakeID) isMe(j types.JID) bool    { return j.User == meJID.User }
func (f fakeID) name(j types.JID) string  { return f.names[j] }
func (f fakeID) me() types.JID            { return meJID }
func newFakeID() fakeID                   { return fakeID{names: map[types.JID]string{aliceJID: "Alice"}} }
func (f fakeID) withName(j types.JID, n string) fakeID { f.names[j] = n; return f }

// fakeNet replaces every whatsmeow network call for one test and restores
// them afterwards. Its fields record what was sent.
type fakeNet struct {
	mu         sync.Mutex
	loggedIn   bool
	connectErr error
	connects   int
	sent       []*waE2E.Message
	sentTo     []types.JID
	peer       []*waE2E.Message
	patches    []appstate.PatchInfo
	reads      [][]types.MessageID
	logoutErr  error
	logouts    int
	pairCode   string
	pairErr    error
	pairPhones []string
	sendErr    error
	nextID     int
	download   []byte
	uploads    int
	onWhatsApp map[string]types.JID
}

func installFakeNet(t *testing.T) *fakeNet {
	t.Helper()
	f := &fakeNet{loggedIn: true, pairCode: "ABCD-EFGH"}
	saved := []func(){}
	save := func(restore func()) { saved = append(saved, restore) }
	{
		old := connectClient
		connectClient = func(ctx context.Context, cli *whatsmeow.Client) error {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.connects++
			return f.connectErr
		}
		save(func() { connectClient = old })
	}
	{
		old := disconnectClient
		disconnectClient = func(cli *whatsmeow.Client) {}
		save(func() { disconnectClient = old })
	}
	{
		old := isLoggedIn
		isLoggedIn = func(cli *whatsmeow.Client) bool { f.mu.Lock(); defer f.mu.Unlock(); return f.loggedIn }
		save(func() { isLoggedIn = old })
	}
	{
		old := waitForLogin
		waitForLogin = func(cli *whatsmeow.Client, d time.Duration) bool { f.mu.Lock(); defer f.mu.Unlock(); return f.loggedIn }
		save(func() { waitForLogin = old })
	}
	{
		old := logoutClient
		logoutClient = func(ctx context.Context, cli *whatsmeow.Client) error {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.logouts++
			return f.logoutErr
		}
		save(func() { logoutClient = old })
	}
	{
		old := pairPhoneCode
		pairPhoneCode = func(ctx context.Context, cli *whatsmeow.Client, phone string) (string, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.pairPhones = append(f.pairPhones, phone)
			return f.pairCode, f.pairErr
		}
		save(func() { pairPhoneCode = old })
	}
	{
		old := sendMessage
		sendMessage = func(ctx context.Context, cli *whatsmeow.Client, to types.JID, msg *waE2E.Message, extra whatsmeow.SendRequestExtra) (whatsmeow.SendResponse, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			if f.sendErr != nil {
				return whatsmeow.SendResponse{}, f.sendErr
			}
			f.sent = append(f.sent, msg)
			f.sentTo = append(f.sentTo, to)
			id := extra.ID
			if id == "" {
				f.nextID++
				id = "AUTO" + string(rune('0'+f.nextID))
			}
			return whatsmeow.SendResponse{ID: id, Timestamp: time.UnixMilli(1_700_000_000_000)}, nil
		}
		save(func() { sendMessage = old })
	}
	{
		old := sendPeerMessage
		sendPeerMessage = func(ctx context.Context, cli *whatsmeow.Client, msg *waE2E.Message) (whatsmeow.SendResponse, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.peer = append(f.peer, msg)
			return whatsmeow.SendResponse{}, nil
		}
		save(func() { sendPeerMessage = old })
	}
	{
		old := markRead
		markRead = func(ctx context.Context, cli *whatsmeow.Client, ids []types.MessageID, ts time.Time, chat, sender types.JID) error {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.reads = append(f.reads, ids)
			return nil
		}
		save(func() { markRead = old })
	}
	{
		old := sendAppState
		sendAppState = func(ctx context.Context, cli *whatsmeow.Client, patch appstate.PatchInfo) error {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.patches = append(f.patches, patch)
			return nil
		}
		save(func() { sendAppState = old })
	}
	{
		old := isOnWhatsApp
		isOnWhatsApp = func(ctx context.Context, cli *whatsmeow.Client, phones []string) ([]types.IsOnWhatsAppResponse, error) {
			var out []types.IsOnWhatsAppResponse
			for _, p := range phones {
				j, ok := f.onWhatsApp[p]
				out = append(out, types.IsOnWhatsAppResponse{Query: p, JID: j, IsIn: ok})
			}
			return out, nil
		}
		save(func() { isOnWhatsApp = old })
	}
	{
		old := newMessageID
		n := 0
		newMessageID = func(cli *whatsmeow.Client) types.MessageID { n++; return types.MessageID("3EB0TEST" + string(rune('A'+n))) }
		save(func() { newMessageID = old })
	}
	{
		old := uploadMedia
		uploadMedia = func(ctx context.Context, cli *whatsmeow.Client, r io.Reader, tmp io.ReadWriteSeeker, mt whatsmeow.MediaType) (whatsmeow.UploadResponse, error) {
			data, _ := io.ReadAll(r)
			f.mu.Lock()
			f.uploads++
			f.mu.Unlock()
			return whatsmeow.UploadResponse{URL: "https://mmg.example/x", DirectPath: "/v/t62/x", MediaKey: make([]byte, 32),
				FileEncSHA256: []byte{1}, FileSHA256: []byte{2}, FileLength: uint64(len(data))}, nil
		}
		save(func() { uploadMedia = old })
	}
	{
		old := downloadMedia
		downloadMedia = func(ctx context.Context, cli *whatsmeow.Client, tok mediaToken, mt whatsmeow.MediaType) ([]byte, error) {
			return f.download, nil
		}
		save(func() { downloadMedia = old })
	}
	{
		old := fetchGroupInfo
		fetchGroupInfo = func(ctx context.Context, cli *whatsmeow.Client, jid types.JID) (*types.GroupInfo, error) {
			return &types.GroupInfo{JID: jid, GroupName: types.GroupName{Name: "Team"},
				Participants: []types.GroupParticipant{{JID: meJID}, {JID: aliceJID}, {JID: bobJID}}}, nil
		}
		save(func() { fetchGroupInfo = old })
	}
	t.Cleanup(func() {
		for i := len(saved) - 1; i >= 0; i-- {
			saved[i]()
		}
	})
	return f
}

// newTestBridge opens a bridge on a temporary store.
func newTestBridge(t *testing.T) (*WABridge, *recorder) {
	t.Helper()
	rec := newRecorder()
	b, err := NewWABridge(t.TempDir(), rec)
	if err != nil {
		t.Fatalf("NewWABridge: %v", err)
	}
	t.Cleanup(b.Close)
	return b, rec
}

// pair gives the store an identity, as a successful link would.
func pair(t *testing.T, b *WABridge) {
	t.Helper()
	b.mu.Lock()
	cli := b.cli
	b.mu.Unlock()
	ad := meJID
	ad.Device = 7
	cli.Store.ID = &ad
	cli.Store.PushName = "Me"
	cli.Store.Account = &waAdv.ADVSignedDeviceIdentity{}
	if err := cli.Store.Save(context.Background()); err != nil {
		t.Fatalf("save device: %v", err)
	}
}

// connected pairs b and connects it with the fake network.
func connected(t *testing.T) (*WABridge, *recorder, *fakeNet) {
	t.Helper()
	f := installFakeNet(t)
	b, rec := newTestBridge(t)
	pair(t, b)
	if err := b.Connect(); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	return b, rec, f
}

// feed runs whatsmeow events through the bridge's handler and waits for them.
func feed(t *testing.T, b *WABridge, evts ...any) {
	t.Helper()
	b.mu.Lock()
	gen, cli, local := b.gen, b.cli, b.local
	b.mu.Unlock()
	h := b.handlerFor(gen, cli, local)
	for _, e := range evts {
		h(e)
	}
	drain(t, b)
}

func decodeMessage(t *testing.T, raw []byte) messageDTO {
	t.Helper()
	var m messageDTO
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("bad message JSON %s: %v", raw, err)
	}
	return m
}

func lastMessage(t *testing.T, rec *recorder) messageDTO {
	t.Helper()
	all := rec.eventsOf(kindMessage)
	if len(all) == 0 {
		t.Fatal("no message event")
	}
	return decodeMessage(t, all[len(all)-1])
}

func lastConversation(t *testing.T, rec *recorder) conversationDTO {
	t.Helper()
	all := rec.eventsOf(kindConversation)
	if len(all) == 0 {
		t.Fatal("no conversation event")
	}
	var c conversationDTO
	if err := json.Unmarshal(all[len(all)-1], &c); err != nil {
		t.Fatal(err)
	}
	return c
}
