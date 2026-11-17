package gmbridge

import (
	"bytes"
	"encoding/json"

	"github.com/google/uuid"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
)

// sessionVersion is bumped if the envelope below changes shape.
const sessionVersion = 1

// sessionFile is what OnSession hands to Kotlin (which encrypts it with a
// Keystore key). It wraps libgm's AuthData (cookies + keys + tokens) with the
// two facts libgm does not keep: the phone ID FinishGaiaPairing returned and
// the account email shown in the UI. Kotlin treats the bytes as opaque.
type sessionFile struct {
	Version int             `json:"v"`
	Auth    *libgm.AuthData `json:"auth"`
	PhoneID string          `json:"phoneId,omitempty"`
	Account string          `json:"account,omitempty"`
}

// marshalSession encodes the session. The read lock matters: libgm rewrites
// AuthData.Cookies on every HTTP response, and encoding/json reading that map
// at the same time is a fatal (unrecoverable) concurrent map access.
func marshalSession(auth *libgm.AuthData, phoneID, account string) ([]byte, error) {
	auth.CookiesLock.RLock()
	defer auth.CookiesLock.RUnlock()
	return json.Marshal(sessionFile{Version: sessionVersion, Auth: auth, PhoneID: phoneID, Account: account})
}

// parseSession reads an envelope written by marshalSession. A bare AuthData
// JSON (the format libgm's own tools write) is accepted too. Empty input
// means "no session".
func parseSession(data []byte) (auth *libgm.AuthData, phoneID, account string, err error) {
	data = bytes.TrimSpace(data)
	if len(data) == 0 || string(data) == "null" {
		return nil, "", "", nil
	}
	var probe map[string]json.RawMessage
	if err := json.Unmarshal(data, &probe); err != nil {
		return nil, "", "", newError(codeInvalidArgument, "session is not valid JSON")
	}
	if _, ok := probe["auth"]; ok {
		var file sessionFile
		if err := json.Unmarshal(data, &file); err != nil {
			return nil, "", "", newError(codeInvalidArgument, "session could not be read")
		}
		if file.Auth == nil {
			return nil, "", "", newError(codeInvalidArgument, "session has no auth data")
		}
		auth, phoneID, account = file.Auth, file.PhoneID, file.Account
	} else {
		auth = &libgm.AuthData{}
		if err := json.Unmarshal(data, auth); err != nil {
			return nil, "", "", newError(codeInvalidArgument, "session could not be read")
		}
	}
	// Both keys are created by NewAuthData and never rotated; without them the
	// session cannot sign token refreshes or decrypt anything.
	if auth.RequestCrypto == nil || auth.RefreshKey == nil {
		return nil, "", "", newError(codeInvalidArgument, "session is missing its keys")
	}
	if account == "" {
		account = auth.Mobile.GetSourceID()
	}
	return auth, phoneID, account, nil
}

// isPaired is the connector's test for a usable Google-account pairing.
// IsLoggedIn is not enough: it is already true between StartGaiaPairing and
// the user confirming the emoji.
func isPaired(auth *libgm.AuthData) bool {
	return auth != nil && auth.PairingID != uuid.Nil && auth.TachyonAuthToken != nil && auth.Mobile != nil
}
