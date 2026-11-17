package wa

import (
	"bytes"
	"encoding/base64"
	"encoding/json"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// The DTOs below are the JSON shapes Kotlin already parses for the Google
// Messages link (app/src/main/java/com/lightdecoder/relay/Dto.kt), so its
// parsing and archive work unchanged. They are unexported on purpose:
// gomobile would try to bind exported structs and skip their slice fields,
// which the "// skipped" guard rejects.
//
// Every slice is initialised so it encodes as [] rather than null, byte
// fields are base64 strings, and every timestamp is epoch ms.

type statusDTO struct {
	Paired    bool   `json:"paired"`
	Connected bool   `json:"connected"`
	OwnID     string `json:"ownId"`
	PushName  string `json:"pushName"`
}

type stateDTO struct {
	State  string `json:"state"`
	Code   string `json:"code"`
	Detail string `json:"detail"`
}

type qrDTO struct {
	Code string `json:"code"`
}

type pairSuccessDTO struct {
	OwnID string `json:"ownId"`
}

type pairErrorDTO struct {
	Code   string `json:"code"`
	Detail string `json:"detail"`
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

type typingDTO struct {
	ConversationID string `json:"conversationId"`
	ParticipantID  string `json:"participantId"`
	Number         string `json:"number"`
	Typing         bool   `json:"typing"`
}

type receiptDTO struct {
	ConversationID string   `json:"conversationId"`
	MessageIDs     []string `json:"messageIds"`
	Status         string   `json:"status"`
	TimestampMs    int64    `json:"timestampMs"`
}

type presenceDTO struct {
	ParticipantID string `json:"participantId"`
	Online        bool   `json:"online"`
	LastSeenMs    int64  `json:"lastSeenMs"`
}

type historySyncDTO struct {
	Progress int  `json:"progress"`
	Done     bool `json:"done"`
}

// Transport and conversation type of everything this package reports.
// Kotlin's own values are "SMS", "MMS", "RCS" and "UNKNOWN"; this one tells
// a WhatsApp chat apart.
const transportWhatsApp = "WHATSAPP"

// Folders, as in gmbridge.
const (
	folderInbox   = "inbox"
	folderArchive = "archive"
)

// Directions, as in gmbridge.
const (
	directionIn      = "in"
	directionOut     = "out"
	directionDeleted = "deleted"
)

// Message statuses: Google Messages' status names and codes, which Kotlin
// already turns into ticks (MessageStatus.kt), so WhatsApp's ticks reuse them.
var (
	statusSending   = gmproto.MessageStatusType_OUTGOING_SENDING
	statusSent      = gmproto.MessageStatusType_OUTGOING_COMPLETE
	statusDelivered = gmproto.MessageStatusType_OUTGOING_DELIVERED
	statusRead      = gmproto.MessageStatusType_OUTGOING_DISPLAYED
	statusFailed    = gmproto.MessageStatusType_OUTGOING_FAILED_GENERIC
	statusIncoming  = gmproto.MessageStatusType_INCOMING_COMPLETE
	statusDeleted   = gmproto.MessageStatusType_MESSAGE_DELETED
)

func setStatus(m *messageDTO, st gmproto.MessageStatusType) {
	m.Status = st.String()
	m.StatusCode = int(st)
}

// statusRank orders outgoing statuses so that a late or repeated receipt
// never moves a message backwards (read, then delivered).
func statusRank(code int) int {
	switch gmproto.MessageStatusType(code) {
	case statusFailed:
		return -1
	case statusSending:
		return 0
	case statusSent:
		return 1
	case statusDelivered:
		return 2
	case statusRead:
		return 3
	default:
		return 0
	}
}

// toJSON encodes without HTML escaping and without the trailing newline
// json.Encoder adds. The DTOs cannot fail to encode, so an error falls back
// to an empty object rather than nil, which Java would see as null.
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

// parseCursor reads the cursor FetchMessages handed out. Empty or null means
// "the newest page".
func parseCursor(raw []byte) (*cursorDTO, error) {
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
	return &c, nil
}

// normalizeMessage makes the slices non-nil, so they encode as [].
func normalizeMessage(m *messageDTO) {
	if m.Parts == nil {
		m.Parts = []partDTO{}
	}
	if m.Reactions == nil {
		m.Reactions = []reactionDTO{}
	}
	for i := range m.Reactions {
		if m.Reactions[i].ParticipantIDs == nil {
			m.Reactions[i].ParticipantIDs = []string{}
		}
	}
	if m.Transport == "" {
		m.Transport = transportWhatsApp
	}
}
