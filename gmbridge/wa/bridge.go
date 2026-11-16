// Package wa wraps whatsmeow (a WhatsApp Web multidevice library) for the
// LightDecoder Android app. It is bound by gomobile into the same .aar as
// package gmbridge (two Go runtimes in one app would clash); Kotlin sees
// com.lightdecoder.gm.wa.Wa and com.lightdecoder.gm.wa.WABridge.
//
// The surface follows gmbridge's rules: strings, []byte JSON, bool and int64
// only, one callback interface, errors whose message starts with a stable
// code ("not_connected: ..."), and every exported entry point recovers
// panics, because a Go panic aborts the whole Android process.
//
// Linking is never automated: the user asks for a pairing code (or a QR
// code) and enters it in WhatsApp on their own phone.
//
// Privacy: whatsmeow gets no logger (waLog.Noop) and the global zerolog
// level is disabled, so neither it nor go.mau.fi/util logs anything. This
// package logs nothing itself, and error details are scrubbed of JIDs,
// numbers and opaque ids.
//
// whatsmeow is MPL-2.0 and its libsignal is GPL-3.0; the app's source is
// public (AGPL-3.0) for gmbridge's sake anyway.
package wa

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3" // the "sqlite3" driver (cgo, built with the NDK by gomobile)
	"github.com/rs/zerolog"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
)

// Version is the WhatsApp bridge API version; Kotlin can check it at startup.
const Version = "1"

// linkedDeviceName is what WhatsApp on the phone lists under Linked devices.
const linkedDeviceName = "LightDecoder"

// dbFileName is the SQLite file in dataDir: whatsmeow's device store (keys,
// sessions, contacts) and this package's chat index and message cache.
const dbFileName = "whatsapp.db"

// tmpDirName holds encrypted uploads while they are sent.
const tmpDirName = "wa-tmp"

const (
	// rpcTimeout bounds one call to WhatsApp.
	rpcTimeout = 75 * time.Second
	// logoutTimeout bounds telling WhatsApp about Logout.
	logoutTimeout = 15 * time.Second
	// logoutConnectWait is how long Logout waits for a temporary connection.
	logoutConnectWait = 20 * time.Second
	// closeDrainWait bounds how long Close waits for queued callbacks.
	closeDrainWait = 2 * time.Second
	// defaultConversationCount / defaultMessageCount apply when count <= 0.
	defaultConversationCount = 100
	defaultMessageCount      = 50
	maxPageCount             = 500
)

func init() {
	// whatsmeow's sqlstore and go.mau.fi/util log through zerolog.Ctx(ctx),
	// which falls back to the global level (gmbridge sets this as well).
	zerolog.SetGlobalLevel(zerolog.Disabled)
	// Sent in the device props when linking; WhatsApp shows it under Linked
	// devices. Only init writes this global, before any client exists.
	store.SetOSInfo(linkedDeviceName, [3]uint32{1, 0, 0})
}

// WABridge owns the WhatsApp store in dataDir and at most one whatsmeow
// client. It is safe to call from several Java threads.
type WABridge struct {
	cb      Callback
	disp    *dispatcher
	dataDir string

	// lifecycle serialises Connect, Disconnect, pairing, Logout, Close and
	// the store wipe after a logout on the phone.
	lifecycle sync.Mutex

	// mu guards the fields below. It is held only for quick reads and
	// writes, never across network calls.
	mu         sync.Mutex
	container  *sqlstore.Container
	local      *localStore
	cli        *whatsmeow.Client
	gen        uint64 // bumped whenever cli is replaced or dropped
	wanted     bool   // Connect (or pairing) asked for a connection and nothing ended it
	connecting bool   // a connect call is dialling; Disconnect cancels it (abortConnecting)
	connCtx    context.Context
	connCancel context.CancelFunc
	closed     bool
	pair       pairState
	// historyAsked: when an on-demand history request was last sent per chat.
	historyAsked map[string]time.Time
	// groupFetched: groups whose metadata was requested this session.
	groupFetched map[string]bool
}

