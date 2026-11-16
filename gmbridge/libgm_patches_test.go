package gmbridge

// Tests of the patched libgm in third_party/mautrix-gmessages (see its
// PATCHES.md), through its exported API, against a fake Google transport.

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"
	"go.mau.fi/util/pblite"
	"google.golang.org/protobuf/proto"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// fakeGoogle answers libgm's requests by URL path suffix. A handler that is
// not set answers 200 with an empty pblite message. Like a real transport it
// fails a request whose context is already done, before "sending" it.
type fakeGoogle struct {
	mu       sync.Mutex
	handlers map[string]func(req *http.Request, body []byte) (*http.Response, error)
	seen     []fakeRequest
}

type fakeRequest struct {
	path string
	body []byte
}

func newFakeGoogle() *fakeGoogle {
	return &fakeGoogle{handlers: make(map[string]func(*http.Request, []byte) (*http.Response, error))}
}

func (f *fakeGoogle) handle(suffix string, h func(req *http.Request, body []byte) (*http.Response, error)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.handlers[suffix] = h
}

func (f *fakeGoogle) RoundTrip(req *http.Request) (*http.Response, error) {
	if err := req.Context().Err(); err != nil {
		if req.Body != nil {
			_ = req.Body.Close()
		}
		return nil, err
	}
	var body []byte
	if req.Body != nil {
		body, _ = io.ReadAll(req.Body)
		_ = req.Body.Close()
	}
	f.mu.Lock()
	f.seen = append(f.seen, fakeRequest{path: req.URL.Path, body: body})
	var h func(*http.Request, []byte) (*http.Response, error)
	for suffix, cand := range f.handlers {
		if strings.HasSuffix(req.URL.Path, suffix) {
			h = cand
		}
	}
	f.mu.Unlock()
	if h != nil {
		return h(req, body)
	}
	return pbliteResponse(req, 200, &gmproto.OutgoingRPCResponse{}), nil
}

func (f *fakeGoogle) CloseIdleConnections() {}

// count returns how many requests went to paths ending in suffix.
func (f *fakeGoogle) count(suffix string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, r := range f.seen {
		if strings.HasSuffix(r.path, suffix) {
			n++
		}
	}
	return n
}

func (f *fakeGoogle) requests(suffix string) []fakeRequest {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []fakeRequest
	for _, r := range f.seen {
		if strings.HasSuffix(r.path, suffix) {
			out = append(out, r)
		}
	}
	return out
}

func (f *fakeGoogle) total() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.seen)
}

func pbliteResponse(req *http.Request, status int, msg proto.Message) *http.Response {
	body, err := pblite.Marshal(msg)
	if err != nil {
		panic(err)
	}
	return &http.Response{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{libgm.ContentTypePBLite}},
		Body:       io.NopCloser(bytes.NewReader(body)),
		Request:    req,
	}
}

func statusResponse(req *http.Request, status int) *http.Response {
	return &http.Response{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"text/plain"}},
		Body:       io.NopCloser(strings.NewReader("")),
		Request:    req,
	}
}

// fakeClient is a libgm client for ad whose HTTP goes to f.
func fakeClient(ad *libgm.AuthData, f *fakeGoogle) *libgm.Client {
	cs := exhttp.SensibleClientSettings
	cs.TransportOverride = func(exhttp.ClientSettings) http.RoundTripper { return f }
	cli := libgm.NewClient(ad, nil, zerolog.Nop(), cs)
	cli.SetEventHandler(func(any) {})
	return cli
}

// goroutinesIn counts the goroutines whose stack mentions fn.
func goroutinesIn(fn string) int {
	buf := make([]byte, 1<<20)
	for {
		n := runtime.Stack(buf, true)
		if n < len(buf) {
			buf = buf[:n]
			break
		}
		buf = make([]byte, 2*len(buf))
	}
	count := 0
	for _, g := range strings.Split(string(buf), "\n\n") {
		if strings.Contains(g, fn) {
			count++
		}
	}
	return count
}

// rpcAction decodes the action of a SendMessage request body.
func rpcAction(t *testing.T, body []byte) gmproto.ActionType {
	t.Helper()
	var msg gmproto.OutgoingRPCMessage
	if err := pblite.Unmarshal(body, &msg); err != nil {
		t.Fatalf("bad SendMessage body: %v", err)
	}
	var data gmproto.OutgoingRPCData
	if err := proto.Unmarshal(msg.GetData().GetMessageData(), &data); err != nil {
		t.Fatalf("bad SendMessage data: %v", err)
	}
	return data.GetAction()
}

const (
	longPollFn = "libgm.(*Client).doLongPoll"
	pingerFn   = "libgm.(*dittoPinger).Loop"
	ackFn      = "libgm.(*SessionHandler).startAckInterval"
)

