package gmbridge

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	"go.mau.fi/util/ptr"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func sampleConversation() *gmproto.Conversation {
	return &gmproto.Conversation{
		ConversationID:       "c1",
		Name:                 "Group",
		IsGroupChat:          true,
		Type:                 gmproto.ConversationType_RCS,
		Status:               gmproto.ConversationStatus_ACTIVE,
		Unread:               true,
		Pinned:               true,
		LastMessageTimestamp: 1_700_000_000_123_456,
		LatestMessageID:      "m9",
		LatestMessage:        &gmproto.LatestMessage{DisplayContent: "snippet", FromMe: 1, DisplayName: "Me"},
		AvatarHexColor:       "ff8800",
		DefaultOutgoingID:    "5",
		Participants: []*gmproto.Participant{
			{ID: &gmproto.SmallInfo{ParticipantID: "5", Number: "+15550000005"}, FullName: "Me", IsMe: true, IsVisible: true},
			{ID: &gmproto.SmallInfo{ParticipantID: "7", Number: "+15550000007"}, FullName: "Alice Example", FirstName: "Alice", IsVisible: true, AvatarHexColor: "#112233", ContactID: "k7", FormattedNumber: "(555) 000-0007"},
			// An invisible duplicate of Alice, as the phone sometimes sends.
			{ID: &gmproto.SmallInfo{ParticipantID: "70", Number: "+15550000007"}, IsVisible: false},
		},
		OtherParticipants: []string{"7"},
	}
}

func sampleMessage() *gmproto.Message {
	key := make([]byte, 32)
	thumbKey := make([]byte, 32)
	for i := range key {
		key[i], thumbKey[i] = byte(i), byte(255-i)
	}
	return &gmproto.Message{
		MessageID:      "m1",
		TmpID:          "tmp-1",
		ConversationID: "c1",
		ParticipantID:  "70",
		Timestamp:      1_700_000_000_987_654,
		Type:           4,
		Subject:        ptr.Ptr("Subj"),
		MessageStatus:  &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_COMPLETE},
		MessageInfo: []*gmproto.MessageInfo{
			{ActionMessageID: ptr.Ptr("p-text"), Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "hello"}}},
			{ActionMessageID: ptr.Ptr("p-media"), Data: &gmproto.MessageInfo_MediaContent{MediaContent: &gmproto.MediaContent{
				Format:        gmproto.MediaFormats_IMAGE_JPEG,
				MediaID:       "media-1",
				MediaName:     "photo.heic",
				Size:          1234,
				Dimensions:    &gmproto.Dimensions{Width: 640, Height: 480},
				DecryptionKey: key,
				MimeType:      "image/heic",
			}}},
			{Data: &gmproto.MessageInfo_MediaContent{MediaContent: &gmproto.MediaContent{
				Format:                 gmproto.MediaFormats_IMAGE_PNG,
				ThumbnailMediaID:       "thumb-2",
				ThumbnailDecryptionKey: thumbKey,
				MediaData:              []byte{1, 2, 3},
			}}},
			{ActionMessageID: ptr.Ptr("p-text2"), Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "world"}}},
		},
		Reactions: []*gmproto.ReactionEntry{
			{Data: &gmproto.ReactionData{Type: gmproto.EmojiType_LIKE}, ParticipantIDs: []string{"7", "5"}},
			{Data: &gmproto.ReactionData{Unicode: "🦊", Type: gmproto.EmojiType_CUSTOM}, ParticipantIDs: []string{"7"}},
			{Data: &gmproto.ReactionData{Type: gmproto.EmojiType_REACTION_TYPE_UNSPECIFIED}, ParticipantIDs: []string{"7"}},
		},
		ReplyMessage: &gmproto.ReplyMessage{MessageID: "m0", ConversationID: "c1"},
	}
}

