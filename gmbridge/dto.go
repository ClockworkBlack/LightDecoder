package gmbridge

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"strconv"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// The DTOs below are the JSON shapes Kotlin parses (contract §4). They are
// unexported on purpose: gomobile would try to bind exported structs and
// skip their slice fields, which the CI "skipped" guard rejects.
//
// Every slice is initialised so it encodes as [] rather than null, byte
// fields are carried as base64 strings, and every timestamp is epoch ms.

type statusDTO struct {
	Paired    bool   `json:"paired"`
	Connected bool   `json:"connected"`
	Account   string `json:"account"`
	PhoneID   string `json:"phoneId"`
	SessionID string `json:"sessionId"`
}

type stateDTO struct {
	State  string `json:"state"`
	Code   string `json:"code"`
	Detail string `json:"detail"`
}

type pairingChallengeDTO struct {
	Emoji    string `json:"emoji"`
	EmojiURL string `json:"emojiUrl"`
	Account  string `json:"account"`
}

type conversationDTO struct {
	ID                  string           `json:"id"`
	Name                string           `json:"name"`
	IsGroup             bool             `json:"isGroup"`
	Type                string           `json:"type"`
	Folder              string           `json:"folder"`
	StatusCode          int              `json:"statusCode"`
	Unread              bool             `json:"unread"`
	Pinned              bool             `json:"pinned"`
	ReadOnly            bool             `json:"readOnly"`
	Muted               bool             `json:"muted"`
	LastMessageTs       int64            `json:"lastMessageTs"`
	LatestMessageID     string           `json:"latestMessageId"`
	Snippet             string           `json:"snippet"`
	SnippetFromMe       bool             `json:"snippetFromMe"`
	SnippetSender       string           `json:"snippetSender"`
	AvatarColor         string           `json:"avatarColor"`
	DefaultOutgoingID   string           `json:"defaultOutgoingId"`
	Participants        []participantDTO `json:"participants"`
	OtherParticipantIDs []string         `json:"otherParticipantIds"`
}

type participantDTO struct {
	ID              string `json:"id"`
	Name            string `json:"name"`
	FirstName       string `json:"firstName"`
	Number          string `json:"number"`
	FormattedNumber string `json:"formattedNumber"`
	IsMe            bool   `json:"isMe"`
	Color           string `json:"color"`
	ContactID       string `json:"contactId"`
	Visible         bool   `json:"visible"`
}

type messageDTO struct {
	ID                 string        `json:"id"`
	TmpID              string        `json:"tmpId"`
	ConversationID     string        `json:"conversationId"`
	SenderID           string        `json:"senderId"`
	SenderName         string        `json:"senderName"`
	FromMe             bool          `json:"fromMe"`
	TimestampMs        int64         `json:"timestampMs"`
	Direction          string        `json:"direction"`
	Status             string        `json:"status"`
	StatusCode         int           `json:"statusCode"`
	ErrorText          string        `json:"errorText"`
	Transport          string        `json:"transport"`
	Subject            string        `json:"subject"`
	Text               string        `json:"text"`
	Parts              []partDTO     `json:"parts"`
	ReplyToID          string        `json:"replyToId"`
	Reactions          []reactionDTO `json:"reactions"`
	IsOld              bool          `json:"isOld"`
	MmsPendingDownload bool          `json:"mmsPendingDownload"`
}

type partDTO struct {
	PartID        string `json:"partId"`
	Kind          string `json:"kind"`
	Text          string `json:"text"`
	MediaID       string `json:"mediaId"`
	Key           string `json:"key"`
	ThumbMediaID  string `json:"thumbMediaId"`
	ThumbKey      string `json:"thumbKey"`
	Mime          string `json:"mime"`
	FileName      string `json:"fileName"`
	Size          int64  `json:"size"`
	Width         int64  `json:"width"`
	Height        int64  `json:"height"`
	InlineData    string `json:"inlineData"`
	NeedsFullSize bool   `json:"needsFullSize"`
}

type reactionDTO struct {
	Emoji          string   `json:"emoji"`
	ParticipantIDs []string `json:"participantIds"`
	FromMe         bool     `json:"fromMe"`
}

type messagePageDTO struct {
	Messages []messageDTO `json:"messages"`
	Cursor   *cursorDTO   `json:"cursor"`
	Total    int64        `json:"total"`
}

type cursorDTO struct {
	LastItemID          string `json:"lastItemId"`
	LastItemTimestampMs int64  `json:"lastItemTimestampMs"`
}