// (d) A long-poll loop asleep in its error backoff, and its pinger, end at
// once on Disconnect, and nothing more is sent.
func TestLibgmDisconnectStopsLoopInBackoff(t *testing.T) {
	f := newFakeGoogle()
	f.handle("/ReceiveMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
		return statusResponse(req, 503), nil
	})
	loops, pingers := goroutinesIn(longPollFn), goroutinesIn(pingerFn)
	cli := fakeClient(pairedAuth(), f)
	temporary := make(chan struct{}, 1)
	cli.SetEventHandler(func(evt any) {
		if _, ok := evt.(*events.ListenTemporaryError); ok {
			select {
			case temporary <- struct{}{}:
			default:
			}
		}
	})
	if err := cli.Connect(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer cli.StopAckInterval()
	select {
	case <-temporary: // the loop now sleeps 15 s before its next attempt
	case <-time.After(3 * time.Second):
		t.Fatal("no temporary error")
	}
	if goroutinesIn(longPollFn) <= loops || goroutinesIn(pingerFn) <= pingers {
		t.Fatal("the long poll and its pinger should be running")
	}
	cli.Disconnect()
	waitFor(t, "the long-poll loop and pinger to end", func() bool {
		return goroutinesIn(longPollFn) == loops && goroutinesIn(pingerFn) == pingers
	})
	sent := f.total()
	time.Sleep(100 * time.Millisecond)
	if f.total() != sent {
		t.Fatal("requests went out after Disconnect")
	}
}

// (d) The post-connect work of a long poll (acks, SetActiveSession) is not
// sent once the long poll was stopped.
func TestLibgmNoSetActiveSessionAfterDisconnect(t *testing.T) {
	f := newFakeGoogle()
	body, writer := io.Pipe()
	defer writer.Close()
	f.handle("/ReceiveMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: body, Request: req}, nil
	})
	cli := fakeClient(pairedAuth(), f)
	recovered := make(chan struct{}, 1)
	cli.SetEventHandler(func(evt any) {
		if _, ok := evt.(*events.ListenRecovered); ok {
			select {
			case recovered <- struct{}{}:
			default:
			}
		}
	})
	if err := cli.Connect(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer cli.StopAckInterval()
	select {
	case <-recovered: // the long poll is open; postConnect waits 2 s
	case <-time.After(3 * time.Second):
		t.Fatal("long poll did not open")
	}
	cli.Disconnect()
	time.Sleep(3500 * time.Millisecond) // past postConnect's waits
	if n := f.count("/SendMessage"); n != 0 {
		t.Fatalf("%d requests to the phone after Disconnect", n)
	}
}

// (c) Pending acks are sent by FlushAcks, and StopAckInterval ends the ack
// goroutine; Connect starts it again.
func TestLibgmAckFlushAndStop(t *testing.T) {
	f := newFakeGoogle()
	body, writer := io.Pipe()
	f.handle("/ReceiveMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: body, Request: req}, nil
	})
	// Earlier tests stop their clients' ack goroutines; let them end.
	waitFor(t, "earlier ack goroutines to end", func() bool { return goroutinesIn(ackFn) == 0 })
	acks := 0
	cli := fakeClient(pairedAuth(), f)
	if err := cli.Connect(context.Background()); err != nil {
		t.Fatal(err)
	}
	// postConnect sends the pending acks and SetActiveSession about 3 s
	// after the long poll opens; let that pass first.
	if _, err := writer.Write([]byte("[[")); err != nil {
		t.Fatal(err)
	}
	waitForLong(t, "postConnect's SetActiveSession", func() bool { return f.count("/SendMessage") >= 1 })
	waitFor(t, "Connect to start the ack goroutine", func() bool { return goroutinesIn(ackFn) == acks+1 })
	cli.StopAckInterval()
	waitFor(t, "the ack goroutine to end", func() bool { return goroutinesIn(ackFn) == acks })

	// One message from the phone (it cannot be decrypted, which libgm acks
	// all the same), streamed the way Google does.
	payload, err := pblite.Marshal(&gmproto.LongPollingPayload{Data: &gmproto.IncomingRPCMessage{ResponseID: "resp-1", MessageData: []byte{1}}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := writer.Write(payload); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "the ack to be queued", func() bool { return cli.PendingAcks() == 1 })
	time.Sleep(5500 * time.Millisecond) // a full tick of the stopped interval
	if f.count("/AckMessages") != 0 {
		t.Fatal("a stopped ack interval sent acks")
	}
	if err := cli.FlushAcks(context.Background()); err != nil {
		t.Fatal(err)
	}
	if cli.PendingAcks() != 0 || f.count("/AckMessages") != 1 {
		t.Fatalf("flush: pending %d, sent %d", cli.PendingAcks(), f.count("/AckMessages"))
	}
	if req := f.requests("/AckMessages")[0]; !bytes.Contains(req.body, []byte("resp-1")) {
		t.Fatal("the ack does not name the message")
	}

	// A failed flush keeps the acks for later.
	f.handle("/AckMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
		return statusResponse(req, 500), nil
	})
	_, _ = writer.Write([]byte("," + string(mustPblite(t, &gmproto.LongPollingPayload{Data: &gmproto.IncomingRPCMessage{ResponseID: "resp-2", MessageData: []byte{1}}}))))
	waitFor(t, "the second ack to be queued", func() bool { return cli.PendingAcks() == 1 })
	if err := cli.FlushAcks(context.Background()); err == nil || cli.PendingAcks() != 1 {
		t.Fatalf("failed flush: %v, pending %d", err, cli.PendingAcks())
	}
	_ = writer.Close()
	cli.Disconnect()
}

