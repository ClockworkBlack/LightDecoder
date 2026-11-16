package wa

import (
	"context"
	"image"
	_ "image/gif" // image.DecodeConfig for sent pictures
	_ "image/jpeg"
	_ "image/png"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
)

const (
	// maxUploadSize is the largest file SendMediaFile sends (contract: 100 MB).
	maxUploadSize = 100 << 20
	// mediaTimeout bounds one upload or download.
	mediaTimeout = 10 * time.Minute
)

// whatsmeow media calls; tests replace them.
var (
	uploadMedia = func(ctx context.Context, cli *whatsmeow.Client, r io.Reader, tmp io.ReadWriteSeeker, mt whatsmeow.MediaType) (whatsmeow.UploadResponse, error) {
		return cli.UploadReader(ctx, r, tmp, mt)
	}
	downloadMedia = func(ctx context.Context, cli *whatsmeow.Client, t mediaToken, mt whatsmeow.MediaType) ([]byte, error) {
		return cli.DownloadMediaWithPath(ctx, t.DirectPath, t.EncSHA256, t.SHA256, t.MediaKey, mt, "", false)
	}
)

// DownloadMedia downloads and decrypts one attachment. mediaID is the token
// in PartDTO.mediaId; key is only used when the token carries no key of its
// own. Needs a connection (WhatsApp hands out media hosts per connection).
func (b *WABridge) DownloadMedia(mediaID string, key []byte) (out []byte, err error) {
	defer recoverInto(&err)
	tok, mt, err := decodeMediaToken(mediaID, key)
	if err != nil {
		return nil, err
	}
	cli, _, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	cancel() // the call context is for short requests; media gets its own
	b.mu.Lock()
	connCtx := b.connCtx
	b.mu.Unlock()
	if connCtx == nil {
		connCtx = ctx
	}
	mctx, mcancel := context.WithTimeout(connCtx, mediaTimeout)
	defer mcancel()
	data, err := downloadMedia(mctx, cli, tok, mt)
	if err != nil {
		return nil, exported(err)
	}
	return data, nil
}

// mediaKind picks how a file is sent: "image", "video", "audio" or "document".
func mediaKind(mimeType string) string {
	base, _, _ := mime.ParseMediaType(mimeType)
	switch {
	case base == "image/jpeg", base == "image/png", base == "image/webp":
		return "image"
	case base == "video/mp4", base == "video/3gpp":
		return "video"
	case strings.HasPrefix(base, "audio/"):
		return "audio"
	default:
		return "document"
	}
}