type contactDTO struct {
	ID              string `json:"id"`
	Name            string `json:"name"`
	Number          string `json:"number"`
	FormattedNumber string `json:"formattedNumber"`
	Color           string `json:"color"`
	ContactID       string `json:"contactId"`
}

type thumbnailDTO struct {
	ParticipantID string `json:"participantId"`
	Data          string `json:"data"`
}

type typingDTO struct {
	ConversationID string `json:"conversationId"`
	ParticipantID  string `json:"participantId"`
	Number         string `json:"number"`
	Typing         bool   `json:"typing"`
}

type simDTO struct {
	ParticipantID string `json:"participantId"`
	Number        string `json:"number"`
	Label         string `json:"label"`
	RCSEnabled    bool   `json:"rcsEnabled"`
}

type settingsDTO struct {
	SIMs                 []simDTO `json:"sims"`
	RCSEnabled           bool     `json:"rcsEnabled"`
	SendReadReceipts     bool     `json:"sendReadReceipts"`
	ShowTypingIndicators bool     `json:"showTypingIndicators"`
	IsDefaultSMSApp      bool     `json:"isDefaultSmsApp"`
}

type sendResultDTO struct {
	TmpID  string `json:"tmpId"`
	OK     bool   `json:"ok"`
	Status string `json:"status"`
	Code   string `json:"code"`
}

type accountDTO struct {
	Account string `json:"account"`
	Enabled bool   `json:"enabled"`
}

// toJSON encodes without HTML escaping (emoji and "<" stay readable) and
// without the trailing newline json.Encoder adds. The DTOs cannot fail to
// encode, so an error falls back to an empty object rather than nil, which
// Java would see as null.
func toJSON(v any) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return []byte("{}")
	}
	return bytes.TrimRight(buf.Bytes(), "\n")
}

func b64(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(data)
}

// microsToMillis converts libgm timestamps (µs) to the contract's ms.
func microsToMillis(us int64) int64 { return us / 1000 }

// normalizeColor makes avatar colours "#RRGGBB" (libgm sends them with or
// without the hash).
func normalizeColor(c string) string {
	c = strings.TrimSpace(c)
	if c == "" || strings.HasPrefix(c, "#") {
		return c
	}
	return "#" + c
}

// --- conversations -----------------------------------------------------------

func folderOf(status gmproto.ConversationStatus) string {
	switch status {
	// An absent status (proto zero) is treated as active so a conversation
	// listed from the inbox is never hidden by an "other" folder.
	case gmproto.ConversationStatus_ACTIVE, gmproto.ConversationStatus_UNKNOWN_CONVERSATION_STATUS:
		return "inbox"
	case gmproto.ConversationStatus_ARCHIVED, gmproto.ConversationStatus_KEEP_ARCHIVED:
		return "archive"
	case gmproto.ConversationStatus_SPAM_FOLDER, gmproto.ConversationStatus_BLOCKED_FOLDER:
		return "spam"
	case gmproto.ConversationStatus_DELETED, gmproto.ConversationStatus_TRASH_FOLDER:
		return "deleted"
	default:
		return "other"
	}
}

func conversationTypeName(t gmproto.ConversationType) string {
	switch t {
	case gmproto.ConversationType_SMS:
		return "SMS"
	case gmproto.ConversationType_RCS:
		return "RCS"
	default:
		return "UNKNOWN"
	}
}

func toConversationDTO(conv *gmproto.Conversation, self selfChecker) conversationDTO {
	latest := conv.GetLatestMessage()
	dto := conversationDTO{
		ID:                  conv.GetConversationID(),
		Name:                conv.GetName(),
		IsGroup:             conv.GetIsGroupChat(),
		Type:                conversationTypeName(conv.GetType()),
		Folder:              folderOf(conv.GetStatus()),
		StatusCode:          int(conv.GetStatus()),
		Unread:              conv.GetUnread(),
		Pinned:              conv.GetPinned(),
		ReadOnly:            conv.GetReadOnly(),
		LastMessageTs:       microsToMillis(conv.GetLastMessageTimestamp()),
		LatestMessageID:     conv.GetLatestMessageID(),
		Snippet:             latest.GetDisplayContent(),
		SnippetFromMe:       latest.GetFromMe() != 0,
		SnippetSender:       latest.GetDisplayName(),
		AvatarColor:         normalizeColor(conv.GetAvatarHexColor()),
		DefaultOutgoingID:   conv.GetDefaultOutgoingID(),
		Participants:        make([]participantDTO, 0, len(conv.GetParticipants())),
		OtherParticipantIDs: make([]string, 0, len(conv.GetOtherParticipants())),
	}
	for _, p := range conv.GetParticipants() {
		dto.Participants = append(dto.Participants, toParticipantDTO(p, self))
	}
	dto.OtherParticipantIDs = append(dto.OtherParticipantIDs, conv.GetOtherParticipants()...)
	return dto
}