// NewWABridge opens (or creates) the store in dataDir, an app-private
// directory. It never touches the network.
func NewWABridge(dataDir string, cb Callback) (b *WABridge, err error) {
	defer recoverInto(&err)
	dataDir = strings.TrimSpace(dataDir)
	if dataDir == "" {
		return nil, newError(codeInvalidArgument, "data directory is empty")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, newError(codeInternal, "could not create the data directory")
	}
	b = &WABridge{
		cb:           cb,
		disp:         newDispatcher(),
		dataDir:      dataDir,
		historyAsked: make(map[string]time.Time),
		groupFetched: make(map[string]bool),
	}
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	if err := b.openLocked(); err != nil {
		b.disp.close(0)
		return nil, exported(err)
	}
	return b, nil
}

func (b *WABridge) dbPath() string { return filepath.Join(b.dataDir, dbFileName) }

// openLocked opens the store and builds a client for its device (a fresh,
// unpaired one if the store has none). Callers hold b.lifecycle.
func (b *WABridge) openLocked() error {
	// Foreign keys are required by whatsmeow; WAL lets reads run alongside
	// the dispatcher's writes; busy_timeout covers the rest.
	dsn := "file:" + b.dbPath() + "?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=10000&_synchronous=NORMAL"
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return newError(codeInternal, "could not open the WhatsApp store")
	}
	ctx, cancel := dbCtx()
	defer cancel()
	container := sqlstore.NewWithDB(db, "sqlite3", nil)
	if err := container.Upgrade(ctx); err != nil {
		_ = db.Close()
		return newError(codeInternal, "could not prepare the WhatsApp store")
	}
	local, err := openLocalStore(db)
	if err != nil {
		_ = db.Close()
		return newError(codeInternal, "could not prepare the chat index")
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		_ = db.Close()
		return newError(codeInternal, "could not read the WhatsApp store")
	}
	cli := newClient(device)
	b.mu.Lock()
	b.gen++
	gen := b.gen
	b.container, b.local, b.cli = container, local, cli
	b.mu.Unlock()
	cli.AddEventHandler(b.handlerFor(gen, cli, local))
	return nil
}

// newClient builds a whatsmeow client with this app's settings.
func newClient(device *store.Device) *whatsmeow.Client {
	cli := whatsmeow.NewClient(device, nil) // nil: waLog.Noop, nothing is logged
	cli.EnableAutoReconnect = true
	// A Connect without network still succeeds and keeps retrying, like a
	// dropped connection (contract: reconnect is automatic while wanted).
	cli.InitialAutoReconnect = true
	// The first app-state sync after linking carries archive, mute and pin.
	cli.EmitAppStateEventsOnFullSync = true
	cli.AutomaticMessageRerequestFromPhone = true
	cli.UseRetryMessageStore = true
	return cli
}

// closeStoreLocked disconnects and closes the store. Callers hold
// b.lifecycle; afterwards b.cli is nil until openLocked.
func (b *WABridge) closeStoreLocked() {
	b.mu.Lock()
	cli, container := b.cli, b.container
	b.cli, b.container, b.local = nil, nil, nil
	b.gen++
	b.mu.Unlock()
	if cli != nil {
		disconnectClient(cli)
		cli.RemoveEventHandlers()
	}
	if container != nil {
		_ = container.Close()
	}
}

// wipeLocked deletes everything in the store and starts over unpaired.
func (b *WABridge) wipeLocked() error {
	b.closeStoreLocked()
	var firstErr error
	for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
		if err := os.Remove(b.dbPath() + suffix); err != nil && !errors.Is(err, os.ErrNotExist) && firstErr == nil {
			firstErr = err
		}
	}
	_ = os.RemoveAll(filepath.Join(b.dataDir, tmpDirName))
	b.mu.Lock()
	b.historyAsked = make(map[string]time.Time)
	b.groupFetched = make(map[string]bool)
	b.mu.Unlock()
	if firstErr != nil {
		return newError(codeInternal, "could not delete the WhatsApp store")
	}
	return b.openLocked()
}

