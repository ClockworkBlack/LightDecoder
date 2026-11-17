package wa

import (
	"strconv"
	"strings"
	"time"

	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// identity resolves the ids and names the DTOs carry. The bridge implements
// it with whatsmeow's device store; tests use a fake.
type identity interface {
	// norm turns a JID into the form the DTOs use: no device part, and a
	// hidden-user (LID) JID replaced by its phone-number JID when known.
	norm(j types.JID) types.JID
	// isMe reports whether j (any form) is this account.
	isMe(j types.JID) bool
	// name is the best display name known for a user JID ("" if none).
	name(j types.JID) string
	// me is this account's user JID in DTO form.
	me() types.JID
}

// content is what one WhatsApp message shows: its text, attachments and the
// message it replies to.
type content struct {
	text    string
	parts   []partDTO
	replyTo string
}

const (
	partText  = "text"
	partMedia = "media"
)

// downloadable is the part of whatsmeow's media messages a token needs.
type downloadable interface {
	GetDirectPath() string
	GetMediaKey() []byte
	GetFileSHA256() []byte
	GetFileEncSHA256() []byte
	GetFileLength() uint64
	GetMimetype() string
}

func mediaPart(kind string, m downloadable, width, height uint32, fileName string, thumb []byte) partDTO {
	tok := mediaToken{
		DirectPath: m.GetDirectPath(),
		MediaKey:   m.GetMediaKey(),
		EncSHA256:  m.GetFileEncSHA256(),
		SHA256:     m.GetFileSHA256(),
		Length:     m.GetFileLength(),
		Type:       kind,
	}
	p := partDTO{
		PartID:     partMedia,
		Kind:       partMedia,
		Mime:       m.GetMimetype(),
		FileName:   fileName,
		Size:       int64(m.GetFileLength()),
		Width:      int64(width),
		Height:     int64(height),
		InlineData: b64(thumb),
	}
	if tok.DirectPath != "" {
		p.MediaID = encodeMediaToken(tok)
	}
	if kind == "sticker" && p.Mime == "" {
		p.Mime = "image/webp"
	}
	return p
}

// contextInfo returns the ContextInfo of whichever sub-message carries one.
func contextInfo(m *waE2E.Message) *waE2E.ContextInfo {
	switch {
	case m.GetExtendedTextMessage() != nil:
		return m.GetExtendedTextMessage().GetContextInfo()
	case m.GetImageMessage() != nil:
		return m.GetImageMessage().GetContextInfo()
	case m.GetVideoMessage() != nil:
		return m.GetVideoMessage().GetContextInfo()
	case m.GetPtvMessage() != nil:
		return m.GetPtvMessage().GetContextInfo()
	case m.GetAudioMessage() != nil:
		return m.GetAudioMessage().GetContextInfo()
	case m.GetDocumentMessage() != nil:
		return m.GetDocumentMessage().GetContextInfo()
	case m.GetStickerMessage() != nil:
		return m.GetStickerMessage().GetContextInfo()
	case m.GetLocationMessage() != nil:
		return m.GetLocationMessage().GetContextInfo()
	case m.GetContactMessage() != nil:
		return m.GetContactMessage().GetContextInfo()
	}
	return nil
}

func formatLocation(lat, lng float64, name, address string) string {
	var sb strings.Builder
	for _, s := range []string{name, address} {
		if s = strings.TrimSpace(s); s != "" {
			sb.WriteString(s)
			sb.WriteString("\n")
		}
	}
	sb.WriteString("https://maps.google.com/?q=")
	sb.WriteString(strconv.FormatFloat(lat, 'f', 6, 64))
	sb.WriteString(",")
	sb.WriteString(strconv.FormatFloat(lng, 'f', 6, 64))
	return sb.String()
}

func pollText(p *waE2E.PollCreationMessage) string {
	var sb strings.Builder
	sb.WriteString(p.GetName())
	for _, o := range p.GetOptions() {
		sb.WriteString("\n• ")
		sb.WriteString(o.GetOptionName())
	}
	return sb.String()
}

// extractContent reads the visible content of a (already unwrapped) message.
// ok is false for messages without anything to show: protocol messages,
// reactions, key distribution, and kinds this bridge does not render.
func extractContent(m *waE2E.Message) (c content, ok bool) {
	if m == nil {
		return c, false
	}
	var media *partDTO
	switch {
	case m.GetConversation() != "":
		c.text = m.GetConversation()
	case m.GetExtendedTextMessage() != nil:
		c.text = m.GetExtendedTextMessage().GetText()
	case m.GetImageMessage() != nil:
		im := m.GetImageMessage()
		p := mediaPart("image", im, im.GetWidth(), im.GetHeight(), "", im.GetJPEGThumbnail())
		media, c.text = &p, im.GetCaption()
	case m.GetVideoMessage() != nil || m.GetPtvMessage() != nil:
		vm := m.GetVideoMessage()
		if vm == nil {
			vm = m.GetPtvMessage()
		}
		p := mediaPart("video", vm, vm.GetWidth(), vm.GetHeight(), "", vm.GetJPEGThumbnail())
		media, c.text = &p, vm.GetCaption()
	case m.GetAudioMessage() != nil:
		am := m.GetAudioMessage()
		p := mediaPart("audio", am, 0, 0, "", nil)
		media = &p
	case m.GetDocumentMessage() != nil:
		dm := m.GetDocumentMessage()
		name := dm.GetFileName()
		if name == "" {
			name = dm.GetTitle()
		}
		p := mediaPart("document", dm, 0, 0, name, dm.GetJPEGThumbnail())
		media, c.text = &p, dm.GetCaption()
	case m.GetStickerMessage() != nil:
		sm := m.GetStickerMessage()
		p := mediaPart("sticker", sm, sm.GetWidth(), sm.GetHeight(), "", sm.GetPngThumbnail())
		media = &p
	case m.GetLocationMessage() != nil:
		lm := m.GetLocationMessage()
		c.text = formatLocation(lm.GetDegreesLatitude(), lm.GetDegreesLongitude(), lm.GetName(), lm.GetAddress())
	case m.GetLiveLocationMessage() != nil:
		lm := m.GetLiveLocationMessage()
		c.text = formatLocation(lm.GetDegreesLatitude(), lm.GetDegreesLongitude(), lm.GetCaption(), "")
	case m.GetContactMessage() != nil:
		cm := m.GetContactMessage()
		c.text = cm.GetDisplayName()
		if vcard := cm.GetVcard(); vcard != "" {
			p := partDTO{PartID: partMedia, Kind: partMedia, Mime: "text/vcard", FileName: "contact.vcf",
				Size: int64(len(vcard)), InlineData: b64([]byte(vcard))}
			media = &p
		}
	case m.GetContactsArrayMessage() != nil:
		c.text = m.GetContactsArrayMessage().GetDisplayName()
	case m.GetPollCreationMessage() != nil:
		c.text = pollText(m.GetPollCreationMessage())
	case m.GetPollCreationMessageV2() != nil:
		c.text = pollText(m.GetPollCreationMessageV2())
	case m.GetPollCreationMessageV3() != nil:
		c.text = pollText(m.GetPollCreationMessageV3())
	default:
		return c, false
	}
	c.parts = []partDTO{}
	if media != nil {
		c.parts = append(c.parts, *media)
	}
	if c.text != "" {
		c.parts = append(c.parts, partDTO{PartID: partText, Kind: partText, Text: c.text})
	}
	if len(c.parts) == 0 {
		return c, false
	}
	c.replyTo = contextInfo(m).GetStanzaID()
	return c, true
}

// actionKind says what a WhatsApp message does to the chat.
type actionKind int

const (
	actIgnore   actionKind = iota
	actNew                 // a message to show (dto)
	actRevoke              // targetID was deleted for everyone
	actEdit                // targetID's content is now content
	actReaction            // reactor reacted to targetID with emoji ("" removes)
)

type msgAction struct {
	kind     actionKind
	chat     types.JID // DTO form
	rawChat  types.JID // as WhatsApp addressed it (history requests need it)
	dto      messageDTO
	targetID string
	content  content
	emoji    string
	reactor  string
	fromMe   bool
	tsMs     int64
}

// classifyMessage turns a whatsmeow message event (live, or parsed from a
// history sync) into what the bridge should do with it.
func classifyMessage(evt *events.Message, id identity, isOld bool) msgAction {
	info := evt.Info
	chat := id.norm(info.Chat)
	sender := id.norm(info.Sender)
	fromMe := info.IsFromMe || id.isMe(info.Sender)
	if fromMe {
		sender = id.me()
	}
	a := msgAction{chat: chat, rawChat: info.Chat.ToNonAD(), fromMe: fromMe, tsMs: info.Timestamp.UnixMilli()}
	if chat.IsEmpty() || chat.Server == types.BroadcastServer || chat.Server == types.NewsletterServer {
		// Status updates, broadcast lists and channels are not chats here.
		return a
	}
	m := evt.Message
	if pm := m.GetProtocolMessage(); pm != nil {
		switch pm.GetType() {
		case waE2E.ProtocolMessage_REVOKE:
			a.kind, a.targetID = actRevoke, pm.GetKey().GetID()
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			if c, ok := extractContent(pm.GetEditedMessage()); ok {
				a.kind, a.targetID, a.content = actEdit, pm.GetKey().GetID(), c
			}
		}
		if a.targetID == "" {
			a.kind = actIgnore
		}
		return a
	}
	if rm := m.GetReactionMessage(); rm != nil {
		if rm.GetKey().GetID() == "" {
			return a
		}
		a.kind, a.targetID, a.emoji, a.reactor = actReaction, rm.GetKey().GetID(), rm.GetText(), sender.String()
		return a
	}
	c, ok := extractContent(m)
	if !ok {
		return a
	}
	a.kind = actNew
	a.dto = messageDTO{
		ID:             info.ID,
		ConversationID: chat.String(),
		SenderID:       sender.String(),
		FromMe:         fromMe,
		TimestampMs:    info.Timestamp.UnixMilli(),
		Text:           c.text,
		Parts:          c.parts,
		ReplyToID:      c.replyTo,
		IsOld:          isOld,
		Transport:      transportWhatsApp,
	}
	if fromMe {
		a.dto.Direction = directionOut
		setStatus(&a.dto, statusSent)
	} else {
		a.dto.Direction = directionIn
		setStatus(&a.dto, statusIncoming)
		a.dto.SenderName = id.name(sender)
		if a.dto.SenderName == "" {
			a.dto.SenderName = info.PushName
		}
	}
	if web := evt.SourceWebMsg; web != nil {
		applyWebExtras(&a.dto, web, chat, id)
	}
	normalizeMessage(&a.dto)
	return a
}

// historyAction classifies one history-sync message. A message the phone
// keeps as "revoked" comes as a stub without content.
func historyAction(evt *events.Message, web *waWeb.WebMessageInfo, id identity) msgAction {
	if web.GetMessageStubType() == waWeb.WebMessageInfo_REVOKE {
		chat := id.norm(evt.Info.Chat)
		return msgAction{kind: actRevoke, chat: chat, rawChat: evt.Info.Chat.ToNonAD(), targetID: evt.Info.ID,
			tsMs: evt.Info.Timestamp.UnixMilli(), fromMe: evt.Info.IsFromMe}
	}
	return classifyMessage(evt, id, true)
}

// applyWebExtras adds what only a history copy knows: the delivery status the
// phone recorded, and the reactions on the message.
func applyWebExtras(m *messageDTO, web *waWeb.WebMessageInfo, chat types.JID, id identity) {
	if m.FromMe {
		switch web.GetStatus() {
		case waWeb.WebMessageInfo_ERROR:
			setStatus(m, statusFailed)
		case waWeb.WebMessageInfo_PENDING:
			setStatus(m, statusSending)
		case waWeb.WebMessageInfo_DELIVERY_ACK:
			setStatus(m, statusDelivered)
		case waWeb.WebMessageInfo_READ, waWeb.WebMessageInfo_PLAYED:
			setStatus(m, statusRead)
		}
	}
	for _, r := range web.GetReactions() {
		reactor, fromMe := reactorOf(r.GetKey(), chat, id)
		if reactor == "" {
			continue
		}
		applyReaction(m, reactor, r.GetText(), fromMe)
	}
}

// reactorOf is who sent the message with this key.
func reactorOf(key *waCommon.MessageKey, chat types.JID, id identity) (string, bool) {
	if key.GetFromMe() {
		return id.me().String(), true
	}
	if p := key.GetParticipant(); p != "" {
		if j, err := types.ParseJID(p); err == nil {
			return id.norm(j).String(), id.isMe(j)
		}
		return "", false
	}
	if chat.Server == types.GroupServer {
		return "", false
	}
	return chat.String(), false
}

// applyReaction records that reactor now reacts with emoji ("" removes the
// reaction). A participant has at most one reaction per message.
func applyReaction(m *messageDTO, reactor, emoji string, fromMe bool) {
	out := m.Reactions[:0:0]
	for _, r := range m.Reactions {
		ids := make([]string, 0, len(r.ParticipantIDs))
		for _, p := range r.ParticipantIDs {
			if p != reactor {
				ids = append(ids, p)
			}
		}
		r.ParticipantIDs = ids
		if fromMe {
			r.FromMe = false
		}
		if len(ids) > 0 || r.FromMe {
			out = append(out, r)
		}
	}
	if emoji != "" {
		found := false
		for i := range out {
			if out[i].Emoji == emoji {
				out[i].ParticipantIDs = append(out[i].ParticipantIDs, reactor)
				out[i].FromMe = out[i].FromMe || fromMe
				found = true
				break
			}
		}
		if !found {
			out = append(out, reactionDTO{Emoji: emoji, ParticipantIDs: []string{reactor}, FromMe: fromMe})
		}
	}
	m.Reactions = out
	normalizeMessage(m)
}

// applyEdit replaces the text of m, keeping its attachments (an edit can only
// change the text or a caption).
func applyEdit(m *messageDTO, c content) {
	parts := make([]partDTO, 0, len(m.Parts)+1)
	for _, p := range m.Parts {
		if p.Kind != partText {
			parts = append(parts, p)
		}
	}
	if c.text != "" {
		parts = append(parts, partDTO{PartID: partText, Kind: partText, Text: c.text})
	}
	m.Parts, m.Text = parts, c.text
	normalizeMessage(m)
}

// deletedMessage is what a revoke turns a message into (as in gmbridge: the
// same id with direction "deleted" and no content). base may be nil.
func deletedMessage(base *messageDTO, id, chat string, tsMs int64) messageDTO {
	var m messageDTO
	if base != nil {
		m = *base
	} else {
		m = messageDTO{ID: id, ConversationID: chat, TimestampMs: tsMs}
	}
	m.Direction = directionDeleted
	m.Text = ""
	m.Parts = []partDTO{}
	setStatus(&m, statusDeleted)
	normalizeMessage(&m)
	return m
}

// userNumber is "+<digits>" for a phone-number JID, "" otherwise.
func userNumber(j types.JID) string {
	if j.Server == types.DefaultUserServer && j.User != "" {
		return "+" + j.User
	}
	return ""
}

func millis(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}
