package libgm

import (
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/crypto"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/util"
)

type AuthData struct {
	// Keys used to encrypt communication with the phone
	RequestCrypto *crypto.AESCTRHelper `json:"request_crypto,omitempty"`
	// Key used to sign requests to refresh the tachyon auth token from the server
	RefreshKey *crypto.JWK `json:"refresh_key,omitempty"`
	// Identity of the paired phone and browser
	Browser *gmproto.Device `json:"browser,omitempty"`
	Mobile  *gmproto.Device `json:"mobile,omitempty"`
	// Key used to authenticate with the server
	TachyonAuthToken []byte    `json:"tachyon_token,omitempty"`
	TachyonExpiry    time.Time `json:"tachyon_expiry,omitzero"`
	TachyonTTL       int64     `json:"tachyon_ttl,omitempty"`
	// Unknown encryption key, not used for anything
	WebEncryptionKey []byte `json:"web_encryption_key,omitempty"`

	SessionID uuid.UUID `json:"session_id,omitempty"`
	DestRegID uuid.UUID `json:"dest_reg_id,omitempty"`
	PairingID uuid.UUID `json:"pairing_id,omitempty"`

	Cookies     map[string]string `json:"cookies,omitempty"`
	CookiesLock sync.RWMutex      `json:"-"`
}

func (ad *AuthData) SetCookies(cookies map[string]string) {
	ad.CookiesLock.Lock()
	ad.Cookies = cookies
	ad.CookiesLock.Unlock()
}

func (ad *AuthData) ClearPairing() {
	ad.Browser = nil
	ad.Mobile = nil
	ad.TachyonAuthToken = nil
	ad.TachyonExpiry = time.Time{}
	ad.TachyonTTL = 0
	ad.WebEncryptionKey = nil
	ad.PairingID = uuid.Nil
}

// LightDecoder patch: the token fields are written under CookiesLock (see
// setTachyonToken), so that a caller encoding AuthData under
// CookiesLock.RLock never sees them half-updated. These read them under the
// same lock for libgm's background goroutines.
func (ad *AuthData) tachyonToken() []byte {
	ad.CookiesLock.RLock()
	defer ad.CookiesLock.RUnlock()
	return ad.TachyonAuthToken
}

// LightDecoder patch: see tachyonToken.
func (ad *AuthData) tachyonExpiry() time.Time {
	ad.CookiesLock.RLock()
	defer ad.CookiesLock.RUnlock()
	return ad.TachyonExpiry
}

// LightDecoder patch: see tachyonToken.
func (ad *AuthData) tachyonTTL() int64 {
	ad.CookiesLock.RLock()
	defer ad.CookiesLock.RUnlock()
	return ad.TachyonTTL
}

// LightDecoder patch: see tachyonToken.
func (ad *AuthData) setTachyonToken(token []byte, expiry time.Time, ttl int64) {
	ad.CookiesLock.Lock()
	defer ad.CookiesLock.Unlock()
	ad.TachyonAuthToken = token
	ad.TachyonExpiry = expiry
	ad.TachyonTTL = ttl
}

// LightDecoder patch: see tachyonToken.
func (ad *AuthData) setDevices(mobile, browser *gmproto.Device) {
	ad.CookiesLock.Lock()
	defer ad.CookiesLock.Unlock()
	ad.Mobile = mobile
	ad.Browser = browser
}

func (ad *AuthData) AddCookiesToRequest(req *http.Request) {
	ad.CookiesLock.RLock()
	defer ad.CookiesLock.RUnlock()
	if ad.Cookies == nil {
		return
	}
	for name, value := range ad.Cookies {
		req.AddCookie(&http.Cookie{Name: name, Value: value})
	}
	sapisid, ok := ad.Cookies["SAPISID"]
	if ok {
		req.Header.Set("Authorization", SAPISIDHash(util.MessagesBaseURL, sapisid))
	}
}

func (ad *AuthData) UpdateCookiesFromResponse(resp *http.Response) {
	ad.CookiesLock.Lock()
	defer ad.CookiesLock.Unlock()
	if ad.Cookies == nil {
		return
	}
	for _, cookie := range resp.Cookies() {
		ad.Cookies[cookie.Name] = cookie.Value
	}
}