// waitForLong is waitFor with a 10 s limit.
func waitForLong(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func mustPblite(t *testing.T, msg proto.Message) []byte {
	t.Helper()
	data, err := pblite.Marshal(msg)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// (a) The cancel that withdraws the emoji prompt goes out even when the
// caller's context is already done: from CancelGaiaPairing, and from
// FinishGaiaPairing when its wait is cancelled or times out.
func TestLibgmPairingCancelIsSent(t *testing.T) {
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	expired, cancel2 := context.WithTimeout(context.Background(), time.Nanosecond)
	defer cancel2()
	<-expired.Done()
	cases := []struct {
		name string
		run  func(cli *libgm.Client, ps *libgm.PairingSession) error
	}{
		{"CancelGaiaPairing", func(cli *libgm.Client, ps *libgm.PairingSession) error {
			return cli.CancelGaiaPairing(cancelled, ps)
		}},
		{"FinishGaiaPairing cancelled", func(cli *libgm.Client, ps *libgm.PairingSession) error {
			_, err := cli.FinishGaiaPairing(cancelled, ps)
			if !errors.Is(err, context.Canceled) {
				t.Errorf("finish: %v", err)
			}
			return nil
		}},
		{"FinishGaiaPairing timed out", func(cli *libgm.Client, ps *libgm.PairingSession) error {
			_, err := cli.FinishGaiaPairing(expired, ps)
			if !errors.Is(err, context.DeadlineExceeded) {
				t.Errorf("finish: %v", err)
			}
			return nil
		}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := newFakeGoogle()
			ad := libgm.NewAuthData()
			ad.Cookies = map[string]string{"SAPISID": "x"}
			ad.DestRegID = uuid.New()
			cli := fakeClient(ad, f)
			ps := &libgm.PairingSession{UUID: uuid.New(), Start: time.Now()}
			if err := c.run(cli, ps); err != nil {
				t.Fatal(err)
			}
			reqs := f.requests("/SendMessage")
			if len(reqs) != 1 || rpcAction(t, reqs[0].body) != gmproto.ActionType_CANCEL_GAIA_PAIRING {
				t.Fatalf("want one CANCEL_GAIA_PAIRING, got %d requests", len(reqs))
			}
			if !bytes.Contains(reqs[0].body, []byte(ps.UUID.String())) {
				t.Error("the cancel does not name the pairing attempt")
			}
		})
	}
}

// gaiaSignIn answers the sign-in that starts a pairing with one phone.
func gaiaSignIn(f *fakeGoogle) {
	f.handle("/SignInGaia", func(req *http.Request, _ []byte) (*http.Response, error) {
		return pbliteResponse(req, 200, &gmproto.SignInGaiaResponse{
			DeviceData: &gmproto.SignInGaiaResponse_DeviceData{
				DeviceWrapper: &gmproto.SignInGaiaResponse_DeviceData_DeviceWrapper{
					Device: &gmproto.Device{UserID: 1, SourceID: "Browser", Network: "GDitto"},
				},
				UnknownItems2: []*gmproto.RPCGaiaData_UnknownContainer_Item2_Item1{{
					DestOrSourceUUID: uuid.NewString(), UnknownInt4: 1,
				}},
			},
			TokenData: &gmproto.TokenData{TachyonAuthToken: []byte("t"), TTL: (24 * time.Hour).Microseconds()},
		}), nil
	})
}

// (b) StartGaiaPairing no longer waits forever for a pairing long poll that
// never opens: it returns when its context ends, and at once when the long
// poll gives up (a 401). Its goroutines end.
func TestLibgmStartGaiaPairingDoesNotPark(t *testing.T) {
	t.Run("context ends", func(t *testing.T) {
		f := newFakeGoogle()
		gaiaSignIn(f)
		f.handle("/ReceiveMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
			return statusResponse(req, 503), nil
		})
		loops := goroutinesIn(longPollFn)
		ad := libgm.NewAuthData()
		ad.SetCookies(map[string]string{"SAPISID": "x"})
		cli := fakeClient(ad, f)
		ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
		defer cancel()
		start := time.Now()
		_, _, err := cli.StartGaiaPairing(ctx, context.Background())
		if !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 3*time.Second {
			t.Fatalf("StartGaiaPairing: %v after %v", err, time.Since(start))
		}
		waitFor(t, "the pairing long poll to stop", func() bool { return goroutinesIn(longPollFn) == loops })
	})
	t.Run("long poll rejected", func(t *testing.T) {
		f := newFakeGoogle()
		gaiaSignIn(f)
		f.handle("/ReceiveMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
			return statusResponse(req, 401), nil
		})
		ad := libgm.NewAuthData()
		ad.SetCookies(map[string]string{"SAPISID": "x"})
		cli := fakeClient(ad, f)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		start := time.Now()
		_, _, err := cli.StartGaiaPairing(ctx, context.Background())
		if !errors.Is(err, libgm.ErrPairingLongPollStopped) || time.Since(start) > 3*time.Second {
			t.Fatalf("StartGaiaPairing: %v after %v", err, time.Since(start))
		}
	})
}