func toParticipantDTO(p *gmproto.Participant, self selfChecker) participantDTO {
	id := p.GetID().GetParticipantID()
	return participantDTO{
		ID:              id,
		Name:            p.GetFullName(),
		FirstName:       p.GetFirstName(),
		Number:          p.GetID().GetNumber(),
		FormattedNumber: p.GetFormattedNumber(),
		IsMe:            p.GetIsMe() || (self != nil && id != "" && self.isSelf(id)),
		Color:           normalizeColor(p.GetAvatarHexColor()),
		ContactID:       p.GetContactID(),
		Visible:         p.GetIsVisible(),
	}
}

// --- messages ----------------------------------------------------------------

// selfChecker answers "is this participant ID one of mine?" from settings and
// conversation data seen so far. A nil checker only knows the "1" convention.
type selfChecker interface {
	isSelf(participantID string) bool
}

func directionOf(status gmproto.MessageStatusType, fromMe bool) string {
	switch {
	case status >= 1 && status < 100:
		return "out"
	case status >= 100 && status < 200:
		return "in"
	case status >= 200 && status < 300:
		return "system"
	case status == gmproto.MessageStatusType_MESSAGE_DELETED:
		return "deleted"
	case fromMe:
		return "out"
	default:
		return "in"
	}
}

// isFromMe mirrors the connector's makeEventSender: the status range wins,
// otherwise participant "1" or one of our own participant IDs means "me".
func isFromMe(status gmproto.MessageStatusType, participantID string, self selfChecker) bool {
	if status >= 100 && status < 200 {
		return false
	}
	if status >= 1 && status < 100 {
		return true
	}
	return participantID == "1" || (self != nil && participantID != "" && self.isSelf(participantID))
}

func transportOf(msgType int64, conv *gmproto.Conversation) string {
	switch msgType {
	case 1:
		return "SMS"
	case 2, 3:
		return "MMS"
	case 4:
		return "RCS"
	}
	switch conv.GetType() {
	case gmproto.ConversationType_SMS:
		return "SMS"
	case gmproto.ConversationType_RCS:
		return "RCS"
	}
	return "UNKNOWN"
}

// mmsPendingDownload is true while the phone itself has not downloaded the
// MMS (auto-download off, downloading, failed), as in the connector.
func mmsPendingDownload(status gmproto.MessageStatusType) bool {
	switch status {
	case gmproto.MessageStatusType_INCOMING_YET_TO_MANUAL_DOWNLOAD,
		gmproto.MessageStatusType_INCOMING_RETRYING_MANUAL_DOWNLOAD,
		gmproto.MessageStatusType_INCOMING_MANUAL_DOWNLOADING,
		gmproto.MessageStatusType_INCOMING_RETRYING_AUTO_DOWNLOAD,
		gmproto.MessageStatusType_INCOMING_AUTO_DOWNLOADING,
		gmproto.MessageStatusType_INCOMING_DOWNLOAD_FAILED,
		gmproto.MessageStatusType_INCOMING_DOWNLOAD_CANCELED,
		gmproto.MessageStatusType_INCOMING_DOWNLOAD_FAILED_TOO_LARGE,
		gmproto.MessageStatusType_INCOMING_DOWNLOAD_FAILED_SIM_HAS_NO_DATA,
		gmproto.MessageStatusType_INCOMING_AWAITING_AUTO_DOWNLOAD:
		return true
	}
	return false
}

// resolveMime follows the contract: the proto's own MIME type, then libgm's
// format table, else empty (Kotlin sniffs the bytes after download).
func resolveMime(mc *gmproto.MediaContent) string {
	if m := strings.TrimSpace(mc.GetMimeType()); m != "" {
		return m
	}
	return libgm.FormatToMediaType[mc.GetFormat()].Format
}

// partIDFor returns the part's action message ID. Parts without one get an
// index-based ID so (messageId, partId) stays unique; such IDs are refused
// by RequestFullSizeImage.
func partIDFor(info *gmproto.MessageInfo, index int) string {
	if id := info.GetActionMessageID(); id != "" {
		return id
	}
	return indexPartPrefix + strconv.Itoa(index)
}

const indexPartPrefix = "idx:"