func (ad *AuthData) HasCookies() bool {
	if ad == nil {
		return false
	} else if !ad.IsGoogleAccount() {
		return true
	}
	ad.CookiesLock.RLock()
	defer ad.CookiesLock.RUnlock()
	return ad.Cookies != nil
}

func (ad *AuthData) IsGoogleAccount() bool {
	return ad.DestRegID != uuid.Nil
}

func (ad *AuthData) AuthNetwork() string {
	if ad.IsGoogleAccount() {
		return util.GoogleNetwork
	}
	return ""
}

const RefreshTachyonBuffer = 1 * time.Hour

type Proxy func(*http.Request) (*url.URL, error)
type EventHandler func(evt any)

type updateDedupItem struct {
	id   string
	hash [32]byte
}

const DefaultBugleDefaultCheckInterval = 2*time.Hour + 55*time.Minute
const minBugleDefaultCheckInterval = 1 * time.Hour

type Client struct {
	Logger         zerolog.Logger
	evHandler      EventHandler
	sessionHandler *SessionHandler

	longPollingConn io.Closer // LightDecoder patch: guarded by listenLock
	listenID        int
	skipCount       int
	disconnecting   atomic.Bool // LightDecoder patch: atomic

	pingInterval             time.Duration
	alertTimeoutCount        int
	pingShortCircuit         chan struct{}
	phone                    phoneLiveness
	dataReceiveCheckInterval time.Duration
	nextDataReceiveCheck     time.Time
	nextDataReceiveCheckLock sync.Mutex
	lastBugleDefaultCheck    time.Time
	bugleDefaultCheckLock    sync.Mutex

	recentUpdates    [8]updateDedupItem
	recentUpdatesPtr int

	conversationsFetchedOnce bool

	GaiaHackyDeviceSwitcher int

	PairCallback atomic.Pointer[func(data *gmproto.PairedData)]

	AuthData *AuthData
	PushKeys *PushKeys
	Config   *gmproto.Config

	http   *http.Client
	lphttp *http.Client

	// LightDecoder patch: listenStop is closed by closeLongPolling, so that a
	// long-poll loop (and its pinger) stops at once, even while it sleeps in
	// its error backoff. listenLock guards listenStop.
	listenLock sync.Mutex
	listenStop chan struct{}
	// LightDecoder patch: panicHandler receives panics recovered in libgm's
	// own background goroutines (see recoverBackground).
	panicHandler atomic.Pointer[func(where string, recovered any)]
}

// SetPanicHandler (LightDecoder patch) installs a handler for panics in
// libgm's background goroutines (long polling, pinger, acks, post-connect).
// With a handler set such a panic is recovered and passed to it, from the
// goroutine that panicked; without one it is re-raised as before. The handler
// must not block.
func (c *Client) SetPanicHandler(handler func(where string, recovered any)) {
	if c == nil {
		return
	}
	if handler == nil {
		c.panicHandler.Store(nil)
	} else {
		c.panicHandler.Store(&handler)
	}
}

// recoverBackground (LightDecoder patch) is deferred at the top of libgm's
// background goroutines.
func (c *Client) recoverBackground(where string) {
	if r := recover(); r != nil {
		if h := c.panicHandler.Load(); h != nil {
			(*h)(where, r)
			return
		}
		panic(r)
	}
}

func NewAuthData() *AuthData {
	return &AuthData{
		RequestCrypto: crypto.NewAESCTRHelper(),
		RefreshKey:    crypto.GenerateECDSAKey(),
	}
}

func NewClient(authData *AuthData, pk *PushKeys, logger zerolog.Logger, httpSettings exhttp.ClientSettings) *Client {
	sessionHandler := &SessionHandler{
		responseWaiters: make(map[string]chan<- *IncomingRPCMessage),
	}
	cli := &Client{
		AuthData:       authData,
		PushKeys:       pk,
		Logger:         logger,
		sessionHandler: sessionHandler,

		http:   httpSettings.Compile(),
		lphttp: httpSettings.WithGlobalTimeout(30 * time.Minute).Compile(),

		pingShortCircuit:         make(chan struct{}),
		listenStop:               make(chan struct{}), // LightDecoder patch
		pingInterval:             1 * time.Minute,
		alertTimeoutCount:        4,
		dataReceiveCheckInterval: DefaultBugleDefaultCheckInterval,
	}
	sessionHandler.client = cli
	return cli
}