func TestMessageDTOFull(t *testing.T) {
	cache := newStateCache()
	conv := sampleConversation()
	cache.putConversation(conv)
	dto := toMessageDTO(sampleMessage(), true, conv, cache)

	if dto.ID != "m1" || dto.TmpID != "tmp-1" || dto.ConversationID != "c1" {
		t.Fatalf("ids: %+v", dto)
	}
	if dto.SenderID != "7" {
		t.Errorf("invisible duplicate participant should map to the visible one, got %q", dto.SenderID)
	}
	if dto.SenderName != "Alice Example" {
		t.Errorf("sender name = %q", dto.SenderName)
	}
	if dto.FromMe || dto.Direction != "in" || dto.Status != "INCOMING_COMPLETE" || dto.StatusCode != 100 {
		t.Errorf("direction/status: %+v", dto)
	}
	if dto.TimestampMs != 1_700_000_000_987 {
		t.Errorf("timestamp should be ms, got %d", dto.TimestampMs)
	}
	if dto.Transport != "RCS" || dto.Subject != "Subj" || !dto.IsOld {
		t.Errorf("transport/subject/isOld: %+v", dto)
	}
	if dto.Text != "hello\nworld" {
		t.Errorf("text = %q", dto.Text)
	}
	if dto.ReplyToID != "m0" {
		t.Errorf("replyToId = %q", dto.ReplyToID)
	}
	if len(dto.Parts) != 4 {
		t.Fatalf("parts: %d", len(dto.Parts))
	}
	media := dto.Parts[1]
	if media.Kind != "media" || media.PartID != "p-media" || media.MediaID != "media-1" || media.Mime != "image/heic" ||
		media.FileName != "photo.heic" || media.Size != 1234 || media.Width != 640 || media.Height != 480 || media.NeedsFullSize {
		t.Errorf("media part: %+v", media)
	}
	if key, _ := base64.StdEncoding.DecodeString(media.Key); len(key) != 32 || key[1] != 1 {
		t.Errorf("key not base64 of the 32 bytes: %q", media.Key)
	}
	thumb := dto.Parts[2]
	if !thumb.NeedsFullSize || thumb.MediaID != "" || thumb.ThumbMediaID != "thumb-2" || thumb.ThumbKey == "" {
		t.Errorf("thumbnail-only part should need full size: %+v", thumb)
	}
	if thumb.Mime != "image/png" {
		t.Errorf("MIME should fall back to the format table, got %q", thumb.Mime)
	}
	if thumb.PartID != "idx:2" {
		t.Errorf("part without action ID should get an index ID, got %q", thumb.PartID)
	}
	if thumb.InlineData != base64.StdEncoding.EncodeToString([]byte{1, 2, 3}) {
		t.Errorf("inline data: %q", thumb.InlineData)
	}
	if len(dto.Reactions) != 2 {
		t.Fatalf("reactions: %+v", dto.Reactions)
	}
	if dto.Reactions[0].Emoji != "👍" || !dto.Reactions[0].FromMe || len(dto.Reactions[0].ParticipantIDs) != 2 {
		t.Errorf("like reaction: %+v", dto.Reactions[0])
	}
	if dto.Reactions[1].Emoji != "🦊" || dto.Reactions[1].FromMe {
		t.Errorf("custom reaction: %+v", dto.Reactions[1])
	}
}

func TestDirectionFromStatus(t *testing.T) {
	cache := newStateCache()
	cache.applySettings(&gmproto.Settings{SIMCards: []*gmproto.SIMCard{{SIMParticipant: &gmproto.SIMParticipant{ID: "42"}}}})
	cases := []struct {
		status      gmproto.MessageStatusType
		participant string
		direction   string
		fromMe      bool
	}{
		{gmproto.MessageStatusType_OUTGOING_DELIVERED, "9", "out", true},
		{gmproto.MessageStatusType_OUTGOING_FAILED_GENERIC, "", "out", true},
		{gmproto.MessageStatusType_INCOMING_DISPLAYED, "1", "in", false},
		{gmproto.MessageStatusType_TOMBSTONE_PARTICIPANT_JOINED, "9", "system", false},
		{gmproto.MessageStatusType_MESSAGE_DELETED, "42", "deleted", true},
		{gmproto.MessageStatusType_STATUS_UNKNOWN, "1", "out", true},
		{gmproto.MessageStatusType_STATUS_UNKNOWN, "42", "out", true},
		{gmproto.MessageStatusType_STATUS_UNKNOWN, "9", "in", false},
		{gmproto.MessageStatusType(555), "9", "in", false},
	}
	for _, c := range cases {
		msg := &gmproto.Message{MessageID: "x", ParticipantID: c.participant, MessageStatus: &gmproto.MessageStatus{Status: c.status}}
		dto := toMessageDTO(msg, false, nil, cache)
		if dto.Direction != c.direction || dto.FromMe != c.fromMe {
			t.Errorf("status %d participant %q: got %s/%v want %s/%v", c.status, c.participant, dto.Direction, dto.FromMe, c.direction, c.fromMe)
		}
	}
	unknown := toMessageDTO(&gmproto.Message{MessageStatus: &gmproto.MessageStatus{Status: 555}}, false, nil, nil)
	if unknown.StatusCode != 555 || unknown.Status == "" {
		t.Errorf("unknown status must be kept: %+v", unknown)
	}
}