func (b *WABridge) isGen(gen uint64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.gen == gen && b.cli != nil
}

// whatsmeow calls that touch the network; tests replace them.
var (
	connectClient    = func(ctx context.Context, cli *whatsmeow.Client) error { return cli.ConnectContext(ctx) }
	disconnectClient = func(cli *whatsmeow.Client) { cli.Disconnect() }
	isLoggedIn       = func(cli *whatsmeow.Client) bool { return cli.IsConnected() && cli.IsLoggedIn() }
	socketConnected  = func(cli *whatsmeow.Client) bool { return cli.IsConnected() }
	waitForLogin     = func(cli *whatsmeow.Client, d time.Duration) bool { return cli.WaitForConnection(d) }
	logoutClient     = func(ctx context.Context, cli *whatsmeow.Client) error { return cli.Logout(ctx) }
)

// isPaired reads whether the device has an identity. Store.ID is set once by
// whatsmeow when linking succeeds.
func isPaired(cli *whatsmeow.Client) bool {
	return cli != nil && cli.Store != nil && cli.Store.ID != nil
}

// --- status ------------------------------------------------------------------

// IsPaired reports whether this device is linked to a WhatsApp account.
func (b *WABridge) IsPaired() (paired bool) {
	defer func() { _ = recover() }()
	b.mu.Lock()
	defer b.mu.Unlock()
	return isPaired(b.cli)
}

// StatusJSON returns {"paired","connected","ownId","pushName"}; ownId is the
// user part of the account's JID (its phone number without "+").
func (b *WABridge) StatusJSON() (out string) {
	defer func() {
		if recover() != nil {
			out = "{}"
		}
	}()
	b.mu.Lock()
	cli, wanted := b.cli, b.wanted
	b.mu.Unlock()
	st := statusDTO{}
	if isPaired(cli) {
		st.Paired = true
		st.Connected = wanted && isLoggedIn(cli)
		st.OwnID = cli.Store.ID.User
		st.PushName = cli.Store.PushName
	}
	return string(toJSON(st))
}

// --- lifecycle ---------------------------------------------------------------

// Connect connects to WhatsApp. When linked, it returns once the connection
// attempt has started ("connected" follows as a state event, and whatsmeow
// reconnects by itself until Disconnect). When not linked, it opens the
// linking connection that PairPhone and StartQR need (the first step of
// pairing). Already connected: nothing happens.
func (b *WABridge) Connect() (err error) {
	defer recoverInto(&err)
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	cli, closed, wanted := b.cli, b.closed, b.wanted
	b.mu.Unlock()
	switch {
	case closed:
		return newError(codeInternal, "bridge is closed")
	case cli == nil:
		return newError(codeInternal, "the WhatsApp store is not available")
	case !isPaired(cli):
		return b.startPairingSocketLocked()
	case wanted && socketConnected(cli):
		return nil
	}
	return b.connectLocked(cli)
}

// connectLocked starts a connection of cli and records it as wanted.
// Callers hold b.lifecycle.
func (b *WABridge) connectLocked(cli *whatsmeow.Client) error {
	ctx, cancel := context.WithCancel(context.Background())
	b.mu.Lock()
	if b.connCancel != nil {
		b.connCancel()
	}
	b.wanted, b.connecting, b.connCtx, b.connCancel = true, true, ctx, cancel
	b.mu.Unlock()
	b.emitState(stateConnecting, "", "")
	cerr := connectClient(ctx, cli)
	b.mu.Lock()
	b.connecting = false
	b.mu.Unlock()
	if errors.Is(cerr, whatsmeow.ErrAlreadyConnected) {
		cerr = nil
	}
	if cerr == nil && ctx.Err() != nil {
		cerr = context.Canceled
	}
	if cerr != nil {
		b.dropConnectionLocked()
		if ctx.Err() != nil {
			// abortConnecting: the caller that cancelled reports the state.
			return newError(codeConnectionClosed, "disconnected while connecting")
		}
		be := toBridgeError(cerr)
		if be.code == codeLoggedOut {
			_ = b.wipeLocked()
			b.emitState(stateLoggedOut, be.code, be.detail)
		} else {
			b.emitState(stateError, be.code, be.detail)
		}
		return be
	}
	return nil
}