func (c *Client) CurrentSessionID() string {
	if c == nil {
		return ""
	}
	return c.sessionHandler.sessionID
}

var ErrClientIsNil = errors.New("client is nil")

func (c *Client) getSessionHandler() *SessionHandler {
	if c == nil {
		return nil
	}
	return c.sessionHandler
}

// SetEventHandler sets the global event handler for all received data.
// The method is called synchronously and must not make any outgoing requests or otherwise block for too long.
func (c *Client) SetEventHandler(eventHandler EventHandler) {
	if c != nil {
		c.evHandler = eventHandler
	}
}

func (c *Client) SetPingInterval(interval time.Duration) {
	if c != nil && interval >= 1*time.Minute && interval < 4*time.Hour {
		c.pingInterval = interval
	}
}

func (c *Client) SetAlertTimeoutCount(count int) {
	if c != nil && count > 0 {
		c.alertTimeoutCount = count
	}
}

// SetDataReceiveCheckInterval sets how often to send an extra GET_UPDATES call
// (and emit a NoDataReceived event) when no data has been received recently.
// Intervals shorter than 5 minutes are ignored to avoid draining the phone's battery.
func (c *Client) SetDataReceiveCheckInterval(interval time.Duration) {
	if interval >= 5*time.Minute {
		c.dataReceiveCheckInterval = interval
	}
}

func (c *Client) checkLoggedIn() error {
	if c == nil {
		return ErrClientIsNil
	} else if c.AuthData.tachyonToken() == nil { // LightDecoder patch: locked read
		return fmt.Errorf("no auth token")
	} else if c.AuthData.Browser == nil {
		return fmt.Errorf("not logged in")
	}
	return nil
}

func (c *Client) startLongPolling(ctx context.Context) {
	c.bumpNextDataReceiveCheck(10 * time.Minute)

	//webEncryptionKeyResponse, err := c.GetWebEncryptionKey()
	//if err != nil {
	//	return fmt.Errorf("failed to get web encryption key: %w", err)
	//}
	//c.updateWebEncryptionKey(webEncryptionKeyResponse.GetKey())
	go func() {
		defer c.recoverBackground("long poll") // LightDecoder patch
		c.doLongPoll(ctx, true, false, c.postConnect)
	}()
	c.sessionHandler.startAckInterval()
}

func (c *Client) Connect(ctx context.Context) error {
	if err := c.checkLoggedIn(); err != nil {
		return err
	}

	// Refresh the auth token here rather than leaving it to the long polling loop, so that
	// callers connecting for the first time (i.e. right after logging in) find out about
	// bad credentials synchronously.
	// LightDecoder patch: the refresh uses ctx, so cancelling ctx aborts a
	// Connect that is still refreshing; and the long poll gets a context
	// that is not cancelled with ctx (only Disconnect stops it).
	err := c.refreshAuthTokenContext(ctx, nil)
	if err != nil {
		if isFatalRefreshError(err) {
			return fmt.Errorf("failed to refresh auth token: %w", err)
		}
		if ctxErr := ctx.Err(); ctxErr != nil {
			return fmt.Errorf("failed to refresh auth token: %w", ctxErr)
		}
		c.Logger.Warn().Err(err).Msg("Transient error refreshing auth token on connect, will retry in long polling loop")
	}
	c.startLongPolling(context.WithoutCancel(ctx))
	return nil
}

func (c *Client) ConnectBackground(ctx context.Context) error {
	if err := c.checkLoggedIn(); err != nil {
		return err
	}
	cleanExit := c.doLongPoll(ctx, true, true, nil)
	c.sessionHandler.sendAckRequest()
	if !cleanExit {
		return fmt.Errorf("polling exited uncleanly")
	}
	return nil
}

