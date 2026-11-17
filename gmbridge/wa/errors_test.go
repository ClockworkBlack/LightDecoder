package wa

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"testing"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
)

func TestClassify(t *testing.T) {
	cases := []struct {
		err  error
		code string
	}{
		{store.ErrDeviceDeleted, codeLoggedOut},
		{whatsmeow.ErrNotLoggedIn, codeNotPaired},
		{whatsmeow.ErrNotConnected, codeNotConnected},
		{whatsmeow.ErrIQDisconnected, codeConnectionClosed},
		{&whatsmeow.DisconnectedError{Action: "message send"}, codeConnectionClosed},
		{whatsmeow.ErrIQTimedOut, codeTimeout},
		{whatsmeow.ErrMessageTimedOut, codeTimeout},
		{whatsmeow.ErrPhoneNumberTooShort, codeInvalidArgument},
		{whatsmeow.ErrPhoneNumberIsNotInternational, codeInvalidArgument},
		{whatsmeow.ErrRecipientNotOnWhatsApp, codeNotOnWhatsApp},
		{whatsmeow.ErrNoPermissionInGroup, codeNotAllowed},
		{whatsmeow.ErrGroupNotFound, codeNotFound},
		{whatsmeow.ErrBroadcastListUnsupported, codeUnsupported},
		{whatsmeow.ErrNoPushName, codeNotReady},
		{whatsmeow.ErrAppStateRateLimited, codeRateLimited},
		{whatsmeow.ErrMediaDownloadFailedWith404, codeNotFound},
		{whatsmeow.ErrMediaDownloadFailedWith410, codeNotFound},
		{whatsmeow.ErrInvalidMediaHMAC, codeInternal},
		{fmt.Errorf("wrapped: %w", whatsmeow.ErrIQRateOverLimit), codeRateLimited},
		{whatsmeow.ErrIQNotFound, codeNotFound},
		{whatsmeow.ErrIQBadRequest, codeInvalidArgument},
		{whatsmeow.ErrIQNotAuthorized, codeNotPaired},
		{context.DeadlineExceeded, codeTimeout},
		{context.Canceled, codeConnectionClosed},
		{errors.New("something odd"), codeInternal},
	}
	for _, c := range cases {
		be := toBridgeError(c.err)
		if be.code != c.code {
			t.Errorf("%v: code %q, want %q", c.err, be.code, c.code)
		}
		if !strings.HasPrefix(be.Error(), c.code) {
			t.Errorf("%v: message %q lacks code prefix", c.err, be.Error())
		}
		if !errors.Is(be, c.err) && c.code != codeInternal {
			t.Errorf("%v: cause lost", c.err)
		}
	}
	if toBridgeError(nil) != nil || exported(nil) != nil {
		t.Error("nil must stay nil")
	}
	be := newError(codeNotFound, "x")
	if toBridgeError(fmt.Errorf("w: %w", be)) != be {
		t.Error("a wrapped bridgeError must be returned as is")
	}
}

// Error details reach Kotlin's activity log, so they must not quote JIDs,
// numbers or ids that whatsmeow puts into its error strings.
func TestDetailsAreScrubbed(t *testing.T) {
	err := errors.New("failed to get devices of 15550002222@s.whatsapp.net (id 3EB0A1B2C3D4E5F60718, +49 151 2345 6789) in 120363000000000001@g.us")
	d := toBridgeError(err).detail
	for _, leak := range []string{"15550002222", "s.whatsapp.net", "3EB0A1B2C3D4E5F60718", "2345", "120363000000000001", "g.us"} {
		if strings.Contains(d, leak) {
			t.Errorf("detail %q leaks %q", d, leak)
		}
	}
	long := toBridgeError(errors.New(strings.Repeat("word ", 100))).detail
	if len(long) > maxDetailLen {
		t.Errorf("detail not truncated: %d", len(long))
	}
	ne := &net.OpError{Op: "dial", Err: errors.New("refused 10.0.0.1")}
	if d := toBridgeError(ne).detail; d != "network error" {
		t.Errorf("network detail %q", d)
	}
}

func TestRecoverInto(t *testing.T) {
	f := func() (err error) {
		defer recoverInto(&err)
		panic("secret message text")
	}
	err := f()
	mustCode(t, err, codeInternal)
	if strings.Contains(err.Error(), "secret") {
		t.Fatalf("panic value leaked: %q", err)
	}
}