func TestTransportAndPendingDownload(t *testing.T) {
	smsConv := &gmproto.Conversation{Type: gmproto.ConversationType_SMS}
	for msgType, want := range map[int64]string{1: "SMS", 2: "MMS", 3: "MMS", 4: "RCS", 0: "SMS", 9: "SMS"} {
		if got := transportOf(msgType, smsConv); got != want {
			t.Errorf("type %d: got %s want %s", msgType, got, want)
		}
	}
	if got := transportOf(0, nil); got != "UNKNOWN" {
		t.Errorf("no type, no conversation: %s", got)
	}
	msg := &gmproto.Message{MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_YET_TO_MANUAL_DOWNLOAD}}
	if !toMessageDTO(msg, false, nil, nil).MmsPendingDownload {
		t.Error("manual-download MMS should be pending")
	}
}

func TestResolveMime(t *testing.T) {
	cases := []struct {
		mc   *gmproto.MediaContent
		want string
	}{
		{&gmproto.MediaContent{MimeType: "image/heif", Format: gmproto.MediaFormats_IMAGE_JPEG}, "image/heif"},
		{&gmproto.MediaContent{Format: gmproto.MediaFormats_IMAGE_GIF}, "image/gif"},
		{&gmproto.MediaContent{Format: gmproto.MediaFormats_IMAGE_UNSPECIFIED}, ""},
		{&gmproto.MediaContent{Format: gmproto.MediaFormats(9999)}, ""},
	}
	for _, c := range cases {
		if got := resolveMime(c.mc); got != c.want {
			t.Errorf("%v: got %q want %q", c.mc, got, c.want)
		}
	}
}

func TestConversationDTO(t *testing.T) {
	cache := newStateCache()
	cache.applySettings(&gmproto.Settings{SIMCards: []*gmproto.SIMCard{{SIMParticipant: &gmproto.SIMParticipant{ID: "70"}}}})
	dto := toConversationDTO(sampleConversation(), cache)
	if dto.ID != "c1" || dto.Name != "Group" || !dto.IsGroup || dto.Type != "RCS" || dto.Folder != "inbox" || dto.StatusCode != 1 {
		t.Errorf("basics: %+v", dto)
	}
	if !dto.Unread || !dto.Pinned || dto.ReadOnly || dto.Muted {
		t.Errorf("flags: %+v", dto)
	}
	if dto.LastMessageTs != 1_700_000_000_123 {
		t.Errorf("lastMessageTs should be ms, got %d", dto.LastMessageTs)
	}
	if dto.Snippet != "snippet" || !dto.SnippetFromMe || dto.SnippetSender != "Me" || dto.LatestMessageID != "m9" {
		t.Errorf("snippet: %+v", dto)
	}
	if dto.AvatarColor != "#ff8800" || dto.DefaultOutgoingID != "5" {
		t.Errorf("color/outgoing: %+v", dto)
	}
	if len(dto.Participants) != 3 || len(dto.OtherParticipantIDs) != 1 {
		t.Fatalf("participants: %+v", dto.Participants)
	}
	me, alice, dup := dto.Participants[0], dto.Participants[1], dto.Participants[2]
	if !me.IsMe || alice.IsMe {
		t.Errorf("isMe from the proto flag: %+v %+v", me, alice)
	}
	if !dup.IsMe || dup.Visible {
		t.Errorf("isMe should also come from SIM participant IDs: %+v", dup)
	}
	if alice.Name != "Alice Example" || alice.FirstName != "Alice" || alice.Number != "+15550000007" ||
		alice.FormattedNumber != "(555) 000-0007" || alice.Color != "#112233" || alice.ContactID != "k7" || !alice.Visible {
		t.Errorf("alice: %+v", alice)
	}

	folders := map[gmproto.ConversationStatus]string{
		gmproto.ConversationStatus_UNKNOWN_CONVERSATION_STATUS: "inbox",
		gmproto.ConversationStatus_ACTIVE:                      "inbox",
		gmproto.ConversationStatus_ARCHIVED:                    "archive",
		gmproto.ConversationStatus_KEEP_ARCHIVED:               "archive",
		gmproto.ConversationStatus_SPAM_FOLDER:                 "spam",
		gmproto.ConversationStatus_BLOCKED_FOLDER:              "spam",
		gmproto.ConversationStatus_DELETED:                     "deleted",
		gmproto.ConversationStatus_TRASH_FOLDER:                "deleted",
		gmproto.ConversationStatus_CROSS_COUNTRY_FOLDER:        "other",
		gmproto.ConversationStatus(77):                         "other",
	}
	for status, want := range folders {
		got := toConversationDTO(&gmproto.Conversation{Status: status}, nil)
		if got.Folder != want || got.StatusCode != int(status) {
			t.Errorf("status %d: folder %s want %s", status, got.Folder, want)
		}
	}
	if got := toConversationDTO(&gmproto.Conversation{Type: 9}, nil).Type; got != "UNKNOWN" {
		t.Errorf("unknown type: %s", got)
	}
}