// LightDecoder patch: ctx is the long-poll loop's context, which ends when the
// long poll is stopped (Disconnect, Reconnect); then nothing more is sent.
func (c *Client) postConnect(ctx context.Context) {
	defer c.recoverBackground("post-connect") // LightDecoder patch
	if !sleepUnlessStopped(2*time.Second, ctx.Done()) {
		return
	}
	if c.skipCount > 0 {
		c.Logger.Warn().Int("skip_count", c.skipCount).Msg("Skip count is non-zero in postConnect, waiting longer")
		for i := 0; i < 3 && c.skipCount > 0; i++ {
			if !sleepUnlessStopped(1*time.Second, ctx.Done()) {
				return
			}
		}
		if c.skipCount > 0 {
			c.Logger.Warn().Int("skip_count", c.skipCount).Msg("Skip count is still non-zero")
		}
		c.triggerEvent(&events.HackySetActiveMayFail{})
	}
	c.Logger.Debug().Msg("Sending acks before get updates request")
	_ = c.sessionHandler.sendAckRequestContext(ctx)
	if !sleepUnlessStopped(1*time.Second, ctx.Done()) {
		return
	}
	c.Logger.Debug().Msg("Sending get updates request")
	err := c.SetActiveSession(ctx)
	if err != nil && ctx.Err() != nil {
		return // LightDecoder patch: the long poll was stopped meanwhile
	} else if err != nil {
		c.Logger.Err(err).Msg("Failed to set active session")
		c.triggerEvent(&events.PingFailed{
			Error: fmt.Errorf("failed to set active session: %w", err),
		})
		return
	}
	c.Logger.Debug().Msg("Sent set active session/get updates request")

	if !c.shouldCheckBugleDefault() {
		c.Logger.Debug().Msg("Skipping bugle default check, already checked recently")
		return
	}
	doneChan := make(chan struct{})
	go func() {
		defer c.recoverBackground("bugle default check") // LightDecoder patch
		select {
		case <-time.After(5 * time.Second):
			c.Logger.Warn().Msg("Checking bugle default on connect is taking long")
			select {
			case c.pingShortCircuit <- struct{}{}:
			default:
			}
		case <-doneChan:
		}
	}()
	bugleRes, err := c.IsBugleDefault(ctx)
	close(doneChan)
	if err != nil {
		c.Logger.Err(err).Msg("Failed to check bugle default")
		return
	}
	c.Logger.Debug().Bool("bugle_default", bugleRes.Success).Msg("Got is bugle default response on connect")
}

func (c *Client) shouldCheckBugleDefault() bool {
	c.bugleDefaultCheckLock.Lock()
	defer c.bugleDefaultCheckLock.Unlock()
	if time.Since(c.lastBugleDefaultCheck) < minBugleDefaultCheckInterval {
		return false
	}
	c.lastBugleDefaultCheck = time.Now()
	return true
}

func (c *Client) Disconnect() {
	if c == nil {
		return
	}
	c.closeLongPolling()
	// Fail any requests that are still waiting for a response from the phone:
	// the responses are delivered over the long polling connection, so they can
	// never arrive after it has been torn down.
	c.sessionHandler.cancelAllResponseWaiters()
	c.http.CloseIdleConnections()
}

// StopAckInterval (LightDecoder patch) stops the client's 5 s ack goroutine,
// which otherwise runs (and retries failed acks) for the life of the process.
// The next Connect or Reconnect starts it again. Pending acks stay queued;
// FlushAcks sends them.
func (c *Client) StopAckInterval() {
	if c != nil {
		c.sessionHandler.stopAckInterval()
	}
}

// FlushAcks (LightDecoder patch) sends the pending message acks now, with
// ctx, and returns the request's error (the acks are then queued again).
func (c *Client) FlushAcks(ctx context.Context) error {
	if c == nil {
		return ErrClientIsNil
	}
	return c.sessionHandler.sendAckRequestContext(ctx)
}

// PendingAcks (LightDecoder patch) reports how many message acks are queued.
func (c *Client) PendingAcks() int {
	if c == nil {
		return 0
	}
	c.sessionHandler.ackMapLock.Lock()
	defer c.sessionHandler.ackMapLock.Unlock()
	return len(c.sessionHandler.ackMap)
}

func (c *Client) IsConnected() bool {
	// TODO add better check (longPollingConn is set to nil while the polling reconnects)
	if c == nil {
		return false
	}
	// LightDecoder patch: read under listenLock.
	c.listenLock.Lock()
	defer c.listenLock.Unlock()
	return c.longPollingConn != nil
}

