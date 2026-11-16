package wa

import (
	"context"
	"errors"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/types/events"
)

const (
	// pairWindow: WhatsApp's linking socket lasts about as long as its six QR
	// codes (60 s + 5 × 20 s); after that the attempt is reported as timed out.
	pairWindow = 170 * time.Second
	// codeWindow: a pairing code stays valid for a few minutes once issued.
	codeWindow = 180 * time.Second
	// pairReadyWait is how long PairPhone waits for the linking socket to be
	// up (whatsmeow's first QR event) before asking for a code.
	pairReadyWait = 20 * time.Second
	// pairCodeTimeout bounds the pairing code request.
	pairCodeTimeout = 30 * time.Second
	// First and later QR code lifetimes, as WhatsApp Web uses them.
	qrFirstTimeout = 60 * time.Second
	qrNextTimeout  = 20 * time.Second
)

// pairingClientDisplay is what the code-linking request names the client as;
// WhatsApp only accepts "Browser (OS)" with common browsers and OSes. The
// name the phone lists afterwards is the device props' OS: "LightDecoder".
const pairingClientDisplay = "Chrome (Linux)"

// pairState is one linking attempt (an unpaired connection). Guarded by b.mu.
type pairState struct {
	active    bool
	seq       uint64        // bumped by every attempt and every stop
	ready     chan struct{} // closed on the attempt's first QR event
	readyDone bool
	codes     []string      // whatsmeow's QR codes for this socket
	qrWanted  bool          // StartQR was called
	qrStop    chan struct{} // non-nil while the QR emitter runs
	timer     *time.Timer   // the attempt's deadline
}

// pairPhoneCode asks WhatsApp for a pairing code; tests replace it.
var pairPhoneCode = func(ctx context.Context, cli *whatsmeow.Client, phone string) (string, error) {
	return cli.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, pairingClientDisplay)
}

// startPairingSocketLocked opens the unpaired connection whatsmeow links
// over, unless one is open. Callers hold b.lifecycle.
func (b *WABridge) startPairingSocketLocked() error {
	b.mu.Lock()
	cli, active := b.cli, b.pair.active
	b.mu.Unlock()
	if active && cli.IsConnected() {
		return nil
	}
	b.stopPairingLocked()
	b.mu.Lock()
	b.pair.seq++
	seq := b.pair.seq
	b.pair.active = true
	b.pair.ready = make(chan struct{})
	b.pair.readyDone = false
	b.pair.codes = nil
	b.pair.timer = time.AfterFunc(pairWindow, func() {
		b.pairingFailed(seq, codePairingTimeout, "the link was not confirmed in time")
	})
	b.mu.Unlock()
	if err := b.connectLocked(cli); err != nil {
		b.stopPairingLocked()
		return err
	}
	return nil
}

// stopPairingLocked ends the linking attempt's bookkeeping (not its
// connection). Callers hold b.lifecycle (or are in it).
func (b *WABridge) stopPairingLocked() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.stopPairingMu()
}

// stopPairingMu is stopPairingLocked with b.mu held.
func (b *WABridge) stopPairingMu() {
	p := &b.pair
	p.seq++
	p.active = false
	p.qrWanted = false
	if p.qrStop != nil {
		close(p.qrStop)
		p.qrStop = nil
	}
	if p.timer != nil {
		p.timer.Stop()
		p.timer = nil
	}
	p.codes = nil
}

// pairingFailed ends attempt seq (if it is still the current one): the
// linking connection is closed and Kotlin gets pair_error, then
// "disconnected". Any goroutine.
func (b *WABridge) pairingFailed(seq uint64, code, detail string) {
	defer func() { _ = recover() }()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	current := b.pair.active && b.pair.seq == seq
	b.mu.Unlock()
	if !current {
		return
	}
	b.stopPairingLocked()
	b.dropConnectionLocked()
	b.emit(kindPairError, pairErrorDTO{Code: code, Detail: detail})
	b.emitState(stateDisconnected, "", "")
}

// PairPhone asks WhatsApp for a pairing code for the user's own phone number
// (international format, digits; "+", spaces and dashes are ignored). The
// user types the 8 characters into WhatsApp on that phone (Linked devices >
// Link with phone number). Connect must have been called first. The result
// arrives as pair_success or pair_error.
func (b *WABridge) PairPhone(phoneNumber string) (code string, err error) {
	defer recoverInto(&err)
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, phoneNumber)
	if len(digits) < 7 {
		return "", newError(codeInvalidArgument, "phone number is too short")
	}
	b.mu.Lock()
	cli, closed, active, ready, seq := b.cli, b.closed, b.pair.active, b.pair.ready, b.pair.seq
	b.mu.Unlock()
	switch {
	case closed:
		return "", newError(codeInternal, "bridge is closed")
	case cli == nil:
		return "", newError(codeInternal, "the WhatsApp store is not available")
	case isPaired(cli):
		return "", newError(codeAlreadyPaired, "already linked; log out first")
	case !active:
		return "", newError(codeNotConnected, "call Connect first")
	}
	select {
	case <-ready:
	case <-time.After(pairReadyWait):
		return "", newError(codeTimeout, "WhatsApp did not open the link")
	}
	ctx, cancel := context.WithTimeout(context.Background(), pairCodeTimeout)
	defer cancel()
	code, err = pairPhoneCode(ctx, cli, digits)
	if err != nil {
		return "", exported(err)
	}
	b.mu.Lock()
	if b.pair.active && b.pair.seq == seq && b.pair.timer != nil {
		b.pair.timer.Reset(codeWindow)
	}
	b.mu.Unlock()
	return strings.ReplaceAll(code, "-", ""), nil
}

