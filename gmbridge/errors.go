package gmbridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
)

// Error codes. They cross into Java as the prefix of the exception message
// ("<code>: <detail>"), because errors.Is and object identity do not survive
// the gomobile boundary. Kotlin parses the prefix into RelayException.code.
const (
	codeNotPaired          = "not_paired"
	codeNotConnected       = "not_connected"
	codeNoCookies          = "no_cookies"
	codeCookiesInvalid     = "cookies_invalid"
	codeNoDevices          = "no_devices"
	codePairingInitTimeout = "pairing_init_timeout"
	codeIncorrectEmoji     = "incorrect_emoji"
	codePairingCancelled   = "pairing_cancelled"
	codePairingTimeout     = "pairing_timeout"
	codePairingInProgress  = "pairing_in_progress"
	codeNoPairing          = "no_pairing"
	codePhoneNotResponding = "phone_not_responding"
	codeConnectionClosed   = "connection_closed"
	codeNotFound           = "not_found"
	codeInvalidArgument    = "invalid_argument"
	codeTooLarge           = "too_large"
	codeSendFailed         = "send_failed"
	codeLoggedOut          = "logged_out"
	codeInternal           = "internal"
)

// codeUnpairRemoteFailed is not an error a call returns: it is the code on
// the "unpaired" state when the local wipe happened but the revoke did not
// reach Google, so the phone may still list the device.
const codeUnpairRemoteFailed = "unpair_remote_failed"

// maxDetailLen keeps exception messages short; a detail is a hint for the
// activity log, never a payload.
const maxDetailLen = 200

// bridgeError is the only error type that leaves the package. Its detail is
// written by this package (or is a sanitised libgm error), so it never holds
// message text, names, numbers, cookies or keys.
type bridgeError struct {
	code   string
	detail string
	cause  error
}

func (e *bridgeError) Error() string {
	if e.detail == "" {
		return e.code
	}
	return e.code + ": " + e.detail
}

func (e *bridgeError) Unwrap() error { return e.cause }

func newError(code, detail string) *bridgeError {
	return &bridgeError{code: code, detail: detail}
}

// toBridgeError maps any error to a bridgeError with a stable code.
// It is safe to call with nil (returns nil).
func toBridgeError(err error) *bridgeError {
	if err == nil {
		return nil
	}
	var be *bridgeError
	if errors.As(err, &be) {
		return be
	}
	code, detail := classify(err)
	return &bridgeError{code: code, detail: detail, cause: err}
}

// classify picks the error code for a libgm (or stdlib) error. Order matters:
// pairing sentinels first, then connection state, then server verdicts.
func classify(err error) (code, detail string) {
	switch {
	case errors.Is(err, libgm.ErrNoCookies):
		return codeNoCookies, "pairing requires Google account cookies"
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return codeNoDevices, "no phone with Google Messages found on this account"
	case errors.Is(err, libgm.ErrHadMultipleDevices):
		return codePairingInitTimeout, "phone did not answer (account has several phones)"
	case errors.Is(err, libgm.ErrPairingInitTimeout):
		return codePairingInitTimeout, "phone did not answer the pairing request"
	case errors.Is(err, libgm.ErrIncorrectEmoji):
		return codeIncorrectEmoji, "a different emoji was chosen on the phone"
	case errors.Is(err, libgm.ErrPairingCancelled):
		return codePairingCancelled, "pairing was cancelled on the phone"
	case errors.Is(err, libgm.ErrPairingTimeout):
		return codePairingTimeout, "pairing timed out"
	case errors.Is(err, libgm.ErrPhoneNotResponding):
		return codePhoneNotResponding, "phone did not respond"
	case errors.Is(err, libgm.ErrConnectionClosed):
		return codeConnectionClosed, "disconnected before the phone answered"
	case errors.Is(err, libgm.ErrClientIsNil):
		return codeNotPaired, "no client"
	case errors.Is(err, events.ErrInvalidCredentials):
		return codeCookiesInvalid, "Google rejected the account cookies"
	case errors.Is(err, events.ErrCallerNoPermission):
		return codeCookiesInvalid, "Google account has no permission"
	case errors.Is(err, events.ErrRequestedEntityNotFound):
		return codeLoggedOut, "pairing no longer exists"
	case errors.Is(err, context.DeadlineExceeded):
		return codePhoneNotResponding, "timed out"
	case errors.Is(err, context.Canceled):
		return codeConnectionClosed, "cancelled"
	}
	if status := httpStatus(err); status != 0 {
		switch status {
		case 401, 403:
			return codeCookiesInvalid, fmt.Sprintf("http %d", status)
		case 404:
			return codeNotFound, "http 404"
		default:
			return codeInternal, fmt.Sprintf("http %d", status)
		}
	}
	// checkLoggedIn returns plain fmt.Errorf values, so the text is all there is.
	if msg := safeErrorString(err); strings.HasSuffix(msg, "no auth token") || strings.HasSuffix(msg, "not logged in") {
		return codeNotPaired, "session has no pairing"
	}
	return codeInternal, sanitizeDetail(err)
}

// httpStatus digs the status code out of events.HTTPError, which libgm uses by
// value (and inside events.RequestError).
func httpStatus(err error) int {
	var he events.HTTPError
	if errors.As(err, &he) && he.Resp != nil {
		return he.Resp.StatusCode
	}
	var hp *events.HTTPError
	if errors.As(err, &hp) && hp != nil && hp.Resp != nil {
		return hp.Resp.StatusCode
	}
	return 0
}

// sanitizeDetail turns an unknown error into a short hint. URL errors are
// replaced wholesale because libgm request URLs carry an API key parameter.
func sanitizeDetail(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		if ue.Timeout() {
			return "network timeout"
		}
		return "network error"
	}
	var ne net.Error
	if errors.As(err, &ne) {
		if ne.Timeout() {
			return "network timeout"
		}
		return "network error"
	}
	msg := safeErrorString(err)
	if len(msg) > maxDetailLen {
		msg = msg[:maxDetailLen]
	}
	return msg
}

// safeErrorString calls Error() under recover: events.RequestError and
// events.HTTPError dereference fields that can be nil.
func safeErrorString(err error) (s string) {
	if err == nil {
		return ""
	}
	defer func() {
		if recover() != nil {
			s = fmt.Sprintf("%T", err)
		}
	}()
	return err.Error()
}

// panicError converts a recovered panic into an error. The panic value is not
// included: it could quote data that was being processed.
func panicError(r any) error {
	return newError(codeInternal, fmt.Sprintf("unexpected failure (%T)", r))
}

// recoverInto is deferred by every exported method that returns an error.
func recoverInto(err *error) {
	if r := recover(); r != nil {
		*err = panicError(r)
	}
}

// exported converts an internal error to what Java should see: always a
// *bridgeError (so the message has a code prefix) or nil.
func exported(err error) error {
	if be := toBridgeError(err); be != nil {
		return be
	}
	return nil
}