func TestJSONShape(t *testing.T) {
	raw := string(toJSON(toMessageDTO(&gmproto.Message{MessageID: "m"}, false, nil, nil)))
	for _, want := range []string{`"parts":[]`, `"reactions":[]`, `"timestampMs":0`, `"mmsPendingDownload":false`, `"replyToId":""`} {
		if !strings.Contains(raw, want) {
			t.Errorf("message JSON lacks %s: %s", want, raw)
		}
	}
	raw = string(toJSON(toConversationDTO(&gmproto.Conversation{ConversationID: "c"}, nil)))
	for _, want := range []string{`"participants":[]`, `"otherParticipantIds":[]`, `"isGroup":false`, `"avatarColor":""`} {
		if !strings.Contains(raw, want) {
			t.Errorf("conversation JSON lacks %s: %s", want, raw)
		}
	}
	if got := string(toJSON(messagePageDTO{Messages: []messageDTO{}})); got != `{"messages":[],"cursor":null,"total":0}` {
		t.Errorf("empty page: %s", got)
	}
	if got := string(toJSON(stateDTO{State: "connected"})); got != `{"state":"connected","code":"","detail":""}` {
		t.Errorf("state: %s", got)
	}
	if got := string(toJSON(map[string]string{"t": "<b>&</b>"})); got != `{"t":"<b>&</b>"}` {
		t.Errorf("HTML must not be escaped: %s", got)
	}
}