// StartQR emits "qr" events, each a code to show as a QR code for WhatsApp
// on the phone to scan (Linked devices > Link a device), until linking
// succeeds, fails, times out or CancelPairing. It opens the linking
// connection if Connect has not.
func (b *WABridge) StartQR() (err error) {
	defer recoverInto(&err)
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	cli, closed := b.cli, b.closed
	b.mu.Unlock()
	switch {
	case closed:
		return newError(codeInternal, "bridge is closed")
	case cli == nil:
		return newError(codeInternal, "the WhatsApp store is not available")
	case isPaired(cli):
		return newError(codeAlreadyPaired, "already linked; log out first")
	}
	if err := b.startPairingSocketLocked(); err != nil {
		return err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	b.pair.qrWanted = true
	b.startQREmitterMu()
	return nil
}

// startQREmitterMu starts the goroutine that emits the QR codes in turn,
// once the codes are known and StartQR wants them. b.mu is held.
func (b *WABridge) startQREmitterMu() {
	p := &b.pair
	if !p.active || !p.qrWanted || len(p.codes) == 0 || p.qrStop != nil {
		return
	}
	stop := make(chan struct{})
	p.qrStop = stop
	go b.emitQRs(p.seq, stop)
}

func (b *WABridge) emitQRs(seq uint64, stop chan struct{}) {
	defer func() { _ = recover() }()
	for i := 0; ; i++ {
		b.mu.Lock()
		if b.pair.seq != seq || !b.pair.active {
			b.mu.Unlock()
			return
		}
		codes := b.pair.codes
		var code string
		if i < len(codes) {
			code = codes[i]
		}
		b.mu.Unlock()
		if code == "" {
			b.pairingFailed(seq, codePairingTimeout, "the QR codes ran out")
			return
		}
		b.emit(kindQR, qrDTO{Code: code})
		wait := qrNextTimeout
		if i == 0 {
			wait = qrFirstTimeout
		}
		select {
		case <-stop:
			return
		case <-time.After(wait):
		}
	}
}

// CancelPairing abandons a linking attempt: the linking connection closes
// and "disconnected" follows. Nothing happens when no attempt runs.
func (b *WABridge) CancelPairing() {
	defer func() { _ = recover() }()
	b.abortConnecting()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	active := b.pair.active
	b.mu.Unlock()
	if !active {
		return
	}
	b.stopPairingLocked()
	b.dropConnectionLocked()
	b.emitState(stateDisconnected, "", "")
}

// --- pairing events (dispatcher) ---------------------------------------------------

func (b *WABridge) onQR(gen uint64, e *events.QR) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.gen != gen || !b.pair.active {
		return
	}
	b.pair.codes = append([]string(nil), e.Codes...)
	if !b.pair.readyDone {
		b.pair.readyDone = true
		close(b.pair.ready)
	}
	b.startQREmitterMu()
}

func (b *WABridge) onRotateADV(gen uint64, e *events.RotateADVSecret) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.gen != gen || !b.pair.active {
		return
	}
	for i, c := range b.pair.codes {
		b.pair.codes[i] = strings.Replace(c, e.OldSecret, e.NewSecret, 1)
	}
}

// onPairSuccess: whatsmeow stored the identity and reconnects by itself as
// a linked device; "connected" follows.
func (b *WABridge) onPairSuccess(gen uint64, e *events.PairSuccess) {
	b.mu.Lock()
	if b.gen != gen {
		b.mu.Unlock()
		return
	}
	b.stopPairingMu()
	b.mu.Unlock()
	b.deliver(kindPairSuccess, pairSuccessDTO{OwnID: e.ID.User})
	b.deliver(kindState, stateDTO{State: stateConnecting})
}

// failCurrentPairing ends the current attempt from the dispatcher.
func (b *WABridge) failCurrentPairing(gen uint64, code, detail string) {
	b.mu.Lock()
	seq, active := b.pair.seq, b.pair.active && b.gen == gen
	b.mu.Unlock()
	if active {
		go b.pairingFailed(seq, code, detail)
	}
}

func pairErrorDetail(err error) string {
	switch {
	case err == nil:
		return "linking failed"
	case errors.Is(err, whatsmeow.ErrPairRejectedLocally):
		return "linking was rejected"
	case errors.Is(err, whatsmeow.ErrPairInvalidDeviceSignature), errors.Is(err, whatsmeow.ErrPairInvalidDeviceIdentityHMAC):
		return "the phone's answer could not be verified"
	}
	return sanitizeDetail(err)
}