func (c *Client) IsLoggedIn() bool {
	return c != nil && c.AuthData != nil && c.AuthData.Browser != nil && c.AuthData.HasCookies()
}

func (c *Client) Reconnect(ctx context.Context) error {
	c.closeLongPolling()
	err := c.checkLoggedIn()
	if err != nil {
		c.Logger.Err(err).Msg("Failed to reconnect")
		c.triggerEvent(&events.ListenFatalError{Error: fmt.Errorf("failed to reconnect: %w", err)})
		return err
	}
	c.startLongPolling(ctx)
	c.Logger.Debug().Msg("Successfully reconnected to server")
	return nil
}

func (c *Client) triggerEvent(evt any) {
	if c.evHandler != nil {
		c.evHandler(evt)
	}
}

func (c *Client) FetchConfig(ctx context.Context) error {
	config, err := c.fetchConfig(ctx)
	if err != nil {
		return fmt.Errorf("failed to fetch config: %w", err)
	}
	if deviceID := config.GetDeviceInfo().GetDeviceID(); deviceID != "" && c.AuthData != nil {
		// LightDecoder patch: written under CookiesLock (see tachyonToken).
		var sessionID uuid.UUID
		sessionID, err = uuid.Parse(deviceID)
		c.AuthData.CookiesLock.Lock()
		c.AuthData.SessionID = sessionID
		c.AuthData.CookiesLock.Unlock()
		if err != nil {
			c.Logger.Err(err).Str("device_id", deviceID).Msg("Failed to parse device ID")
		}
	}
	c.Config = config
	return nil
}

func (c *Client) fetchConfig(ctx context.Context) (*gmproto.Config, error) {
	if c == nil {
		return nil, ErrClientIsNil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, util.ConfigURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to prepare request: %w", err)
	}
	util.BuildRelayHeaders(req, "", "*/*")
	req.Header.Set("sec-fetch-site", "same-origin")
	req.Header.Del("x-user-agent")
	req.Header.Del("origin")
	c.AuthData.AddCookiesToRequest(req)

	resp, err := c.http.Do(req)
	if resp != nil {
		c.AuthData.UpdateCookiesFromResponse(resp)
	}
	config, err := typedHTTPResponse[*gmproto.Config](resp, err)
	if err != nil {
		return nil, err
	}

	version, parseErr := config.ParsedClientVersion()
	if parseErr != nil {
		return nil, fmt.Errorf("failed to parse client version: %w", err)
	}

	currVersion := util.ConfigMessage
	if version.Year != currVersion.Year || version.Month != currVersion.Month || version.Day != currVersion.Day {
		toLog := c.diffVersionFormat(currVersion, version)
		c.Logger.Trace().Any("version", toLog).Msg("Messages for web version is not latest")
	} else {
		c.Logger.Debug().Any("version", currVersion).Msg("Using latest messages for web version")
	}

	return config, nil
}

func (c *Client) diffVersionFormat(curr *gmproto.ConfigVersion, latest *gmproto.ConfigVersion) string {
	return fmt.Sprintf("%d.%d.%d -> %d.%d.%d", curr.Year, curr.Month, curr.Day, latest.Year, latest.Month, latest.Day)
}

func (c *Client) updateTachyonAuthToken(data *gmproto.TokenData) {
	validForDuration := time.Duration(data.GetTTL()) * time.Microsecond
	if validForDuration == 0 {
		validForDuration = 24 * time.Hour
	}
	// LightDecoder patch: all three fields are written together under
	// CookiesLock (see tachyonToken).
	expiry := time.Now().UTC().Add(validForDuration)
	c.AuthData.setTachyonToken(data.GetTachyonAuthToken(), expiry, validForDuration.Microseconds())
	c.Logger.Debug().
		Time("tachyon_expiry", expiry).
		Int64("valid_for", data.GetTTL()).
		Msg("Updated tachyon token")
}

type PushKeys struct {
	URL    string
	P256DH []byte
	Auth   []byte
}

