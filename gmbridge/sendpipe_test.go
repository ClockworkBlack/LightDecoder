package gmbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// fakePipe stands in for libgm's SendMessageAsync: the post returns at once,
// the phone's answer comes when the test releases it.
type fakePipe struct {
	mu       sync.Mutex
	posted   []string // tmpIDs, in the order they reached "Google"
	answers  map[string]chan *gmproto.SendMessageResponse
	postFail error
}

func (f *fakePipe) install(t *testing.T) {
	f.answers = map[string]chan *gmproto.SendMessageResponse{}
	stub(t, &sendMessageAsync, func(ctx context.Context, _ *libgm.Client, req *gmproto.SendMessageRequest) (func(context.Context) (*gmproto.SendMessageResponse, error), error) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.postFail != nil {
			return nil, f.postFail
		}
		id := req.GetTmpID()
		f.posted = append(f.posted, id)
		ch := make(chan *gmproto.SendMessageResponse, 1)
		f.answers[id] = ch
		return func(waitCtx context.Context) (*gmproto.SendMessageResponse, error) {
			select {
			case r, ok := <-ch:
				if !ok {
					return nil, fmt.Errorf("x: %w", libgm.ErrConnectionClosed)
				}
				return r, nil
			case <-waitCtx.Done():
				return nil, waitCtx.Err()
			}
		}, nil
	})
}

func (f *fakePipe) answer(id string, status gmproto.SendMessageResponse_Status) {
	f.mu.Lock()
	ch := f.answers[id]
	f.mu.Unlock()
	ch <- &gmproto.SendMessageResponse{Status: status}
}

func sendResult(t *testing.T, out []byte) sendResultDTO {
	t.Helper()
	var r sendResultDTO
	if err := json.Unmarshal(out, &r); err != nil {
		t.Fatal(err)
	}
	return r
}

// Three texts are all posted before the phone answers any of them, in order,
// and each answer is collected afterwards with SendText's result.
func TestStartedTextsDoNotWaitForThePhone(t *testing.T) {
	b, _ := newPairedBridge(t)
	markLive(b)
	b.cache.putConversation(&gmproto.Conversation{ConversationID: "c1"})
	f := &fakePipe{}
	f.install(t)

	for _, id := range []string{"t1", "t2", "t3"} {
		if err := b.StartText("c1", id, "hello "+id, ""); err != nil {
			t.Fatalf("start %s: %v", id, err)
		}
	}
	if fmt.Sprint(f.posted) != "[t1 t2 t3]" {
		t.Fatalf("posted in order without waiting: %v", f.posted)
	}

	f.answer("t2", gmproto.SendMessageResponse_SUCCESS)
	f.answer("t1", gmproto.SendMessageResponse_SUCCESS)
	f.answer("t3", gmproto.SendMessageResponse_FAILURE_2)
	for _, id := range []string{"t1", "t2"} {
		out, err := b.FinishSend(id)
		if err != nil {
			t.Fatalf("finish %s: %v", id, err)
		}
		if r := sendResult(t, out); !r.OK || r.TmpID != id || r.Status != "SUCCESS" {
			t.Errorf("finish %s: %+v", id, r)
		}
	}
	out, err := b.FinishSend("t3")
	if err != nil {
		t.Fatal(err)
	}
	if r := sendResult(t, out); r.OK || r.Status != "FAILURE_2" || r.Code != codeSendFailed {
		t.Errorf("a transient answer is reported for the outbox to retry: %+v", r)
	}
	// Collected once only.
	if _, err := b.FinishSend("t1"); codeOf(err) != codePhoneNotResponding {
		t.Errorf("second finish: %v", err)
	}
}

// Failures keep SendText's rules: before Google got it, safe to resend; after,
// maybe delivered.
func TestStartedTextFailuresKeepTheSendRules(t *testing.T) {
	b, _ := newPairedBridge(t)
	markLive(b)
	b.cache.putConversation(&gmproto.Conversation{ConversationID: "c1"})
	f := &fakePipe{}
	f.install(t)

	if err := b.StartText("c1", "t1", "  ", ""); codeOf(err) != codeInvalidArgument {
		t.Errorf("empty text: %v", err)
	}
	if err := b.StartText("", "t1", "hi", ""); codeOf(err) != codeInvalidArgument {
		t.Errorf("no conversation: %v", err)
	}

	// Refused before anything was written: the plain mapping, which the outbox sends again.
	f.postFail = errClientRetired
	if err := b.StartText("c1", "t1", "hi", ""); codeOf(err) != codeNotPaired {
		t.Errorf("refused post: %v", err)
	}
	f.postFail = nil

	// Accepted, then the connection dropped while waiting for the phone.
	if err := b.StartText("c1", "t2", "hi", ""); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	close(f.answers["t2"])
	f.mu.Unlock()
	if _, err := b.FinishSend("t2"); codeOf(err) != codePhoneNotResponding {
		t.Errorf("disconnected after Google accepted it: %v", err)
	}

	// Disconnect while waiting: the wait ends, as maybe delivered.
	if err := b.StartText("c1", "t3", "hi", ""); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		_, err := b.FinishSend("t3")
		done <- err
	}()
	b.mu.Lock()
	cancel := b.connCancel
	b.mu.Unlock()
	cancel()
	select {
	case err := <-done:
		if codeOf(err) != codePhoneNotResponding {
			t.Errorf("disconnected while waiting: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the wait did not end on disconnect")
	}
}

// A started send whose answer is never asked for is forgotten again.
func TestAbandonedStartIsForgotten(t *testing.T) {
	var p pendingSends
	ctx, cancel := context.WithCancel(context.Background())
	ps := &pendingSend{tmpID: "t1", ctx: ctx, cancel: cancel}
	ps.timer = time.AfterFunc(10*time.Millisecond, func() {
		if p.take("t1") != nil {
			cancel()
		}
	})
	p.put(ps)
	time.Sleep(100 * time.Millisecond)
	if !errors.Is(ctx.Err(), context.Canceled) {
		t.Error("its wait was not cancelled")
	}
	if p.take("t1") != nil {
		t.Error("still pending")
	}
}