func TestMessagePageOrderOverlapAndCursor(t *testing.T) {
	b := &GMBridge{cache: newStateCache()}
	msg := func(id string, ms int64) *gmproto.Message {
		return &gmproto.Message{MessageID: id, ConversationID: "c1", Timestamp: ms * 1000}
	}
	// Phone order is newest first.
	resp := &gmproto.ListMessagesResponse{
		Messages:      []*gmproto.Message{msg("m5", 500), msg("m4", 400), msg("m3", 300)},
		TotalMessages: 42,
	}
	page := b.messagePage(resp, nil)
	if ids := pageIDs(page); ids != "m3,m4,m5" {
		t.Fatalf("first page should be oldest→newest, got %s", ids)
	}
	if page.Cursor == nil || page.Cursor.LastItemID != "m3" || page.Cursor.LastItemTimestampMs != 300 || page.Total != 42 {
		t.Fatalf("fallback cursor from the oldest message: %+v", page.Cursor)
	}

	// Next page overlaps with the cursor message and a newer one.
	resp = &gmproto.ListMessagesResponse{
		Messages: []*gmproto.Message{msg("m4", 400), msg("m3", 300), msg("m2b", 300), msg("m1", 100)},
		Cursor:   &gmproto.Cursor{LastItemID: "m1", LastItemTimestamp: 100},
	}
	page = b.messagePage(resp, &gmproto.Cursor{LastItemID: "m3", LastItemTimestamp: 300})
	if ids := pageIDs(page); ids != "m1,m2b" {
		t.Fatalf("overlap should be dropped (same-ms sibling kept), got %s", ids)
	}
	if page.Cursor == nil || page.Cursor.LastItemID != "m1" || page.Cursor.LastItemTimestampMs != 100 {
		t.Fatalf("phone cursor should be passed through: %+v", page.Cursor)
	}

	// Only overlap → done.
	page = b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{msg("m1", 100)}},
		&gmproto.Cursor{LastItemID: "m1", LastItemTimestamp: 100})
	if len(page.Messages) != 0 || page.Cursor != nil {
		t.Fatalf("no new messages means no cursor: %+v", page)
	}

	if _, ok := b.cache.message("m2b"); !ok {
		t.Error("fetched messages should be indexed for replies/reactions")
	}

	// Only same-millisecond siblings of the cursor came back: they are
	// returned, but the cursor must not stay at that millisecond, or a caller
	// paging until the cursor is null would alternate between them forever.
	page = b.messagePage(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{msg("m3", 300), msg("m2b", 300)}},
		&gmproto.Cursor{LastItemID: "m3", LastItemTimestamp: 300})
	if ids := pageIDs(page); ids != "m2b" || page.Cursor != nil {
		t.Fatalf("same-ms page must end paging: %s %+v", ids, page.Cursor)
	}
	// A phone cursor that does not move back is replaced by the oldest message.
	page = b.messagePage(&gmproto.ListMessagesResponse{
		Messages: []*gmproto.Message{msg("m2b", 300), msg("m1", 100)},
		Cursor:   &gmproto.Cursor{LastItemID: "m3", LastItemTimestamp: 300},
	}, &gmproto.Cursor{LastItemID: "m3", LastItemTimestamp: 300})
	if page.Cursor == nil || page.Cursor.LastItemID != "m1" || page.Cursor.LastItemTimestampMs != 100 {
		t.Fatalf("non-advancing phone cursor: %+v", page.Cursor)
	}
}

func pageIDs(p messagePageDTO) string {
	ids := make([]string, len(p.Messages))
	for i, m := range p.Messages {
		ids[i] = m.ID
	}
	return strings.Join(ids, ",")
}

func TestCursorParsing(t *testing.T) {
	for _, raw := range []string{"", "null", "  ", `{}`} {
		c, err := parseCursor([]byte(raw))
		if err != nil || c != nil {
			t.Errorf("%q: %v %v", raw, c, err)
		}
	}
	c, err := parseCursor([]byte(`{"lastItemId":"m3","lastItemTimestampMs":300}`))
	if err != nil || c.GetLastItemID() != "m3" || c.GetLastItemTimestamp() != 300 {
		t.Errorf("cursor: %v %v", c, err)
	}
	if _, err := parseCursor([]byte(`{nope`)); codeOf(err) != codeInvalidArgument {
		t.Errorf("bad cursor: %v", err)
	}
	// Round trip through the DTO the page returns.
	back, _ := parseCursor(toJSON(toCursorDTO(&gmproto.Cursor{LastItemID: "x", LastItemTimestamp: 7})))
	if back.GetLastItemID() != "x" || back.GetLastItemTimestamp() != 7 {
		t.Errorf("round trip: %v", back)
	}
}

