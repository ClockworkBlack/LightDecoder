package wa

import (
	"bytes"
	"strings"
	"testing"

	"go.mau.fi/whatsmeow"
)

func TestMediaTokenRoundTrip(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	in := mediaToken{DirectPath: "/v/t62.7118-24/abc?ccb=11-4", MediaKey: key, EncSHA256: []byte{1, 2}, SHA256: []byte{3, 4}, Length: 12345, Type: "video"}
	s := encodeMediaToken(in)
	if !strings.HasPrefix(s, mediaTokenPrefix) || strings.ContainsAny(s, "+/= ") {
		t.Fatalf("token %q is not URL-safe", s)
	}
	out, mt, err := decodeMediaToken(s, nil)
	if err != nil {
		t.Fatal(err)
	}
	if mt != whatsmeow.MediaVideo {
		t.Errorf("media type %q", mt)
	}
	if out.DirectPath != in.DirectPath || !bytes.Equal(out.MediaKey, key) || !bytes.Equal(out.EncSHA256, in.EncSHA256) ||
		!bytes.Equal(out.SHA256, in.SHA256) || out.Length != in.Length || out.Type != in.Type {
		t.Errorf("round trip changed the token: %+v", out)
	}
	// Stickers are images to the media servers.
	_, mt, err = decodeMediaToken(encodeMediaToken(mediaToken{DirectPath: "/x", MediaKey: key, Type: "sticker"}), nil)
	if err != nil || mt != whatsmeow.MediaImage {
		t.Errorf("sticker: %v %q", err, mt)
	}
}

func TestMediaTokenKeyFallback(t *testing.T) {
	key := bytes.Repeat([]byte{9}, 32)
	s := encodeMediaToken(mediaToken{DirectPath: "/x", Type: "image"})
	if _, _, err := decodeMediaToken(s, nil); err == nil {
		t.Fatal("a token without a key needs one")
	}
	out, _, err := decodeMediaToken(s, key)
	if err != nil || !bytes.Equal(out.MediaKey, key) {
		t.Fatalf("key parameter not used: %v", err)
	}
}

func TestMediaTokenRejects(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	cases := map[string]string{
		"":                codeInvalidArgument,
		"wa1.":            codeInvalidArgument,
		"wa1.!!!":         codeInvalidArgument,
		"gm-media-id":     codeInvalidArgument,
		"wa1.bm90IGpzb24": codeInvalidArgument,
		encodeMediaToken(mediaToken{DirectPath: "/x", MediaKey: key, Type: "history"}):           codeInvalidArgument,
		encodeMediaToken(mediaToken{DirectPath: "https://evil/x", MediaKey: key, Type: "image"}): codeNotFound,
		encodeMediaToken(mediaToken{DirectPath: "/x", MediaKey: key[:5], Type: "image"}):         codeInvalidArgument,
	}
	for in, code := range cases {
		_, _, err := decodeMediaToken(in, nil)
		mustCode(t, err, code)
	}
}