func toPartDTO(info *gmproto.MessageInfo, index int) (partDTO, bool) {
	switch data := info.GetData().(type) {
	case *gmproto.MessageInfo_MessageContent:
		return partDTO{
			PartID: partIDFor(info, index),
			Kind:   "text",
			Text:   data.MessageContent.GetContent(),
		}, true
	case *gmproto.MessageInfo_MediaContent:
		mc := data.MediaContent
		return partDTO{
			PartID:        partIDFor(info, index),
			Kind:          "media",
			MediaID:       mc.GetMediaID(),
			Key:           b64(mc.GetDecryptionKey()),
			ThumbMediaID:  mc.GetThumbnailMediaID(),
			ThumbKey:      b64(mc.GetThumbnailDecryptionKey()),
			Mime:          resolveMime(mc),
			FileName:      mc.GetMediaName(),
			Size:          mc.GetSize(),
			Width:         mc.GetDimensions().GetWidth(),
			Height:        mc.GetDimensions().GetHeight(),
			InlineData:    b64(mc.GetMediaData()),
			NeedsFullSize: mc.GetMediaID() == "" && mc.GetThumbnailMediaID() != "",
		}, true
	}
	return partDTO{}, false
}

// reactionEmoji follows the connector: custom reactions carry their own
// unicode, the fixed types map through EmojiType.Unicode.
func reactionEmoji(data *gmproto.ReactionData) string {
	switch data.GetType() {
	case gmproto.EmojiType_CUSTOM, gmproto.EmojiType_EMOTIFY:
		return data.GetUnicode()
	}
	if e := data.GetType().Unicode(); e != "" {
		return e
	}
	return data.GetUnicode()
}

func toReactionDTOs(entries []*gmproto.ReactionEntry, self selfChecker) []reactionDTO {
	out := make([]reactionDTO, 0, len(entries))
	for _, r := range entries {
		emoji := reactionEmoji(r.GetData())
		if emoji == "" {
			continue
		}
		ids := append(make([]string, 0, len(r.GetParticipantIDs())), r.GetParticipantIDs()...)
		fromMe := false
		for _, id := range ids {
			if id == "1" || (self != nil && self.isSelf(id)) {
				fromMe = true
			}
		}
		out = append(out, reactionDTO{Emoji: emoji, ParticipantIDs: ids, FromMe: fromMe})
	}
	return out
}

// toMessageDTO converts one message. conv may be nil (not cached yet); it is
// only used for the sender's display name, alternate participant IDs and the
// transport fallback.
func toMessageDTO(msg *gmproto.Message, isOld bool, conv *gmproto.Conversation, self selfChecker) messageDTO {
	status := msg.GetMessageStatus().GetStatus()
	fromMe := isFromMe(status, msg.GetParticipantID(), self)
	senderID := msg.GetParticipantID()
	if !fromMe && conv != nil {
		senderID = findAlternateParticipantID(conv, senderID)
	}
	dto := messageDTO{
		ID:                 msg.GetMessageID(),
		TmpID:              msg.GetTmpID(),
		ConversationID:     msg.GetConversationID(),
		SenderID:           senderID,
		SenderName:         senderName(msg, conv, senderID),
		FromMe:             fromMe,
		TimestampMs:        microsToMillis(msg.GetTimestamp()),
		Direction:          directionOf(status, fromMe),
		Status:             status.String(),
		StatusCode:         int(status),
		ErrorText:          msg.GetMessageStatus().GetErrMsg(),
		Transport:          transportOf(msg.GetType(), conv),
		Subject:            msg.GetSubject(),
		Parts:              make([]partDTO, 0, len(msg.GetMessageInfo())),
		ReplyToID:          msg.GetReplyMessage().GetMessageID(),
		Reactions:          toReactionDTOs(msg.GetReactions(), self),
		IsOld:              isOld,
		MmsPendingDownload: mmsPendingDownload(status),
	}
	var texts []string
	for i, info := range msg.GetMessageInfo() {
		part, ok := toPartDTO(info, i)
		if !ok {
			continue
		}
		if part.Kind == "text" {
			texts = append(texts, part.Text)
		}
		dto.Parts = append(dto.Parts, part)
	}
	dto.Text = strings.Join(texts, "\n")
	return dto
}

// findAlternateParticipantID is the connector's fix-up: some messages name an
// invisible duplicate participant; map it to the visible one with the same
// number so senders line up with the participant list.
func findAlternateParticipantID(conv *gmproto.Conversation, participantID string) string {
	var matched *gmproto.Participant
	for _, p := range conv.GetParticipants() {
		if !p.GetIsVisible() && p.GetID().GetParticipantID() == participantID && p.GetID().GetNumber() != "" {
			matched = p
		}
	}
	if matched == nil {
		return participantID
	}
	for _, p := range conv.GetParticipants() {
		if p.GetIsVisible() && p.GetID().GetNumber() == matched.GetID().GetNumber() {
			return p.GetID().GetParticipantID()
		}
	}
	return participantID
}

