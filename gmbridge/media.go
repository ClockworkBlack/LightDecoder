package gmbridge

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// maxMediaBytes caps one download. Stickers and photos are far smaller; the
// cap keeps a hostile or broken blob from exhausting the app's memory.
const maxMediaBytes = 64 << 20

// maxUploadBytes mirrors the outbox's own limit on attachments.
const maxUploadBytes = 100 << 20

// DownloadMedia downloads and decrypts one attachment (mediaID + its 32-byte
// key from a PartDTO). It needs a pairing but not a live connection: libgm
// fetches the blob directly with the session token.
func (b *GMBridge) DownloadMedia(mediaID string, key []byte) (out []byte, err error) {
	defer recoverInto(&err)
	if mediaID == "" {
		return nil, newError(codeInvalidArgument, "media ID is empty")
	}
	if len(key) != 32 {
		return nil, newError(codeInvalidArgument, "media key must be 32 bytes")
	}
	cli, err := b.pairedClient()
	if err != nil {
		return nil, err
	}
	// Java hands Go a view of its array that is only valid during the call.
	stream, err := cli.DownloadMedia(mediaID, bytes.Clone(key))
	if err != nil {
		return nil, exported(err)
	}
	defer stream.Close()
	return readCapped(stream, maxMediaBytes)
}

// readCapped reads at most limit bytes; anything longer is too_large.
// libgm does not check the HTTP status of downloads: an error page surfaces
// as a bad stream header, which means the media is gone or not yet uploaded.
func readCapped(r io.Reader, limit int64) ([]byte, error) {
	data, err := io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		msg := safeErrorString(err)
		switch {
		case strings.Contains(msg, "header signature"), strings.Contains(msg, "chunk size too large"):
			return nil, newError(codeNotFound, "media is not available")
		case strings.Contains(msg, "failed to decrypt"):
			return nil, newError(codeInternal, "media could not be decrypted")
		}
		return nil, exported(err)
	}
	if int64(len(data)) > limit {
		return nil, newError(codeTooLarge, "media is larger than 64 MiB")
	}
	if len(data) == 0 {
		return nil, newError(codeNotFound, "media is empty")
	}
	return data, nil
}

// RequestFullSizeImage asks the phone to upload the original of a part that
// only has a thumbnail. Nothing is returned: an updated "message" event with
// the same message ID and a filled-in mediaId follows when the phone is done.
func (b *GMBridge) RequestFullSizeImage(messageID, partID string) (err error) {
	defer recoverInto(&err)
	if messageID == "" || partID == "" || strings.HasPrefix(partID, indexPartPrefix) {
		return newError(codeInvalidArgument, "message ID and the part's action ID are required")
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return err
	}
	defer cancel()
	_, err = cli.GetFullSizeImage(ctx, messageID, partID)
	return exported(err)
}

// SendMedia uploads an attachment and sends it, with the caption as a second
// (text) part when given, and returns a SendResultDTO.
func (b *GMBridge) SendMedia(conversationID, tmpID string, data []byte, fileName, mime, caption, replyToID string) (out []byte, err error) {
	defer recoverInto(&err)
	if conversationID == "" || tmpID == "" {
		return nil, newError(codeInvalidArgument, "conversation ID and tmp ID are required")
	}
	if len(data) == 0 {
		return nil, newError(codeInvalidArgument, "attachment is empty")
	}
	if len(data) > maxUploadBytes {
		return nil, newError(codeTooLarge, "attachment is larger than 100 MiB")
	}
	mime = strings.TrimSpace(mime)
	if mime == "" || !strings.Contains(mime, "/") {
		mime = http.DetectContentType(data)
	}
	if fileName = strings.TrimSpace(fileName); fileName == "" {
		fileName = "attachment"
	}
	cli, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	conv, err := b.conversationForWrite(ctx, cli, conversationID)
	if err != nil {
		return nil, exported(err)
	}
	// UploadMedia encrypts a copy before returning, so the Java-owned data
	// slice is not used after this call.
	media, err := cli.UploadMedia(data, fileName, mime)
	if err != nil {
		return nil, uploadError(err)
	}
	infos := []*gmproto.MessageInfo{{Data: &gmproto.MessageInfo_MediaContent{MediaContent: media}}}
	if strings.TrimSpace(caption) != "" {
		infos = append(infos, textInfo(caption))
	}
	return b.send(ctx, cli, conv, tmpID, infos, replyToID)
}

// uploadError keeps auth failures recognisable and reports the rest as a
// failed send (the outbox may retry it).
func uploadError(err error) error {
	be := toBridgeError(err)
	switch be.code {
	case codeCookiesInvalid, codeLoggedOut, codeNotPaired:
		return be
	}
	var urlish interface{ Timeout() bool }
	if errors.As(err, &urlish) {
		return newError(codeSendFailed, "upload failed: network error")
	}
	return newError(codeSendFailed, "upload failed: "+be.detail)
}
