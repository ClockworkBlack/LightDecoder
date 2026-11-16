package wa

import (
	"sync"
	"time"
)

// Event kinds (contract "Events").
const (
	kindState        = "state"
	kindQR           = "qr"
	kindPairSuccess  = "pair_success"
	kindPairError    = "pair_error"
	kindMessage      = "message"
	kindConversation = "conversation"
	kindReceipt      = "receipt"
	kindTyping       = "typing"
	kindPresence     = "presence"
	kindHistorySync  = "history_sync"
)

// Values of the "state" event, the same words as gmbridge's.
const (
	stateConnecting   = "connecting"
	stateConnected    = "connected"
	stateDisconnected = "disconnected"
	stateLoggedOut    = "logged_out"
	stateError        = "error"
	stateUnpaired     = "unpaired"
)

// Callback is implemented in Kotlin. It is called from a single Go dispatcher
// goroutine, in order, never from whatsmeow's event handler directly (that
// handler only enqueues into an unbounded FIFO). It returns error so that
// gomobile clears any Java exception it throws.
type Callback interface {
	// OnEvent delivers one event; kind is one of the contract's kinds and
	// payload is UTF-8 JSON.
	OnEvent(kind string, payload []byte) error
}

// dispatcher is the single goroutine that interprets whatsmeow events and
// calls into Kotlin, exactly as in gmbridge: whatsmeow calls its handlers
// synchronously from its receive loop, so the handler only appends a job to
// this unbounded FIFO. One goroutine runs the jobs in order, so Kotlin sees
// events in the order whatsmeow produced them and never concurrently. A panic
// in a job (or in Kotlin, surfacing as a Go panic) is contained to that job.
type dispatcher struct {
	mu     sync.Mutex
	cond   *sync.Cond
	queue  []func()
	closed bool
	done   chan struct{}
}

func newDispatcher() *dispatcher {
	d := &dispatcher{done: make(chan struct{})}
	d.cond = sync.NewCond(&d.mu)
	go d.run()
	return d
}

// push enqueues a job; it never blocks. Jobs pushed after close are dropped.
func (d *dispatcher) push(job func()) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return false
	}
	d.queue = append(d.queue, job)
	d.cond.Signal()
	return true
}

func (d *dispatcher) run() {
	defer close(d.done)
	for {
		d.mu.Lock()
		for len(d.queue) == 0 && !d.closed {
			d.cond.Wait()
		}
		if len(d.queue) == 0 {
			d.mu.Unlock()
			return
		}
		job := d.queue[0]
		d.queue[0] = nil
		d.queue = d.queue[1:]
		d.mu.Unlock()
		runJob(job)
	}
}

func runJob(job func()) {
	defer func() { _ = recover() }()
	job()
}

// close stops accepting jobs, lets the queued ones run, and waits up to wait
// for them, so that a stuck callback cannot hang Close on the caller's thread.
func (d *dispatcher) close(wait time.Duration) {
	d.mu.Lock()
	d.closed = true
	d.cond.Broadcast()
	d.mu.Unlock()
	select {
	case <-d.done:
	case <-time.After(wait):
	}
}

// --- delivery (dispatcher goroutine only) -------------------------------------

func (b *WABridge) deliver(kind string, v any) {
	if b.cb == nil {
		return
	}
	payload := toJSON(v)
	func() {
		defer func() { _ = recover() }()
		_ = b.cb.OnEvent(kind, payload)
	}()
}

// --- enqueueing (any goroutine) -----------------------------------------------

func (b *WABridge) emit(kind string, v any) {
	b.disp.push(func() { b.deliver(kind, v) })
}

func (b *WABridge) emitState(state, code, detail string) {
	b.emit(kindState, stateDTO{State: state, Code: code, Detail: detail})
}

// emitStateErr emits a state whose code/detail come from an error.
func (b *WABridge) emitStateErr(state string, err error) {
	be := toBridgeError(err)
	if be == nil {
		b.emitState(state, "", "")
		return
	}
	b.emitState(state, be.code, be.detail)
}