// dropConnectionLocked ends the wanted connection (if any): no reconnects.
func (b *WABridge) dropConnectionLocked() {
	b.mu.Lock()
	cli, cancel := b.cli, b.connCancel
	b.wanted, b.connCtx, b.connCancel = false, nil, nil
	b.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if cli != nil {
		disconnectClient(cli)
	}
}

// abortConnecting cancels a connect that is still dialling, so the caller
// (Disconnect, Logout, Close) does not wait for it behind the lifecycle lock.
func (b *WABridge) abortConnecting() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.connecting && b.connCancel != nil {
		b.connCancel()
	}
}

// Disconnect closes the connection (keeping the link) and abandons a
// pairing attempt. whatsmeow stops reconnecting.
func (b *WABridge) Disconnect() {
	defer func() { _ = recover() }()
	b.abortConnecting()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.stopPairingLocked()
	b.dropConnectionLocked()
	b.emitState(stateDisconnected, "", "")
}

// Logout unlinks this device: it tells WhatsApp (best effort, connecting
// briefly if needed), then wipes the local store (keys, chat index, message
// cache) whatever happened. It returns nil when WhatsApp was told or nothing
// was linked; otherwise an unpair_remote_failed error, after the wipe, and
// the "unpaired" state carries the same code, so the app can tell the user
// to remove the device on the phone.
func (b *WABridge) Logout() (err error) {
	defer recoverInto(&err)
	b.abortConnecting()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.stopPairingLocked()
	b.mu.Lock()
	cli, wanted, closed := b.cli, b.wanted, b.closed
	b.mu.Unlock()
	if closed {
		return newError(codeInternal, "bridge is closed")
	}
	var remoteErr error
	if isPaired(cli) {
		// Drop everything the client reports from here on: a temporary
		// connection must not deliver data that is about to be deleted.
		b.mu.Lock()
		b.gen++
		b.mu.Unlock()
		remoteErr = func() (e error) {
			defer recoverInto(&e)
			if !wanted || !isLoggedIn(cli) {
				// A temporary connection, ended by dropConnectionLocked below
				// (whatsmeow runs its loops on this context).
				ctx, cancel := context.WithCancel(context.Background())
				defer cancel()
				if cerr := connectClient(ctx, cli); cerr != nil && !errors.Is(cerr, whatsmeow.ErrAlreadyConnected) {
					return cerr
				}
				if !waitForLogin(cli, logoutConnectWait) {
					return newError(codeNotConnected, "could not reach WhatsApp")
				}
			}
			ctx, cancel := context.WithTimeout(context.Background(), logoutTimeout)
			defer cancel()
			return logoutClient(ctx, cli)
		}()
		// An account that already removed this device is unlinked anyway.
		if be := toBridgeError(remoteErr); be != nil && (be.code == codeLoggedOut || be.code == codeNotPaired) {
			remoteErr = nil
		}
	}
	b.dropConnectionLocked()
	if werr := b.wipeLocked(); werr != nil {
		b.emitState(stateUnpaired, codeInternal, "the local store could not be deleted")
		return werr
	}
	if remoteErr != nil {
		const detail = "the phone may still list this device"
		b.emitState(stateUnpaired, codeUnpairRemoteFailed, detail)
		return &bridgeError{code: codeUnpairRemoteFailed, detail: detail, cause: remoteErr}
	}
	b.emitState(stateUnpaired, "", "")
	return nil
}

// handleLoggedOut runs on its own goroutine (it needs the lifecycle lock and
// the dispatcher must never block) when WhatsApp says this device was
// unlinked: from the phone, or by WhatsApp itself.
func (b *WABridge) handleLoggedOut(gen uint64, detail string) {
	defer func() { _ = recover() }()
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	if !b.isGen(gen) {
		return
	}
	b.stopPairingLocked()
	b.dropConnectionLocked()
	_ = b.wipeLocked()
	b.emitState(stateLoggedOut, codeLoggedOut, detail)
}