func (c *Client) RegisterPush(ctx context.Context, keys *PushKeys) error {
	if c.PushKeys == nil || c.PushKeys.URL != keys.URL {
		err := c.refreshAuthToken(keys)
		if err != nil {
			return fmt.Errorf("failed to refresh auth token: %w", err)
		}
	}
	err := c.UpdateSettings(ctx, &gmproto.SettingsUpdateRequest{
		PushSettings: &gmproto.SettingsUpdateRequest_PushSettings{
			Enabled: true,
		},
	})
	if err != nil {
		return fmt.Errorf("failed to update settings to enable push: %w", err)
	}
	c.PushKeys = keys
	return nil
}

func isFatalRefreshError(err error) bool {
	if errors.Is(err, events.ErrInvalidCredentials) || errors.Is(err, events.ErrRequestedEntityNotFound) {
		return true
	}
	var httpErr events.HTTPError
	if errors.As(err, &httpErr) {
		switch httpErr.Resp.StatusCode {
		case http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound:
			return true
		}
	}
	return false
}

func (c *Client) refreshAuthToken(pushKeyOverride *PushKeys) error {
	return c.refreshAuthTokenContext(c.Logger.WithContext(context.TODO()), pushKeyOverride)
}

// refreshAuthTokenContext is refreshAuthToken with a context for its request
// (LightDecoder patch: Connect and the long poll pass theirs, so it can be
// cancelled).
func (c *Client) refreshAuthTokenContext(ctx context.Context, pushKeyOverride *PushKeys) error {
	if c.AuthData.Browser == nil || (time.Until(c.AuthData.tachyonExpiry()) > RefreshTachyonBuffer && pushKeyOverride == nil) {
		return nil
	}
	jwk := c.AuthData.RefreshKey
	requestID := uuid.NewString()
	timestamp := time.Now().UnixMilli() * 1000

	signBytes := sha256.Sum256([]byte(fmt.Sprintf("%s:%d", requestID, timestamp)))
	privKey, err := jwk.GetPrivateKey()
	if err != nil {
		return err
	}
	sig, err := ecdsa.SignASN1(rand.Reader, privKey, signBytes[:])
	if err != nil {
		return err
	}

	var moreParams *gmproto.RegisterRefreshRequest_MoreParameters
	keys := c.PushKeys
	if pushKeyOverride != nil {
		keys = pushKeyOverride
	}
	if keys != nil {
		moreParams = &gmproto.RegisterRefreshRequest_MoreParameters{
			Three: 3,
		}
		moreParams.PushReg = &gmproto.RegisterRefreshRequest_PushRegistration{
			Type:   "messages_web",
			Url:    keys.URL,
			P256Dh: base64.RawURLEncoding.EncodeToString(keys.P256DH),
			Auth:   base64.RawURLEncoding.EncodeToString(keys.Auth),
		}
	}
	c.Logger.Debug().
		Time("tachyon_expiry", c.AuthData.tachyonExpiry()).
		Bool("force_refresh", pushKeyOverride != nil).
		Bool("include_push_keys", moreParams.GetPushReg() != nil).
		Msg("Refreshing auth token")

	payload := &gmproto.RegisterRefreshRequest{
		MessageAuth: &gmproto.AuthMessage{
			RequestID:        requestID,
			TachyonAuthToken: c.AuthData.tachyonToken(),
			Network:          c.AuthData.AuthNetwork(),
			ConfigVersion:    util.ConfigMessage,
		},
		CurrBrowserDevice: c.AuthData.Browser,
		UnixTimestamp:     timestamp,
		Signature:         sig,
		Parameters: &gmproto.RegisterRefreshRequest_Parameters{
			EmptyArr:       &gmproto.EmptyArr{},
			MoreParameters: moreParams,
		},
		MessageType: 2, // hmm
	}

	resp, err := typedHTTPResponse[*gmproto.RegisterRefreshResponse](
		c.makeProtobufHTTPRequestContext(ctx, util.RegisterRefreshURL, payload, ContentTypePBLite, false),
	)
	if err != nil {
		return err
	}

	if resp.GetTokenData().GetTachyonAuthToken() == nil {
		return fmt.Errorf("no tachyon auth token in refresh response")
	}

	c.updateTachyonAuthToken(resp.GetTokenData())
	c.triggerEvent(&events.AuthTokenRefreshed{})
	return nil
}
