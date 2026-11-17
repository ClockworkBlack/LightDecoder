package wa

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/url"
	"regexp"
	"strconv"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
)

// Error codes. As in gmbridge, they cross into Java as the prefix of the
// exception message ("<code>: <detail>"), because errors.Is and object
// identity do not survive the gomobile boundary. The shared ones have the
// same spelling as gmbridge's, so Kotlin's RelayException parsing applies.
const (
	codeNotPaired        = "not_paired"
	codeNotConnected     = "not_connected"
	codeConnectionClosed = "connection_closed"
	codeNotFound         = "not_found"
	codeInvalidArgument  = "invalid_argument"
	codeTooLarge         = "too_large"
	codeSendFailed       = "send_failed"
	codeLoggedOut        = "logged_out"
	codeInternal         = "internal"
	codePairingTimeout   = "pairing_timeout"
	codePairingCancelled = "pairing_cancelled"

	// WhatsApp-specific codes.
	codeAlreadyPaired  = "already_paired"
	codeTimeout        = "timeout"
	codeRateLimited    = "rate_limited"
	codeNotOnWhatsApp  = "not_on_whatsapp"
	codeNotAllowed     = "not_allowed"
	codeUnsupported    = "unsupported"
	codeNotReady       = "not_ready"
	codePairFailed     = "pair_failed"
	codeStreamReplaced = "stream_replaced"
	codeTempBanned     = "temp_banned"
	codeClientOutdated = "client_outdated"
	codeConnectFailure = "connect_failure"
	codeConnectionLost = "connection_lost"
)

// codeUnpairRemoteFailed is the code of Logout's error and of the
// "unpaired" state when the local wipe happened but WhatsApp was not told,
// so the phone may still list the device.
const codeUnpairRemoteFailed = "unpair_remote_failed"

// maxDetailLen keeps exception messages short; a detail is a hint for the
// activity log, never a payload.
const maxDetailLen = 200

// bridgeError is the only error type that leaves the package. Its detail is
// written by this package or is a scrubbed whatsmeow error, so it never holds
// message text, names, numbers, JIDs, keys or codes.
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

// classify picks the error code for a whatsmeow (or stdlib) error.
func classify(err error) (code, detail string) {
	var disc *whatsmeow.DisconnectedError
	var iq *whatsmeow.IQError
	var dl whatsmeow.DownloadHTTPError
	switch {
	case errors.Is(err, store.ErrDeviceDeleted):
		return codeLoggedOut, "this device was unlinked"
	case errors.Is(err, whatsmeow.ErrNotLoggedIn), errors.Is(err, whatsmeow.ErrClientIsNil):
		return codeNotPaired, "link with your phone first"
	case errors.Is(err, whatsmeow.ErrNotConnected):
		return codeNotConnected, "not connected to WhatsApp"
	case errors.As(err, &disc):
		return codeConnectionClosed, "disconnected before WhatsApp answered"
	case errors.Is(err, whatsmeow.ErrAlreadyConnected):
		return codeInternal, "already connected"
	case errors.Is(err, whatsmeow.ErrIQTimedOut), errors.Is(err, whatsmeow.ErrMessageTimedOut):
		return codeTimeout, "WhatsApp did not answer in time"
	case errors.Is(err, whatsmeow.ErrPhoneNumberTooShort):
		return codeInvalidArgument, "phone number is too short"
	case errors.Is(err, whatsmeow.ErrPhoneNumberIsNotInternational):
		return codeInvalidArgument, "phone number must start with the country code"
	case errors.Is(err, whatsmeow.ErrRecipientNotOnWhatsApp):
		return codeNotOnWhatsApp, "the recipient is not on WhatsApp"
	case errors.Is(err, whatsmeow.ErrNoPermissionInGroup), errors.Is(err, whatsmeow.ErrNotInGroup):
		return codeNotAllowed, "no permission in this group"
	case errors.Is(err, whatsmeow.ErrGroupNotFound):
		return codeNotFound, "group not found"
	case errors.Is(err, whatsmeow.ErrBroadcastListUnsupported), errors.Is(err, whatsmeow.ErrUnknownServer):
		return codeUnsupported, "this kind of chat is not supported"
	case errors.Is(err, whatsmeow.ErrNoPushName):
		return codeNotReady, "WhatsApp has not finished syncing yet"
	case errors.Is(err, whatsmeow.ErrAppStateRateLimited):
		return codeRateLimited, "WhatsApp is rate limiting chat settings"
	case errors.Is(err, whatsmeow.ErrAppStateUpdate):
		return codeSendFailed, "WhatsApp rejected the chat setting"
	case errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith404),
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith410),
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith403):
		return codeNotFound, "media is no longer on the WhatsApp server"
	case errors.As(err, &dl) && dl.Response != nil:
		return codeInternal, "media download failed with http " + strconv.Itoa(dl.StatusCode)
	case errors.Is(err, whatsmeow.ErrNoURLPresent), errors.Is(err, whatsmeow.ErrNothingDownloadableFound):
		return codeNotFound, "media has no download path"
	case errors.Is(err, whatsmeow.ErrInvalidMediaHMAC),
		errors.Is(err, whatsmeow.ErrInvalidMediaEncSHA256),
		errors.Is(err, whatsmeow.ErrInvalidMediaSHA256),
		errors.Is(err, whatsmeow.ErrInvalidUnencryptedMediaSHA256),
		errors.Is(err, whatsmeow.ErrTooShortFile):
		return codeInternal, "media failed its integrity check"
	case errors.As(err, &iq):
		return classifyIQ(iq.Code)
	case errors.Is(err, context.DeadlineExceeded):
		return codeTimeout, "timed out"
	case errors.Is(err, context.Canceled):
		return codeConnectionClosed, "cancelled"
	}
	return codeInternal, sanitizeDetail(err)
}

func classifyIQ(status int) (code, detail string) {
	d := "WhatsApp answered " + strconv.Itoa(status)
	switch status {
	case 400, 406:
		return codeInvalidArgument, d
	case 401:
		return codeNotPaired, d
	case 403, 405:
		return codeNotAllowed, d
	case 404, 410:
		return codeNotFound, d
	case 429:
		return codeRateLimited, d
	default:
		return codeInternal, d
	}
}

// Scrubbing patterns: anything JID-shaped, then long digit runs (phone
// numbers, ids) and long hex/base64 runs (keys, hashes, message ids).
var (
	jidLike    = regexp.MustCompile(`[0-9A-Za-z._:+-]*@[A-Za-z.]+`)
	digitRun   = regexp.MustCompile(`\+?[0-9][0-9 ]{4,}[0-9]`)
	opaqueRuns = regexp.MustCompile(`[0-9A-Za-z+/=_-]{16,}`)
)

// sanitizeDetail turns an unknown error into a short hint. whatsmeow's error
// strings often quote JIDs or message ids, so those are scrubbed.
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
	return scrub(safeErrorString(err))
}

func scrub(msg string) string {
	msg = jidLike.ReplaceAllString(msg, "<id>")
	msg = opaqueRuns.ReplaceAllString(msg, "<data>")
	msg = digitRun.ReplaceAllString(msg, "<n>")
	if len(msg) > maxDetailLen {
		msg = msg[:maxDetailLen]
	}
	return msg
}

// safeErrorString calls Error() under recover.
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