// Close disconnects, closes the store, delivers queued callbacks (briefly)
// and stops the dispatcher. The bridge is unusable afterwards.
func (b *WABridge) Close() {
	defer func() { _ = recover() }()
	b.abortConnecting()
	b.lifecycle.Lock()
	b.stopPairingLocked()
	b.dropConnectionLocked()
	b.closeStoreLocked()
	b.mu.Lock()
	b.closed = true
	b.mu.Unlock()
	b.lifecycle.Unlock()
	b.disp.close(closeDrainWait)
}

// --- client access for calls ---------------------------------------------------

// linked returns the client and index of a linked device, connected or not
// (for calls that only read the local index).
func (b *WABridge) linked() (*whatsmeow.Client, *localStore, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch {
	case b.closed:
		return nil, nil, newError(codeInternal, "bridge is closed")
	case b.cli == nil || b.local == nil:
		return nil, nil, newError(codeInternal, "the WhatsApp store is not available")
	case !isPaired(b.cli):
		return nil, nil, newError(codeNotPaired, "link with your phone first")
	}
	return b.cli, b.local, nil
}

// rpc returns the client of a connected, linked device and a call context
// that ends at rpcTimeout or when the connection is dropped.
func (b *WABridge) rpc() (*whatsmeow.Client, *localStore, context.Context, context.CancelFunc, error) {
	cli, local, err := b.linked()
	if err != nil {
		return nil, nil, nil, nil, err
	}
	b.mu.Lock()
	wanted, connCtx := b.wanted, b.connCtx
	b.mu.Unlock()
	if !wanted || connCtx == nil {
		return nil, nil, nil, nil, newError(codeNotConnected, "connect first")
	}
	if !isLoggedIn(cli) {
		return nil, nil, nil, nil, newError(codeNotConnected, "not connected to WhatsApp right now")
	}
	ctx, cancel := context.WithTimeout(connCtx, rpcTimeout)
	return cli, local, ctx, cancel, nil
}

// --- identity --------------------------------------------------------------------

// resolver implements identity with whatsmeow's device store.
type resolver struct{ cli *whatsmeow.Client }

func (r resolver) norm(j types.JID) types.JID {
	j = j.ToNonAD()
	switch j.Server {
	case types.LegacyUserServer:
		j.Server = types.DefaultUserServer
	case types.HiddenUserServer:
		if r.cli != nil && r.cli.Store != nil && r.cli.Store.LIDs != nil {
			ctx, cancel := dbCtx()
			defer cancel()
			if pn, err := r.cli.Store.LIDs.GetPNForLID(ctx, j); err == nil && !pn.IsEmpty() {
				return pn.ToNonAD()
			}
		}
	}
	return j
}

func (r resolver) me() types.JID {
	if r.cli == nil || r.cli.Store == nil || r.cli.Store.ID == nil {
		return types.EmptyJID
	}
	return r.cli.Store.ID.ToNonAD()
}

func (r resolver) isMe(j types.JID) bool {
	if j.IsEmpty() || r.cli == nil || r.cli.Store == nil {
		return false
	}
	if id := r.cli.Store.ID; id != nil && j.User == id.User && (j.Server == id.Server || j.Server == types.LegacyUserServer) {
		return true
	}
	lid := r.cli.Store.LID
	return !lid.IsEmpty() && j.User == lid.User && j.Server == lid.Server
}

func (r resolver) name(j types.JID) string {
	if j.IsEmpty() || r.cli == nil || r.cli.Store == nil || r.cli.Store.Contacts == nil {
		return ""
	}
	ctx, cancel := dbCtx()
	defer cancel()
	c, err := r.cli.Store.Contacts.GetContact(ctx, j)
	if err != nil || !c.Found {
		return ""
	}
	return contactName(c)
}

func contactName(c types.ContactInfo) string {
	for _, n := range []string{c.FullName, c.FirstName, c.PushName, c.BusinessName} {
		if n = strings.TrimSpace(n); n != "" {
			return n
		}
	}
	return ""
}
