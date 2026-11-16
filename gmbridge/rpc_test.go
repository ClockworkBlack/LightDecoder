package gmbridge

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// A send cut off after Google accepted the POST may still go out from the
// server's queue, so it must not come back as connection_closed (which the
// outbox resends) but as phone_not_responding (which it does not).
func TestSendErrorAfterAcceptIsMaybeDelivered(t *testing.T) {
	cases := []struct {
		name string
		err  error
		code string
	}{
		{"disconnected while waiting for the phone", fmt.Errorf("x: %w", libgm.ErrConnectionClosed), codePhoneNotResponding},
		{"bare cancel from the response wait", context.Canceled, codePhoneNotResponding},
		{"cancel while reading the POST response", fmt.Errorf("failed to read response body: %w", context.Canceled), codePhoneNotResponding},
		{"POST cancelled in flight", &url.Error{Op: "Post", URL: "https://example.invalid/send?key=SECRET", Err: context.Canceled}, codeConnectionClosed},
		{"POST failed in flight", &url.Error{Op: "Post", URL: "https://example.invalid/send?key=SECRET", Err: errors.New("connection reset")}, codeInternal},
		{"phone timeout", fmt.Errorf("%w in 1m0s", libgm.ErrPhoneNotResponding), codePhoneNotResponding},
		{"rpc deadline", context.DeadlineExceeded, codePhoneNotResponding},
		{"rejected POST", events.HTTPError{Resp: &http.Response{StatusCode: 401}}, codeCookiesInvalid},
	}
	for _, c := range cases {
		err := sendError(c.err)
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
	b.cache.reset()
	if force(rcsAuto) {
		t.Error("cache reset (unpair, new pairing) should forget ForceRCS")
	}
}