// (e) A panic in one of libgm's background goroutines (here: the long-poll
// loop, through the event handler it calls) goes to the panic handler
// instead of crashing the process.
func TestLibgmBackgroundPanicIsRecovered(t *testing.T) {
	f := newFakeGoogle()
	f.handle("/ReceiveMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
		return statusResponse(req, 503), nil
	})
	cli := fakeClient(pairedAuth(), f)
	cli.SetEventHandler(func(evt any) {
		if _, ok := evt.(*events.ListenTemporaryError); ok {
			panic("boom")
		}
	})
	got := make(chan string, 4)
	cli.SetPanicHandler(func(where string, recovered any) { got <- where })
	if err := cli.Connect(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer cli.StopAckInterval()
	defer cli.Disconnect()
	select {
	case where := <-got:
		if where != "long poll" {
			t.Errorf("panic reported from %q", where)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the panic was not reported")
	}
}

// (f) Token fields are written under CookiesLock, so encoding the session
// while libgm refreshes the token is race-free (run with -race).
func TestLibgmTokenRefreshVersusSessionEncoding(t *testing.T) {
	f := newFakeGoogle()
	var refreshes atomic.Int32
	f.handle("/RegisterRefresh", func(req *http.Request, _ []byte) (*http.Response, error) {
		refreshes.Add(1)
		return pbliteResponse(req, 200, &gmproto.RegisterRefreshResponse{
			TokenData: &gmproto.TokenData{TachyonAuthToken: []byte("fresh-token"), TTL: 3600_000_000},
		}), nil
	})
	f.handle("/ReceiveMessages", func(req *http.Request, _ []byte) (*http.Response, error) {
		return statusResponse(req, 503), nil
	})
	ad := pairedAuth()
	ad.TachyonExpiry = time.Now().Add(-time.Hour)
	cli := fakeClient(ad, f)
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
				if _, err := marshalSession(ad, "p", "a"); err != nil {
					t.Error(err)
					return
				}
			}
		}
	}()
	err := cli.Connect(context.Background())
	close(stop)
	wg.Wait()
	cli.Disconnect()
	cli.StopAckInterval()
	if err != nil || refreshes.Load() == 0 {
		t.Fatalf("connect: %v, %d refreshes", err, refreshes.Load())
	}
	ad.CookiesLock.RLock()
	token := string(ad.TachyonAuthToken)
	ad.CookiesLock.RUnlock()
	if token != "fresh-token" || tokenExpiresWithin(ad, 30*time.Minute) {
		t.Error("the refreshed token was not stored")
	}
}

// Item 5's libgm part: Connect's token refresh uses Connect's context, so
// cancelling it ends a Connect that is waiting for the refresh.
func TestLibgmConnectRefreshIsCancellable(t *testing.T) {
	f := newFakeGoogle()
	f.handle("/RegisterRefresh", func(req *http.Request, _ []byte) (*http.Response, error) {
		<-req.Context().Done()
		return nil, req.Context().Err()
	})
	ad := pairedAuth()
	ad.TachyonExpiry = time.Now().Add(-time.Hour)
	cli := fakeClient(ad, f)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- cli.Connect(ctx) }()
	waitFor(t, "the refresh request", func() bool { return f.count("/RegisterRefresh") == 1 })
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Connect: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Connect did not return when cancelled")
	}
	if cli.IsConnected() || f.count("/ReceiveMessages") != 0 {
		t.Fatal("a cancelled Connect must not start the long poll")
	}
}