// SendMediaFile sends the file at path (an image, video, audio or any other
// document; at most 100 MB) with an optional caption, and returns the sent
// MessageDTO like SendText. mime may be empty (it is then sniffed); fileName
// is what the recipient sees for documents.
func (b *WABridge) SendMediaFile(conversationID, tmpID, path, fileName, mimeType, caption, replyToID string) (out []byte, err error) {
	defer recoverInto(&err)
	chat, err := parseChat(conversationID)
	if err != nil {
		return nil, err
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, newError(codeInvalidArgument, "the file cannot be read")
	}
	defer f.Close()
	st, err := f.Stat()
	switch {
	case err != nil || !st.Mode().IsRegular():
		return nil, newError(codeInvalidArgument, "the file cannot be read")
	case st.Size() == 0:
		return nil, newError(codeInvalidArgument, "the file is empty")
	case st.Size() > maxUploadSize:
		return nil, newError(codeTooLarge, "files up to 100 MB can be sent")
	}
	head := make([]byte, 512)
	n, _ := io.ReadFull(f, head)
	head = head[:n]
	if strings.TrimSpace(mimeType) == "" {
		mimeType = http.DetectContentType(head)
	}
	if fileName == "" {
		fileName = filepath.Base(path)
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return nil, newError(codeInvalidArgument, "the file cannot be read")
	}
	kind := mediaKind(mimeType)
	var width, height uint32
	if kind == "image" {
		if cfg, _, err := image.DecodeConfig(f); err == nil {
			width, height = uint32(cfg.Width), uint32(cfg.Height)
		}
		if _, err := f.Seek(0, io.SeekStart); err != nil {
			return nil, newError(codeInvalidArgument, "the file cannot be read")
		}
	}

	cli, local, ctx, cancel, err := b.rpc()
	if err != nil {
		return nil, err
	}
	defer cancel()
	b.mu.Lock()
	connCtx := b.connCtx
	b.mu.Unlock()
	if connCtx == nil {
		connCtx = ctx
	}
	tmpDir := filepath.Join(b.dataDir, tmpDirName)
	if err := os.MkdirAll(tmpDir, 0o700); err != nil {
		return nil, newError(codeInternal, "no room for the upload")
	}
	tmp, err := os.CreateTemp(tmpDir, "up-*")
	if err != nil {
		return nil, newError(codeInternal, "no room for the upload")
	}
	defer func() {
		_ = tmp.Close()
		_ = os.Remove(tmp.Name())
	}()
	uctx, ucancel := context.WithTimeout(connCtx, mediaTimeout)
	defer ucancel()
	up, err := uploadMedia(uctx, cli, f, tmp, mediaTypes[kind])
	if err != nil {
		return nil, sendFailed(err)
	}

	id := resolver{cli: cli}
	ci := replyContext(local, id, chat, replyToID)
	msg := &waE2E.Message{}
	length := up.FileLength
	if length == 0 {
		length = uint64(st.Size())
	}
	switch kind {
	case "image":
		msg.ImageMessage = &waE2E.ImageMessage{
			URL: new(up.URL), DirectPath: new(up.DirectPath), MediaKey: up.MediaKey,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: new(length),
			Mimetype: new(mimeType), Width: new(width), Height: new(height), ContextInfo: ci,
		}
		if caption != "" {
			msg.ImageMessage.Caption = new(caption)
		}
	case "video":
		msg.VideoMessage = &waE2E.VideoMessage{
			URL: new(up.URL), DirectPath: new(up.DirectPath), MediaKey: up.MediaKey,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: new(length),
			Mimetype: new(mimeType), ContextInfo: ci,
		}
		if caption != "" {
			msg.VideoMessage.Caption = new(caption)
		}
	case "audio":
		base, _, _ := mime.ParseMediaType(mimeType)
		msg.AudioMessage = &waE2E.AudioMessage{
			URL: new(up.URL), DirectPath: new(up.DirectPath), MediaKey: up.MediaKey,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: new(length),
			Mimetype: new(mimeType), PTT: new(base == "audio/ogg"), ContextInfo: ci,
		}
		caption = "" // audio has no caption
	default:
		msg.DocumentMessage = &waE2E.DocumentMessage{
			URL: new(up.URL), DirectPath: new(up.DirectPath), MediaKey: up.MediaKey,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: new(length),
			Mimetype: new(mimeType), FileName: new(fileName), Title: new(fileName), ContextInfo: ci,
		}
		if caption != "" {
			msg.DocumentMessage.Caption = new(caption)
		}
	}
	resp, err := sendMessage(ctx, cli, chat, msg, whatsmeow.SendRequestExtra{ID: newMessageID(cli)})
	if err != nil {
		return nil, sendFailed(err)
	}
	m := outgoing(id, chat, resp.ID, tmpID, resp.Timestamp)
	m.ReplyToID = replyToID
	part := partDTO{
		PartID: partMedia, Kind: partMedia, Mime: mimeType, Size: int64(length),
		Width: int64(width), Height: int64(height),
		MediaID: encodeMediaToken(mediaToken{
			DirectPath: up.DirectPath, MediaKey: up.MediaKey, EncSHA256: up.FileEncSHA256,
			SHA256: up.FileSHA256, Length: length, Type: kind,
		}),
	}
	if kind == "document" {
		part.FileName = fileName
	}
	m.Parts = []partDTO{part}
	if caption != "" {
		m.Text = caption
		m.Parts = append(m.Parts, partDTO{PartID: partText, Kind: partText, Text: caption})
	}
	return b.sent(local, id, chat, m), nil
}
