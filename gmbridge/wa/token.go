package wa

import (
	"encoding/base64"
	"encoding/json"
	"strings"

	"go.mau.fi/whatsmeow"
)

// mediaToken is what PartDTO.mediaId carries: everything DownloadMedia needs
// to fetch and decrypt one attachment, so Kotlin can download it later (even
// after a restart) without Go keeping the message. Kotlin treats it as
// opaque. It holds the media key, so it is as private as the message itself;
// Kotlin stores it in the same app-private archive.
type mediaToken struct {
	DirectPath string `json:"p"`
	MediaKey   []byte `json:"k,omitempty"`
	EncSHA256  []byte `json:"e,omitempty"`
	SHA256     []byte `json:"s,omitempty"`
	Length     uint64 `json:"l,omitempty"`
	Type       string `json:"t"`
}

// mediaTokenPrefix versions the format.
const mediaTokenPrefix = "wa1."

// The media kinds a token may name, and the HKDF info whatsmeow derives the
// keys with. Stickers are images to WhatsApp's media servers.
var mediaTypes = map[string]whatsmeow.MediaType{
	"image":    whatsmeow.MediaImage,
	"video":    whatsmeow.MediaVideo,
	"audio":    whatsmeow.MediaAudio,
	"document": whatsmeow.MediaDocument,
	"sticker":  whatsmeow.MediaImage,
}

func encodeMediaToken(t mediaToken) string {
	data, err := json.Marshal(t)
	if err != nil {
		return ""
	}
	return mediaTokenPrefix + base64.RawURLEncoding.EncodeToString(data)
}

// decodeMediaToken parses and validates a token. key, when the token has no
// media key of its own, supplies it (DownloadMedia's second parameter).
func decodeMediaToken(s string, key []byte) (mediaToken, whatsmeow.MediaType, error) {
	var t mediaToken
	rest, ok := strings.CutPrefix(strings.TrimSpace(s), mediaTokenPrefix)
	if !ok {
		return t, "", newError(codeInvalidArgument, "not a WhatsApp media id")
	}
	data, err := base64.RawURLEncoding.DecodeString(rest)
	if err != nil {
		return t, "", newError(codeInvalidArgument, "media id is damaged")
	}
	if err := json.Unmarshal(data, &t); err != nil {
		return t, "", newError(codeInvalidArgument, "media id is damaged")
	}
	mt, ok := mediaTypes[t.Type]
	if !ok {
		return t, "", newError(codeInvalidArgument, "media id has an unknown media type")
	}
	if !strings.HasPrefix(t.DirectPath, "/") {
		return t, "", newError(codeNotFound, "media has no download path")
	}
	if len(t.MediaKey) == 0 && len(key) > 0 {
		t.MediaKey = append([]byte(nil), key...)
	}
	if len(t.MediaKey) != 32 {
		return t, "", newError(codeInvalidArgument, "media id has no valid key")
	}
	return t, mt, nil
}