func TestTypingSettingsContactsThumbnails(t *testing.T) {
	conv := sampleConversation()
	typing := toTypingDTO(&gmproto.TypingData{ConversationID: "c1", User: &gmproto.User{Number: "+15550000007"}, Type: gmproto.TypingTypes_STARTED_TYPING}, conv)
	if typing.ParticipantID != "7" || !typing.Typing || typing.Number != "+15550000007" {
		t.Errorf("typing: %+v", typing)
	}
	if got := toTypingDTO(&gmproto.TypingData{User: &gmproto.User{Number: "+1"}}, nil); got.ParticipantID != "" || got.Typing {
		t.Errorf("unknown typing: %+v", got)
	}

	cache := newStateCache()
	settings := &gmproto.Settings{
		SIMCards: []*gmproto.SIMCard{{
			SIMParticipant: &gmproto.SIMParticipant{ID: "5"},
			SIMData:        &gmproto.SIMData{CarrierName: "Carrier", FormattedPhoneNumber: "+1 555", SIMPayload: &gmproto.SIMPayload{Two: 2, SIMNumber: 1}},
			RCSChats:       &gmproto.RCSChats{Enabled: true},
		}},
		RCSSettings: &gmproto.RCSSettings{IsEnabled: true, SendReadReceipts: true, IsDefaultSMSApp: ptr.Ptr(true)},
	}
	dto := toSettingsDTO(settings, cache.applySettings(settings))
	if len(dto.SIMs) != 1 || dto.SIMs[0].ParticipantID != "5" || dto.SIMs[0].Label != "Carrier" || !dto.SIMs[0].RCSEnabled ||
		!dto.RCSEnabled || !dto.SendReadReceipts || dto.ShowTypingIndicators || !dto.IsDefaultSMSApp {
		t.Errorf("settings: %+v", dto)
	}
	// A later event without the optional flag keeps the last value.
	settings.RCSSettings.IsDefaultSMSApp = nil
	if !cache.applySettings(settings) {
		t.Error("isDefaultSmsApp should be sticky when absent")
	}
	if p := cache.simPayloadFor(conv); p.GetSIMNumber() != 1 {
		t.Errorf("SIM for DefaultOutgoingID: %v", p)
	}
	if !cache.isSelf("5") || !cache.isSelf("1") || cache.isSelf("7") {
		t.Error("self IDs")
	}

	contact := toContactDTO(&gmproto.Contact{ParticipantID: "7", NameAgain: "Alice", AvatarHexColor: "abcdef", ContactID: "k",
		Number: &gmproto.ContactNumber{Number: "+15550000007", FormattedNumber: ptr.Ptr("(555) 000-0007")}})
	if contact.Name != "Alice" || contact.Number != "+15550000007" || contact.FormattedNumber != "(555) 000-0007" || contact.Color != "#abcdef" {
		t.Errorf("contact: %+v", contact)
	}

	thumbs := toThumbnailDTOs(&gmproto.GetThumbnailResponse{Thumbnail: []*gmproto.GetThumbnailResponse_Thumbnail{
		{Identifier: "7", Data: &gmproto.ThumbnailData{ImageBuffer: []byte("png")}},
		{Identifier: "8", Data: &gmproto.ThumbnailData{}},
	}})
	if len(thumbs) != 1 || thumbs[0].ParticipantID != "7" || thumbs[0].Data != base64.StdEncoding.EncodeToString([]byte("png")) {
		t.Errorf("thumbnails: %+v", thumbs)
	}
}

func TestSIMFallbacks(t *testing.T) {
	cache := newStateCache()
	if cache.simPayloadFor(nil) != nil {
		t.Error("no SIMs known")
	}
	convSIM := &gmproto.Conversation{SimCard: &gmproto.SIMCard{SIMData: &gmproto.SIMData{SIMPayload: &gmproto.SIMPayload{SIMNumber: 3}}}}
	if cache.simPayloadFor(convSIM).GetSIMNumber() != 3 {
		t.Error("conversation's own SIM card")
	}
	cache.applySettings(&gmproto.Settings{SIMCards: []*gmproto.SIMCard{{SIMParticipant: &gmproto.SIMParticipant{ID: "9"},
		SIMData: &gmproto.SIMData{SIMPayload: &gmproto.SIMPayload{SIMNumber: 2}}}}})
	if cache.simPayloadFor(&gmproto.Conversation{DefaultOutgoingID: "unknown"}).GetSIMNumber() != 2 {
		t.Error("single SIM fallback")
	}
}

func TestDTOKeysAreCamelCase(t *testing.T) {
	var m map[string]any
	_ = json.Unmarshal(toJSON(toMessageDTO(sampleMessage(), false, nil, nil)), &m)
	for k := range m {
		if strings.Contains(k, "_") || strings.ToLower(k[:1]) != k[:1] {
			t.Errorf("key %q is not camelCase", k)
		}
	}
}
