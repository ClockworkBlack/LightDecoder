package gmbridge

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func TestErrorCodeMapping(t *testing.T) {
	httpErr := func(status int) error {
		return events.HTTPError{Action: "polling", Resp: &http.Response{StatusCode: status}}
	}
	cases := []struct {
		err  error
		code string
	}{
		{libgm.ErrNoCookies, codeNoCookies},
		{fmt.Errorf("x: %w", libgm.ErrNoDevicesFound), codeNoDevices},
		{libgm.ErrPairingInitTimeout, codePairingInitTimeout},
		{fmt.Errorf("%w (%w)", libgm.ErrPairingInitTimeout, libgm.ErrHadMultipleDevices), codePairingInitTimeout},
		{libgm.ErrIncorrectEmoji, codeIncorrectEmoji},
		{fmt.Errorf("%w (user chose 'this is not me' option)", libgm.ErrPairingCancelled), codePairingCancelled},
		{fmt.Errorf("%w (code: 1/2)", libgm.ErrPairingTimeout), codePairingTimeout},
		{fmt.Errorf("%w in 1m0s", libgm.ErrPhoneNotResponding), codePhoneNotResponding},
		{fmt.Errorf("failed to send client finish: %w", libgm.ErrConnectionClosed), codeConnectionClosed},
		{libgm.ErrClientIsNil, codeNotPaired},
		{fmt.Errorf("failed to refresh auth token: %w", events.ErrInvalidCredentials), codeCookiesInvalid},
		{events.RequestError{Data: &gmproto.ErrorResponse{Type: 16, Message: events.ErrInvalidCredentials.Data.Message}}, codeCookiesInvalid},
		{fmt.Errorf("failed to prepare gaia pairing: %w", events.ErrCallerNoPermission), codeCookiesInvalid},
		{fmt.Errorf("failed to refresh auth token: %w", events.ErrRequestedEntityNotFound), codeLoggedOut},
		{httpErr(401), codeCookiesInvalid},
		{fmt.Errorf("wrapped: %w", httpErr(403)), codeCookiesInvalid},
		{httpErr(404), codeNotFound},
		{httpErr(500), codeInternal},
		{context.DeadlineExceeded, codePhoneNotResponding},
		{context.Canceled, codeConnectionClosed},
		{fmt.Errorf("no auth token"), codeNotPaired},
		{fmt.Errorf("failed to reconnect: %w", fmt.Errorf("not logged in")), codeNotPaired},
		{errors.New("something else"), codeInternal},
		{newError(codeTooLarge, "big"), codeTooLarge},
	}
	for _, c := range cases {
		be := toBridgeError(c.err)
		if be.code != c.code {
			t.Errorf("%v: got %s want %s", c.err, be.code, c.code)
		}
		if !strings.HasPrefix(be.Error(), c.code) {
			t.Errorf("message must start with the code: %q", be.Error())
		}
	}
	if toBridgeError(nil) != nil || exported(nil) != nil {
		t.Error("nil must stay nil (and not become a typed-nil interface)")
	}
}

func TestErrorDetailsAreSafe(t *testing.T) {
	// URLs of libgm requests carry an API key parameter: never echo them.
	ue := &url.Error{Op: "Post", URL: "https://example.invalid/rpc?key=SECRET", Err: errors.New("dial tcp: no route")}
	be := toBridgeError(fmt.Errorf("failed to send request: %w", ue))
	if strings.Contains(be.Error(), "SECRET") || be.detail != "network error" {
		t.Errorf("URL leaked or not sanitised: %q", be.Error())
	}
	// A RequestError with neither Data nor HTTP panics in Error(); we must not.
	be = toBridgeError(events.RequestError{})
	if be.code != codeInternal || be.detail == "" {
		t.Errorf("nil RequestError: %+v", be)
	}
	long := toBridgeError(errors.New(strings.Repeat("x", 1000)))
	if len(long.detail) > maxDetailLen {
		t.Errorf("detail not truncated: %d", len(long.detail))
	}
	if got := newError(codeInternal, "").Error(); got != codeInternal {
		t.Errorf("empty detail: %q", got)
	}
	if got := panicError("secret text").Error(); strings.Contains(got, "secret") {
		t.Errorf("panic value leaked: %q", got)
	}
}