// participantIDForNumber maps a typing notification's phone number to a
// participant, preferring visible entries (connector getPhoneNumberParticipantID).
func participantIDForNumber(conv *gmproto.Conversation, number string) string {
	if conv == nil || number == "" {
		return ""
	}
	var invisible *gmproto.Participant
	for _, p := range conv.GetParticipants() {
		if p.GetID().GetNumber() == number {
			if p.GetIsVisible() {
				return p.GetID().GetParticipantID()
			}
			invisible = p
		}
	}
	return invisible.GetID().GetParticipantID()
}

func senderName(msg *gmproto.Message, conv *gmproto.Conversation, senderID string) string {
	if sp := msg.GetSenderParticipant(); sp != nil {
		if n := firstNonEmpty(sp.GetFullName(), sp.GetFirstName()); n != "" {
			return n
		}
	}
	for _, p := range conv.GetParticipants() {
		if id := p.GetID().GetParticipantID(); id != "" && (id == senderID || id == msg.GetParticipantID()) {
			if n := firstNonEmpty(p.GetFullName(), p.GetFirstName()); n != "" {
				return n
			}
		}
	}
	return ""
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// --- contacts, thumbnails, typing, settings -----------------------------------

func toContactDTO(c *gmproto.Contact) contactDTO {
	return contactDTO{
		ID:              c.GetParticipantID(),
		Name:            firstNonEmpty(c.GetName(), c.GetNameAgain()),
		Number:          c.GetNumber().GetNumber(),
		FormattedNumber: c.GetNumber().GetFormattedNumber(),
		Color:           normalizeColor(c.GetAvatarHexColor()),
		ContactID:       c.GetContactID(),
	}
}

func toThumbnailDTOs(resp *gmproto.GetThumbnailResponse) []thumbnailDTO {
	out := make([]thumbnailDTO, 0, len(resp.GetThumbnail()))
	for _, t := range resp.GetThumbnail() {
		data := t.GetData().GetImageBuffer()
		if len(data) == 0 {
			continue
		}
		out = append(out, thumbnailDTO{ParticipantID: t.GetIdentifier(), Data: b64(data)})
	}
	return out
}

func toTypingDTO(t *gmproto.TypingData, conv *gmproto.Conversation) typingDTO {
	return typingDTO{
		ConversationID: t.GetConversationID(),
		ParticipantID:  participantIDForNumber(conv, t.GetUser().GetNumber()),
		Number:         t.GetUser().GetNumber(),
		Typing:         t.GetType() == gmproto.TypingTypes_STARTED_TYPING,
	}
}

func toSettingsDTO(s *gmproto.Settings, isDefaultSMSApp bool) settingsDTO {
	dto := settingsDTO{
		SIMs:                 make([]simDTO, 0, len(s.GetSIMCards())),
		RCSEnabled:           s.GetRCSSettings().GetIsEnabled(),
		SendReadReceipts:     s.GetRCSSettings().GetSendReadReceipts(),
		ShowTypingIndicators: s.GetRCSSettings().GetShowTypingIndicators(),
		IsDefaultSMSApp:      isDefaultSMSApp,
	}
	for _, sim := range s.GetSIMCards() {
		dto.SIMs = append(dto.SIMs, simDTO{
			ParticipantID: sim.GetSIMParticipant().GetID(),
			Number:        firstNonEmpty(sim.GetSIMData().GetFormattedPhoneNumber(), sim.GetSIMData().GetInternationalPhoneNumber()),
			Label:         sim.GetSIMData().GetCarrierName(),
			RCSEnabled:    sim.GetRCSChats().GetEnabled(),
		})
	}
	return dto
}

// --- cursor ------------------------------------------------------------------

// parseCursor accepts nil, empty, "null" or a CursorDTO from a previous page.
func parseCursor(raw []byte) (*gmproto.Cursor, error) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	var c cursorDTO
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, newError(codeInvalidArgument, "cursor is not valid JSON")
	}
	if c.LastItemID == "" && c.LastItemTimestampMs == 0 {
		return nil, nil
	}
	return &gmproto.Cursor{LastItemID: c.LastItemID, LastItemTimestamp: c.LastItemTimestampMs}, nil
}

func toCursorDTO(c *gmproto.Cursor) *cursorDTO {
	if c == nil || (c.GetLastItemID() == "" && c.GetLastItemTimestamp() == 0) {
		return nil
	}
	return &cursorDTO{LastItemID: c.GetLastItemID(), LastItemTimestampMs: c.GetLastItemTimestamp()}
}
