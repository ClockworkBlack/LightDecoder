package gmbridge

import (
	"context"
	"errors"
	"strings"
	"sync"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// Sending several texts in a row without a phone round trip between them.
//
// SendText returns only when the phone has answered, which takes seconds,
// and the app sends one message at a time to keep their order: a burst of
// messages went out one phone round trip apart. StartText returns as soon as
// Google has accepted the message, and FinishSend waits for the phone's
// answer to it. The app starts each message after the previous one was
// accepted (so Google gets them in order) and collects the answers
// alongside. A start that fails is reported exactly as SendText reports it;
// so is an answer.

// pendingAbandonAfter forgets a started send whose answer was never asked
// for (the app stopped in between), so its wait does not linger. Longer than
// rpcTimeout, which ends the wait itself first.
const pendingAbandonAfter = rpcTimeout + 30*time.Second

type pendingSend struct {
	tmpID  string
	ctx    context.Context
	cancel context.CancelFunc
	wait   func(context.Context) (*gmproto.SendMessageResponse, error)
	timer  *time.Timer
}

type pendingSends struct {
	mu   sync.Mutex
	byID map[string]*pendingSend
}

func (p *pendingSends) put(s *pendingSend) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.byID == nil {
		p.byID = make(map[string]*pendingSend)
	}
	if old := p.byID[s.tmpID]; old != nil {
		old.timer.Stop()
		old.cancel()
	}
	p.byID[s.tmpID] = s
}

func (p *pendingSends) take(tmpID string) *pendingSend {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.byID[tmpID]
	if s != nil {
		delete(p.byID, tmpID)
		s.timer.Stop()
	}
	return s
}

// sendMessageAsync is cli.SendMessageAsync; tests replace it.
var sendMessageAsync = func(ctx context.Context, cli *libgm.Client, req *gmproto.SendMessageRequest) (func(context.Context) (*gmproto.SendMessageResponse, error), error) {
	return cli.SendMessageAsync(ctx, req)
}

// StartText posts a text message and returns once Google has accepted it;
// FinishSend(tmpID) then returns the SendResultDTO SendText would have.
// A failure here is what SendText would report for the same failure.
func (b *GMBridge) StartText(conversationID, tmpID, text, replyToID string) (err error) {
	defer recoverInto(&err)
	if conversationID == "" || tmpID == "" {
		return newError(codeInvalidArgument, "conversation ID and tmp ID are required")
	}
	if strings.TrimSpace(text) == "" {
		return newError(codeInvalidArgument, "text is empty")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	conv, err := b.conversationForWrite(ctx, cli, conversationID)
	if err != nil {
		cancel()
		return exported(err)
	}
	req := b.buildSendRequest(conv, tmpID, []*gmproto.MessageInfo{textInfo(text)}, replyToID)
	// Nothing has gone to Google yet, so whatever happens here is safe to send again.
	if cerr := ctx.Err(); cerr != nil {
		cancel()
		if errors.Is(cerr, context.DeadlineExceeded) {
			return newError(codeSendFailed, "timed out before sending")
		}
		return newError(codeConnectionClosed, "disconnected before sending")
	}
	ctx, wrote := traceRequestWrites(ctx)
	wait, err := sendMessageAsync(ctx, cli, req)
	if err != nil {
		cancel()
		return sendError(err, wrote.Load())
	}
	ps := &pendingSend{tmpID: tmpID, ctx: ctx, cancel: cancel, wait: wait}
	ps.timer = time.AfterFunc(pendingAbandonAfter, func() {
		if b.pending.take(tmpID) != nil {
			cancel()
		}
	})
	b.pending.put(ps)
	return nil
}

// FinishSend waits for the phone's answer to a message StartText posted and
// returns a SendResultDTO. Google already had the message, so a lost
// connection or a timeout here is phone_not_responding (the message may
// still go out; its echo settles it), as in SendText.
func (b *GMBridge) FinishSend(tmpID string) (out []byte, err error) {
	defer recoverInto(&err)
	ps := b.pending.take(tmpID)
	if ps == nil {
		return nil, newError(codePhoneNotResponding, "no answer is awaited for this message")
	}
	defer ps.cancel()
	resp, err := ps.wait(ps.ctx)
	if err != nil {
		return nil, sendError(err, true)
	}
	result := sendResultDTO{TmpID: tmpID, Status: resp.GetStatus().String()}
	if resp.GetStatus() == gmproto.SendMessageResponse_SUCCESS {
		result.OK = true
	} else {
		result.Code = codeSendFailed
	}
	return toJSON(result), nil
}
